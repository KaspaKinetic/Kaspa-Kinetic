package com.kaspakinetic.wallet.core.covenants

import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exact Kaspa Toccata (`rusty-kaspa` 2.1.0) script opcodes.
 *
 * CRITICAL TOCATTA CONSENSUS NOTES:
 * - `OP_CHECKLOCKTIMEVERIFY` (`0xB0`) and `OP_CHECKSEQUENCEVERIFY` (`0xB1`) POP their stack argument
 *   in Kaspa (`rusty-kaspa`), unlike Bitcoin — NEVER emit a trailing `OP_DROP` after them!
 * - Data pushes > 75 bytes MUST use `OP_PUSHDATA1` (`0x4C`, 1-byte length) or `OP_PUSHDATA2` (`0x4D`, 2-byte LE length).
 */
object KaspaOpcodes {
    const val OP_0: Int = 0x00
    const val OP_FALSE: Int = 0x00
    const val OP_PUSHDATA1: Int = 0x4C
    const val OP_PUSHDATA2: Int = 0x4D
    const val OP_PUSHDATA4: Int = 0x4E
    const val OP_1NEGATE: Int = 0x4F
    const val OP_1: Int = 0x51
    const val OP_TRUE: Int = 0x51
    const val OP_2: Int = 0x52
    const val OP_3: Int = 0x53
    const val OP_4: Int = 0x54
    const val OP_5: Int = 0x55
    const val OP_16: Int = 0x60

    // Flow control
    const val OP_NOP: Int = 0x61
    const val OP_IF: Int = 0x63
    const val OP_NOTIF: Int = 0x64
    const val OP_ELSE: Int = 0x67
    const val OP_ENDIF: Int = 0x68
    const val OP_VERIFY: Int = 0x69
    const val OP_RETURN: Int = 0x6A

    // Stack
    const val OP_2DROP: Int = 0x6D
    const val OP_2DUP: Int = 0x6E
    const val OP_DROP: Int = 0x75
    const val OP_DUP: Int = 0x76
    const val OP_NIP: Int = 0x77
    const val OP_OVER: Int = 0x78
    const val OP_PICK: Int = 0x79
    const val OP_ROLL: Int = 0x7A
    const val OP_ROT: Int = 0x7B
    const val OP_SWAP: Int = 0x7C
    const val OP_TUCK: Int = 0x7D

    // Splice & comparison
    const val OP_CAT: Int = 0x7E
    const val OP_SIZE: Int = 0x82
    const val OP_EQUAL: Int = 0x87
    const val OP_EQUALVERIFY: Int = 0x88

    // Arithmetic & logic
    const val OP_NOT: Int = 0x91
    const val OP_ADD: Int = 0x93
    const val OP_SUB: Int = 0x94
    const val OP_BOOLAND: Int = 0x9A
    const val OP_BOOLOR: Int = 0x9B
    const val OP_NUMEQUAL: Int = 0x9C
    const val OP_NUMEQUALVERIFY: Int = 0x9D
    const val OP_LESSTHAN: Int = 0x9F
    const val OP_GREATERTHAN: Int = 0xA0
    const val OP_LESSTHANOREQUAL: Int = 0xA1
    const val OP_GREATERTHANOREQUAL: Int = 0xA2

    // Crypto & locktime
    const val OP_SHA256: Int = 0xA8
    const val OP_BLAKE2B: Int = 0xAA
    const val OP_CHECKSIG_ECDSA: Int = 0xAB
    const val OP_CHECKSIG: Int = 0xAC
    const val OP_CHECKSIGVERIFY: Int = 0xAD
    const val OP_CHECKLOCKTIMEVERIFY: Int = 0xB0
    const val OP_CHECKSEQUENCEVERIFY: Int = 0xB1

