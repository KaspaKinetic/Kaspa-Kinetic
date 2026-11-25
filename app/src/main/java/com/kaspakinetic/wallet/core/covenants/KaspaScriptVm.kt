package com.kaspakinetic.wallet.core.covenants

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx
import java.io.ByteArrayOutputStream

/**
 * Deterministic on-device Kaspa Toccata Script VM dry-run verifier.
 *
 * Executes P2SH signatureScript + redeemScript against a real Transaction v1 (`RawTx`) before
 * broadcast so malformed scripts, invalid Schnorr signatures, unmet CSV/CLTV locks, or broken
 * successor covenant bindings are caught locally before any transaction ever touches the network!
 *
 * Enforces exact `rusty-kaspa` 2.1.0 semantics:
 * - P2SH: verifies `OP_BLAKE2B <32-byte hash> OP_EQUAL`, then executes popped redeem script on remaining stack.
 * - `OP_CHECKSEQUENCEVERIFY` (0xB1) & `OP_CHECKLOCKTIMEVERIFY` (0xB0) POP their top stack element.
 * - Real BIP-340 Schnorr signature verification against `KaspaMassAndTx.computeSigHashAll(...)` (v1).
 * - Toccata introspection opcodes (`OP_TXINPUTINDEX`, `OP_TXINPUTAMOUNT`, `OP_TXINPUTSPK`,
 *   `OP_TXOUTPUTAMOUNT`, `OP_TXOUTPUTSPK`, `OP_INPUTCOVENANTID`, `OP_OUTPUTCOVENANTID`, `OP_TXOUTPUTCOUNT`).
 */
object KaspaScriptVm {
    data class VmResult(
        val success: Boolean,
        val error: String? = null,
        val scriptUnitsUsed: Long = 0L,
        val recommendedComputeBudget: Int = 1,
    )

    // Exact rusty-kaspa Toccata script-unit metering (consensus/core/src/mass/units.rs & txscript/src/lib.rs):
    // - Schnorr/ECDSA sigop: Gram(1000) * SCRIPT_UNITS_PER_GRAM(100) = 100,000 script units
    // - BLAKE2b hash: 2 units/byte; SHA-256 hash: 1 unit/byte (plus conservative per-op base cost)
    // - Per-input allowance: compute_budget * 10,000 + 9,999 script units
    private const val COST_BASIC_OP = 20L
    private const val COST_BLAKE2B_PER_BYTE = 2L
    private const val COST_SHA256_PER_BYTE = 1L
    private const val COST_CHECKSIG_OP = 100_000L

    private const val LOCK_TIME_THRESHOLD = 500_000_000_000L
    private const val MAX_TX_IN_SEQUENCE_NUM = -1L // u64::MAX
    private const val SEQUENCE_LOCK_TIME_DISABLED = 1L shl 63
    private const val SEQUENCE_LOCK_TIME_MASK = 0x0000_0000_FFFF_FFFFL

    private class VmDiagnostics {
        var lastFalseReason: String? = null
        var lastIntrospectionKind: String? = null
    }

