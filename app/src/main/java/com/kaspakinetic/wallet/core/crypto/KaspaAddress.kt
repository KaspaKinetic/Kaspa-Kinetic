package com.kaspakinetic.wallet.core.crypto

import com.kaspakinetic.wallet.core.model.KaspaNetwork
import java.math.BigDecimal

/**
 * Kaspa Bech32 address codec, ScriptPublicKey encoder, URI parser, and Burn Address builder.
 * Matches Kaspium & Rusty-Kaspa `kaspa-addresses` byte-for-byte.
 */
object KaspaAddress {
    const val VERSION_PUBKEY: Byte = 0x00
    const val VERSION_PUBKEY_ECDSA: Byte = 0x01
    const val VERSION_SCRIPT_HASH: Byte = 0x08

    const val OP_EQUAL: Byte = 0x87.toByte()
    const val OP_BLAKE2B: Byte = 0xAA.toByte()
    const val OP_CHECKSIG_ECDSA: Byte = 0xAB.toByte()
    const val OP_CHECKSIG: Byte = 0xAC.toByte()

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = longArrayOf(
        0x98f2bc8e61L,
        0x79b76d99e2L,
        0xf33e5fb3c4L,
        0xae2eabe2a8L,
        0x1e4f43e470L,
    )

    const val MAINNET_BURN_ADDRESS =
        "kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e"