    // Toccata transaction & covenant introspection (rusty-kaspa 2.1.0)
    const val OP_TXVERSION: Int = 0xB2
    const val OP_TXINPUTCOUNT: Int = 0xB3
    const val OP_TXOUTPUTCOUNT: Int = 0xB4
    const val OP_TXLOCKTIME: Int = 0xB5
    const val OP_TXINPUTINDEX: Int = 0xB9
    const val OP_TXINPUTAMOUNT: Int = 0xBE
    const val OP_TXINPUTSPK: Int = 0xBF
    const val OP_TXINPUTDAASCORE: Int = 0xC0
    const val OP_TXOUTPUTAMOUNT: Int = 0xC2
    const val OP_TXOUTPUTSPK: Int = 0xC3
    const val OP_NUM2BIN: Int = 0xCD
    const val OP_BIN2NUM: Int = 0xCE
    const val OP_INPUTCOVENANTID: Int = 0xCF
    const val OP_COVINPUTCOUNT: Int = 0xD0
    const val OP_COVINPUTIDX: Int = 0xD1
    const val OP_COVOUTPUTCOUNT: Int = 0xD2
    const val OP_COVOUTPUTIDX: Int = 0xD3
    const val OP_OUTPUTCOVENANTID: Int = 0xD5
}

/**
 * Canonical Kaspa Script builder guaranteeing minimal push opcodes, proper `OP_PUSHDATA1` /
 * `OP_PUSHDATA2` framing for payloads > 75 bytes, and minimal little-endian sign-magnitude integers.
 */
class KaspaScriptBuilder {
    private val out = ByteArrayOutputStream()

    fun op(opcode: Int): KaspaScriptBuilder {
        out.write(opcode and 0xFF)
        return this
    }

    fun raw(bytes: ByteArray): KaspaScriptBuilder {
        if (bytes.isNotEmpty()) out.write(bytes)
        return this
    }

    fun pushData(data: ByteArray): KaspaScriptBuilder {
        val len = data.size
        when {
            len == 0 -> out.write(KaspaOpcodes.OP_0)
            len == 1 && (data[0].toInt() and 0xFF) in 1..16 -> {
                out.write(KaspaOpcodes.OP_1 + (data[0].toInt() and 0xFF) - 1)
            }
            len == 1 && (data[0].toInt() and 0xFF) == 0x81 -> {
                out.write(KaspaOpcodes.OP_1NEGATE)
            }
            len <= 75 -> {
                out.write(len)
                out.write(data)
            }
            len <= 0xFF -> {
                out.write(KaspaOpcodes.OP_PUSHDATA1)
                out.write(len)
                out.write(data)
            }
            len <= 0xFFFF -> {
                out.write(KaspaOpcodes.OP_PUSHDATA2)
                out.write(len and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(data)
            }
            else -> {
                out.write(KaspaOpcodes.OP_PUSHDATA4)
                out.write(len and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 24) and 0xFF)
                out.write(data)
            }
        }
        return this
    }

    /**
     * Pushes a byte array unconditionally with explicit length framing (never collapses 1-byte
     * values into `OP_1..OP_16` when raw byte push is explicitly desired for state/redeem scripts).
     */
    fun pushRawBytes(data: ByteArray): KaspaScriptBuilder {
        val len = data.size
        when {
            len == 0 -> out.write(KaspaOpcodes.OP_0)
            len <= 75 -> {
                out.write(len)
                out.write(data)
            }
            len <= 0xFF -> {
                out.write(KaspaOpcodes.OP_PUSHDATA1)
                out.write(len)
                out.write(data)
            }
            len <= 0xFFFF -> {
                out.write(KaspaOpcodes.OP_PUSHDATA2)
                out.write(len and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(data)
            }
            else -> {
                out.write(KaspaOpcodes.OP_PUSHDATA4)
                out.write(len and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 24) and 0xFF)
                out.write(data)
            }
        }
        return this
    }

