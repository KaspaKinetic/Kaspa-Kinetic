package com.kaspakinetic.wallet.core.tx

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.MAX_INPUTS_PER_TX
import com.kaspakinetic.wallet.core.model.MassBreakdown
import com.kaspakinetic.wallet.core.model.PreparedTx
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.SignedTx
import com.kaspakinetic.wallet.core.model.TxOutputSummary
import com.kaspakinetic.wallet.core.model.UtxoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Pure Kaspa L1 Post-Toccata / KIP-9 Mass Engine, SigHash v0 Calculator,
 * TransactionID Calculator (rusty-kaspa v2.1.0), Transaction Builder, PSKT exporter,
 * and Schnorr Transaction Signer.
 */
object KaspaMassAndTx {
    // Post-Toccata / Rusty-Kaspa v2.1.0 Consensus Parameters (MAINNET_PARAMS / TESTNET_PARAMS)
    const val MASS_PER_TX_BYTE: Long = 1L
    const val MASS_PER_SCRIPT_PUBKEY_BYTE: Long = 10L
    const val MASS_PER_SIG_OP: Long = 1000L
    const val TRANSIENT_BYTE_TO_MASS_FACTOR: Long = 4L
    val STORAGE_MASS_PARAMETER: BigInteger =
        BigInteger.valueOf(SOMPI_PER_KAS).multiply(BigInteger.valueOf(10_000L))

    const val BLOCK_COMPUTE_MASS_LIMIT: Long = 500_000L
    const val BLOCK_STORAGE_MASS_LIMIT: Long = 500_000L
    const val BLOCK_TRANSIENT_MASS_LIMIT: Long = 1_000_000L
    const val MAX_STANDARD_TX_MASS: Long = 100_000L

    // Signature script size for standard P2PK Schnorr: 0x41 (1) + 64-byte sig + 0x01 SigHashAll (1) = 66
    const val SCHNORR_SIGNATURE_SCRIPT_SIZE: Int = 66
    const val SIG_HASH_ALL: Byte = 0x01
    const val SIG_HASH_NONE: Byte = 0x02
    const val SIG_HASH_SINGLE: Byte = 0x04
    const val SIG_HASH_ANYONE_CAN_PAY: Byte = 0x80.toByte()
    const val TRANSACTION_SIGNING_DOMAIN = "TransactionSigningHash"
    const val TRANSACTION_ID_DOMAIN = "TransactionID"
    const val TRANSACTION_HASH_DOMAIN = "TransactionHash"
    const val COVENANT_ID_DOMAIN = "CovenantID"
    const val PAYLOAD_DIGEST_DOMAIN = "PayloadDigest"
    const val TRANSACTION_REST_DOMAIN = "TransactionRest"
    const val TRANSACTION_V1_ID_DOMAIN = "TransactionV1Id"

    // Toccata v1 consensus parameters (rusty-kaspa 2.1.0 consensus/core/src/mass/units.rs)
    const val TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM: Long = 100L
    const val MASS_PER_COMPUTE_BUDGET_UNIT: Long = 100L // GRAMS_PER_COMPUTE_BUDGET_UNIT
    const val TOCCATA_SCRIPT_UNITS_PER_BUDGET_UNIT: Long = 10_000L
    const val TOCCATA_BASE_SCRIPT_UNITS: Long = 9_999L
    // Standard P2PK Schnorr OP_CHECKSIG costs 100,000 script units; budget=12 grants 129,999 units (safe headroom).
    const val DEFAULT_V1_P2PK_COMPUTE_BUDGET: Int = 12

    val SUBNETWORK_ID_NATIVE: ByteArray = ByteArray(20)
    const val SUBNETWORK_ID_NATIVE_HEX = "0000000000000000000000000000000000000000"

    // High-fee typo guard for normal sends (explicitly bypassed ONLY inside Miner Donation mode)
    const val HIGH_FEE_ABSOLUTE_GUARD_SOMPI: Long = 10L * SOMPI_PER_KAS // 10 KAS

    data class CovenantBinding(
        val authorizingInput: Int,
        val covenantIdHex: String,
    )

    data class TxInput(
        val outpointTxId: String,
        val outpointIndex: Int,
        val signatureScript: ByteArray,
        val sequence: Long = 0L,
        val sigOpCount: Int = 1,
        val utxo: UtxoItem,
        val computeBudget: Int = 0,
    )

    data class TxOutput(
        val valueSompi: Long,
        val scriptPublicKeyVersion: Int,
        val scriptPublicKey: ByteArray,
        val address: String,
        val covenant: CovenantBinding? = null,
    )

    data class RawTx(
        val version: Int = 0,
        val inputs: List<TxInput>,
        val outputs: List<TxOutput>,
        val lockTime: Long = 0L,
        val subnetworkId: ByteArray = SUBNETWORK_ID_NATIVE,
        val gas: Long = 0L,
        val payload: ByteArray = ByteArray(0),
        val storageMassOverride: Long? = null,
    )

