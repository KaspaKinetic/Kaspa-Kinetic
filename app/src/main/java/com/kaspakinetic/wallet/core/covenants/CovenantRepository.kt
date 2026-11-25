package com.kaspakinetic.wallet.core.covenants

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WalletCovenantVaultStore(
    val walletId: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastUpdatedAtMs: Long = System.currentTimeMillis(),
    val covenants: List<TrackedCovenant> = emptyList(),
)

/**
 * Pure JVM, Mutex-protected per-wallet disk storage for Covenant vaults and auto-updating internal
 * `.kinetic` snapshots.
 *
 * CRITICAL ISOLATION INVARIANT:
 * - Every file on disk (`<walletId>_store.json`, `<walletId>_vault.kinetic`) is keyed STRICTLY by
 *   the immutable [walletId] — NEVER by wallet display name! Multiple wallets sharing identical
 *   display names (e.g., two wallets named `"Main Kaspa Wallet"`) can NEVER collide or overwrite
 *   each other's covenants or internal `.kinetic` snapshots.
 * - Every persisted covenant is explicitly bound to [walletId] under [storeMutex].
 */
class CovenantVaultDiskStore(private val covenantsDir: File) {
    private val storeMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        covenantsDir.mkdirs()
    }

    fun storeFile(walletId: String): File = File(covenantsDir, "${sanitizeId(walletId)}_store.json")
    fun internalKineticFile(walletId: String): File = File(covenantsDir, "${sanitizeId(walletId)}_vault.kinetic")

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "default" }

    private fun loadVaultUnlocked(walletId: String): WalletCovenantVaultStore {
        val file = storeFile(walletId)
        if (!file.exists()) {
            val now = System.currentTimeMillis()
            return WalletCovenantVaultStore(walletId = walletId, createdAtMs = now, lastUpdatedAtMs = now)
        }
        return runCatching {
            val decoded = json.decodeFromString<WalletCovenantVaultStore>(file.readText(Charsets.UTF_8))
            // Enforce strict walletId binding on every loaded covenant
            decoded.copy(
                walletId = walletId,
                covenants = decoded.covenants.map { it.copy(walletId = walletId) },
            )
        }.getOrElse {
            val now = System.currentTimeMillis()
            WalletCovenantVaultStore(walletId = walletId, createdAtMs = now, lastUpdatedAtMs = now)
        }
    }

    suspend fun loadVault(walletId: String): WalletCovenantVaultStore = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            loadVaultUnlocked(walletId)
        }
    }

    suspend fun saveVaultAndInternalSnapshot(
        walletId: String,
        walletName: String,
        primaryAddressMainnet: String,
        covenants: List<TrackedCovenant>,
        existingCreatedAtMs: Long? = null,
        seedBackupKey32: ByteArray? = null,
        touchUpdatedTimestamp: Boolean = true,
    ): Pair<WalletCovenantVaultStore, ByteArray?> = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val prev = loadVaultUnlocked(walletId)
            val now = System.currentTimeMillis()
            val createdAt = existingCreatedAtMs?.takeIf { it > 0L } ?: prev.createdAtMs.takeIf { it > 0L } ?: now
            val updatedAt = if (touchUpdatedTimestamp) maxOf(now, prev.lastUpdatedAtMs) else prev.lastUpdatedAtMs
            val scopedCovenants = covenants.map { it.copy(walletId = walletId) }

            val nextStore = WalletCovenantVaultStore(
                walletId = walletId,
                createdAtMs = createdAt,
                lastUpdatedAtMs = updatedAt,
                covenants = scopedCovenants,
            )
            // 1. Atomic write of internal JSON index keyed strictly by walletId
            KineticBackupEngine.writeFileAtomically(
                storeFile(walletId),
                json.encodeToString(nextStore).toByteArray(Charsets.UTF_8),
            )

            // 2. Atomic auto-update of encrypted internal `.kinetic` archive keyed strictly by walletId
            var encryptedBytes: ByteArray? = null
            if (seedBackupKey32 != null) {
                val payload = KineticBackupEngine.KineticVaultPayload(
                    walletId = walletId,
                    walletName = walletName,
                    walletFingerprint = KineticBackupEngine.walletFingerprint(primaryAddressMainnet),
                    createdAtMs = createdAt,
                    lastUpdatedAtMs = updatedAt,
                    covenants = scopedCovenants,
                )
                encryptedBytes = runCatching {
                    KineticBackupEngine.encryptKineticBackup(payload = payload, seedBackupKey32 = seedBackupKey32)
                }.getOrNull()
                if (encryptedBytes != null) {
                    KineticBackupEngine.writeFileAtomically(internalKineticFile(walletId), encryptedBytes)
                }
            }

            nextStore to encryptedBytes
        }
    }

    /**
     * Atomically loads [walletId]'s existing vault, merges [incoming] additively (zero data loss),
     * binds all items strictly to [walletId], and writes both JSON store & internal `.kinetic`.
     */
    suspend fun mergeIncomingAndSave(
        walletId: String,
        walletName: String,
        primaryAddressMainnet: String,
        incoming: List<TrackedCovenant>,
        incomingCreatedAtMs: Long = 0L,
        seedBackupKey32: ByteArray? = null,
    ): Triple<WalletCovenantVaultStore, CovenantMergeEngine.MergeReport, ByteArray?> = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val prev = loadVaultUnlocked(walletId)
            val report = CovenantMergeEngine.mergeAdditive(
                existing = prev.covenants,
                incoming = incoming,
                targetWalletId = walletId,
            )
            val now = System.currentTimeMillis()
            val createdAt = listOf(prev.createdAtMs, incomingCreatedAtMs)
                .filter { it > 0L }
                .minOrNull() ?: now
            val updatedAt = maxOf(now, prev.lastUpdatedAtMs)
            val scopedCovenants = report.merged.map { it.copy(walletId = walletId) }

            val nextStore = WalletCovenantVaultStore(
                walletId = walletId,
                createdAtMs = createdAt,
                lastUpdatedAtMs = updatedAt,
                covenants = scopedCovenants,
            )
            KineticBackupEngine.writeFileAtomically(
                storeFile(walletId),
                json.encodeToString(nextStore).toByteArray(Charsets.UTF_8),
            )
            var encryptedBytes: ByteArray? = null
            if (seedBackupKey32 != null) {
                val payload = KineticBackupEngine.KineticVaultPayload(
                    walletId = walletId,
                    walletName = walletName,
                    walletFingerprint = KineticBackupEngine.walletFingerprint(primaryAddressMainnet),
                    createdAtMs = createdAt,
                    lastUpdatedAtMs = updatedAt,
                    covenants = scopedCovenants,
                )
                encryptedBytes = runCatching {
                    KineticBackupEngine.encryptKineticBackup(payload = payload, seedBackupKey32 = seedBackupKey32)
                }.getOrNull()
                if (encryptedBytes != null) {
                    KineticBackupEngine.writeFileAtomically(internalKineticFile(walletId), encryptedBytes)
                }
            }
            Triple(nextStore, report, encryptedBytes)
        }
    }

    suspend fun deleteVault(walletId: String) = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            runCatching { storeFile(walletId).delete() }
            runCatching { internalKineticFile(walletId).delete() }
        }
    }
}