    data class DecodedAddress(
        val prefix: String,
        val version: Byte,
        val payload: ByteArray,
    ) {
        val network: KaspaNetwork?
            get() = when (prefix) {
                KaspaNetwork.MAINNET.hrp -> KaspaNetwork.MAINNET
                KaspaNetwork.TESTNET_10.hrp -> KaspaNetwork.TESTNET_10
                else -> null
            }

        fun encode(): String = encodeAddress(prefix, version, payload)

        fun toScriptPublicKey(): ByteArray = when (version) {
            VERSION_PUBKEY -> ByteArray(payload.size + 2).apply {
                this[0] = payload.size.toByte()
                payload.copyInto(this, 1)
                this[payload.size + 1] = OP_CHECKSIG
            }
            VERSION_PUBKEY_ECDSA -> ByteArray(payload.size + 2).apply {
                this[0] = payload.size.toByte()
                payload.copyInto(this, 1)
                this[payload.size + 1] = OP_CHECKSIG_ECDSA
            }
            VERSION_SCRIPT_HASH -> ByteArray(payload.size + 3).apply {
                this[0] = OP_BLAKE2B
                this[1] = payload.size.toByte()
                payload.copyInto(this, 2)
                this[payload.size + 2] = OP_EQUAL
            }
            else -> error("Unsupported Kaspa address version: $version")
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedAddress) return false
            return prefix == other.prefix && version == other.version && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var res = prefix.hashCode()
            res = 31 * res + version.hashCode()
            res = 31 * res + payload.contentHashCode()
            return res
        }
    }

    data class HighlightedAddressParts(
        val prefixWithColon: String,
        val middleBody: String,
        val checksum8: String,
    )

    data class ParsedKaspaUri(
        val address: String,
        val amountKas: BigDecimal? = null,
        val label: String? = null,
        val message: String? = null,
    )

    /**
     * Returns the official zero-point Schnorr P2PK unspendable burn address for [network]:
     * - Mainnet: `kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e`
     * - Testnet-10: dynamically encoded `kaspatest:` + version `0x00` + 32 zero bytes (`0x00 * 32`).
     */
    fun burnAddress(network: KaspaNetwork): String =
        encodeAddress(network.hrp, VERSION_PUBKEY, ByteArray(32))

    fun encodePubKey(xOnlyPubKey32: ByteArray, network: KaspaNetwork = KaspaNetwork.MAINNET): String {
        require(xOnlyPubKey32.size == 32) { "Schnorr public key must be 32 bytes" }
        return encodeAddress(network.hrp, VERSION_PUBKEY, xOnlyPubKey32)
    }

    fun encodeScriptHash(scriptHash32: ByteArray, network: KaspaNetwork = KaspaNetwork.MAINNET): String {
        require(scriptHash32.size == 32) { "P2SH script hash must be 32 bytes" }
        return encodeAddress(network.hrp, VERSION_SCRIPT_HASH, scriptHash32)
    }

    /**
     * Computes a canonical Kaspa P2SH address (`VERSION_SCRIPT_HASH = 0x08`) from a SilverScript
     * redeem script using unkeyed BLAKE2b-256 (`rusty-kaspa` `pay_to_script_hash_script`).
     */
    fun encodeP2shFromRedeemScript(redeemScript: ByteArray, network: KaspaNetwork = KaspaNetwork.MAINNET): String {
        val scriptHash = KaspaCrypto.blake2b256(redeemScript, domainKey = null)
        return encodeScriptHash(scriptHash, network)
    }

    fun p2shScriptPublicKey(redeemScript: ByteArray): ByteArray {
        val scriptHash = KaspaCrypto.blake2b256(redeemScript, domainKey = null)
        return ByteArray(35).apply {
            this[0] = OP_BLAKE2B
            this[1] = 32.toByte()
            scriptHash.copyInto(this, 2)
            this[34] = OP_EQUAL
        }
    }

    fun encodeAddress(prefix: String, version: Byte, payload: ByteArray): String {
        val hrp = prefix.lowercase()
        require(hrp.isNotEmpty()) { "Empty address prefix" }
        val raw = ByteArray(1 + payload.size)
        raw[0] = version
        payload.copyInto(raw, 1)
        val words = convertBits(raw, fromBits = 8, toBits = 5, pad = true)
        val checksum = createChecksum(hrp, words)
        val sb = StringBuilder(hrp.length + 1 + words.size + checksum.size)
        sb.append(hrp).append(':')
        for (w in words) sb.append(CHARSET[w])
        for (c in checksum) sb.append(CHARSET[c])
        return sb.toString()
    }

    fun decode(address: String): DecodedAddress? {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.length > 90) return null
        if (trimmed != trimmed.lowercase() && trimmed != trimmed.uppercase()) return null
        val lower = trimmed.lowercase()
        val sep = lower.lastIndexOf(':')
        if (sep <= 0 || lower.length - sep - 1 < 9) return null

        val hrp = lower.substring(0, sep)
        if (hrp.any { it.code < 33 || it.code > 126 }) return null

        val payloadAndChecksum = lower.substring(sep + 1)
        val values = IntArray(payloadAndChecksum.length)
        for (i in payloadAndChecksum.indices) {
            val idx = CHARSET.indexOf(payloadAndChecksum[i])
            if (idx == -1) return null
            values[i] = idx
        }

        if (!verifyChecksum(hrp, values)) return null

        val data5 = values.copyOfRange(0, values.size - 8)
        val raw = convertBits(data5, fromBits = 5, toBits = 8, pad = false)
        if (raw.size < 2) return null

        val version = raw[0]
        val payload = raw.copyOfRange(1, raw.size)
        when (version) {
            VERSION_PUBKEY -> if (payload.size != 32) return null
            VERSION_PUBKEY_ECDSA -> if (payload.size != 33) return null
            VERSION_SCRIPT_HASH -> if (payload.size != 32) return null
            else -> return null
        }
        return DecodedAddress(hrp, version, payload)
    }

    fun isValid(address: String, expectedNetwork: KaspaNetwork? = null): Boolean {
        val decoded = decode(address) ?: return false
        return expectedNetwork == null || decoded.prefix == expectedNetwork.hrp
    }

    fun convertNetworkPrefix(address: String, targetNetwork: KaspaNetwork): String {
        val decoded = decode(address) ?: return address
        return encodeAddress(targetNetwork.hrp, decoded.version, decoded.payload)
    }

    fun splitForHighlight(address: String): HighlightedAddressParts {
        val colon = address.indexOf(':')
        if (colon < 0 || address.length <= colon + 9) {
            return HighlightedAddressParts("", address, "")
        }
        val prefix = address.substring(0, colon + 1)
        val rest = address.substring(colon + 1)
        val body = rest.dropLast(8)
        val checksum = rest.takeLast(8)
        return HighlightedAddressParts(prefix, body, checksum)
    }

    fun parseUriOrAddress(raw: String): ParsedKaspaUri? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        val qIdx = cleaned.indexOf('?')
        val addressPart = if (qIdx >= 0) cleaned.substring(0, qIdx) else cleaned
        val decoded = decode(addressPart) ?: return null
        var amount: BigDecimal? = null
        var label: String? = null
        var message: String? = null
        if (qIdx >= 0 && qIdx + 1 < cleaned.length) {
            cleaned.substring(qIdx + 1).split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).lowercase()
                    val value = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    when (key) {
                        "amount" -> amount = runCatching { BigDecimal(value) }.getOrNull()
                        "label" -> label = value
                        "message" -> message = value
                    }
                }
            }
        }
        return ParsedKaspaUri(
            address = decoded.encode(),
            amountKas = amount,
            label = label,
            message = message,
        )
    }

    fun buildUri(address: String, amountKas: String? = null): String {
        val amt = amountKas?.trim()?.takeIf { it.isNotEmpty() && (it.toDoubleOrNull() ?: 0.0) > 0.0 }
        return if (amt != null) "$address?amount=$amt" else address
    }

    private fun polymod(values: IntArray): Long {
        var chk = 1L
        for (v in values) {
            val top = chk ushr 35
            chk = ((chk and 0x07ffffffffL) shl 5) xor v.toLong()
            for (i in GENERATOR.indices) {
                if (((top ushr i) and 1L) == 1L) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk xor 1L
    }

    private fun hrpExpand(hrp: String): IntArray {
        val bytes = hrp.toByteArray(Charsets.UTF_8)
        return IntArray(bytes.size) { bytes[it].toInt() and 31 }
    }

    private fun verifyChecksum(hrp: String, dataWithChecksum: IntArray): Boolean {
        val expanded = hrpExpand(hrp)
        val combined = IntArray(expanded.size + 1 + dataWithChecksum.size)
        expanded.copyInto(combined, 0)
        combined[expanded.size] = 0
        dataWithChecksum.copyInto(combined, expanded.size + 1)
        return polymod(combined) == 0L
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val expanded = hrpExpand(hrp)
        val values = IntArray(expanded.size + 1 + data.size + 8)
        expanded.copyInto(values, 0)
        values[expanded.size] = 0
        data.copyInto(values, expanded.size + 1)
        val poly = polymod(values)
        return IntArray(8) { i -> ((poly ushr (5 * (7 - i))) and 31L).toInt() }
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        val out = ArrayList<Int>((data.size * fromBits + toBits - 1) / toBits)
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            acc = (acc shl fromBits) or (b.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad && bits > 0) {
            out.add((acc shl (toBits - bits)) and maxv)
        }
        return out.toIntArray()
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        val out = ArrayList<Byte>((data.size * fromBits) / toBits + 1)
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        for (v in data) {
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) {
            out.add(((acc shl (toBits - bits)) and maxv).toByte())
        }
        return out.toByteArray()
    }
}
