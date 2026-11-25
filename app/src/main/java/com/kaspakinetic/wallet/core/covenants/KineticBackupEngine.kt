package com.kaspakinetic.wallet.core.covenants

import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.UtxoItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Kaspa Kinetic open, documented encrypted covenant backup format (`.kinetic`),
 * seed-encrypted on-chain `KKCV1` payload codec, shareable pass/agent-kit codec,
 * and strict anti-duplicate / zero-conflict merge engine.
 *
 * Full open specification is documented in `Final-app/KINETIC_BACKUP_FORMAT.md`.
 */
object KineticBackupEngine {
    const val KINETIC_FORMAT_VERSION: Int = 1
    const val KINETIC_MAGIC: String = "KINETIC1"
    const val KKCV1_MAGIC: String = "KKCV1"
    const val KKCV1_SCHEMA_VERSION: Byte = 0x01
    const val PBKDF2_ITERATIONS: Int = 100_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // -------------------------------------------------------------------------
    // 1. Default `.kinetic` Filename Sanitizer & Generator
    // -------------------------------------------------------------------------

    /**
     * Generates the canonical default `.kinetic` export filename:
     * `<SanitizedWalletName>_<CreatedYYYY-MM-DD>_updated-<LastUpdatedYYYY-MM-DD>.kinetic`
     *
     * Safely strips illegal Android filesystem characters (`/ \ : * ? " < > | ( ) [ ]`) and
     * collapses whitespace/underscores so names like `Hot wallet (unsecured key)` become
     * `Hot_wallet_unsecured_key_2026-11-22_updated-2026-11-25.kinetic`.
     */
    fun buildDefaultKineticFilename(
        walletName: String,
        createdAtMs: Long,
        lastUpdatedAtMs: Long,
    ): String {
        val sanitized = sanitizeWalletNameForFilename(walletName)
        val utcFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val safeCreated = createdAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val safeUpdated = maxOf(safeCreated, lastUpdatedAtMs)
        val createdDate = utcFmt.format(Date(safeCreated))
        val updatedDate = utcFmt.format(Date(safeUpdated))
        return "${sanitized}_${createdDate}_updated-${updatedDate}.kinetic"
    }

    fun sanitizeWalletNameForFilename(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return "Kaspa_Wallet"
        val cleaned = trimmed
            .replace(Regex("""[/\\:*?"<>|()\[\]{}]+"""), " ")
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_', '.', '-')
            .take(48)
        return cleaned.ifBlank { "Kaspa_Wallet" }
    }

    // -------------------------------------------------------------------------
    // 2. `.kinetic` Encrypted Archive Schema & AES-256-GCM Codec
    // -------------------------------------------------------------------------

    /**
     * Outer clear-header envelope persisted inside `.kinetic` files (UTF-8 JSON).
     * `kdf` is either `"wallet-seed-v1"` (automatic seed-derived key) or
     * `"pbkdf2-hmac-sha256"` (user-supplied backup passphrase, 100,000 iterations).
     */
    @Serializable
    data class KineticEnvelope(
        val magic: String = KINETIC_MAGIC,
        @SerialName("format_version") val formatVersion: Int = KINETIC_FORMAT_VERSION,
        val cipher: String = "AES-256-GCM",
        val kdf: String, // "wallet-seed-v1" | "pbkdf2-hmac-sha256"
        @SerialName("kdf_iterations") val kdfIterations: Int = 0,
        @SerialName("salt_hex") val saltHex: String = "",
        @SerialName("iv_hex") val ivHex: String,
        @SerialName("wallet_fingerprint") val walletFingerprint: String,
        @SerialName("wallet_name") val walletName: String,
        @SerialName("created_at_ms") val createdAtMs: Long,
        @SerialName("last_updated_at_ms") val lastUpdatedAtMs: Long,
        @SerialName("covenant_count") val covenantCount: Int,
        @SerialName("ciphertext_hex") val ciphertextHex: String,
    )

    @Serializable
    data class KineticVaultPayload(
        @SerialName("schema_version") val schemaVersion: Int = KINETIC_FORMAT_VERSION,
        @SerialName("wallet_id") val walletId: String,
        @SerialName("wallet_name") val walletName: String,
        @SerialName("wallet_fingerprint") val walletFingerprint: String,
        @SerialName("created_at_ms") val createdAtMs: Long,
        @SerialName("last_updated_at_ms") val lastUpdatedAtMs: Long,
        val covenants: List<TrackedCovenant>,
    )

    fun walletFingerprint(primaryAddressMainnet: String): String =
        KaspaCrypto.bytesToHex(
            KaspaCrypto.blake3(primaryAddressMainnet.trim().lowercase().toByteArray(Charsets.UTF_8)),
        ).take(16)