    /**
     * Verifies a standard P2PK Schnorr input (`<32-byte pubkey> OP_CHECKSIG`) against real Toccata
     * `100,000`-unit `OP_CHECKSIG` metering and the input's committed `compute_budget`.
     */
    fun verifyP2pkInput(
        tx: KaspaMassAndTx.RawTx,
        inputIndex: Int,
        enforceCommittedBudget: Boolean = true,
    ): VmResult {
        if (inputIndex !in tx.inputs.indices) {
            return VmResult(false, "Input index $inputIndex out of range")
        }
        val input = tx.inputs[inputIndex]
        val prevSpk = KaspaCrypto.hexToBytes(input.utxo.scriptPublicKeyHex)
        val stack = ArrayDeque<ByteArray>()
        val diag = VmDiagnostics()
        var units = 0L

        val sigRun = runCatching {
            units += executeScript(
                script = input.signatureScript,
                stack = stack,
                tx = tx,
                inputIndex = inputIndex,
                virtualDaaScore = 0L,
                pushOnly = true,
                diag = diag,
            )
        }
        if (sigRun.isFailure) {
            return VmResult(false, "P2PK sigScript error: ${sigRun.exceptionOrNull()?.message}", units)
        }

        val spkRun = runCatching {
            units += executeScript(
                script = prevSpk,
                stack = stack,
                tx = tx,
                inputIndex = inputIndex,
                virtualDaaScore = 0L,
                pushOnly = false,
                diag = diag,
            )
        }
        if (spkRun.isFailure) {
            return VmResult(false, "P2PK scriptPubKey failed: ${spkRun.exceptionOrNull()?.message}", units)
        }
        if (stack.isEmpty() || !isTruthy(stack.last())) {
            return VmResult(false, diag.lastFalseReason ?: "P2PK script evaluated to FALSE", units)
        }

        val recommended = KaspaMassAndTx.computeBudgetForScriptUnits(units + 20_000L)
            .coerceAtLeast(KaspaMassAndTx.DEFAULT_V1_P2PK_COMPUTE_BUDGET)
        if (enforceCommittedBudget && tx.version >= 1) {
            val allowed = KaspaMassAndTx.scriptUnitsForComputeBudget(input.computeBudget)
            if (units > allowed) {
                return VmResult(
                    success = false,
                    error = "script units ($units) exceeded the amount committed in input #$inputIndex ($allowed for compute_budget=${input.computeBudget}; need >= $recommended)",
                    scriptUnitsUsed = units,
                    recommendedComputeBudget = recommended,
                )
            }
        }
        return VmResult(
            success = true,
            error = null,
            scriptUnitsUsed = units,
            recommendedComputeBudget = recommended,
        )
    }

    fun verifyP2shInput(
        tx: KaspaMassAndTx.RawTx,
        inputIndex: Int,
        virtualDaaScore: Long = 0L,
        enforceCommittedBudget: Boolean = true,
        currentTimeMs: Long = 0L,
    ): VmResult {
        if (inputIndex !in tx.inputs.indices) {
            return VmResult(false, "Input index $inputIndex out of range")
        }
        val input = tx.inputs[inputIndex]
        val prevSpk = KaspaCrypto.hexToBytes(input.utxo.scriptPublicKeyHex)
        if (prevSpk.size != 35 ||
            prevSpk[0] != KaspaAddress.OP_BLAKE2B ||
            prevSpk[1] != 32.toByte() ||
            prevSpk[34] != KaspaAddress.OP_EQUAL
        ) {
            return VmResult(false, "Previous output scriptPublicKey is not standard Kaspa P2SH")
        }
        val expectedHash = prevSpk.copyOfRange(2, 34)

        // 1. Execute push-only sigScript
        val stack = ArrayDeque<ByteArray>()
        val diag = VmDiagnostics()
        var units = 0L
        val sigRun = runCatching {
            units += executeScript(
                script = input.signatureScript,
                stack = stack,
                tx = tx,
                inputIndex = inputIndex,
                virtualDaaScore = virtualDaaScore,
                currentTimeMs = currentTimeMs,
                pushOnly = true,
                diag = diag,
            )
        }
        if (sigRun.isFailure) {
            return VmResult(false, "sigScript error: ${sigRun.exceptionOrNull()?.message}", units)
        }
        if (stack.isEmpty()) {
            return VmResult(false, "sigScript produced empty stack", units)
        }

        // 2. Pop redeemScript and verify unkeyed BLAKE2b-256 hash matches P2SH scriptPubKey
        val redeemScript = stack.removeLast()
        val actualHash = KaspaCrypto.blake2b256(redeemScript, domainKey = null)
        units += COST_BASIC_OP + (redeemScript.size.toLong() * COST_BLAKE2B_PER_BYTE) + 32L
        if (!actualHash.contentEquals(expectedHash)) {
            return VmResult(
                false,
                "Redeem script BLAKE2b hash does not match P2SH scriptPublicKey " +
                    "(expected ${KaspaCrypto.bytesToHex(expectedHash).take(12)}..., got ${KaspaCrypto.bytesToHex(actualHash).take(12)}...)",
                units,
            )
        }

        // 3. Execute redeemScript against remaining stack arguments
        val redeemRun = runCatching {
            units += executeScript(
                script = redeemScript,
                stack = stack,
                tx = tx,
                inputIndex = inputIndex,
                virtualDaaScore = virtualDaaScore,
                currentTimeMs = currentTimeMs,
                pushOnly = false,
                diag = diag,
            )
        }
        if (redeemRun.isFailure) {
            return VmResult(false, "redeemScript failed: ${redeemRun.exceptionOrNull()?.message}", units)
        }
        if (stack.isEmpty()) {
            return VmResult(false, "Script ended with empty stack", units)
        }
        val top = stack.last()
        if (!isTruthy(top)) {
            return VmResult(false, diag.lastFalseReason ?: "Script evaluated to FALSE", units)
        }

        // Add conservative safety headroom (+20,000 units = +2 budget units; min 4 for keyless P2SH, 16 for signed P2SH)
        val minFloor = if (units >= COST_CHECKSIG_OP) 16 else 4
        val recommended = KaspaMassAndTx.computeBudgetForScriptUnits(units + 20_000L).coerceAtLeast(minFloor)
        if (enforceCommittedBudget && tx.version >= 1) {
            val allowed = KaspaMassAndTx.scriptUnitsForComputeBudget(input.computeBudget)
            if (units > allowed) {
                return VmResult(
                    success = false,
                    error = "script units ($units) exceeded the amount committed in P2SH input #$inputIndex ($allowed for compute_budget=${input.computeBudget}; need >= $recommended)",
                    scriptUnitsUsed = units,
                    recommendedComputeBudget = recommended,
                )
            }
        }
        return VmResult(
            success = true,
            error = null,
            scriptUnitsUsed = units,
            recommendedComputeBudget = recommended,
        )
    }