    fun pushInt(value: Long): KaspaScriptBuilder {
        when {
            value == 0L -> out.write(KaspaOpcodes.OP_0)
            value == -1L -> out.write(KaspaOpcodes.OP_1NEGATE)
            value in 1L..16L -> out.write(KaspaOpcodes.OP_1 + value.toInt() - 1)
            else -> pushData(serializeScriptInt(value))
        }
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Minimal little-endian sign-magnitude integer encoding matching `kaspa_txscript::serialize_i64`. */
        fun serializeScriptInt(value: Long, fixedLen: Int? = null): ByteArray {
            if (fixedLen != null) {
                val buf = ByteBuffer.allocate(fixedLen).order(ByteOrder.LITTLE_ENDIAN)
                if (fixedLen == 8) buf.putLong(value) else {
                    var v = value
                    repeat(fixedLen) {
                        buf.put((v and 0xFFL).toByte())
                        v = v ushr 8
                    }
                }
                return buf.array()
            }
            if (value == 0L) return ByteArray(0)
            val negative = value < 0L
            var abs = if (negative) -value else value
            val bytes = ArrayList<Byte>(9)
            while (abs > 0L) {
                bytes.add((abs and 0xFFL).toByte())
                abs = abs ushr 8
            }
            if ((bytes.last().toInt() and 0x80) != 0) {
                bytes.add(if (negative) 0x80.toByte() else 0x00)
            } else if (negative) {
                bytes[bytes.lastIndex] = (bytes.last().toInt() or 0x80).toByte()
            }
            return bytes.toByteArray()
        }

        fun deserializeScriptInt(bytes: ByteArray): Long {
            if (bytes.isEmpty()) return 0L
            var result = 0L
            for (i in bytes.indices) {
                result = result or ((bytes[i].toLong() and 0xFFL) shl (8 * i))
            }
            val signBit = bytes.last().toInt() and 0x80
            return if (signBit != 0) {
                val mask = 0x80L shl (8 * (bytes.size - 1))
                -(result and mask.inv())
            } else {
                result
            }
        }
    }
}

// ============================================================================
// Portable SilverScript `SilAbiArtifact` Schema (v1 — ISC-compatible)
// ============================================================================

const val SIL_ABI_SCHEMA_VERSION: Int = 1
const val SIL_COMPILER_VERSION: String = "silverscript-kinetic-0.1.0"

@Serializable
data class SilAbiArtifact(
    @SerialName("schema_version") val schemaVersion: Int = SIL_ABI_SCHEMA_VERSION,
    @SerialName("compiler_version") val compilerVersion: String = SIL_COMPILER_VERSION,
    val structs: Map<String, SilStructArtifact> = emptyMap(),
    val contracts: Map<String, SilContractArtifact> = emptyMap(),
)

@Serializable
data class SilStructArtifact(
    val fields: List<SilFieldArtifact> = emptyList(),
)

@Serializable
data class SilFieldArtifact(
    val name: String,
    val type: String,
)

@Serializable
data class SilContractArtifact(
    @SerialName("source_path") val sourcePath: String,
    @SerialName("runtime_state") val runtimeState: SilRuntimeStateArtifact = SilRuntimeStateArtifact(),
    val entries: Map<String, SilEntryArtifact> = emptyMap(),
    val compiled: SilCompiledContractArtifact,
)

@Serializable
data class SilRuntimeStateArtifact(
    val source: String = "",
    val fields: List<SilFieldArtifact> = emptyList(),
)

@Serializable
data class SilEntryArtifact(
    @SerialName("dispatch_tag") val dispatchTag: String, // 8 lowercase hex chars (4 bytes KCC-01)
    val signature: String = "",
    val params: List<SilFieldArtifact> = emptyList(),
)

@Serializable
data class SilStateSpanArtifact(
    val offset: Int = 0,
    val len: Int = 0,
)