    // -------------------------------------------------------------------------
    // Exact Post-Toccata / KIP-9 Mass Calculation
    // -------------------------------------------------------------------------

    fun isDust(amountSompi: Long): Boolean {
        if (amountSompi < DUST_LIMIT_SOMPI) return true
        // Standard relay dust formula from Kaspium / Rusty-Kaspa
        val standardOutPlusIn3x = (8L + 2L + 8L + 35L + 148L) * 3L // 603
        return (amountSompi * 1000L / standardOutPlusIn3x) < 100_000L && amountSompi < DUST_LIMIT_SOMPI
    }

    fun utxoPlurality(scriptLen: Int, hasCovenantId: Boolean = false): Long {
        val baseConst = 32 + 4 + 8 + 8 + 1 + 2 + 8 // 63
        val total = baseConst + scriptLen + if (hasCovenantId) 32 else 0
        return ((total + 99) / 100).toLong().coerceAtLeast(1L)
    }

    fun estimatedSerializedSize(tx: RawTx): Long {
        var size = 0L
        size += 2 // version u16
        size += 8 // input count u64
        for (input in tx.inputs) {
            size += 32 + 4 // outpoint
            size += 8 + input.signatureScript.size
            size += 8 // sequence
            if (tx.version >= 1) {
                size += 2 // compute_budget (u16)
            }
        }
        size += 8 // output count u64
        for (output in tx.outputs) {
            size += 8 // value u64
            size += 2 // spk version u16
            size += 8 + output.scriptPublicKey.size
            if (tx.version >= 1 && output.covenant != null) {
                size += 2 // authorizing_input (u16)
                size += 32 // covenant_id
            }
        }
        size += 8 // lockTime
        size += 20 // subnetworkId
        size += 8 // gas
        size += 32 // payload hash
        size += 8 + tx.payload.size // payload length + payload
        return size
    }

    fun scriptUnitsForComputeBudget(computeBudget: Int): Long =
        computeBudget.toLong().coerceAtLeast(0L) * TOCCATA_SCRIPT_UNITS_PER_BUDGET_UNIT + TOCCATA_BASE_SCRIPT_UNITS

    fun computeBudgetForScriptUnits(scriptUnits: Long): Int {
        if (scriptUnits <= TOCCATA_BASE_SCRIPT_UNITS) return 0
        return ((scriptUnits - TOCCATA_BASE_SCRIPT_UNITS + TOCCATA_SCRIPT_UNITS_PER_BUDGET_UNIT - 1L) /
            TOCCATA_SCRIPT_UNITS_PER_BUDGET_UNIT).toInt().coerceIn(0, 65_535)
    }

    fun calculateMass(tx: RawTx): MassBreakdown {
        val size = estimatedSerializedSize(tx)
        val sizeMass = size * MASS_PER_TX_BYTE
        val spkMass = tx.outputs.sumOf { (2L + it.scriptPublicKey.size) * MASS_PER_SCRIPT_PUBKEY_BYTE }
        val sigOpOrBudgetMass = if (tx.version >= 1) {
            // Exact rusty-kaspa calc_non_contextual_masses: GRAMS_PER_COMPUTE_BUDGET_UNIT (100) * sum(compute_budget)
            tx.inputs.sumOf { it.computeBudget.toLong().coerceAtLeast(0L) * MASS_PER_COMPUTE_BUDGET_UNIT }
        } else {
            tx.inputs.sumOf { it.sigOpCount.toLong() * MASS_PER_SIG_OP }
        }
        val computeMass = sizeMass + spkMass + sigOpOrBudgetMass
        val transientMass = size * TRANSIENT_BYTE_TO_MASS_FACTOR
        val storageMass = tx.storageMassOverride ?: calculateStorageMass(tx)

        val storageCofactor = BLOCK_COMPUTE_MASS_LIMIT.toDouble() / BLOCK_STORAGE_MASS_LIMIT.toDouble()
        val transientCofactor = BLOCK_COMPUTE_MASS_LIMIT.toDouble() / BLOCK_TRANSIENT_MASS_LIMIT.toDouble()

        val normStorage = ceil(storageMass.toDouble() * storageCofactor).toLong()
        val normTransient = ceil(transientMass.toDouble() * transientCofactor).toLong()
        val normalized = maxOf(computeMass, normTransient, normStorage)
        return MassBreakdown(
            computeMass = computeMass,
            transientMass = transientMass,
            storageMass = storageMass,
            normalizedMass = normalized,
        )
    }