    private fun executeScript(
        script: ByteArray,
        stack: ArrayDeque<ByteArray>,
        tx: KaspaMassAndTx.RawTx,
        inputIndex: Int,
        virtualDaaScore: Long,
        currentTimeMs: Long = 0L,
        pushOnly: Boolean,
        diag: VmDiagnostics = VmDiagnostics(),
    ): Long {
        var pc = 0
        var units = 0L
        val condStack = ArrayDeque<Boolean>()

        fun isExecuting(): Boolean = condStack.all { it }
        fun pop(): ByteArray {
            if (stack.isEmpty()) error("Stack underflow at pc=$pc")
            return stack.removeLast()
        }
        fun popInt(): Long = KaspaScriptBuilder.deserializeScriptInt(pop())
        fun pushInt(v: Long) = stack.addLast(KaspaScriptBuilder.serializeScriptInt(v))
        fun pushBool(b: Boolean) = stack.addLast(if (b) byteArrayOf(1) else ByteArray(0))

        while (pc < script.size) {
            val op = script[pc++].toInt() and 0xFF
            units += COST_BASIC_OP

            // Data push opcodes (0x00..0x4E)
            if (op == KaspaOpcodes.OP_0) {
                if (isExecuting()) stack.addLast(ByteArray(0))
                continue
            }
            if (op in 1..75) {
                if (pc + op > script.size) error("Truncated OP_DATA_$op")
                val data = script.copyOfRange(pc, pc + op)
                pc += op
                if (isExecuting()) stack.addLast(data)
                continue
            }
            if (op == KaspaOpcodes.OP_PUSHDATA1) {
                if (pc >= script.size) error("Truncated OP_PUSHDATA1 len")
                val len = script[pc++].toInt() and 0xFF
                if (pc + len > script.size) error("Truncated OP_PUSHDATA1 payload")
                val data = script.copyOfRange(pc, pc + len)
                pc += len
                if (isExecuting()) stack.addLast(data)
                continue
            }
            if (op == KaspaOpcodes.OP_PUSHDATA2) {
                if (pc + 2 > script.size) error("Truncated OP_PUSHDATA2 len")
                val len = (script[pc].toInt() and 0xFF) or ((script[pc + 1].toInt() and 0xFF) shl 8)
                pc += 2
                if (pc + len > script.size) error("Truncated OP_PUSHDATA2 payload")
                val data = script.copyOfRange(pc, pc + len)
                pc += len
                if (isExecuting()) stack.addLast(data)
                continue
            }
            if (op == KaspaOpcodes.OP_PUSHDATA4) {
                if (pc + 4 > script.size) error("Truncated OP_PUSHDATA4 len")
                val len = (script[pc].toInt() and 0xFF) or
                    ((script[pc + 1].toInt() and 0xFF) shl 8) or
                    ((script[pc + 2].toInt() and 0xFF) shl 16) or
                    ((script[pc + 3].toInt() and 0xFF) shl 24)
                pc += 4
                if (len < 0 || pc + len > script.size) error("Truncated OP_PUSHDATA4 payload")
                val data = script.copyOfRange(pc, pc + len)
                pc += len
                if (isExecuting()) stack.addLast(data)
                continue
            }
            if (op == KaspaOpcodes.OP_1NEGATE) {
                if (isExecuting()) pushInt(-1L)
                continue
            }
            if (op in KaspaOpcodes.OP_1..KaspaOpcodes.OP_16) {
                if (isExecuting()) pushInt((op - KaspaOpcodes.OP_1 + 1).toLong())
                continue
            }

            if (pushOnly) error("Non-push opcode 0x${op.toString(16)} inside sigScript")

            // Flow control executes even inside false branches to maintain nesting
            when (op) {
                KaspaOpcodes.OP_IF -> {
                    val v = if (isExecuting()) isTruthy(pop()) else false
                    condStack.addLast(v)
                    continue
                }
                KaspaOpcodes.OP_NOTIF -> {
                    val v = if (isExecuting()) !isTruthy(pop()) else false
                    condStack.addLast(v)
                    continue
                }
                KaspaOpcodes.OP_ELSE -> {
                    if (condStack.isEmpty()) error("Unmatched OP_ELSE")
                    val prev = condStack.removeLast()
                    condStack.addLast(!prev)
                    continue
                }
                KaspaOpcodes.OP_ENDIF -> {
                    if (condStack.isEmpty()) error("Unmatched OP_ENDIF")
                    condStack.removeLast()
                    continue
                }
            }

            if (!isExecuting()) continue

            when (op) {
                KaspaOpcodes.OP_NOP -> Unit
                KaspaOpcodes.OP_VERIFY -> if (!isTruthy(pop())) {
                    error(diag.lastFalseReason ?: "OP_VERIFY failed at pc=$pc")
                }
                KaspaOpcodes.OP_RETURN -> error("OP_RETURN executed")
                KaspaOpcodes.OP_DROP -> pop()
                KaspaOpcodes.OP_2DROP -> {
                    pop(); pop()
                }
                KaspaOpcodes.OP_DUP -> {
                    val a = pop()
                    stack.addLast(a)
                    stack.addLast(a.copyOf())
                }
                KaspaOpcodes.OP_2DUP -> {
                    val b = pop()
                    val a = pop()
                    stack.addLast(a); stack.addLast(b)
                    stack.addLast(a.copyOf()); stack.addLast(b.copyOf())
                }
                KaspaOpcodes.OP_NIP -> {
                    val top = pop()
                    pop()
                    stack.addLast(top)
                }
                KaspaOpcodes.OP_OVER -> {
                    val b = pop()
                    val a = pop()
                    stack.addLast(a); stack.addLast(b); stack.addLast(a.copyOf())
                }
                KaspaOpcodes.OP_SWAP -> {
                    val b = pop()
                    val a = pop()
                    stack.addLast(b); stack.addLast(a)
                }
                KaspaOpcodes.OP_ROT -> {
                    val c = pop()
                    val b = pop()
                    val a = pop()
                    stack.addLast(b); stack.addLast(c); stack.addLast(a)
                }
                KaspaOpcodes.OP_TUCK -> {
                    val b = pop()
                    val a = pop()
                    stack.addLast(b.copyOf()); stack.addLast(a); stack.addLast(b)
                }
                KaspaOpcodes.OP_PICK -> {
                    val n = popInt().toInt()
                    if (n < 0 || n >= stack.size) error("OP_PICK index $n out of bounds")
                    stack.addLast(stack[stack.size - 1 - n].copyOf())
                }
                KaspaOpcodes.OP_ROLL -> {
                    val n = popInt().toInt()
                    if (n < 0 || n >= stack.size) error("OP_ROLL index $n out of bounds")
                    val idx = stack.size - 1 - n
                    val list = stack.toMutableList()
                    val item = list.removeAt(idx)
                    stack.clear()
                    stack.addAll(list)
                    stack.addLast(item)
                }
                KaspaOpcodes.OP_CAT -> {
                    val b = pop()
                    val a = pop()
                    val out = ByteArrayOutputStream(a.size + b.size)
                    out.write(a); out.write(b)
                    stack.addLast(out.toByteArray())
                }
                KaspaOpcodes.OP_SIZE -> {
                    if (stack.isEmpty()) error("OP_SIZE on empty stack")
                    pushInt(stack.last().size.toLong())
                }
                KaspaOpcodes.OP_EQUAL -> {
                    val b = pop(); val a = pop()
                    val eq = a.contentEquals(b)
                    if (!eq && a.size == 37 && b.size == 37) {
                        diag.lastFalseReason = "Output scriptPubKey mismatch"
                    }
                    pushBool(eq)
                }
                KaspaOpcodes.OP_EQUALVERIFY -> {
                    val b = pop(); val a = pop()
                    if (!a.contentEquals(b)) {
                        val reason = when {
                            a.size == 4 && b.size == 4 ->
                                "Unmatched entry dispatch tag (got ${KaspaCrypto.bytesToHex(a)}, expected ${KaspaCrypto.bytesToHex(b)})"
                            a.size == 32 && b.size == 32 ->
                                "Covenant binding mismatch (got ${KaspaCrypto.bytesToHex(a).take(12)}..., expected ${KaspaCrypto.bytesToHex(b).take(12)}...)"
                            (a.size in 35..37 && b.size in 35..37) || diag.lastIntrospectionKind == "SPK" ->
                                "Output scriptPubKey mismatch"
                            else ->
                                "OP_EQUALVERIFY failed (len ${a.size} vs ${b.size})"
                        }
                        error(reason)
                    }
                }
                KaspaOpcodes.OP_NOT -> pushBool(!isTruthy(pop()))
                KaspaOpcodes.OP_ADD -> {
                    val b = popInt(); val a = popInt()
                    pushInt(a + b)
                }
                KaspaOpcodes.OP_SUB -> {
                    val b = popInt(); val a = popInt()
                    pushInt(a - b)
                }
                KaspaOpcodes.OP_BOOLAND -> {
                    val b = isTruthy(pop()); val a = isTruthy(pop())
                    pushBool(a && b)
                }
                KaspaOpcodes.OP_BOOLOR -> {
                    val b = isTruthy(pop()); val a = isTruthy(pop())
                    val ok = a || b
                    if (!ok && diag.lastIntrospectionKind == "SPK") {
                        diag.lastFalseReason = "Output scriptPubKey did not match any approved address"
                    }
                    pushBool(ok)
                }
                KaspaOpcodes.OP_NUMEQUAL -> {
                    val b = popInt(); val a = popInt()
                    pushBool(a == b)
                }
                KaspaOpcodes.OP_NUMEQUALVERIFY -> {
                    val b = popInt(); val a = popInt()
                    if (a != b) error("OP_NUMEQUALVERIFY failed: vault stage/value $a != required $b")
                }
                KaspaOpcodes.OP_LESSTHAN -> {
                    val b = popInt(); val a = popInt()
                    val ok = a < b
                    if (!ok) diag.lastFalseReason = "Condition failed: $a < $b"
                    pushBool(ok)
                }
                KaspaOpcodes.OP_GREATERTHAN -> {
                    val b = popInt(); val a = popInt()
                    val ok = a > b
                    if (!ok) diag.lastFalseReason = "Condition failed: $a > $b"
                    pushBool(ok)
                }
                KaspaOpcodes.OP_LESSTHANOREQUAL -> {
                    val b = popInt(); val a = popInt()
                    val ok = a <= b
                    if (!ok) diag.lastFalseReason = "Spend/allowance cap exceeded: $a > $b"
                    pushBool(ok)
                }
                KaspaOpcodes.OP_GREATERTHANOREQUAL -> {
                    val b = popInt(); val a = popInt()
                    val ok = a >= b
                    if (!ok) diag.lastFalseReason = "Required amount/threshold not met: $a < $b"
                    pushBool(ok)
                }
                KaspaOpcodes.OP_SHA256 -> {
                    val data = pop()
                    units += (data.size.toLong() * COST_SHA256_PER_BYTE) + 32L
                    stack.addLast(KaspaCrypto.sha256(data))
                }
                KaspaOpcodes.OP_BLAKE2B -> {
                    val data = pop()
                    units += (data.size.toLong() * COST_BLAKE2B_PER_BYTE) + 32L
                    stack.addLast(KaspaCrypto.blake2b256(data, domainKey = null))
                }
                KaspaOpcodes.OP_CHECKSIG, KaspaOpcodes.OP_CHECKSIGVERIFY -> {
                    units += COST_CHECKSIG_OP
                    val pubKey = pop()
                    val sigWithType = pop()
                    val valid = verifySchnorrSig(tx, inputIndex, pubKey, sigWithType)
                    if (!valid) {
                        val pkHint = if (pubKey.isNotEmpty()) KaspaCrypto.bytesToHex(pubKey).take(16) else "<empty>"
                        val reason = "OP_CHECKSIG returned FALSE (signature/pubkey mismatch — expected pubkey ${pkHint}..., sigLen=${sigWithType.size})"
                        diag.lastFalseReason = reason
                        if (op == KaspaOpcodes.OP_CHECKSIGVERIFY) {
                            error(reason)
                        } else {
                            pushBool(false)
                        }
                    } else if (op == KaspaOpcodes.OP_CHECKSIG) {
                        pushBool(true)
                    }
                }
                KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY -> {
                    // Kaspa CLTV pops its argument and decodes as u64 LE (rusty-kaspa opcodes.rs)
                    val rawBytes = pop()
                    if (rawBytes.size > 8) error("CLTV lockTime value longer than 8 bytes")
                    var stackLockTime = 0L
                    for (i in rawBytes.indices) {
                        stackLockTime = stackLockTime or ((rawBytes[i].toLong() and 0xFFL) shl (8 * i))
                    }
                    val sameDomain = (tx.lockTime < LOCK_TIME_THRESHOLD && stackLockTime < LOCK_TIME_THRESHOLD) ||
                        (tx.lockTime >= LOCK_TIME_THRESHOLD && stackLockTime >= LOCK_TIME_THRESHOLD)
                    if (!sameDomain) {
                        error("CLTV mismatched locktime types: tx=${tx.lockTime}, stack=$stackLockTime")
                    }
                    if (stackLockTime > tx.lockTime) {
                        error("CLTV not satisfied: tx.lockTime=${tx.lockTime} < required=$stackLockTime")
                    }
                    if (tx.inputs[inputIndex].sequence == MAX_TX_IN_SEQUENCE_NUM) {
                        error("CLTV transaction input is finalized")
                    }
                    if (virtualDaaScore > 0L && tx.lockTime < LOCK_TIME_THRESHOLD && tx.lockTime >= virtualDaaScore) {
                        error("CLTV lockTime ${tx.lockTime} is not finalized yet (current DAA=$virtualDaaScore)")
                    }
                    // Kaspa consensus (`rusty-kaspa` is_finalized): timestamp lockTime is finalized ONLY when pastMedianTime > tx.lockTime!
                    if (currentTimeMs > 0L && tx.lockTime >= LOCK_TIME_THRESHOLD && tx.lockTime >= currentTimeMs) {
                        error("CLTV lockTime ${tx.lockTime} is not finalized yet (pastMedianTime=$currentTimeMs)")
                    }
                }
                KaspaOpcodes.OP_CHECKSEQUENCEVERIFY -> {
                    // Kaspa CSV pops its argument and decodes as u64 LE (NEVER emit trailing OP_DROP!)
                    val rawBytes = pop()
                    if (rawBytes.size > 8) error("CSV sequence value longer than 8 bytes")
                    var stackSeq = 0L
                    for (i in rawBytes.indices) {
                        stackSeq = stackSeq or ((rawBytes[i].toLong() and 0xFFL) shl (8 * i))
                    }
                    if ((stackSeq and SEQUENCE_LOCK_TIME_DISABLED) == 0L) {
                        val inp = tx.inputs[inputIndex]
                        if ((inp.sequence and SEQUENCE_LOCK_TIME_DISABLED) != 0L) {
                            error("CSV transaction sequence has disabled bit set")
                        }
                        val reqMasked = stackSeq and SEQUENCE_LOCK_TIME_MASK
                        val inpMasked = inp.sequence and SEQUENCE_LOCK_TIME_MASK
                        if (reqMasked > inpMasked) {
                            error("CSV not satisfied: input.sequence=$inpMasked < required=$reqMasked")
                        }
                        if (virtualDaaScore > 0L) {
                            val ageDaa = if (inp.utxo.blockDaaScore > 0L) {
                                (virtualDaaScore - inp.utxo.blockDaaScore).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            if (ageDaa < reqMasked) {
                                error("CSV age not reached: ageDaa=$ageDaa < required=$reqMasked (utxoBlockDaa=${inp.utxo.blockDaaScore}, currentDaa=$virtualDaaScore)")
                            }
                        }
                    }
                }
                KaspaOpcodes.OP_TXVERSION -> pushInt(tx.version.toLong())
                KaspaOpcodes.OP_TXINPUTCOUNT -> pushInt(tx.inputs.size.toLong())
                KaspaOpcodes.OP_TXOUTPUTCOUNT -> pushInt(tx.outputs.size.toLong())
                KaspaOpcodes.OP_TXLOCKTIME -> pushInt(tx.lockTime)
                KaspaOpcodes.OP_TXINPUTINDEX -> pushInt(inputIndex.toLong())
                KaspaOpcodes.OP_TXINPUTAMOUNT -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.inputs.indices) error("OP_TXINPUTAMOUNT index $idx out of range")
                    pushInt(tx.inputs[idx].utxo.amountSompi)
                }
                KaspaOpcodes.OP_TXINPUTSPK -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.inputs.indices) error("OP_TXINPUTSPK index $idx out of range")
                    val u = tx.inputs[idx].utxo
                    diag.lastIntrospectionKind = "SPK"
                    stack.addLast(encodeVersionedSpk(u.scriptPublicKeyVersion, KaspaCrypto.hexToBytes(u.scriptPublicKeyHex)))
                }
                KaspaOpcodes.OP_TXINPUTDAASCORE -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.inputs.indices) error("OP_TXINPUTDAASCORE index $idx out of range")
                    pushInt(tx.inputs[idx].utxo.blockDaaScore)
                }
                KaspaOpcodes.OP_TXOUTPUTAMOUNT -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.outputs.indices) error("OP_TXOUTPUTAMOUNT index $idx out of range")
                    pushInt(tx.outputs[idx].valueSompi)
                }
                KaspaOpcodes.OP_TXOUTPUTSPK -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.outputs.indices) error("OP_TXOUTPUTSPK index $idx out of range")
                    val o = tx.outputs[idx]
                    diag.lastIntrospectionKind = "SPK"
                    stack.addLast(encodeVersionedSpk(o.scriptPublicKeyVersion, o.scriptPublicKey))
                }
                KaspaOpcodes.OP_NUM2BIN -> {
                    val size = popInt().toInt()
                    if (size < 0 || size > 8) error("OP_NUM2BIN size $size out of range 0..8")
                    val raw = pop()
                    val num = KaspaScriptBuilder.deserializeScriptInt(raw)
                    val minimal = KaspaScriptBuilder.serializeScriptInt(num)
                    if (minimal.size > size) {
                        error("OP_NUM2BIN cannot fit value $num into $size bytes")
                    }
                    if (size == 0) {
                        stack.addLast(ByteArray(0))
                    } else {
                        val out = ByteArray(size)
                        if (minimal.isNotEmpty()) {
                            minimal.copyInto(out, 0)
                            if (minimal.size < size && (minimal.last().toInt() and 0x80) != 0) {
                                out[minimal.lastIndex] = (out[minimal.lastIndex].toInt() and 0x7F).toByte()
                                out[size - 1] = 0x80.toByte()
                            }
                        }
                        stack.addLast(out)
                    }
                }
                KaspaOpcodes.OP_BIN2NUM -> {
                    val raw = pop()
                    val num = KaspaScriptBuilder.deserializeScriptInt(raw)
                    stack.addLast(KaspaScriptBuilder.serializeScriptInt(num))
                }
                KaspaOpcodes.OP_INPUTCOVENANTID -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.inputs.indices) error("OP_INPUTCOVENANTID index $idx out of range")
                    val covHex = tx.inputs[idx].utxo.covenantId
                        ?: error("Input $idx has no covenant_id")
                    diag.lastIntrospectionKind = "COV"
                    stack.addLast(KaspaCrypto.hexToBytes(covHex))
                }
                KaspaOpcodes.OP_OUTPUTCOVENANTID -> {
                    val idx = popInt().toInt()
                    if (idx !in tx.outputs.indices) error("OP_OUTPUTCOVENANTID index $idx out of range")
                    val cov = tx.outputs[idx].covenant
                        ?: error("Output $idx has no covenant binding")
                    diag.lastIntrospectionKind = "COV"
                    stack.addLast(KaspaCrypto.hexToBytes(cov.covenantIdHex))
                }
                else -> error("Unsupported opcode 0x${op.toString(16)} at pc=${pc - 1}")
            }
        }
        if (condStack.isNotEmpty()) error("Unclosed OP_IF block")
        return units
    }

    /** Versioned SPK representation returned by `OP_TXINPUTSPK` / `OP_TXOUTPUTSPK` (u16 BE/LE version + script). */
    fun encodeVersionedSpk(version: Int, script: ByteArray): ByteArray {
        val out = ByteArray(2 + script.size)
        out[0] = ((version ushr 8) and 0xFF).toByte()
        out[1] = (version and 0xFF).toByte()
        script.copyInto(out, 2)
        return out
    }

    private fun verifySchnorrSig(
        tx: KaspaMassAndTx.RawTx,
        inputIndex: Int,
        pubKey: ByteArray,
        sigWithType: ByteArray,
    ): Boolean {
        if (pubKey.size != 32 || sigWithType.size != 65) return false
        val sig64 = sigWithType.copyOfRange(0, 64)
        val hashType = sigWithType[64]
        val sigHash = KaspaMassAndTx.computeSigHashAll(tx, inputIndex, hashType)
        return KaspaCrypto.verifySchnorr(pubKey, sigHash, sig64)
    }

    private fun isTruthy(bytes: ByteArray): Boolean {
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            if (b != 0) {
                // Negative zero (0x80 as MSB only) is falsy in Kaspa Script
                if (i == bytes.lastIndex && b == 0x80) return false
                return true
            }
        }
        return false
    }
}