@Serializable
data class SilCompiledContractArtifact(
    @SerialName("bytecode_hex") val bytecodeHex: String,
    @SerialName("template_hash_hex") val templateHashHex: String,
    @SerialName("state_span") val stateSpan: SilStateSpanArtifact = SilStateSpanArtifact(),
)

sealed class SilArgValue {
    data class IntVal(val value: Long) : SilArgValue()
    data class BoolVal(val value: Boolean) : SilArgValue()
    data class BytesVal(val bytes: ByteArray) : SilArgValue()
}

/**
 * Portable SilverScript ABI codec implementing KCC-01 4-byte BLAKE3 dispatch tags,
 * canonical template hashing (`silverscript-core/src/utils.rs`), and P2SH signature script framing.
 */
object SilAbiCodec {
    /**
     * Exact KCC-01 entry dispatch tag: `blake3(entrySignature)[0..4]`.
     * Example: `entrySignature = "withdraw(sig)"` -> 4-byte tag (8 lowercase hex chars).
     */
    fun computeKcc01DispatchTag(entrySignature: String): ByteArray {
        val digest = KaspaCrypto.blake3(entrySignature.toByteArray(Charsets.UTF_8), domainKey = null)
        return digest.copyOfRange(0, 4)
    }

    fun computeKcc01DispatchTagHex(entrySignature: String): String =
        KaspaCrypto.bytesToHex(computeKcc01DispatchTag(entrySignature))

    /**
     * Exact canonical template hash matching `silverscript-core/src/utils.rs::template_hash`:
     * `blake3(u64_le(prefix.len) || prefix || u64_le(suffix.len) || suffix)`.
     */
    fun computeTemplateHash(prefix: ByteArray, suffix: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(16 + prefix.size + suffix.size)
        out.write(KaspaScriptBuilder.serializeScriptInt(prefix.size.toLong(), fixedLen = 8))
        out.write(prefix)
        out.write(KaspaScriptBuilder.serializeScriptInt(suffix.size.toLong(), fixedLen = 8))
        out.write(suffix)
        return KaspaCrypto.blake3(out.toByteArray(), domainKey = null)
    }

    /**
     * Builds the complete P2SH signature script (`sigScript`) for calling [entryName] on [contractName]:
     * `<pushed args...> <pushed 4-byte KCC-01 dispatch tag> <canonical pushed redeemScript>`
     * Uses `OP_PUSHDATA1` / `OP_PUSHDATA2` automatically whenever redeemScript > 75 bytes.
     */
    fun encodeContractEntrySigScript(
        abi: SilAbiArtifact,
        contractName: String,
        entryName: String,
        args: List<SilArgValue>,
        redeemScriptOverride: ByteArray? = null,
    ): ByteArray {
        val contract = abi.contracts[contractName]
            ?: throw IllegalArgumentException("Unknown contract '$contractName'")
        val entry = contract.entries[entryName]
            ?: throw IllegalArgumentException("Unknown entry '$contractName::$entryName'")
        require(entry.params.size == args.size) {
            "Entry '$contractName::$entryName' expects ${entry.params.size} args, got ${args.size}"
        }
        val tagBytes = KaspaCrypto.hexToBytes(entry.dispatchTag)
        require(tagBytes.size == 4) { "Invalid KCC-01 dispatch tag length: ${tagBytes.size}" }
        val redeemScript = redeemScriptOverride ?: KaspaCrypto.hexToBytes(contract.compiled.bytecodeHex)

        val builder = KaspaScriptBuilder()
        for (arg in args) {
            when (arg) {
                is SilArgValue.IntVal -> builder.pushInt(arg.value)
                is SilArgValue.BoolVal -> builder.pushInt(if (arg.value) 1L else 0L)
                is SilArgValue.BytesVal -> builder.pushData(arg.bytes)
            }
        }
        builder.pushData(tagBytes)
        builder.pushRawBytes(redeemScript)
        return builder.toByteArray()
    }
}