    fun calculateStorageMass(tx: RawTx): Long {
        if (tx.inputs.isEmpty() || tx.outputs.isEmpty()) return 0L
        var outsPlurality = 0L
        var harmonicOuts = BigInteger.ZERO
        for (o in tx.outputs) {
            if (o.valueSompi <= 0L) return Long.MAX_VALUE
            val p = utxoPlurality(o.scriptPublicKey.size, hasCovenantId = o.covenant != null)
            outsPlurality += p
            val pSq = BigInteger.valueOf(p * p)
            harmonicOuts = harmonicOuts.add(
                STORAGE_MASS_PARAMETER.multiply(pSq).divide(BigInteger.valueOf(o.valueSompi)),
            )
        }

        val insPlurality = tx.inputs.sumOf {
            utxoPlurality(it.utxo.scriptPublicKeyHex.length / 2, hasCovenantId = !it.utxo.covenantId.isNullOrBlank())
        }
        val isRelaxed = outsPlurality == 1L ||
            insPlurality == 1L ||
            (tx.inputs.size <= 2 && outsPlurality == 2L && insPlurality == 2L)

        val storageBig = if (isRelaxed) {
            var harmonicIns = BigInteger.ZERO
            for (i in tx.inputs) {
                val p = utxoPlurality(i.utxo.scriptPublicKeyHex.length / 2, hasCovenantId = !i.utxo.covenantId.isNullOrBlank())
                val pSq = BigInteger.valueOf(p * p)
                harmonicIns = harmonicIns.add(
                    STORAGE_MASS_PARAMETER.multiply(pSq)
                        .divide(BigInteger.valueOf(i.utxo.amountSompi.coerceAtLeast(1L))),
                )
            }
            harmonicOuts.subtract(harmonicIns).max(BigInteger.ZERO)
        } else {
            val sumIns = tx.inputs.fold(BigInteger.ZERO) { acc, inp ->
                acc.add(BigInteger.valueOf(inp.utxo.amountSompi))
            }
            val meanIns = sumIns.divide(BigInteger.valueOf(insPlurality.coerceAtLeast(1L)))
            if (meanIns <= BigInteger.ZERO) {
                harmonicOuts
            } else {
                val arithmeticIns = BigInteger.valueOf(insPlurality)
                    .multiply(STORAGE_MASS_PARAMETER.divide(meanIns))
                harmonicOuts.subtract(arithmeticIns).max(BigInteger.ZERO)
            }
        }
        return storageBig.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }

    // -------------------------------------------------------------------------
    // Exact Rusty-Kaspa v2.1.0 TransactionID & SigHash v0
    // -------------------------------------------------------------------------

    private fun ByteArrayOutputStream.writeU16LE(value: Int) {
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        write(b)
    }

    private fun ByteArrayOutputStream.writeU32LE(value: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        write(b)
    }

    private fun ByteArrayOutputStream.writeU64LE(value: Long) {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        write(b)
    }

    private fun ByteArrayOutputStream.writeVarBytes(bytes: ByteArray) {
        writeU64LE(bytes.size.toLong())
        if (bytes.isNotEmpty()) write(bytes)
    }

    private fun writeOutputForHash(out: ByteArrayOutputStream, output: TxOutput, version: Int) {
        out.writeU64LE(output.valueSompi)
        out.writeU16LE(output.scriptPublicKeyVersion)
        out.writeVarBytes(output.scriptPublicKey)
        if (version >= 1) {
            val cov = output.covenant
            out.write(if (cov != null) 1 else 0)
            if (cov != null) {
                out.writeU16LE(cov.authorizingInput)
                val covBytes = KaspaCrypto.hexToBytes(cov.covenantIdHex)
                require(covBytes.size == 32) { "covenant_id must be 32 bytes" }
                out.write(covBytes)
            }
        }
    }

    private fun writeTransactionBytes(
        tx: RawTx,
        excludeSignatureScript: Boolean,
        excludeMassCommit: Boolean,
        excludePayload: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeU16LE(tx.version)
        out.writeU64LE(tx.inputs.size.toLong())
        for (input in tx.inputs) {
            out.write(KaspaCrypto.hexToBytes(input.outpointTxId))
            out.writeU32LE(input.outpointIndex)
            if (!excludeSignatureScript) {
                out.writeVarBytes(input.signatureScript)
                if (tx.version < 1) {
                    out.write(input.sigOpCount and 0xFF)
                }
            } else {
                out.writeVarBytes(ByteArray(0))
            }
            out.writeU64LE(input.sequence)
            if (!excludeMassCommit && tx.version >= 1) {
                out.writeU16LE(input.computeBudget)
            }
        }
        out.writeU64LE(tx.outputs.size.toLong())
        for (output in tx.outputs) {
            writeOutputForHash(out, output, tx.version)
        }
        out.writeU64LE(tx.lockTime)
        out.write(tx.subnetworkId)
        out.writeU64LE(tx.gas)
        if (!excludePayload) {
            out.writeVarBytes(tx.payload)
        } else {
            out.writeVarBytes(ByteArray(0))
        }
        if (!excludeMassCommit) {
            val mass = tx.storageMassOverride ?: calculateStorageMass(tx)
            if (tx.version < 1) {
                if (mass > 0L) out.writeU64LE(mass)
            } else {
                out.writeU64LE(mass)
            }
        }
        return out.toByteArray()
    }