/**
 * Android repository wrapper over [CovenantVaultDiskStore] adding optional SAF persistable external
 * `.kinetic` file syncing and URI read/write helpers.
 */
class CovenantRepository(context: Context) {
    private val appContext = context.applicationContext
    private val diskStore = CovenantVaultDiskStore(File(appContext.filesDir, "covenants"))

    suspend fun loadVault(walletId: String): WalletCovenantVaultStore = diskStore.loadVault(walletId)

    /**
     * Atomically saves the wallet's covenant store AND auto-updates the internal encrypted `.kinetic`
     * snapshot (plus optional SAF external auto-sync URI if configured).
     */
    suspend fun saveVaultAndAutoSnapshot(
        walletId: String,
        walletName: String,
        primaryAddressMainnet: String,
        covenants: List<TrackedCovenant>,
        existingCreatedAtMs: Long? = null,
        seedBackupKey32: ByteArray? = null,
        externalAutoSyncUri: String? = null,
        touchUpdatedTimestamp: Boolean = true,
    ): Pair<WalletCovenantVaultStore, String?> = withContext(Dispatchers.IO) {
        val (nextStore, encryptedBytes) = diskStore.saveVaultAndInternalSnapshot(
            walletId = walletId,
            walletName = walletName,
            primaryAddressMainnet = primaryAddressMainnet,
            covenants = covenants,
            existingCreatedAtMs = existingCreatedAtMs,
            seedBackupKey32 = seedBackupKey32,
            touchUpdatedTimestamp = touchUpdatedTimestamp,
        )
        val externalWarn = syncExternalSafIfLinked(externalAutoSyncUri, encryptedBytes)
        nextStore to externalWarn
    }

    suspend fun mergeIncomingAndAutoSnapshot(
        walletId: String,
        walletName: String,
        primaryAddressMainnet: String,
        incoming: List<TrackedCovenant>,
        incomingCreatedAtMs: Long = 0L,
        seedBackupKey32: ByteArray? = null,
        externalAutoSyncUri: String? = null,
    ): Triple<WalletCovenantVaultStore, CovenantMergeEngine.MergeReport, String?> = withContext(Dispatchers.IO) {
        val (nextStore, report, encryptedBytes) = diskStore.mergeIncomingAndSave(
            walletId = walletId,
            walletName = walletName,
            primaryAddressMainnet = primaryAddressMainnet,
            incoming = incoming,
            incomingCreatedAtMs = incomingCreatedAtMs,
            seedBackupKey32 = seedBackupKey32,
        )
        val externalWarn = syncExternalSafIfLinked(externalAutoSyncUri, encryptedBytes)
        Triple(nextStore, report, externalWarn)
    }

    private fun syncExternalSafIfLinked(externalAutoSyncUri: String?, encryptedBytes: ByteArray?): String? {
        if (externalAutoSyncUri.isNullOrBlank() || encryptedBytes == null) return null
        val writeOk = runCatching {
            val uri = Uri.parse(externalAutoSyncUri)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(encryptedBytes)
                out.flush()
            } ?: error("Output stream unavailable")
        }
        return if (writeOk.isFailure) {
            "External auto-sync .kinetic file could not be updated (moved or permission revoked). Internal backup is safe — please re-link or download a fresh copy."
        } else {
            null
        }
    }

    suspend fun deleteVault(walletId: String) = diskStore.deleteVault(walletId)

    suspend fun writeBytesToUri(uri: Uri, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: error("Could not open destination file for writing")
        }
    }

    suspend fun readBytesFromUri(uri: Uri): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: error("Could not open selected .kinetic file")
        }
    }
}
