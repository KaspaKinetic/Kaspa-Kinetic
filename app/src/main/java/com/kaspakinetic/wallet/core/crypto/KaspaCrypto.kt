package com.kaspakinetic.wallet.core.crypto

import com.kaspakinetic.wallet.core.model.DerivationKind
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.Blake3Parameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Strictly verified pure-Kotlin/BouncyCastle Kaspa L1 cryptographic core:
 * - Keyed BLAKE2b-256 domain hashing (matching `kaspa_hashes`)
 * - BIP-32 HD derivation (`m/44'/111111'/0'`) + Legacy KDX `BIP32Kdx` (`m/44'/972/0'/0'/i'`)
 * - `kpub` / `ktub` / `xpub` Base58Check watch-only derivation
 * - BIP-340 Schnorr signing & verification + KIP-5 Personal Message signing
 */
object KaspaCrypto {
    private val SECP_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val CURVE = SECP_PARAMS.curve
    private val G: ECPoint = SECP_PARAMS.g
    val N: BigInteger = SECP_PARAMS.n
    val P: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16,
    )
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val SEVEN = BigInteger.valueOf(7)

    // Extended Key version prefixes (matching Kaspium / Rusty-Kaspa / BIP-32 / SLIP-132 / Ledger)
    const val KPUB_MAINNET_VERSION: Int = 0x038f332e
    const val KPRV_MAINNET_VERSION: Int = 0x038f2ef4
    const val KTUB_TESTNET_VERSION: Int = 0x0390a241
    const val KTRV_TESTNET_VERSION: Int = 0x03909e07
    const val KDUB_DEVNET_VERSION: Int = 0x0390467d
    const val KDRV_DEVNET_VERSION: Int = 0x03904242
    const val XPUB_BITCOIN_VERSION: Int = 0x0488b21e
    const val XPRV_BITCOIN_VERSION: Int = 0x0488ade4
    const val TPUB_BITCOIN_VERSION: Int = 0x043587cf
    const val TPRV_BITCOIN_VERSION: Int = 0x04358394
    const val YPUB_SLIP132_VERSION: Int = 0x049d7cb2
    const val YPRV_SLIP132_VERSION: Int = 0x049d7878
    const val ZPUB_SLIP132_VERSION: Int = 0x04b24746
    const val ZPRV_SLIP132_VERSION: Int = 0x04b2430c
    const val UPUB_SLIP132_VERSION: Int = 0x044a5262
    const val VPUB_SLIP132_VERSION: Int = 0x045f1cf6
    const val YPUB_MULTI_VERSION: Int = 0x0295b43f
    const val ZPUB_MULTI_VERSION: Int = 0x02aa7ed3

    private val KNOWN_PUBLIC_EXT_KEY_VERSIONS = setOf(
        KPUB_MAINNET_VERSION,
        KTUB_TESTNET_VERSION,
        KDUB_DEVNET_VERSION,
        XPUB_BITCOIN_VERSION,
        TPUB_BITCOIN_VERSION,
        YPUB_SLIP132_VERSION,
        ZPUB_SLIP132_VERSION,
        UPUB_SLIP132_VERSION,
        VPUB_SLIP132_VERSION,
        YPUB_MULTI_VERSION,
        ZPUB_MULTI_VERSION,
    )

    private val KNOWN_PRIVATE_EXT_KEY_VERSIONS = setOf(
        KPRV_MAINNET_VERSION,
        KTRV_TESTNET_VERSION,
        KDRV_DEVNET_VERSION,
        XPRV_BITCOIN_VERSION,
        TPRV_BITCOIN_VERSION,
        YPRV_SLIP132_VERSION,
        ZPRV_SLIP132_VERSION,
    )

    private val EXT_PUB_KEY_TOKEN_REGEX = Regex(
        """(?:kpub|ktub|xpub|tpub|ypub|zpub|upub|vpub|Ypub|Zpub|drub|trub)[1-9A-HJ-NP-Za-km-z]{100,120}""",
    )

    // Ledger Wallet Kaspa Advanced Logs raw APDU GET_PUBLIC_KEY hex response:
    // 0x41 (65) + 65-byte uncompressed secp256k1 pubkey (0x04 + X32 + Y32) + 0x20 (32) + 32-byte chainCode = 99 bytes (198 hex chars)
    private val LEDGER_RAW_HEX_XPUB_REGEX = Regex("""(?i)4104[0-9a-f]{128}20[0-9a-f]{64}""")

    const val HARDENED_OFFSET: Long = 0x80000000L
    const val PERSONAL_MESSAGE_DOMAIN = "PersonalMessageSigningHash"

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    // -------------------------------------------------------------------------
    // Hashing Primitives
    // -------------------------------------------------------------------------

    fun blake2b256(data: ByteArray, domainKey: String? = null): ByteArray {
        val keyBytes = domainKey?.toByteArray(Charsets.UTF_8)
        val digest = if (keyBytes != null && keyBytes.isNotEmpty()) {
            Blake2bDigest(keyBytes, 32, null, null)
        } else {
            Blake2bDigest(256)
        }
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    /**
     * Exact BLAKE3-256 matching `rusty-kaspa`'s `blake3_hasher!` macro:
     * when [domainKey] is non-null, pads UTF-8 bytes with trailing zeroes to a 32-byte key
     * and invokes keyed BLAKE3 (`Blake3Parameters.key(key32)`).
     */
    fun blake3(data: ByteArray, domainKey: String? = null): ByteArray {
        val digest = Blake3Digest(256)
        if (!domainKey.isNullOrEmpty()) {
            val raw = domainKey.toByteArray(Charsets.UTF_8)
            val key32 = ByteArray(32)
            raw.copyInto(key32, destinationOffset = 0, startIndex = 0, endIndex = minOf(raw.size, 32))
            digest.init(Blake3Parameters.key(key32))
        }
        if (data.isNotEmpty()) {
            digest.update(data, 0, data.size)
        }
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    fun pbkdf2HmacSha256(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = 100_000,
        keyLengthBits: Int = 256,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptAes256Gcm(
        key32: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
        iv12: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) },
    ): Pair<ByteArray, ByteArray> {
        require(key32.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv12.size == 12) { "AES-GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, iv12))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return iv12 to ciphertextWithTag
    }

    fun decryptAes256Gcm(
        key32: ByteArray,
        iv12: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        require(key32.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv12.size == 12) { "AES-GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, iv12))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    fun hash160(data: ByteArray): ByteArray {
        val sha = sha256(data)
        val ripemd = RIPEMD160Digest()
        ripemd.update(sha, 0, sha.size)
        val out = ByteArray(20)
        ripemd.doFinal(out, 0)
        return out
    }

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(tagHash.size * 2 + msg.size)
        tagHash.copyInto(combined, 0)
        tagHash.copyInto(combined, tagHash.size)
        msg.copyInto(combined, tagHash.size * 2)
        return sha256(combined)
    }

    // -------------------------------------------------------------------------
    // Hex & BigInt Byte Utilities
    // -------------------------------------------------------------------------

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4]).append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "Odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun bigIntTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val srcStart = if (raw.size > 32) raw.size - 32 else 0
        val len = minOf(raw.size, 32)
        System.arraycopy(raw, srcStart, out, 32 - len, len)
        return out
    }

    fun bytesToUnsignedBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    // -------------------------------------------------------------------------
    // Base58Check
    // -------------------------------------------------------------------------

    fun base58CheckEncode(payload: ByteArray): String {
        val checksum = doubleSha256(payload).copyOfRange(0, 4)
        val full = ByteArray(payload.size + 4)
        payload.copyInto(full, 0)
        checksum.copyInto(full, payload.size)

        var leadingZeros = 0
        while (leadingZeros < full.size && full[leadingZeros] == 0.toByte()) {
            leadingZeros++
        }
        var num = BigInteger(1, full)
        val base = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(BASE58_ALPHABET[r.toInt()])
            num = q
        }
        repeat(leadingZeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun base58CheckDecode(input: String): ByteArray {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Empty Base58Check string" }
        var leadingZeros = 0
        while (leadingZeros < trimmed.length && trimmed[leadingZeros] == '1') {
            leadingZeros++
        }
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (ch in trimmed) {
            val idx = BASE58_ALPHABET.indexOf(ch)
            require(idx >= 0) { "Invalid Base58 character: $ch" }
            num = num.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        val rawNoLead = if (num == BigInteger.ZERO) ByteArray(0) else {
            val b = num.toByteArray()
            if (b.isNotEmpty() && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }
        val full = ByteArray(leadingZeros + rawNoLead.size)
        rawNoLead.copyInto(full, leadingZeros)
        require(full.size >= 5) { "Base58Check payload too short" }
        val payload = full.copyOfRange(0, full.size - 4)
        val checksum = full.copyOfRange(full.size - 4, full.size)
        val expected = doubleSha256(payload).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "Invalid Base58Check checksum" }
        return payload
    }

    // -------------------------------------------------------------------------
    // Secp256k1 & BIP-340 Schnorr
    // -------------------------------------------------------------------------

    fun isValidPrivateKey(privKey32: ByteArray): Boolean {
        if (privKey32.size != 32) return false
        val d = bytesToUnsignedBigInt(privKey32)
        return d >= BigInteger.ONE && d < N
    }

    fun compressedPublicKey(privKey32: ByteArray): ByteArray {
        require(isValidPrivateKey(privKey32)) { "Invalid secp256k1 private key" }
        val d = bytesToUnsignedBigInt(privKey32)
        return G.multiply(d).normalize().getEncoded(true)
    }

    fun xOnlyPublicKey(privKey32: ByteArray): ByteArray {
        val compressed = compressedPublicKey(privKey32)
        return compressed.copyOfRange(1, 33)
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x < BigInteger.ZERO || x >= P) return null
        val ySq = x.modPow(THREE, P).add(SEVEN).mod(P)
        val exp = P.add(BigInteger.ONE).divide(FOUR)
        val y = ySq.modPow(exp, P)
        if (y.modPow(TWO, P) != ySq) return null
        val evenY = if (y.testBit(0)) P.subtract(y) else y
        return CURVE.createPoint(x, evenY)
    }

    /**
     * Generates a 64-byte BIP-340 Schnorr signature over a 32-byte [msgHash32].
     */
    fun signSchnorr(
        privateKey32: ByteArray,
        msgHash32: ByteArray,
        auxRand32: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    ): ByteArray {
        require(privateKey32.size == 32 && msgHash32.size == 32 && auxRand32.size == 32) {
            "BIP-340 inputs must all be 32 bytes"
        }
        val d0 = bytesToUnsignedBigInt(privateKey32)
        require(d0 >= BigInteger.ONE && d0 < N) { "Private key out of range" }
        val pPoint = G.multiply(d0).normalize()
        val d = if (pPoint.affineYCoord.toBigInteger().testBit(0)) N.subtract(d0) else d0
        val pXBytes = bigIntTo32Bytes(pPoint.affineXCoord.toBigInteger())

        val tHash = taggedHash("BIP0340/aux", auxRand32)
        val dBytes = bigIntTo32Bytes(d)
        val tBytes = ByteArray(32) { i -> (dBytes[i].toInt() xor tHash[i].toInt()).toByte() }

        val nonceInput = ByteArray(96)
        tBytes.copyInto(nonceInput, 0)
        pXBytes.copyInto(nonceInput, 32)
        msgHash32.copyInto(nonceInput, 64)
        val k0 = bytesToUnsignedBigInt(taggedHash("BIP0340/nonce", nonceInput)).mod(N)
        require(k0 != BigInteger.ZERO) { "BIP-340 nonce k0 == 0" }

        val rPoint = G.multiply(k0).normalize()
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) N.subtract(k0) else k0
        val rXBytes = bigIntTo32Bytes(rPoint.affineXCoord.toBigInteger())

        val challengeInput = ByteArray(96)
        rXBytes.copyInto(challengeInput, 0)
        pXBytes.copyInto(challengeInput, 32)
        msgHash32.copyInto(challengeInput, 64)
        val e = bytesToUnsignedBigInt(taggedHash("BIP0340/challenge", challengeInput)).mod(N)

        val s = k.add(e.multiply(d)).mod(N)
        val sBytes = bigIntTo32Bytes(s)
        dBytes.fill(0)
        tBytes.fill(0)
        nonceInput.fill(0)

        return ByteArray(64).apply {
            rXBytes.copyInto(this, 0)
            sBytes.copyInto(this, 32)
        }
    }

    /**
     * Verifies a 64-byte BIP-340 Schnorr signature against a 32-byte x-only public key and 32-byte message digest.
     */
    fun verifySchnorr(publicKey32: ByteArray, msgHash32: ByteArray, signature64: ByteArray): Boolean {
        if (publicKey32.size != 32 || msgHash32.size != 32 || signature64.size != 64) return false
        val pPoint = liftX(bytesToUnsignedBigInt(publicKey32)) ?: return false
        val rBytes = signature64.copyOfRange(0, 32)
        val sBytes = signature64.copyOfRange(32, 64)
        val r = bytesToUnsignedBigInt(rBytes)
        val s = bytesToUnsignedBigInt(sBytes)
        if (r >= P || s >= N) return false

        val challengeInput = ByteArray(96)
        rBytes.copyInto(challengeInput, 0)
        publicKey32.copyInto(challengeInput, 32)
        msgHash32.copyInto(challengeInput, 64)
        val e = bytesToUnsignedBigInt(taggedHash("BIP0340/challenge", challengeInput)).mod(N)

        val sG = G.multiply(s)
        val eP = pPoint.multiply(e).negate()
        val rPoint = sG.add(eP).normalize()
        if (rPoint.isInfinity) return false
        if (rPoint.affineYCoord.toBigInteger().testBit(0)) return false
        return rPoint.affineXCoord.toBigInteger() == r
    }

    // -------------------------------------------------------------------------
    // KIP-5 Personal Message Signing & Verification
    // -------------------------------------------------------------------------

    fun hashPersonalMessage(message: String): ByteArray =
        blake2b256(message.toByteArray(Charsets.UTF_8), domainKey = PERSONAL_MESSAGE_DOMAIN)

    fun signPersonalMessage(privateKey32: ByteArray, message: String): String {
        val digest = hashPersonalMessage(message)
        return bytesToHex(signSchnorr(privateKey32, digest))
    }

    fun verifyPersonalMessage(address: String, message: String, signatureHex: String): Boolean {
        val decoded = KaspaAddress.decode(address) ?: return false
        if (decoded.version != KaspaAddress.VERSION_PUBKEY) return false
        val sig = runCatching { hexToBytes(signatureHex) }.getOrNull() ?: return false
        val digest = hashPersonalMessage(message)
        return verifySchnorr(decoded.payload, digest, sig)
    }

    // -------------------------------------------------------------------------
    // BIP-32 HD Node (Standard + Legacy KDX + kpub/ktub/xpub Watch-Only)
    // -------------------------------------------------------------------------

    class HdNode(
        private val privateKeyBytes: ByteArray?,
        private val publicKeyBytes33: ByteArray?,
        val chainCode: ByteArray,
        val depth: Int = 0,
        val parentFingerprint: Int = 0,
        val childIndex: Int = 0,
        val isKdxLegacy: Boolean = false,
    ) {
        val isNeutered: Boolean get() = privateKeyBytes == null

        val compressedPublicKey: ByteArray by lazy {
            publicKeyBytes33?.copyOf() ?: compressedPublicKey(privateKeyBytes!!)
        }

        val xOnlyPublicKey: ByteArray
            get() = compressedPublicKey.copyOfRange(1, 33)

        fun privateKeyCopy(): ByteArray =
            privateKeyBytes?.copyOf() ?: error("Watch-only HD node has no private key")

        fun zeroize() {
            privateKeyBytes?.fill(0)
            chainCode.fill(0)
        }

        private fun fingerprintInt(): Int {
            val fp = hash160(compressedPublicKey)
            return ((fp[0].toInt() and 0xFF) shl 24) or
                ((fp[1].toInt() and 0xFF) shl 16) or
                ((fp[2].toInt() and 0xFF) shl 8) or
                (fp[3].toInt() and 0xFF)
        }

        fun deriveChild(index: Long): HdNode {
            require(index in 0L..0xFFFFFFFFL) { "Invalid BIP-32 index: $index" }
            val hardened = index >= HARDENED_OFFSET
            val data = if (hardened) {
                require(!isNeutered) { "Cannot derive hardened child from watch-only key" }
                ByteArray(37).apply {
                    this[0] = 0x00
                    privateKeyBytes!!.copyInto(this, 1)
                    ByteBuffer.wrap(this, 33, 4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt())
                }
            } else if (isKdxLegacy) {
                // KDX legacy uses 32-byte x-only pubkey for non-hardened derivation
                ByteArray(36).apply {
                    xOnlyPublicKey.copyInto(this, 0)
                    ByteBuffer.wrap(this, 32, 4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt())
                }
            } else {
                ByteArray(37).apply {
                    compressedPublicKey.copyInto(this, 0)
                    ByteBuffer.wrap(this, 33, 4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt())
                }
            }

            val i = hmacSha512(chainCode, data)
            data.fill(0)
            val il = i.copyOfRange(0, 32)
            val ir = i.copyOfRange(32, 64)
            i.fill(0)

            val ilInt = bytesToUnsignedBigInt(il)
            if (ilInt == BigInteger.ZERO || ilInt >= N) {
                il.fill(0)
                return deriveChild(index + 1)
            }

            return if (!isNeutered) {
                val kPar = bytesToUnsignedBigInt(privateKeyBytes!!)
                val ki = ilInt.add(kPar).mod(N)
                il.fill(0)
                if (ki == BigInteger.ZERO) return deriveChild(index + 1)
                HdNode(
                    privateKeyBytes = bigIntTo32Bytes(ki),
                    publicKeyBytes33 = null,
                    chainCode = ir,
                    depth = depth + 1,
                    parentFingerprint = fingerprintInt(),
                    childIndex = index.toInt(),
                    isKdxLegacy = isKdxLegacy,
                )
            } else {
                val parentPoint = CURVE.decodePoint(compressedPublicKey)
                val childPoint = parentPoint.add(G.multiply(ilInt)).normalize()
                il.fill(0)
                if (childPoint.isInfinity) return deriveChild(index + 1)
                HdNode(
                    privateKeyBytes = null,
                    publicKeyBytes33 = childPoint.getEncoded(true),
                    chainCode = ir,
                    depth = depth + 1,
                    parentFingerprint = fingerprintInt(),
                    childIndex = index.toInt(),
                    isKdxLegacy = isKdxLegacy,
                )
            }
        }

        fun deriveHardened(index: Int): HdNode = deriveChild(index.toLong() + HARDENED_OFFSET)

        fun derivePath(path: String): HdNode {
            val segments = path.trim().split('/').filter { it.isNotEmpty() }
            var current = this
            val startIdx = if (segments.firstOrNull() == "m") 1 else 0
            for (s in startIdx until segments.size) {
                val seg = segments[s]
                val next = if (seg.endsWith("'")) {
                    val idx = seg.dropLast(1).toInt()
                    current.deriveHardened(idx)
                } else {
                    val idx = seg.toLong()
                    current.deriveChild(idx)
                }
                if (current !== this) current.zeroize()
                current = next
            }
            return current
        }

        fun toExtendedPublicKey(network: KaspaNetwork = KaspaNetwork.MAINNET): String {
            val version = when (network) {
                KaspaNetwork.MAINNET -> KPUB_MAINNET_VERSION
                KaspaNetwork.TESTNET_10 -> KTUB_TESTNET_VERSION
            }
            return toExtendedPublicKeyWithVersion(version)
        }

        fun toExtendedPublicKeyWithVersion(version: Int): String {
            val buf = ByteArray(78)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(version)
            bb.put(depth.toByte())
            bb.putInt(parentFingerprint)
            bb.putInt(childIndex)
            chainCode.copyInto(buf, 13)
            compressedPublicKey.copyInto(buf, 45)
            return base58CheckEncode(buf)
        }

        companion object {
            fun fromSeed(seed: ByteArray, isKdxLegacy: Boolean = false): HdNode {
                require(seed.size in 16..64) { "Invalid BIP-32 seed length" }
                val i = hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
                val il = i.copyOfRange(0, 32)
                val ir = i.copyOfRange(32, 64)
                i.fill(0)
                require(isValidPrivateKey(il)) { "Invalid master key derived from seed" }
                return HdNode(
                    privateKeyBytes = il,
                    publicKeyBytes33 = null,
                    chainCode = ir,
                    depth = 0,
                    parentFingerprint = 0,
                    childIndex = 0,
                    isKdxLegacy = isKdxLegacy,
                )
            }

            fun fromExtendedPublicKey(encoded: String): HdNode {
                val cleaned = sanitizeExtendedPublicKey(encoded)
                if (isRawLedgerHexCandidate(cleaned)) {
                    return fromLedgerApduHex(cleaned)
                }
                val buf = base58CheckDecode(cleaned)
                require(buf.size == 78) { "Extended public key must be 78 bytes" }
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
                val version = bb.int
                require(version !in KNOWN_PRIVATE_EXT_KEY_VERSIONS) {
                    "Extended private keys cannot be imported as watch-only"
                }
                val depth = buf[4].toInt() and 0xFF
                val parentFingerprint = bb.getInt(5)
                val childIndex = bb.getInt(9)
                val chainCode = buf.copyOfRange(13, 45)
                val pubKey = buf.copyOfRange(45, 78)
                val pubPrefix = pubKey[0].toInt() and 0xFF
                require(pubPrefix == 0x02 || pubPrefix == 0x03) {
                    "Extended key does not contain a compressed secp256k1 public key"
                }
                val hasPublicPrefixName = cleaned.length >= 4 &&
                    (cleaned.substring(1, 4).equals("pub", ignoreCase = true) ||
                        cleaned.substring(1, 4).equals("tub", ignoreCase = true) ||
                        cleaned.substring(1, 4).equals("rub", ignoreCase = true))
                require(version in KNOWN_PUBLIC_EXT_KEY_VERSIONS || hasPublicPrefixName) {
                    "Unsupported extended public key prefix (expected kpub, ktub, or xpub)"
                }
                val point = CURVE.decodePoint(pubKey)
                require(!point.isInfinity && point.isValid) { "Public key point not on secp256k1" }
                return HdNode(
                    privateKeyBytes = null,
                    publicKeyBytes33 = pubKey,
                    chainCode = chainCode,
                    depth = depth,
                    parentFingerprint = parentFingerprint,
                    childIndex = childIndex,
                )
            }

            /**
             * Parses Ledger Wallet's raw 198-char (99-byte) Kaspa APDU GET_PUBLIC_KEY hex export:
             * `0x41` (65-byte length) + 65-byte uncompressed secp256k1 pubkey (`0x04` + X + Y) +
             * `0x20` (32-byte length) + 32-byte BIP-32 chain code.
             */
            fun fromLedgerApduHex(hexKey: String): HdNode {
                val cleanHex = hexKey.trim().removePrefix("0x").removePrefix("0X")
                require(cleanHex.length == 198) { "Ledger raw hex key must be 198 hex characters (99 bytes)" }
                val bytes = hexToBytes(cleanHex)
                require(bytes.size == 99) { "Ledger raw hex key must decode to 99 bytes" }
                require((bytes[0].toInt() and 0xFF) == 0x41) { "Invalid Ledger key pubkey length marker (expected 0x41)" }
                require((bytes[1].toInt() and 0xFF) == 0x04) { "Invalid uncompressed secp256k1 pubkey prefix (expected 0x04)" }
                require((bytes[66].toInt() and 0xFF) == 0x20) { "Invalid Ledger chain code length marker (expected 0x20)" }

                val uncompressedPub65 = bytes.copyOfRange(1, 66)
                val chainCode32 = bytes.copyOfRange(67, 99)
                val point = CURVE.decodePoint(uncompressedPub65).normalize()
                require(!point.isInfinity && point.isValid) { "Ledger public key point is not on secp256k1" }
                val compressed33 = point.getEncoded(true)
                require(compressed33.size == 33 && (compressed33[0] == 0x02.toByte() || compressed33[0] == 0x03.toByte())) {
                    "Failed to compress Ledger secp256k1 public key"
                }
                return HdNode(
                    privateKeyBytes = null,
                    publicKeyBytes33 = compressed33,
                    chainCode = chainCode32,
                    depth = 3,
                    parentFingerprint = 0,
                    childIndex = HARDENED_OFFSET.toInt(),
                )
            }

            private fun isRawLedgerHexCandidate(cleaned: String): Boolean {
                val noPrefix = cleaned.removePrefix("0x").removePrefix("0X")
                return noPrefix.length == 198 && noPrefix.all {
                    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                }
            }
        }
    }

    /**
     * Extracts and cleans a Base58Check extended public key (`kpub`, `ktub`, `xpub`, `tpub`,
     * `ypub`, `zpub`, etc.) OR a 198-character Ledger Wallet Kaspa raw APDU hex key (`4104...`)
     * from clipboard pastes, QR payloads, Ledger Live JSON logs, or BIP-380 descriptors.
     */
    fun sanitizeExtendedPublicKey(raw: String): String {
        val noInvisible = raw.replace(Regex("[\\s\\u200B-\\u200D\\uFEFF\\u00A0]+"), "")
        val ledgerMatch = LEDGER_RAW_HEX_XPUB_REGEX.find(noInvisible)
        if (ledgerMatch != null) return ledgerMatch.value.lowercase()
        val match = EXT_PUB_KEY_TOKEN_REGEX.find(noInvisible)
        if (match != null) return match.value
        return noInvisible.trim('"', '\'', '`', ',', ';')
    }

    fun isValidExtendedPublicKey(value: String): Boolean =
        runCatching { HdNode.fromExtendedPublicKey(value) }.isSuccess

    fun normalizeKpubToMainnet(encoded: String): String =
        HdNode.fromExtendedPublicKey(encoded).toExtendedPublicKey(KaspaNetwork.MAINNET)

    fun convertKpubForNetwork(encoded: String, network: KaspaNetwork): String =
        HdNode.fromExtendedPublicKey(encoded).toExtendedPublicKey(network)

    // -------------------------------------------------------------------------
    // High-Level Kaspa HD Wallet Derivation Helpers
    // -------------------------------------------------------------------------

    fun accountPath(kind: DerivationKind): String = when (kind) {
        DerivationKind.STANDARD_111111 -> "m/44'/111111'/0'"
        DerivationKind.LEGACY_972_KDX -> "m/44'/972/0'"
        DerivationKind.LEGACY_972_BIP44 -> "m/44'/972'/0'"
    }

    fun addressPath(kind: DerivationKind, chain: Int, index: Int): String = when (kind) {
        DerivationKind.STANDARD_111111 -> "m/44'/111111'/0'/$chain/$index"
        DerivationKind.LEGACY_972_KDX -> "m/44'/972/0'/$chain'/$index'"
        DerivationKind.LEGACY_972_BIP44 -> "m/44'/972'/0'/$chain/$index"
    }

    fun deriveKpubFromSeed(
        seed: ByteArray,
        kind: DerivationKind = DerivationKind.STANDARD_111111,
        network: KaspaNetwork = KaspaNetwork.MAINNET,
    ): String {
        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        val account = master.derivePath(accountPath(kind))
        return try {
            account.toExtendedPublicKey(network)
        } finally {
            account.zeroize()
            master.zeroize()
        }
    }

    fun deriveAddressFromSeed(
        seed: ByteArray,
        chain: Int,
        index: Int,
        kind: DerivationKind = DerivationKind.STANDARD_111111,
        network: KaspaNetwork = KaspaNetwork.MAINNET,
    ): String {
        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        val leaf = master.derivePath(addressPath(kind, chain, index))
        return try {
            KaspaAddress.encodePubKey(leaf.xOnlyPublicKey, network)
        } finally {
            leaf.zeroize()
            master.zeroize()
        }
    }

    fun derivePrivateKeyFromSeed(
        seed: ByteArray,
        chain: Int,
        index: Int,
        kind: DerivationKind = DerivationKind.STANDARD_111111,
    ): ByteArray {
        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        val leaf = master.derivePath(addressPath(kind, chain, index))
        return try {
            leaf.privateKeyCopy()
        } finally {
            leaf.zeroize()
            master.zeroize()
        }
    }

    fun deriveAddressFromKpub(
        kpub: String,
        chain: Int,
        index: Int,
        network: KaspaNetwork = KaspaNetwork.MAINNET,
    ): String {
        val account = HdNode.fromExtendedPublicKey(kpub)
        val chainNode = account.deriveChild(chain.toLong())
        val leaf = chainNode.deriveChild(index.toLong())
        return KaspaAddress.encodePubKey(leaf.xOnlyPublicKey, network)
    }

    /**
     * Derives a deterministic 32-byte AES-256 key dedicated exclusively to encrypting/decrypting
     * on-chain `KKCV1` covenant recovery payloads and automatic internal `.kinetic` backups
     * on the isolated internal hardened BIP-32 path `m/44'/111111'/99'/0'/0'`.
     */
    fun deriveCovenantBackupKey(seed: ByteArray, kind: DerivationKind = DerivationKind.STANDARD_111111): ByteArray {
        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        val leaf = master.derivePath("m/44'/111111'/99'/0'/0'")
        return try {
            val priv = leaf.privateKeyCopy()
            try {
                blake3(priv, "KaspaKineticCovenantBackupV1")
            } finally {
                priv.fill(0)
            }
        } finally {
            leaf.zeroize()
            master.zeroize()
        }
    }

    /**
     * Derives a dedicated covenant sub-keypair (privateKey32, xOnlyPubKey32) on isolated path
     * `m/44'/111111'/98'/0'/<index>'` for secondary covenant keys.
     */
    fun deriveCovenantSubKey(
        seed: ByteArray,
        index: Int,
        kind: DerivationKind = DerivationKind.STANDARD_111111,
    ): Pair<ByteArray, ByteArray> {
        require(index >= 0) { "Sub-key index must be non-negative" }
        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        val leaf = master.derivePath("m/44'/111111'/98'/0'/$index'")
        return try {
            leaf.privateKeyCopy() to leaf.xOnlyPublicKey
        } finally {
            leaf.zeroize()
            master.zeroize()
        }
    }

    data class ResolvedSigningKey(
        val privateKey32: ByteArray,
        val xOnlyPubKey32: ByteArray,
        val derivationPath: String,
        val chain: Int = -1,
        val index: Int = -1,
    ) {
        fun zeroize() {
            privateKey32.fill(0)
        }
    }

    /**
     * Self-healing deterministic BIP-32 key resolver for covenant spending:
     * 1. Tries any stored hint path (`m/44'/111111'/0'/chain/index` or `m/44'/111111'/98'/0'/i'`)
     *    and EXPLICITLY asserts `derivedXOnlyPubKey == targetPubKey`.
     * 2. If missing or mismatched (e.g. after wiping and restoring a wallet where `receiveIndex`
     *    reset or shifted), scans the wallet's deterministic BIP-32 keyspace:
     *    - Receive chain `0..maxStandardIndex`
     *    - Change chain `0..maxStandardIndex`
     *    - Covenant hardened & unhardened sub-keys `m/44'/111111'/98'/0'/0'..maxSubKeyIndex'`
     *    until `derivedXOnlyPubKey == targetPubKey`!
     * 3. Returns `null` if no key in this wallet matches (NEVER silently signs with the wrong key!).
     */
    fun resolveSigningKeyForPubKey(
        seed: ByteArray,
        targetXOnlyPubKeyHex: String,
        kind: DerivationKind = DerivationKind.STANDARD_111111,
        hintPath: String = "",
        hintChain: Int = -1,
        hintIndex: Int = -1,
        maxStandardIndex: Int = 200,
        maxSubKeyIndex: Int = 32,
    ): ResolvedSigningKey? {
        val cleanHex = targetXOnlyPubKeyHex.trim().lowercase()
        if (cleanHex.length != 64) return null
        val targetPub = runCatching { hexToBytes(cleanHex) }.getOrNull() ?: return null
        if (targetPub.size != 32) return null

        val master = HdNode.fromSeed(seed, isKdxLegacy = kind == DerivationKind.LEGACY_972_KDX)
        try {
            fun tryPath(path: String, chain: Int = -1, index: Int = -1): ResolvedSigningKey? {
                val leaf = runCatching { master.derivePath(path) }.getOrNull() ?: return null
                return try {
                    if (leaf.xOnlyPublicKey.contentEquals(targetPub)) {
                        ResolvedSigningKey(
                            privateKey32 = leaf.privateKeyCopy(),
                            xOnlyPubKey32 = leaf.xOnlyPublicKey.copyOf(),
                            derivationPath = path,
                            chain = chain,
                            index = index,
                        )
                    } else {
                        null
                    }
                } finally {
                    leaf.zeroize()
                }
            }

            // 1. Stored explicit derivation path hint
            if (hintPath.isNotBlank()) {
                tryPath(hintPath.trim(), hintChain, hintIndex)?.let { return it }
            }
            // 2. Stored (chain, index) hint
            if (hintChain in 0..1 && hintIndex >= 0) {
                tryPath(addressPath(kind, hintChain, hintIndex), hintChain, hintIndex)?.let { return it }
            }

            // 3. Fast account-cached scan of receive (0) and change (1) chains across 0..maxStandardIndex
            val account = master.derivePath(accountPath(kind))
            try {
                for (chain in 0..1) {
                    val chainIdx = if (kind == DerivationKind.LEGACY_972_KDX) {
                        chain.toLong() or HARDENED_OFFSET
                    } else {
                        chain.toLong()
                    }
                    val chainNode = account.deriveChild(chainIdx)
                    try {
                        for (idx in 0..maxStandardIndex) {
                            val childIdx = if (kind == DerivationKind.LEGACY_972_KDX) {
                                idx.toLong() or HARDENED_OFFSET
                            } else {
                                idx.toLong()
                            }
                            val leaf = chainNode.deriveChild(childIdx)
                            val matched = leaf.xOnlyPublicKey.contentEquals(targetPub)
                            if (matched) {
                                val res = ResolvedSigningKey(
                                    privateKey32 = leaf.privateKeyCopy(),
                                    xOnlyPubKey32 = leaf.xOnlyPublicKey.copyOf(),
                                    derivationPath = addressPath(kind, chain, idx),
                                    chain = chain,
                                    index = idx,
                                )
                                leaf.zeroize()
                                return res
                            }
                            leaf.zeroize()
                        }
                    } finally {
                        chainNode.zeroize()
                    }
                }
            } finally {
                account.zeroize()
            }

            // 4. Covenant sub-key branch m/44'/111111'/98'/0'/<i'> (hardened & unhardened)
            val subBranchHardened = runCatching { master.derivePath("m/44'/111111'/98'/0'") }.getOrNull()
            if (subBranchHardened != null) {
                try {
                    for (idx in 0..maxSubKeyIndex) {
                        val hLeaf = subBranchHardened.deriveChild(idx.toLong() or HARDENED_OFFSET)
                        if (hLeaf.xOnlyPublicKey.contentEquals(targetPub)) {
                            val res = ResolvedSigningKey(
                                privateKey32 = hLeaf.privateKeyCopy(),
                                xOnlyPubKey32 = hLeaf.xOnlyPublicKey.copyOf(),
                                derivationPath = "m/44'/111111'/98'/0'/$idx'",
                            )
                            hLeaf.zeroize()
                            return res
                        }
                        hLeaf.zeroize()

                        val uLeaf = subBranchHardened.deriveChild(idx.toLong())
                        if (uLeaf.xOnlyPublicKey.contentEquals(targetPub)) {
                            val res = ResolvedSigningKey(
                                privateKey32 = uLeaf.privateKeyCopy(),
                                xOnlyPubKey32 = uLeaf.xOnlyPublicKey.copyOf(),
                                derivationPath = "m/44'/111111'/98'/0'/$idx",
                            )
                            uLeaf.zeroize()
                            return res
                        }
                        uLeaf.zeroize()
                    }
                } finally {
                    subBranchHardened.zeroize()
                }
            }

            return null
        } finally {
            master.zeroize()
        }
    }
}