    /**
     * Computes the exact Kaspa TransactionID matching `rusty-kaspa` `tx::id`:
     * - Version 0: BLAKE2b-256 keyed `"TransactionID"` (excludes signature scripts & mass commit)
     * - Version >= 1 (Toccata): BLAKE3 `TransactionV1Id(PayloadDigest(payload) || TransactionRest(restPreimage))`
     */
    fun computeTransactionId(tx: RawTx): String {
        if (tx.version == 0) {
            val preimage = writeTransactionBytes(
                tx = tx,
                excludeSignatureScript = true,
                excludeMassCommit = true,
                excludePayload = false,
            )
            return KaspaCrypto.bytesToHex(KaspaCrypto.blake2b256(preimage, TRANSACTION_ID_DOMAIN))
        }
        val payloadDigest = KaspaCrypto.blake3(tx.payload, PAYLOAD_DIGEST_DOMAIN)
        val restPreimage = writeTransactionBytes(
            tx = tx,
            excludeSignatureScript = true,
            excludeMassCommit = true,
            excludePayload = true,
        )
        val restDigest = KaspaCrypto.blake3(restPreimage, TRANSACTION_REST_DOMAIN)
        val combined = ByteArray(64)
        payloadDigest.copyInto(combined, 0)
        restDigest.copyInto(combined, 32)
        return KaspaCrypto.bytesToHex(KaspaCrypto.blake3(combined, TRANSACTION_V1_ID_DOMAIN))
    }

    /**
     * Computes the full `tx::hash` matching `rusty-kaspa` `hashing::tx::hash`:
     * BLAKE2b-256 keyed `"TransactionHash"`, committing to signature scripts, `compute_budget` (v1),
     * output `CovenantBinding`s (v1), and `storage_mass`.
     */
    fun computeTransactionHash(tx: RawTx): String {
        val full = writeTransactionBytes(
            tx = tx,
            excludeSignatureScript = false,
            excludeMassCommit = false,
            excludePayload = false,
        )
        return KaspaCrypto.bytesToHex(KaspaCrypto.blake2b256(full, TRANSACTION_HASH_DOMAIN))
    }

    /**
     * Computes the exact KIP-20 `covenant_id` matching `rusty-kaspa` `hashing::covenant_id::covenant_id`:
     * BLAKE2b-256 keyed `"CovenantID"` over the genesis outpoint and its authorized outputs
     * `(outputIndex, value, spkVersion, spkBytes)`.
     */
    fun computeCovenantId(
        genesisTxIdHex: String,
        genesisOutputIndex: Int,
        authOutputs: List<Pair<Int, TxOutput>>,
    ): String {
        val out = ByteArrayOutputStream()
        out.write(KaspaCrypto.hexToBytes(genesisTxIdHex))
        out.writeU32LE(genesisOutputIndex)
        out.writeU64LE(authOutputs.size.toLong())
        for ((index, output) in authOutputs) {
            out.writeU32LE(index)
            out.writeU64LE(output.valueSompi)
            out.writeU16LE(output.scriptPublicKeyVersion)
            out.writeVarBytes(output.scriptPublicKey)
        }
        return KaspaCrypto.bytesToHex(KaspaCrypto.blake2b256(out.toByteArray(), COVENANT_ID_DOMAIN))
    }

