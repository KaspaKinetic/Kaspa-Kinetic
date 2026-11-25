package com.kaspakinetic.wallet.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure-Kotlin BIP-39 implementation supporting 12, 15, 18, 21, and 24-word English mnemonics,
 * NFKD normalization, 4-letter unique prefix lookup, and PBKDF2-HMAC-SHA512 seed derivation.
 */
object Bip39 {
    val SUPPORTED_WORD_COUNTS = listOf(12, 15, 18, 21, 24)

    fun generateMnemonic(wordCount: Int = 24, random: SecureRandom = SecureRandom()): String {
        val entropyBits = when (wordCount) {
            12 -> 128
            15 -> 160
            18 -> 192
            21 -> 224
            24 -> 256
            else -> throw IllegalArgumentException("Unsupported BIP-39 word count: $wordCount")
        }
        val entropy = ByteArray(entropyBits / 8)
        random.nextBytes(entropy)
        return try {
            entropyToMnemonic(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * Minimum 6-sided dice rolls needed to reach full BIP-39 entropy (`ceil(bits / log2(6))`):
     * - 12 words (128 bits): 50 rolls
     * - 24 words (256 bits): 99 rolls
     */
    fun requiredDiceRollsForWordCount(wordCount: Int): Int = when (wordCount) {
        12 -> 50
        15 -> 62
        18 -> 75
        21 -> 87
        24 -> 99
        else -> 99
    }

    /**
     * Derives a valid BIP-39 mnemonic deterministically from physical 6-sided dice rolls (`1..6`)
     * using SHA-256 entropy conditioning (compatible with standard Coldcard / SeedSigner dice math).
     */
    fun mnemonicFromDiceRolls(rolls: List<Int>, wordCount: Int = 24): String {
        val required = requiredDiceRollsForWordCount(wordCount)
        require(rolls.size >= required) { "Need at least $required dice rolls for $wordCount words" }
        require(rolls.all { it in 1..6 }) { "Each dice roll must be between 1 and 6" }
        val rollString = rolls.joinToString(separator = "")
        val digest = MessageDigest.getInstance("SHA-256").digest(rollString.toByteArray(Charsets.UTF_8))
        val byteLen = when (wordCount) {
            12 -> 16
            15 -> 20
            18 -> 24
            21 -> 28
            24 -> 32
            else -> throw IllegalArgumentException("Unsupported BIP-39 word count: $wordCount")
        }
        val entropy = digest.copyOfRange(0, byteLen)
        return try {
            entropyToMnemonic(entropy)
        } finally {
            digest.fill(0)
            entropy.fill(0)
        }
    }

    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size in setOf(16, 20, 24, 28, 32)) {
            "Invalid entropy length: ${entropy.size} bytes"
        }
        val entBits = entropy.size * 8
        val csBits = entBits / 32
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val totalBits = entBits + csBits
        val wordCount = totalBits / 11

        fun bitAt(bitIndex: Int): Int {
            val b = if (bitIndex < entBits) {
                entropy[bitIndex / 8].toInt() and 0xFF
            } else {
                hash[(bitIndex - entBits) / 8].toInt() and 0xFF
            }
            val offset = 7 - (bitIndex % 8)
            return (b ushr offset) and 1
        }

        val words = ArrayList<String>(wordCount)
        for (w in 0 until wordCount) {
            var idx = 0
            for (b in 0 until 11) {
                idx = (idx shl 1) or bitAt(w * 11 + b)
            }
            words.add(Bip39Wordlist.ENGLISH[idx])
        }
        return words.joinToString(" ")
    }

    fun normalizePhrase(raw: String): String {
        return raw.trim()
            .split(Regex("[\\s,;]+"))
            .mapNotNull { token ->
                val stripped = token
                    .trimStart { it.isDigit() }
                    .trimStart { it == '.' || it == ')' || it == ':' || it == '-' }
                    .trim()
                    .lowercase()
                stripped.takeIf { it.isNotEmpty() }
            }
            .joinToString(" ")
    }

    fun isValidWord(word: String): Boolean =
        Bip39Wordlist.WORD_INDEX.containsKey(word.trim().lowercase())

    /**
     * Returns BIP-39 suggestions matching [prefix].
     * Per BIP-39 spec, the first 4 characters uniquely identify every English word!
     */
    fun suggestWords(prefix: String, limit: Int = 8): List<String> {
        val clean = prefix.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        val matches = ArrayList<String>(limit)
        for (word in Bip39Wordlist.ENGLISH) {
            if (word.startsWith(clean)) {
                matches.add(word)
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    /**
     * When the user types 4 characters (or a unique prefix), returns the exact unique BIP-39 word,
     * or null if 0 or >1 words match.
     */
    fun uniqueWordForPrefix(prefix: String): String? {
        val clean = prefix.trim().lowercase()
        if (clean.length < 3) return null
        var match: String? = null
        for (word in Bip39Wordlist.ENGLISH) {
            if (word.startsWith(clean)) {
                if (match != null) return null
                match = word
            }
        }
        return match
    }

    fun validateMnemonic(phrase: String): Boolean {
        val normalized = normalizePhrase(phrase)
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.size !in SUPPORTED_WORD_COUNTS) return false

        val totalBits = words.size * 11
        val csBits = totalBits / 33
        val entBits = totalBits - csBits
        val entropy = ByteArray(entBits / 8)

        var bitPos = 0
        var checksumRead = 0
        for (word in words) {
            val idx = Bip39Wordlist.WORD_INDEX[word] ?: return false
            for (shift in 10 downTo 0) {
                val bit = (idx ushr shift) and 1
                if (bitPos < entBits) {
                    if (bit == 1) {
                        entropy[bitPos / 8] = (entropy[bitPos / 8].toInt() or (1 shl (7 - (bitPos % 8)))).toByte()
                    }
                } else {
                    checksumRead = (checksumRead shl 1) or bit
                }
                bitPos++
            }
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        entropy.fill(0)
        var expectedChecksum = 0
        for (i in 0 until csBits) {
            val bit = ((hash[i / 8].toInt() and 0xFF) ushr (7 - (i % 8))) and 1
            expectedChecksum = (expectedChecksum shl 1) or bit
        }
        return checksumRead == expectedChecksum
    }

    /**
     * Derives the 64-byte BIP-39 master seed using PBKDF2WithHmacSHA512 (2048 iterations)
     * with NFKD-normalized mnemonic & passphrase. Caller MUST zeroize the returned ByteArray.
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normMnemonic = Normalizer.normalize(normalizePhrase(mnemonic), Normalizer.Form.NFKD)
        val normSalt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
        val chars = normMnemonic.toCharArray()
        val saltBytes = normSalt.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(chars, saltBytes, 2048, 512)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
            saltBytes.fill(0)
        }
    }
}