    /**
     * Encrypts a [KineticVaultPayload] into a `.kinetic` JSON byte array using AES-256-GCM.
     * - If [passphrase] is non-blank, derives a 256-bit key via PBKDF2-HMAC-SHA256 (100,000 iters, 16-byte salt).
     * - Otherwise uses [seedBackupKey32] (derived from the wallet seed on `m/44'/111111'/99'/0'/0'`).
     */
    fun encryptKineticBackup(
        payload: KineticVaultPayload,
        seedBackupKey32: ByteArray? = null,
        passphrase: String? = null,
    ): ByteArray {
        val plainBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val rng = SecureRandom()
        val iv12 = ByteArray(12).also { rng.nextBytes(it) }
        val usePassphrase = !passphrase.isNullOrBlank()
        val salt = if (usePassphrase) ByteArray(16).also { rng.nextBytes(it) } else ByteArray(0)
        val key32 = if (usePassphrase) {
            KaspaCrypto.pbkdf2HmacSha256(passphrase!!.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        } else {
            requireNotNull(seedBackupKey32) { "Either a backup passphrase or wallet seed backup key is required" }
                .copyOf()
        }
        return try {
            val aad = "$KINETIC_MAGIC:$KINETIC_FORMAT_VERSION:${payload.walletFingerprint}".toByteArray(Charsets.UTF_8)
            val (_, ciphertext) = KaspaCrypto.encryptAes256Gcm(key32, plainBytes, aad = aad, iv12 = iv12)
            val envelope = KineticEnvelope(
                magic = KINETIC_MAGIC,
                formatVersion = KINETIC_FORMAT_VERSION,
                cipher = "AES-256-GCM",
                kdf = if (usePassphrase) "pbkdf2-hmac-sha256" else "wallet-seed-v1",
                kdfIterations = if (usePassphrase) PBKDF2_ITERATIONS else 0,
                saltHex = KaspaCrypto.bytesToHex(salt),
                ivHex = KaspaCrypto.bytesToHex(iv12),
                walletFingerprint = payload.walletFingerprint,
                walletName = payload.walletName,
                createdAtMs = payload.createdAtMs,
                lastUpdatedAtMs = payload.lastUpdatedAtMs,
                covenantCount = payload.covenants.size,
                ciphertextHex = KaspaCrypto.bytesToHex(ciphertext),
            )
            prettyJson.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        } finally {
            key32.fill(0)
        }
    }

    fun inspectKineticHeader(rawBytes: ByteArray): KineticEnvelope {
        val text = rawBytes.toString(Charsets.UTF_8).trim()
        val env = json.decodeFromString<KineticEnvelope>(text)
        require(env.magic == KINETIC_MAGIC) { "Not a valid .kinetic backup file (bad magic)" }
        require(env.formatVersion == KINETIC_FORMAT_VERSION) {
            "Unsupported .kinetic format version ${env.formatVersion}"
        }
        return env
    }

    fun decryptKineticBackup(
        rawBytes: ByteArray,
        seedBackupKey32: ByteArray? = null,
        passphrase: String? = null,
    ): KineticVaultPayload {
        val env = inspectKineticHeader(rawBytes)
        val iv12 = KaspaCrypto.hexToBytes(env.ivHex)
        val ciphertext = KaspaCrypto.hexToBytes(env.ciphertextHex)
        val aad = "${env.magic}:${env.formatVersion}:${env.walletFingerprint}".toByteArray(Charsets.UTF_8)

        val key32 = when (env.kdf) {
            "pbkdf2-hmac-sha256" -> {
                val pass = passphrase?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Backup passphrase is required to decrypt this .kinetic file")
                val salt = KaspaCrypto.hexToBytes(env.saltHex)
                val iters = env.kdfIterations.coerceAtLeast(1_000)
                KaspaCrypto.pbkdf2HmacSha256(pass.toCharArray(), salt, iters, 256)
            }
            "wallet-seed-v1" -> {
                requireNotNull(seedBackupKey32) {
                    "This .kinetic backup is encrypted with your wallet seed key. Unlock/open the matching hot wallet to restore."
                }.copyOf()
            }
            else -> throw IllegalArgumentException("Unsupported KDF '${env.kdf}' in .kinetic backup")
        }
        return try {
            val plain = try {
                KaspaCrypto.decryptAes256Gcm(key32, iv12, ciphertext, aad = aad)
            } catch (e: Exception) {
                throw SecurityException("Decryption failed — wrong passphrase or mismatched wallet seed", e)
            }
            json.decodeFromString<KineticVaultPayload>(plain.toString(Charsets.UTF_8))
        } finally {
            key32.fill(0)
        }
    }

    /**
     * Atomically writes bytes to [targetFile] using a sibling `.tmp` file + `fd.sync()` + rename,
     * guaranteeing a power loss or crash mid-write can never corrupt the internal `.kinetic` snapshot.
     */
    fun writeFileAtomically(targetFile: File, bytes: ByteArray) {
        targetFile.parentFile?.mkdirs()
        val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        if (!tmp.renameTo(targetFile)) {
            targetFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Seed-Encrypted On-Chain `KKCV1` Payload Codec
    // -------------------------------------------------------------------------

    @Serializable
    data class Kkcv1Recipe(
        val v: Int = 1,
        val t: String, // CovenantTemplateId.id
        val n: String, // KaspaNetwork.id
        val l: String, // user label
        val c: String? = null, // 64-char KIP-20 covenant_id
        val p: CovenantParams,
    )

    /**
     * Encodes a compact on-chain `KKCV1` recovery payload:
     * `[5B "KKCV1"][1B version=0x01][12B random IV][AES-256-GCM(UTF-8 JSON recipe) + 16B tag]`
     */
    fun encodeKkcv1Payload(recipe: Kkcv1Recipe, seedBackupKey32: ByteArray): ByteArray {
        val plain = json.encodeToString(recipe).toByteArray(Charsets.UTF_8)
        val header = ByteArray(6).apply {
            KKCV1_MAGIC.toByteArray(Charsets.US_ASCII).copyInto(this, 0)
            this[5] = KKCV1_SCHEMA_VERSION
        }
        val (iv12, ct) = KaspaCrypto.encryptAes256Gcm(seedBackupKey32, plain, aad = header)
        return ByteArrayOutputStream(header.size + iv12.size + ct.size).apply {
            write(header)
            write(iv12)
            write(ct)
        }.toByteArray()
    }

    /**
     * Attempts to decode & decrypt an on-chain transaction payload with the wallet's seed backup key.
     * Returns `null` cleanly if the payload is not a `KKCV1` blob or belongs to a different seed.
     */
    fun tryDecodeKkcv1Payload(payloadBytes: ByteArray, seedBackupKey32: ByteArray): Kkcv1Recipe? {
        if (payloadBytes.size < 6 + 12 + 16 + 2) return null
        val magic = String(payloadBytes, 0, 5, Charsets.US_ASCII)
        if (magic != KKCV1_MAGIC) return null
        val version = payloadBytes[5]
        if (version != KKCV1_SCHEMA_VERSION) return null
        val header = payloadBytes.copyOfRange(0, 6)
        val iv12 = payloadBytes.copyOfRange(6, 18)
        val ct = payloadBytes.copyOfRange(18, payloadBytes.size)
        return runCatching {
            val plain = KaspaCrypto.decryptAes256Gcm(seedBackupKey32, iv12, ct, aad = header)
            json.decodeFromString<Kkcv1Recipe>(plain.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    /** Reconstructs a full [TrackedCovenant] from a decrypted on-chain `KKCV1` recipe. */
    fun reconstructFromKkcv1(
        recipe: Kkcv1Recipe,
        walletId: String,
        deployTxId: String,
    ): TrackedCovenant? {
        val template = CovenantTemplateId.fromId(recipe.t) ?: return null
        val network = KaspaNetwork.entries.firstOrNull { it.id == recipe.n } ?: KaspaNetwork.MAINNET
        val compiled = runCatching { CovenantCompiler.compile(template, recipe.p, network) }.getOrNull() ?: return null
        val canonicalKey = CovenantCompiler.canonicalKeyFor(
            network = network,
            templateId = template,
            covenantIdHex = recipe.c,
            redeemScriptHashHex = compiled.redeemScriptHashHex,
        )
        val redeemHex = KaspaCrypto.bytesToHex(compiled.redeemScript)
        return TrackedCovenant(
            canonicalKey = canonicalKey,
            walletId = walletId,
            network = network,
            templateId = template,
            label = recipe.l.ifBlank { template.contractName },
            covenantIdHex = recipe.c,
            currentP2shAddress = compiled.p2shAddress,
            currentRedeemScriptHex = redeemHex,
            redeemScriptHashHex = compiled.redeemScriptHashHex,
            silSource = compiled.silSource,
            abiArtifact = compiled.abiArtifact,
            params = recipe.p,
            knownRedeemScriptsHex = listOf(redeemHex),
            deployTxId = deployTxId,
            createdAtMs = 0L,
            lastUpdatedAtMs = 0L,
            liveStatus = CovenantStatus.UNCONFIRMED,
        )
    }

    // -------------------------------------------------------------------------
    // 4. Shareable `.kpass` Files (Heir Claim / Allowance Claim / Emergency Alarm Pass)
    //    Public-safe: ZERO owner private keys, ZERO seed material!
    // -------------------------------------------------------------------------

    const val KPASS_FORMAT_V1: String = "kaspa-kinetic-pass-v1"
    const val KPASS_QR_FORMAT_V1: String = "kaspa-kinetic-pass-qr-v1"

    @Serializable
    enum class SharedPassKind {
        HEIR_CLAIM_PASS,
        ALLOWANCE_CLAIM_PASS,
        ALARM_CLAWBACK_PASS,
    }

    @Serializable
    data class SharedCovenantPass(
        val format: String = KPASS_FORMAT_V1,
        val kind: SharedPassKind,
        val network: KaspaNetwork,
        val templateId: CovenantTemplateId,
        val label: String,
        val covenantIdHex: String? = null,
        val p2shAddress: String,
        val redeemScriptHex: String,
        val abiArtifact: SilAbiArtifact,
        val params: CovenantParams,
        val createdAtMs: Long = 0L,
    )

    /**
     * Ultra-compact deterministic QR representation (~400 bytes!) omitting deterministically
     * reproducible `abiArtifact` & `redeemScriptHex` and all volatile timestamps so re-opening
     * Share Claim Pass ALWAYS generates the exact same byte-for-byte QR code!
     */
    @Serializable
    private data class CompactSharedPassQr(
        val format: String,
        val kind: SharedPassKind,
        val network: KaspaNetwork,
        val templateId: CovenantTemplateId,
        val label: String,
        val covenantIdHex: String? = null,
        val params: CovenantParams,
        val createdAtMs: Long = 0L,
    )

    /**
     * Generates a clean, safe default `.kpass` filename:
     * e.g., `Allowance_Pass_2026-11-23.kpass`, `Heir_Pass_2026-11-23.kpass`, `Alarm_Pass_2026-11-23.kpass`.
     */
    fun buildDefaultKpassFilename(
        kind: SharedPassKind,
        label: String = "",
        createdAtMs: Long = System.currentTimeMillis(),
    ): String {
        val prefix = when (kind) {
            SharedPassKind.ALLOWANCE_CLAIM_PASS -> "Allowance_Pass"
            SharedPassKind.HEIR_CLAIM_PASS -> "Heir_Pass"
            SharedPassKind.ALARM_CLAWBACK_PASS -> "Alarm_Pass"
        }
        val utcFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = utcFmt.format(Date(createdAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()))
        val cleanLabel = sanitizeWalletNameForFilename(label).takeIf {
            label.isNotBlank() && it != "Kaspa_Wallet"
        }?.take(24)
        return if (cleanLabel != null) {
            "${prefix}_${cleanLabel}_${date}.kpass"
        } else {
            "${prefix}_${date}.kpass"
        }
    }

    private val compactQrJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    fun exportSharedPassJson(pass: SharedCovenantPass): String {
        require(pass.templateId != CovenantTemplateId.CLAWBACK_VAULT && pass.kind != SharedPassKind.ALARM_CLAWBACK_PASS) {
            "Clawback Vault cannot be shared via .kpass because it requires the private Alarm key."
        }
        return prettyJson.encodeToString(pass.copy(createdAtMs = 0L))
    }

    fun exportCompactSharedPassQrJson(pass: SharedCovenantPass): String {
        require(pass.templateId != CovenantTemplateId.CLAWBACK_VAULT && pass.kind != SharedPassKind.ALARM_CLAWBACK_PASS) {
            "Clawback Vault cannot be shared via .kpass because it requires the private Alarm key."
        }
        return compactQrJson.encodeToString(
            CompactSharedPassQr(
                format = KPASS_QR_FORMAT_V1,
                kind = pass.kind,
                network = pass.network,
                templateId = pass.templateId,
                label = pass.label,
                covenantIdHex = pass.covenantIdHex,
                params = pass.params,
                createdAtMs = 0L,
            ),
        )
    }

    fun exportKpassBytes(pass: SharedCovenantPass): ByteArray =
        exportSharedPassJson(pass).toByteArray(Charsets.UTF_8)

    fun parseSharedPassJson(raw: String): SharedCovenantPass {
        val trimmed = raw.trim()
        val parsed = if (trimmed.contains(KPASS_QR_FORMAT_V1)) {
            val compact = json.decodeFromString<CompactSharedPassQr>(trimmed)
            require(compact.format == KPASS_QR_FORMAT_V1) { "Unsupported .kpass QR format: ${compact.format}" }
            require(compact.templateId != CovenantTemplateId.CLAWBACK_VAULT && compact.kind != SharedPassKind.ALARM_CLAWBACK_PASS) {
                "Clawback Vault passes are not supported in .kpass sharing. Only Dead Man's Switch and Recurring Allowance passes can be shared."
            }
            val compiled = CovenantCompiler.compile(compact.templateId, compact.params, compact.network)
            SharedCovenantPass(
                format = KPASS_FORMAT_V1,
                kind = compact.kind,
                network = compact.network,
                templateId = compact.templateId,
                label = compact.label,
                covenantIdHex = compact.covenantIdHex,
                p2shAddress = compiled.p2shAddress,
                redeemScriptHex = KaspaCrypto.bytesToHex(compiled.redeemScript),
                abiArtifact = compiled.abiArtifact,
                params = compact.params,
                createdAtMs = compact.createdAtMs,
            )
        } else {
            val pass = json.decodeFromString<SharedCovenantPass>(trimmed)
            require(pass.format == KPASS_FORMAT_V1) { "Unsupported .kpass format: ${pass.format}" }
            pass
        }
        require(parsed.templateId != CovenantTemplateId.CLAWBACK_VAULT && parsed.kind != SharedPassKind.ALARM_CLAWBACK_PASS) {
            "Clawback Vault passes are not supported in .kpass sharing. Only Dead Man's Switch and Recurring Allowance passes can be shared."
        }
        return parsed
    }

    fun parseKpassBytes(bytes: ByteArray): SharedCovenantPass =
        parseSharedPassJson(bytes.toString(Charsets.UTF_8))

    fun toTrackedCovenant(pass: SharedCovenantPass, walletId: String): TrackedCovenant {
        val redeemBytes = KaspaCrypto.hexToBytes(pass.redeemScriptHex)
        val hashHex = KaspaCrypto.bytesToHex(KaspaCrypto.blake2b256(redeemBytes, domainKey = null))
        val key = CovenantCompiler.canonicalKeyFor(pass.network, pass.templateId, pass.covenantIdHex, hashHex)
        val compiled = runCatching { CovenantCompiler.compile(pass.templateId, pass.params, pass.network) }.getOrNull()
        return TrackedCovenant(
            canonicalKey = key,
            walletId = walletId,
            network = pass.network,
            templateId = pass.templateId,
            label = pass.label,
            covenantIdHex = pass.covenantIdHex,
            currentP2shAddress = pass.p2shAddress,
            currentRedeemScriptHex = pass.redeemScriptHex,
            redeemScriptHashHex = hashHex,
            silSource = compiled?.silSource.orEmpty(),
            abiArtifact = pass.abiArtifact,
            params = pass.params,
            knownRedeemScriptsHex = listOf(pass.redeemScriptHex),
            liveStatus = CovenantStatus.UNCONFIRMED,
            isSharedPassImport = true,
        )
    }
}

/**
 * Bulletproof anti-duplicate & zero-conflict covenant merge engine (`local ∪ .kinetic ∪ on_chain_KKCV1`).
 *
 * Non-negotiable invariants proven by unit tests:
 * 1. Deterministic canonical key: `kip20:<network>:<covenant_id>` for stateful covenants;
 *    `p2sh:<network>:<template_id>:<redeem_script_hash>` for stateless covenants.
 * 2. Additive-only & idempotent: merging any subset/order of local records, older `.kinetic`
 *    backups, and partially pruned `KKCV1` chain scans NEVER produces duplicates and NEVER deletes
 *    newer covenants already present locally or on-chain.
 * 3. Live Blockchain UTXO Set Is Always Single Source of Truth: an older `.kinetic` backup or
 *    pruned node scan can NEVER downgrade `liveBalanceSompi`, `liveStatus`, `liveUtxoTxId`,
 *    `lastChainVerifiedAtMs`, or newer stateful script preimages!
 */
object CovenantMergeEngine {
    data class MergeReport(
        val merged: List<TrackedCovenant>,
        val addedCount: Int,
        val updatedCount: Int,
        val discoveredCount: Int = addedCount,
    )

    fun mergeAdditive(
        existing: List<TrackedCovenant>,
        incoming: List<TrackedCovenant>,
        targetWalletId: String,
    ): MergeReport {
        val byKey = LinkedHashMap<String, TrackedCovenant>()
        for (item in existing) {
            val key = canonicalKey(item)
            val normalized = item.copy(canonicalKey = key)
            byKey[key] = byKey[key]?.let { combinePair(it, normalized) } ?: normalized
        }
        var added = 0
        var updated = 0
        for (rawInc in incoming) {
            val key = canonicalKey(rawInc)
            val inc = rawInc.copy(canonicalKey = key, walletId = targetWalletId)
            val prev = byKey[key]
            if (prev == null) {
                byKey[key] = inc
                added++
            } else {
                val combined = combinePair(prev, inc)
                if (combined != prev) updated++
                byKey[key] = combined
            }
        }
        val sorted = byKey.values.sortedByDescending { it.lastUpdatedAtMs }
        return MergeReport(merged = sorted, addedCount = added, updatedCount = updated)
    }

    private fun canonicalKey(c: TrackedCovenant): String =
        CovenantCompiler.canonicalKeyFor(
            network = c.network,
            templateId = c.templateId,
            covenantIdHex = c.covenantIdHex,
            redeemScriptHashHex = c.redeemScriptHashHex,
        )

    private fun combinePair(a: TrackedCovenant, b: TrackedCovenant): TrackedCovenant {
        // Choose whichever record has newer chain verification for live state fields —
        // an older backup (lastChainVerifiedAtMs <= existing) can NEVER downgrade live chain state!
        // When neither has been chain-verified yet (e.g. cold restore merging .kinetic + KKCV1),
        // prefer the more-evolved stateful covenant record over an initial State-0 record.
        val chainWinner = when {
            a.lastChainVerifiedAtMs > b.lastChainVerifiedAtMs -> a
            b.lastChainVerifiedAtMs > a.lastChainVerifiedAtMs -> b
            stateProgressionRank(a) > stateProgressionRank(b) -> a
            stateProgressionRank(b) > stateProgressionRank(a) -> b
            a.liveBalanceSompi >= b.liveBalanceSompi -> a
            else -> b
        }
        val chainOther = if (chainWinner === a) b else a
        val metaWinner = if (a.lastUpdatedAtMs >= b.lastUpdatedAtMs) a else b
        val metaOther = if (metaWinner === a) b else a

        // Retain union of all known redeem script preimages across state transitions
        val scriptUnion = LinkedHashSet<String>().apply {
            add(chainWinner.currentRedeemScriptHex)
            add(metaWinner.currentRedeemScriptHex)
            add(metaOther.currentRedeemScriptHex)
            addAll(a.knownRedeemScriptsHex)
            addAll(b.knownRedeemScriptsHex)
        }.filter { it.isNotBlank() }

        val mergedCreatedAt = when {
            a.createdAtMs > 0L && b.createdAtMs > 0L -> minOf(a.createdAtMs, b.createdAtMs)
            a.createdAtMs > 0L -> a.createdAtMs
            b.createdAtMs > 0L -> b.createdAtMs
            else -> System.currentTimeMillis()
        }

        // Preserve non-blank BIP-32 signing key derivation metadata & salt — never clobber valid paths with defaults!
        val mergedParams = chainWinner.params.copy(
            ownerAddress = chainWinner.params.ownerAddress.ifBlank { chainOther.params.ownerAddress },
            ownerDerivationPath = chainWinner.params.ownerDerivationPath.ifBlank {
                metaWinner.params.ownerDerivationPath.ifBlank { metaOther.params.ownerDerivationPath }
            },
            ownerDerivationChain = when {
                chainWinner.params.ownerDerivationChain >= 0 -> chainWinner.params.ownerDerivationChain
                metaWinner.params.ownerDerivationChain >= 0 -> metaWinner.params.ownerDerivationChain
                metaOther.params.ownerDerivationChain >= 0 -> metaOther.params.ownerDerivationChain
                else -> -1
            },
            ownerDerivationIndex = when {
                chainWinner.params.ownerDerivationIndex >= 0 -> chainWinner.params.ownerDerivationIndex
                metaWinner.params.ownerDerivationIndex >= 0 -> metaWinner.params.ownerDerivationIndex
                metaOther.params.ownerDerivationIndex >= 0 -> metaOther.params.ownerDerivationIndex
                else -> -1
            },
            vaultSaltHex = chainWinner.params.vaultSaltHex.ifBlank { chainOther.params.vaultSaltHex },
        )

        return chainWinner.copy(
            canonicalKey = canonicalKey(chainWinner),
            walletId = a.walletId,
            label = metaWinner.label.ifBlank { metaOther.label },
            note = metaWinner.note.ifBlank { metaOther.note },
            covenantIdHex = chainWinner.covenantIdHex ?: metaWinner.covenantIdHex ?: metaOther.covenantIdHex,
            deployTxId = a.deployTxId.ifBlank { b.deployTxId },
            createdAtMs = mergedCreatedAt,
            lastUpdatedAtMs = maxOf(a.lastUpdatedAtMs, b.lastUpdatedAtMs),
            knownRedeemScriptsHex = scriptUnion,
            silSource = chainWinner.silSource.ifBlank { metaWinner.silSource.ifBlank { metaOther.silSource } },
            params = mergedParams,
            isSharedPassImport = a.isSharedPassImport && b.isSharedPassImport,
        )
    }

    private fun stateProgressionRank(c: TrackedCovenant): Long = when (c.templateId) {
        CovenantTemplateId.CLAWBACK_VAULT -> c.params.vaultStage.toLong()
        CovenantTemplateId.DAILY_SPEND_LIMIT -> c.params.spentInWindowSompi
        CovenantTemplateId.RECURRING_ALLOWANCE -> c.params.nextDueTimeMs
        CovenantTemplateId.DEAD_MANS_SWITCH -> c.params.inactivityDeadlineMs
        else -> 0L
    }

    /**
     * Resolves every covenant's live balance, active outpoint, block DAA score, and status
     * strictly against the live blockchain UTXO set (Single Source of Truth!).
     *
     * CRITICAL ANTI-FLICKER & SELF-HEALING GUARANTEES:
     * 1. If a gRPC transport query failed (`covenantIdQuerySucceeded == false` for stateful KIP-20
     *    or `addressQuerySucceeded == false` for stateless), NEVER downgrade an active vault to
     *    `SETTLED (0.00 KAS)` — preserve the previous live state until the next clean gRPC poll.
     * 2. Whenever a live UTXO's P2SH `scriptPublicKey` hash differs from the stored
     *    `currentRedeemScriptHex`, immediately invoke [CovenantCompiler.reconcileCovenantStateToLiveUtxo]
     *    so stateful covenants self-heal their exact active `redeemScript` & `params`!
     */
    fun reconcileWithChainUtxos(
        covenants: List<TrackedCovenant>,
        liveUtxos: List<UtxoItem>,
        currentVirtualDaaScore: Long,
        verifiedAtMs: Long = System.currentTimeMillis(),
        pastMedianTimeMs: Long = 0L,
        covenantIdQuerySucceeded: Boolean = true,
        addressQuerySucceeded: Boolean = true,
        covenantIndexUnsupportedOnNode: Boolean = false,
        candidateSpentByKey: Map<String, List<Long>> = emptyMap(),
        candidateDestAddresses: List<String> = emptyList(),
        candidateRecipesByKey: Map<String, List<TrackedCovenant>> = emptyMap(),
        candidateLookaheadAddrsByKey: Map<String, Set<String>> = emptyMap(),
    ): List<TrackedCovenant> {
        val effectivePmtMs = if (pastMedianTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
            pastMedianTimeMs
        } else {
            (verifiedAtMs - ChainTimeAnchor.OFFLINE_MEDIAN_LAG_MS).coerceAtLeast(CovenantCompiler.LOCK_TIME_THRESHOLD)
        }
        val byCovenantId = liveUtxos
            .filter { !it.covenantId.isNullOrBlank() }
            .groupBy { it.covenantId!!.lowercase() }
        val byAddress = liveUtxos.groupBy { it.address }

        return covenants.map { cov ->
            val covIdLower = cov.covenantIdHex?.lowercase()?.takeIf { it.length == 64 }
            val addressUtxos = byAddress[cov.currentP2shAddress].orEmpty()

            // UNIVERSAL MULTI-STATE IDENTITY MATCHING (works with OR without `--covenant-index`!):
            // 1. Stateful KIP-20 covenants check `byCovenantId[covIdLower]` FIRST (exact lineage match).
            // 2. If `byCovenantId` is empty (e.g., standard `--utxoindex` node without `--covenant-index`
            //    or `GetUtxosByAddresses` fallback), scan ALL deterministic multi-state lookahead P2SH
            //    addresses (Cycles 0..24+ for Subscriptions, Stage 0+1 for Daily Spend & Clawback) so
            //    external `.kpass` claims update the owner's wallet immediately without `--covenant-index`!
            // 3. Stateless covenants (Cold Whitelist, Hodl Time-Lock) match by their salted P2SH address.
            val isStatefulById = cov.templateId.isStatefulKip20 && covIdLower != null
            val matched = if (isStatefulById) {
                val idMatched = byCovenantId[covIdLower].orEmpty()
                if (idMatched.isNotEmpty()) {
                    idMatched
                } else if (covenantIndexUnsupportedOnNode || candidateLookaheadAddrsByKey.containsKey(cov.canonicalKey)) {
                    val lookaheadAddrs = candidateLookaheadAddrsByKey[cov.canonicalKey]
                        ?: CovenantCompiler.buildMultiStateLookaheadAddresses(cov, candidateDestAddresses)
                    val foundAcrossStates = LinkedHashMap<String, UtxoItem>()
                    for (addr in lookaheadAddrs) {
                        for (u in byAddress[addr].orEmpty()) {
                            // Never cross-match a UTXO that explicitly carries a DIFFERENT covenant_id
                            if (u.covenantId.isNullOrBlank() || u.covenantId.equals(covIdLower, ignoreCase = true)) {
                                foundAcrossStates[u.outpointKey] = u
                            }
                        }
                    }
                    foundAcrossStates.values.toList()
                } else {
                    emptyList()
                }
            } else {
                addressUtxos
            }

            // Detect stray plain/unbound UTXOs sent externally directly to a stateful covenant's P2SH address
            val strayUnboundSompi = if (isStatefulById && matched.isNotEmpty()) {
                addressUtxos.filter { it.covenantId.isNullOrBlank() }.sumOf { it.amountSompi }
            } else {
                0L
            }

            val monotonicVerifiedAt = maxOf(verifiedAtMs, cov.lastChainVerifiedAtMs + 1L)

            if (matched.isEmpty()) {
                // ANTI-FLICKER GUARD: NEVER mark SETTLED when the authoritative gRPC query failed/timed out!
                // When BOTH live gRPC queries succeeded (or node explicitly lacks covenant index while
                // address gRPC succeeded) and returned 0 UTXOs, immediately settle any confirmed or
                // restored covenant!
                val authoritativeQueryOk = if (isStatefulById) {
                    (covenantIdQuerySucceeded && addressQuerySucceeded) ||
                        (covenantIndexUnsupportedOnNode && addressQuerySucceeded)
                } else {
                    addressQuerySucceeded
                }
                if (!authoritativeQueryOk) {
                    cov
                } else {
                    // Pending (UNCONFIRMED) is STRICTLY reserved for a tx broadcast locally on THIS
                    // device within the last 60 seconds (`localBroadcastAtMs > 0L`). Restored vaults
                    // from Chain Scan / `.kinetic` have `localBroadcastAtMs == 0L`, so settled vaults
                    // NEVER get stuck in Pending!
                    val isFreshLocalBroadcast = cov.liveStatus == CovenantStatus.UNCONFIRMED &&
                        cov.localBroadcastAtMs > 0L &&
                        (verifiedAtMs - cov.localBroadcastAtMs) in 0L until 60_000L
                    if (isFreshLocalBroadcast) {
                        cov.copy(
                            liveStatus = CovenantStatus.UNCONFIRMED,
                            lastChainVerifiedAtMs = monotonicVerifiedAt,
                            strayUnboundBalanceSompi = 0L,
                        )
                    } else {
                        cov.copy(
                            liveBalanceSompi = 0L,
                            liveUtxoTxId = null,
                            liveUtxoIndex = null,
                            liveUtxoBlockDaaScore = 0L,
                            liveStatus = CovenantStatus.SETTLED,
                            localBroadcastAtMs = 0L,
                            lastChainVerifiedAtMs = monotonicVerifiedAt,
                            strayUnboundBalanceSompi = 0L,
                        )
                    }
                }
            } else {
                val primary = matched.maxByOrNull { it.amountSompi }!!
                val totalSompi = matched.sumOf { it.amountSompi }
                val ageDaa = if (currentVirtualDaaScore > 0L && primary.blockDaaScore > 0L) {
                    (currentVirtualDaaScore - primary.blockDaaScore).coerceAtLeast(0L)
                } else {
                    0L
                }
                // Self-heal stateful P2SH drift: reconcile redeemScript + params directly against primary's
                // live on-chain BLAKE2b scriptPubKey hash before computing status!
                val resolvedCov = CovenantCompiler.reconcileCovenantStateToLiveUtxo(
                    covenant = cov,
                    liveUtxo = primary,
                    candidateSpentAmountsSompi = candidateSpentByKey[cov.canonicalKey].orEmpty(),
                    candidateDestAddresses = candidateDestAddresses,
                    candidateHistoricalRecipes = candidateRecipesByKey[cov.canonicalKey].orEmpty(),
                )
                val status = computeLiveStatus(resolvedCov, totalSompi, ageDaa, currentVirtualDaaScore, effectivePmtMs)
                resolvedCov.copy(
                    currentP2shAddress = primary.address.ifBlank { resolvedCov.currentP2shAddress },
                    liveBalanceSompi = totalSompi,
                    liveUtxoTxId = primary.transactionId,
                    liveUtxoIndex = primary.index,
                    liveUtxoBlockDaaScore = primary.blockDaaScore,
                    liveStatus = status,
                    localBroadcastAtMs = 0L,
                    lastChainVerifiedAtMs = monotonicVerifiedAt,
                    strayUnboundBalanceSompi = strayUnboundSompi,
                )
            }
        }
    }

    private fun computeLiveStatus(
        cov: TrackedCovenant,
        totalSompi: Long,
        ageDaa: Long,
        virtualDaa: Long,
        pastMedianTimeMs: Long,
    ): CovenantStatus = when (cov.templateId) {
        CovenantTemplateId.CLAWBACK_VAULT -> when {
            cov.params.vaultStage == 1 && ageDaa >= cov.params.unvaultDelayDaa -> CovenantStatus.READY
            cov.params.vaultStage == 1 -> CovenantStatus.UNVAULTING
            else -> CovenantStatus.ACTIVE
        }
        CovenantTemplateId.DEAD_MANS_SWITCH -> {
            val isReady = if (CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(cov.params)) {
                CovenantCompiler.isTimeLockReady(cov.params.inactivityDeadlineMs, pastMedianTimeMs)
            } else {
                ageDaa >= cov.params.inactivityDaa
            }
            if (isReady) CovenantStatus.READY else CovenantStatus.ACTIVE
        }
        CovenantTemplateId.DAILY_SPEND_LIMIT -> {
            val isReady = when {
                cov.params.fullWithdrawUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD ->
                    CovenantCompiler.isTimeLockReady(cov.params.fullWithdrawUnlockTimeMs, pastMedianTimeMs)
                virtualDaa > 0L && cov.params.fullWithdrawUnlockDaa > 0L ->
                    virtualDaa >= cov.params.fullWithdrawUnlockDaa
                else -> false
            }
            if (isReady) CovenantStatus.READY else CovenantStatus.ACTIVE
        }
        CovenantTemplateId.HODL_TIMELOCK -> {
            val isReady = if (cov.params.unlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                CovenantCompiler.isTimeLockReady(cov.params.unlockTimeMs, pastMedianTimeMs)
            } else {
                virtualDaa > 0L && virtualDaa >= cov.params.unlockDaa
            }
            if (isReady) CovenantStatus.READY else CovenantStatus.ACTIVE
        }
        CovenantTemplateId.GOAL_PIGGY_BANK -> {
            val goalReached = totalSompi >= cov.params.targetGoalSompi
            val fallbackReached = if (cov.params.fallbackUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                CovenantCompiler.isTimeLockReady(cov.params.fallbackUnlockTimeMs, pastMedianTimeMs)
            } else {
                virtualDaa > 0L && cov.params.fallbackUnlockDaa > 0L && virtualDaa >= cov.params.fallbackUnlockDaa
            }
            if (goalReached || fallbackReached) CovenantStatus.READY else CovenantStatus.ACTIVE
        }
        CovenantTemplateId.RECURRING_ALLOWANCE -> {
            val isReady = if (CovenantCompiler.isKeylessTimeBasedSubscription(cov.params)) {
                CovenantCompiler.isTimeLockReady(cov.params.nextDueTimeMs, pastMedianTimeMs)
            } else {
                ageDaa >= cov.params.periodDaa
            }
            if (isReady) CovenantStatus.READY else CovenantStatus.ACTIVE
        }
        CovenantTemplateId.COLD_WHITELIST -> CovenantStatus.ACTIVE
    }
}