    /**
     * Computes exact Schnorr SigHash matching `rusty-kaspa` `calc_schnorr_signature_hash` for both
     * v0 and Toccata v1 transactions:
     * - Version 0: includes `sig_op_counts_hash` and per-input `sig_op_count`, outputs exclude covenant binding.
     * - Version >= 1: omits `sig_op_counts_hash` / `sig_op_count` AND omits `compute_budget`;
     *   commits to output `CovenantBinding`s (`hasCovenant`, `authorizing_input`, `covenant_id`).
     */
    fun computeSigHashAll(tx: RawTx, inputIndex: Int, hashType: Byte = SIG_HASH_ALL): ByteArray {
        val hashTypeInt = hashType.toInt() and 0xFF
        val anyoneCanPay = (hashTypeInt and (SIG_HASH_ANYONE_CAN_PAY.toInt() and 0xFF)) != 0
        val baseType = hashTypeInt and 0x1F
        val isNone = baseType == (SIG_HASH_NONE.toInt() and 0xFF)
        val isSingle = baseType == (SIG_HASH_SINGLE.toInt() and 0xFF)

        val prevOutputsHash = if (anyoneCanPay) {
            ByteArray(32)
        } else {
            ByteArrayOutputStream().apply {
                for (inp in tx.inputs) {
                    write(KaspaCrypto.hexToBytes(inp.outpointTxId))
                    writeU32LE(inp.outpointIndex)
                }
            }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
        }

        val sequencesHash = if (anyoneCanPay || isSingle || isNone) {
            ByteArray(32)
        } else {
            ByteArrayOutputStream().apply {
                for (inp in tx.inputs) writeU64LE(inp.sequence)
            }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
        }

        val sigOpCountsHash = if (tx.version < 1) {
            if (anyoneCanPay) {
                ByteArray(32)
            } else {
                ByteArrayOutputStream().apply {
                    for (inp in tx.inputs) write(inp.sigOpCount and 0xFF)
                }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
            }
        } else {
            null
        }

        val outputsHash = when {
            isNone -> ByteArray(32)
            isSingle -> {
                if (inputIndex >= tx.outputs.size) {
                    ByteArray(32)
                } else {
                    ByteArrayOutputStream().apply {
                        writeOutputForHash(this, tx.outputs[inputIndex], tx.version)
                    }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
                }
            }
            else -> ByteArrayOutputStream().apply {
                for (o in tx.outputs) {
                    writeOutputForHash(this, o, tx.version)
                }
            }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
        }

        val payloadHash = if (tx.subnetworkId.contentEquals(SUBNETWORK_ID_NATIVE) && tx.payload.isEmpty()) {
            ByteArray(32)
        } else {
            ByteArrayOutputStream().apply {
                writeVarBytes(tx.payload)
            }.toByteArray().let { KaspaCrypto.blake2b256(it, TRANSACTION_SIGNING_DOMAIN) }
        }

        val input = tx.inputs[inputIndex]
        val prevSpk = KaspaCrypto.hexToBytes(input.utxo.scriptPublicKeyHex)
        val preimage = ByteArrayOutputStream().apply {
            writeU16LE(tx.version)
            write(prevOutputsHash)
            write(sequencesHash)
            if (sigOpCountsHash != null) write(sigOpCountsHash)
            write(KaspaCrypto.hexToBytes(input.outpointTxId))
            writeU32LE(input.outpointIndex)
            writeU16LE(input.utxo.scriptPublicKeyVersion)
            writeVarBytes(prevSpk)
            writeU64LE(input.utxo.amountSompi)
            writeU64LE(input.sequence)
            if (tx.version < 1) write(input.sigOpCount and 0xFF)
            write(outputsHash)
            writeU64LE(tx.lockTime)
            write(tx.subnetworkId)
            writeU64LE(tx.gas)
            write(payloadHash)
            write(hashTypeInt)
        }.toByteArray()

        return KaspaCrypto.blake2b256(preimage, TRANSACTION_SIGNING_DOMAIN)
    }

    // -------------------------------------------------------------------------
    // Transaction Preparation (Standard, Send All, Custom UTXO, Compound, Burn, Miner Donation)
    // -------------------------------------------------------------------------

    fun prepareSend(
        network: KaspaNetwork,
        availableUtxos: List<UtxoItem>,
        recipientAddress: String,
        changeAddress: String,
        amountSompi: Long,
        feeRate: Double,
        sendAll: Boolean = false,
        customSelectedUtxos: List<UtxoItem>? = null,
        isBurn: Boolean = false,
        overrideFeeSompi: Long? = null,
        allowHighFeeDonation: Boolean = false,
    ): PreparedTx {
        val decodedRecipient = KaspaAddress.decode(recipientAddress)
            ?: throw IllegalArgumentException("Invalid recipient Kaspa address")
        val decodedChange = KaspaAddress.decode(changeAddress)
            ?: throw IllegalArgumentException("Invalid change Kaspa address")
        require(decodedRecipient.prefix == network.hrp && decodedChange.prefix == network.hrp) {
            "Address network prefix does not match ${network.id}"
        }
        require(feeRate.isFinite() && feeRate >= 1.0) { "Invalid fee rate" }

        val spendablePool = (customSelectedUtxos?.takeIf { it.isNotEmpty() } ?: availableUtxos)
            .filter { !it.isCoinbase && it.amountSompi > 0L }
            .sortedByDescending { it.amountSompi }
        require(spendablePool.isNotEmpty()) { "No spendable UTXOs available" }

        val recipientSpk = decodedRecipient.toScriptPublicKey()
        val changeSpk = decodedChange.toScriptPublicKey()

        if (sendAll) {
            val selected = spendablePool.take(MAX_INPUTS_PER_TX)
            val totalInput = selected.sumOf { it.amountSompi }
            var fee = overrideFeeSompi ?: 2_000L
            var finalMass = MassBreakdown(0, 0, 0, 0)
            var finalAmount = 0L

            for (iter in 0 until 8) {
                val outAmt = totalInput - fee
                require(outAmt >= DUST_LIMIT_SOMPI) { "Insufficient funds to cover transaction fee" }
                val candidate = buildRawTx(
                    selected = selected,
                    outputs = listOf(TxOutput(outAmt, 0, recipientSpk, decodedRecipient.encode())),
                    placeholderSigs = true,
                )
                val mass = calculateMass(candidate)
                require(mass.normalizedMass <= MAX_STANDARD_TX_MASS) { "Transaction mass exceeds standard limit" }
                val minRequiredFee = ceil(mass.normalizedMass.toDouble() * feeRate).toLong().coerceAtLeast(1L)
                val requiredFee = maxOf(minRequiredFee, overrideFeeSompi ?: 0L)
                if (fee >= requiredFee) {
                    finalMass = mass
                    finalAmount = outAmt
                    break
                }
                fee = requiredFee
            }
            if (!allowHighFeeDonation) {
                validateNormalSendFeeGuard(fee, finalAmount)
            }
            val actualFee = totalInput - finalAmount
            val reviewHash = computeReviewHash(
                network = network,
                recipient = decodedRecipient.encode(),
                changeAddr = decodedChange.encode(),
                amount = finalAmount,
                change = 0L,
                fee = actualFee,
                mass = finalMass.normalizedMass,
                selected = selected,
            )
            return PreparedTx(
                senderAddress = selected.first().address,
                recipientAddress = decodedRecipient.encode(),
                changeAddress = decodedChange.encode(),
                amountSompi = finalAmount,
                changeSompi = 0L,
                feeSompi = actualFee,
                totalInputSompi = totalInput,
                selectedUtxos = selected,
                massBreakdown = finalMass,
                reviewHash = reviewHash,
                isSendAll = true,
                isBurn = isBurn,
                isMinerDonation = allowHighFeeDonation,
            )
        }

        require(amountSompi >= DUST_LIMIT_SOMPI) { "Amount is below Kaspa dust limit (0.0001 KAS)" }
        val selected = ArrayList<UtxoItem>()
        var totalInput = 0L
        var cursor = 0
        var fee = overrideFeeSompi ?: 2_000L

        var finalChange = 0L
        var finalMass = MassBreakdown(0, 0, 0, 0)

        val fixedSelection = customSelectedUtxos != null && customSelectedUtxos.isNotEmpty()
        if (fixedSelection) {
            selected.addAll(spendablePool.take(MAX_INPUTS_PER_TX))
            totalInput = selected.sumOf { it.amountSompi }
            cursor = selected.size
        }

        var converged = false
        for (step in 0 until 16) {
            while (totalInput < amountSompi + fee) {
                if (fixedSelection || cursor >= spendablePool.size) {
                    throw IllegalStateException("Insufficient spendable funds")
                }
                if (selected.size >= MAX_INPUTS_PER_TX) {
                    throw IllegalStateException("Too many small UTXOs (>80 inputs). Compound UTXOs first.")
                }
                val next = spendablePool[cursor++]
                selected.add(next)
                totalInput += next.amountSompi
            }

            val provisionalChange = totalInput - amountSompi - fee
            val change = if (provisionalChange >= DUST_LIMIT_SOMPI) provisionalChange else 0L
            val actualFee = totalInput - amountSompi - change

            val outputs = ArrayList<TxOutput>(2).apply {
                add(TxOutput(amountSompi, 0, recipientSpk, decodedRecipient.encode()))
                if (change > 0L) add(TxOutput(change, 0, changeSpk, decodedChange.encode()))
            }

            val candidate = buildRawTx(selected, outputs, placeholderSigs = true)
            val mass = calculateMass(candidate)
            require(mass.normalizedMass <= MAX_STANDARD_TX_MASS) {
                "Transaction storage/compute mass (${mass.normalizedMass}) exceeds standard limit"
            }
            val minRequiredFee = ceil(mass.normalizedMass.toDouble() * feeRate).toLong().coerceAtLeast(1L)
            val requiredFee = maxOf(minRequiredFee, overrideFeeSompi ?: 0L)

            if (actualFee >= requiredFee) {
                finalChange = change
                finalMass = mass
                converged = true
                break
            }
            fee = requiredFee
        }
        require(converged) { "Unable to converge transaction fee and mass" }

        val finalFee = totalInput - amountSompi - finalChange
        if (!allowHighFeeDonation) {
            validateNormalSendFeeGuard(finalFee, amountSompi)
        }
        val reviewHash = computeReviewHash(
            network = network,
            recipient = decodedRecipient.encode(),
            changeAddr = decodedChange.encode(),
            amount = amountSompi,
            change = finalChange,
            fee = finalFee,
            mass = finalMass.normalizedMass,
            selected = selected,
        )
        return PreparedTx(
            senderAddress = selected.first().address,
            recipientAddress = decodedRecipient.encode(),
            changeAddress = decodedChange.encode(),
            amountSompi = amountSompi,
            changeSompi = finalChange,
            feeSompi = finalFee,
            totalInputSompi = totalInput,
            selectedUtxos = selected,
            massBreakdown = finalMass,
            reviewHash = reviewHash,
            isSendAll = false,
            isBurn = isBurn,
            isMinerDonation = allowHighFeeDonation,
        )
    }

    fun prepareCompound(
        network: KaspaNetwork,
        utxosToCompound: List<UtxoItem>,
        destinationAddress: String,
        feeRate: Double,
        overrideFeeSompi: Long? = null,
    ): PreparedTx {
        require(utxosToCompound.size >= 2) { "Select at least 2 UTXOs to compound" }
        val base = prepareSend(
            network = network,
            availableUtxos = utxosToCompound,
            recipientAddress = destinationAddress,
            changeAddress = destinationAddress,
            amountSompi = 0L,
            feeRate = feeRate,
            sendAll = true,
            customSelectedUtxos = utxosToCompound.take(MAX_INPUTS_PER_TX),
            overrideFeeSompi = overrideFeeSompi,
            allowHighFeeDonation = false,
        )
        return base.copy(isCompound = true)
    }

    /**
     * Prepares a single explicit Miner Donation self-transfer where the transaction fee
     * equals [donationFeeSompi] (bypassing the normal send high-fee typo guard ONLY here).
     */
    fun prepareMinerDonationSplit(
        network: KaspaNetwork,
        availableUtxos: List<UtxoItem>,
        selfAddress: String,
        donationFeeSompi: Long,
        minNetworkFeeRate: Double = 1.0,
    ): PreparedTx {
        require(donationFeeSompi >= 1_000L) { "Donation per split must be at least 0.00001 KAS" }
        val spendable = availableUtxos
            .filter { !it.isCoinbase && it.amountSompi > 0L }
            .sortedByDescending { it.amountSompi }
            .take(MAX_INPUTS_PER_TX)
        require(spendable.isNotEmpty()) { "No spendable UTXOs available for miner donation" }
        val total = spendable.sumOf { it.amountSompi }
        val returnAmount = total - donationFeeSompi
        require(returnAmount >= DUST_LIMIT_SOMPI) {
            "Remaining balance returning to your wallet would fall below dust limit (0.0001 KAS)"
        }
        return prepareSend(
            network = network,
            availableUtxos = spendable,
            recipientAddress = selfAddress,
            changeAddress = selfAddress,
            amountSompi = returnAmount,
            feeRate = minNetworkFeeRate.coerceAtLeast(1.0),
            sendAll = true,
            customSelectedUtxos = spendable,
            overrideFeeSompi = donationFeeSompi,
            allowHighFeeDonation = true,
        )
    }

    private fun validateNormalSendFeeGuard(feeSompi: Long, amountSompi: Long) {
        if (feeSompi > HIGH_FEE_ABSOLUTE_GUARD_SOMPI) {
            throw IllegalStateException(
                "High-fee safety guard triggered: fee (${feeSompi / SOMPI_PER_KAS.toDouble()} KAS) exceeds 10 KAS limit for standard transfers.",
            )
        }
        if (amountSompi >= SOMPI_PER_KAS && feeSompi > amountSompi / 2L) {
            throw IllegalStateException(
                "High-fee safety guard triggered: fee is abnormally large relative to transfer amount.",
            )
        }
    }

    fun buildRawTx(
        selected: List<UtxoItem>,
        outputs: List<TxOutput>,
        placeholderSigs: Boolean,
    ): RawTx {
        val sigTemplate = if (placeholderSigs) ByteArray(SCHNORR_SIGNATURE_SCRIPT_SIZE) else ByteArray(0)
        val inputs = selected.map { utxo ->
            TxInput(
                outpointTxId = utxo.transactionId,
                outpointIndex = utxo.index,
                signatureScript = sigTemplate.copyOf(),
                sequence = 0L,
                sigOpCount = 1,
                utxo = utxo,
            )
        }
        return RawTx(version = 0, inputs = inputs, outputs = outputs)
    }

    fun signPreparedTransaction(
        prepared: PreparedTx,
        keyLookup: (address: String) -> ByteArray?,
    ): SignedTx {
        val decodedRecipient = KaspaAddress.decode(prepared.recipientAddress)
            ?: throw IllegalArgumentException("Invalid recipient address")
        val decodedChange = KaspaAddress.decode(prepared.changeAddress)
            ?: throw IllegalArgumentException("Invalid change address")

        val outputs = ArrayList<TxOutput>(2).apply {
            add(TxOutput(prepared.amountSompi, 0, decodedRecipient.toScriptPublicKey(), prepared.recipientAddress))
            if (prepared.changeSompi > 0L) {
                add(TxOutput(prepared.changeSompi, 0, decodedChange.toScriptPublicKey(), prepared.changeAddress))
            }
        }
        val unsigned = buildRawTx(prepared.selectedUtxos, outputs, placeholderSigs = false)
        val signedInputs = ArrayList<TxInput>(unsigned.inputs.size)

        for (i in unsigned.inputs.indices) {
            val inp = unsigned.inputs[i]
            val privKey = keyLookup(inp.utxo.address)
                ?: throw IllegalStateException("Signing key not found for input address ${inp.utxo.address}")
            try {
                val sigHash = computeSigHashAll(unsigned, i)
                val sig64 = KaspaCrypto.signSchnorr(privKey, sigHash)
                // Signature script: OP_DATA_65 (0x41) || 64-byte Schnorr sig || SigHashAll (0x01)
                val sigScript = ByteArray(66)
                sigScript[0] = 65.toByte()
                sig64.copyInto(sigScript, 1)
                sigScript[65] = SIG_HASH_ALL
                signedInputs.add(inp.copy(signatureScript = sigScript))
            } finally {
                privKey.fill(0)
            }
        }

        val signedTx = unsigned.copy(inputs = signedInputs)
        val txId = computeTransactionId(signedTx)
        val submitJson = toRestSubmitJson(signedTx)
        val outSummaries = signedTx.outputs.mapIndexed { idx, out ->
            TxOutputSummary(
                index = idx,
                address = out.address,
                amountSompi = out.valueSompi,
                scriptPublicKeyHex = KaspaCrypto.bytesToHex(out.scriptPublicKey),
            )
        }
        return SignedTx(
            transactionId = txId,
            feeSompi = prepared.feeSompi,
            mass = prepared.massBreakdown.normalizedMass,
            submitJson = submitJson,
            reviewHash = prepared.reviewHash,
            outputs = outSummaries,
        )
    }

    /**
     * Generates REST `POST /transactions` payload matching Rusty-Kaspa / api.kaspa.org schema.
     */
    fun toRestSubmitJson(tx: RawTx): String {
        val root = buildJsonObject {
            put(
                "transaction",
                buildJsonObject {
                    put("version", tx.version)
                    put(
                        "inputs",
                        buildJsonArray {
                            tx.inputs.forEach { inp ->
                                add(
                                    buildJsonObject {
                                        put(
                                            "previousOutpoint",
                                            buildJsonObject {
                                                put("transactionId", inp.outpointTxId)
                                                put("index", inp.outpointIndex)
                                            },
                                        )
                                        put("signatureScript", KaspaCrypto.bytesToHex(inp.signatureScript))
                                        put("sequence", inp.sequence)
                                        if (tx.version >= 1) {
                                            put("computeBudget", inp.computeBudget)
                                        } else {
                                            put("sigOpCount", inp.sigOpCount)
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "outputs",
                        buildJsonArray {
                            tx.outputs.forEach { out ->
                                add(
                                    buildJsonObject {
                                        put("amount", out.valueSompi)
                                        put(
                                            "scriptPublicKey",
                                            buildJsonObject {
                                                put("version", out.scriptPublicKeyVersion)
                                                put("scriptPublicKey", KaspaCrypto.bytesToHex(out.scriptPublicKey))
                                            },
                                        )
                                        if (tx.version >= 1 && out.covenant != null) {
                                            put(
                                                "covenant",
                                                buildJsonObject {
                                                    put("authorizingInput", out.covenant.authorizingInput)
                                                    put("covenantId", out.covenant.covenantIdHex)
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put("lockTime", tx.lockTime)
                    put("subnetworkId", KaspaCrypto.bytesToHex(tx.subnetworkId))
                    if (tx.gas > 0L) put("gas", tx.gas)
                    if (tx.payload.isNotEmpty()) put("payload", KaspaCrypto.bytesToHex(tx.payload))
                    if (tx.version >= 1) {
                        val sMass = tx.storageMassOverride ?: calculateStorageMass(tx)
                        if (sMass > 0L) put("storageMass", sMass)
                    }
                },
            )
            put("allowOrphan", false)
        }
        return root.toString()
    }

    /**
     * Exports an unsigned PSKT (Partially Signed Kaspa Transaction) JSON envelope for Watch-Only cold wallets.
     */
    fun exportUnsignedPsktJson(prepared: PreparedTx, network: KaspaNetwork): String {
        val root = buildJsonObject {
            put("format", "kaspa-pskt-v1")
            put("network", network.id)
            put("reviewHash", prepared.reviewHash)
            put("mass", prepared.massBreakdown.normalizedMass)
            put("feeSompi", prepared.feeSompi)
            put(
                "inputs",
                buildJsonArray {
                    prepared.selectedUtxos.forEach { u ->
                        add(
                            buildJsonObject {
                                put("address", u.address)
                                put("transactionId", u.transactionId)
                                put("index", u.index)
                                put("amountSompi", u.amountSompi)
                                put("scriptPublicKey", u.scriptPublicKeyHex)
                                put("scriptPublicKeyVersion", u.scriptPublicKeyVersion)
                                put("blockDaaScore", u.blockDaaScore)
                            },
                        )
                    }
                },
            )
            put(
                "outputs",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", prepared.recipientAddress)
                            put("amountSompi", prepared.amountSompi)
                        },
                    )
                    if (prepared.changeSompi > 0L) {
                        add(
                            buildJsonObject {
                                put("address", prepared.changeAddress)
                                put("amountSompi", prepared.changeSompi)
                            },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    private fun computeReviewHash(
        network: KaspaNetwork,
        recipient: String,
        changeAddr: String,
        amount: Long,
        change: Long,
        fee: Long,
        mass: Long,
        selected: List<UtxoItem>,
    ): String {
        val canonical = buildString {
            append(network.id).append('|')
            append(recipient).append('|')
            append(changeAddr).append('|')
            append(amount).append('|')
            append(change).append('|')
            append(fee).append('|')
            append(mass).append('|')
            selected.forEach { append(it.transactionId).append(':').append(it.index).append(',') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return KaspaCrypto.bytesToHex(digest)
    }
}
