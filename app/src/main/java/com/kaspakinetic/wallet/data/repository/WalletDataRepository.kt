package com.kaspakinetic.wallet.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaspakinetic.wallet.core.model.AutoLockTimeout
import com.kaspakinetic.wallet.core.model.ContactRecord
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.NodeConfig
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.WalletRecord
import com.kaspakinetic.wallet.core.model.WalletTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.walletMetaDataStore by preferencesDataStore(name = "kaspa_kinetic_wallet_data")

@Serializable
data class WalletPreferencesState(
    val wallets: List<WalletRecord> = emptyList(),
    val activeWalletId: String? = null,
    val contacts: List<ContactRecord> = emptyList(),
    val txNotes: Map<String, String> = emptyMap(),
    val nodeConfig: NodeConfig = NodeConfig(),
    val fiatCurrency: FiatCurrency = FiatCurrency.USD,
    val kasOnlyPrivacyMode: Boolean = false,
    val hideBalances: Boolean = false,
    val biometricsEnabled: Boolean = false,
    val authOnLaunch: Boolean = false,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.FIVE_MINUTES,
    val incomingNotificationsEnabled: Boolean = true,
    val appLaunchCount: Int = 0,
    val donationPopupOptOut: Boolean = false,
    val hasSeenLagTipStep: Boolean = false,
    val walletGoalsKas: Map<String, Double> = emptyMap(),
    val walletCustomMonthlyDca: Map<String, Double> = emptyMap(),
    val covenantsActivated: Boolean = false,
    val activatedCovenantWalletIds: Set<String> = emptySet(),
    val mainnetCovenantsEnabled: Boolean = false,
    val covenantsAutoSyncUri: String? = null,
    val covenantsAutoSyncUrisByWallet: Map<String, String> = emptyMap(),
    val covenantsReminderOptOut: Boolean = false,
) {
    val activeWallet: WalletRecord?
        get() = wallets.firstOrNull { it.id == activeWalletId } ?: wallets.firstOrNull()

    /** Strictly per-wallet Covenants activation check (new/other wallets start unactivated!). */
    fun isCovenantsActivatedForWallet(walletId: String?): Boolean {
        val id = walletId?.takeIf { it.isNotBlank() } ?: return false
        return id in activatedCovenantWalletIds
    }

    /** Strictly per-wallet SAF external `.kinetic` auto-sync URI keyed by immutable `walletId`. */
    fun covenantsAutoSyncUriForWallet(walletId: String?): String? {
        val id = walletId?.takeIf { it.isNotBlank() } ?: return null
        return covenantsAutoSyncUrisByWallet[id]?.takeIf { it.isNotBlank() }
    }

    val isActiveWalletCovenantsActivated: Boolean
        get() = isCovenantsActivatedForWallet(activeWallet?.id)
}

class WalletDataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val walletsJson = stringPreferencesKey("wallets_json")
        val activeWalletId = stringPreferencesKey("active_wallet_id")
        val contactsJson = stringPreferencesKey("contacts_json")
        val txNotesJson = stringPreferencesKey("tx_notes_json")
        val nodeConfigJson = stringPreferencesKey("node_config_json")
        val fiatCurrency = stringPreferencesKey("fiat_currency")
        val kasOnlyPrivacy = booleanPreferencesKey("kas_only_privacy")
        val hideBalances = booleanPreferencesKey("hide_balances")
        val biometricsEnabled = booleanPreferencesKey("biometrics_enabled")
        val authOnLaunch = booleanPreferencesKey("auth_on_launch")
        val autoLockTimeout = stringPreferencesKey("auto_lock_timeout")
        val incomingNotifications = booleanPreferencesKey("incoming_notifications")
        val appLaunchCount = intPreferencesKey("app_launch_count")
        val donationPopupOptOut = booleanPreferencesKey("donation_popup_opt_out")
        val hasSeenLagTipStep = booleanPreferencesKey("has_seen_lag_tip_step")
        val walletGoalsKasJson = stringPreferencesKey("wallet_goals_kas_json")
        val walletCustomMonthlyDcaJson = stringPreferencesKey("wallet_custom_monthly_dca_json")
        val covenantsActivated = booleanPreferencesKey("covenants_activated")
        val activatedCovenantWalletIdsJson = stringPreferencesKey("activated_covenant_wallet_ids_json")
        val mainnetCovenantsEnabled = booleanPreferencesKey("mainnet_covenants_enabled")
        val covenantsAutoSyncUri = stringPreferencesKey("covenants_auto_sync_uri")
        val covenantsAutoSyncUrisJson = stringPreferencesKey("covenants_auto_sync_uris_json")
        val covenantsReminderOptOut = booleanPreferencesKey("covenants_reminder_opt_out")
        fun cachedTxsKey(walletId: String, network: KaspaNetwork) =
            stringPreferencesKey("cached_txs_${walletId}_${network.id}")
    }

    val state: Flow<WalletPreferencesState> = appContext.walletMetaDataStore.data.map { prefs ->
        val wallets = prefs[Keys.walletsJson]?.let { raw ->
            runCatching { json.decodeFromString<List<WalletRecord>>(raw) }.getOrNull()
        } ?: emptyList()
        val contacts = prefs[Keys.contactsJson]?.let { raw ->
            runCatching { json.decodeFromString<List<ContactRecord>>(raw) }.getOrNull()
        } ?: emptyList()
        val notes = prefs[Keys.txNotesJson]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        } ?: emptyMap()
        val nodeConfig = prefs[Keys.nodeConfigJson]?.let { raw ->
            runCatching { json.decodeFromString<NodeConfig>(raw) }.getOrNull()
        } ?: NodeConfig()
        val currency = prefs[Keys.fiatCurrency]?.let { code ->
            FiatCurrency.entries.firstOrNull { it.code == code }
        } ?: defaultCurrencyForSystem()
        val autoLock = prefs[Keys.autoLockTimeout]?.let { name ->
            AutoLockTimeout.entries.firstOrNull { it.name == name }
        } ?: AutoLockTimeout.FIVE_MINUTES
        val walletGoals = prefs[Keys.walletGoalsKasJson]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrNull()
        } ?: emptyMap()
        val walletCustomDca = prefs[Keys.walletCustomMonthlyDcaJson]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrNull()
        } ?: emptyMap()
        val activeId = prefs[Keys.activeWalletId] ?: wallets.firstOrNull()?.id
        val legacyGlobalCovActivated = prefs[Keys.covenantsActivated] ?: false
        val rawActivatedSet = prefs[Keys.activatedCovenantWalletIdsJson]?.let { raw ->
            runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
        }
        // Clean one-way migration: if a v3/v3.1 user had global `covenants_activated == true` and
        // has no per-wallet set yet, migrate ONLY the active wallet (never newly created wallets!).
        val activatedWalletIds: Set<String> = when {
            rawActivatedSet != null -> rawActivatedSet
            legacyGlobalCovActivated && !activeId.isNullOrBlank() -> setOf(activeId)
            else -> emptySet()
        }

        val rawAutoSyncMap = prefs[Keys.covenantsAutoSyncUrisJson]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        }
        val legacyAutoSyncUri = prefs[Keys.covenantsAutoSyncUri]?.takeIf { it.isNotBlank() }
        val autoSyncByWallet: Map<String, String> = when {
            rawAutoSyncMap != null -> rawAutoSyncMap
            legacyAutoSyncUri != null && !activeId.isNullOrBlank() -> mapOf(activeId to legacyAutoSyncUri)
            else -> emptyMap()
        }

        WalletPreferencesState(
            wallets = wallets,
            activeWalletId = prefs[Keys.activeWalletId],
            contacts = contacts,
            txNotes = notes,
            nodeConfig = nodeConfig,
            fiatCurrency = currency,
            kasOnlyPrivacyMode = prefs[Keys.kasOnlyPrivacy] ?: false,
            hideBalances = prefs[Keys.hideBalances] ?: false,
            biometricsEnabled = prefs[Keys.biometricsEnabled] ?: false,
            authOnLaunch = prefs[Keys.authOnLaunch] ?: false,
            autoLockTimeout = autoLock,
            incomingNotificationsEnabled = prefs[Keys.incomingNotifications] ?: true,
            appLaunchCount = prefs[Keys.appLaunchCount] ?: 0,
            donationPopupOptOut = prefs[Keys.donationPopupOptOut] ?: false,
            hasSeenLagTipStep = prefs[Keys.hasSeenLagTipStep] ?: false,
            walletGoalsKas = walletGoals,
            walletCustomMonthlyDca = walletCustomDca,
            covenantsActivated = !activeId.isNullOrBlank() && activeId in activatedWalletIds,
            activatedCovenantWalletIds = activatedWalletIds,
            mainnetCovenantsEnabled = prefs[Keys.mainnetCovenantsEnabled] ?: false,
            covenantsAutoSyncUri = activeId?.let { autoSyncByWallet[it] },
            covenantsAutoSyncUrisByWallet = autoSyncByWallet,
            covenantsReminderOptOut = prefs[Keys.covenantsReminderOptOut] ?: false,
        )
    }

    suspend fun upsertWallet(wallet: WalletRecord, selectAsActive: Boolean = true) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletsJson]?.let {
                runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()
            }.orEmpty().toMutableList()
            val idx = current.indexOfFirst { it.id == wallet.id }
            if (idx >= 0) current[idx] = wallet else current.add(wallet)
            prefs[Keys.walletsJson] = json.encodeToString(current)
            if (selectAsActive || prefs[Keys.activeWalletId].isNullOrBlank()) {
                prefs[Keys.activeWalletId] = wallet.id
            }
        }
    }

    suspend fun selectWallet(walletId: String) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.activeWalletId] = walletId
        }
    }

    suspend fun renameWallet(walletId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletsJson]?.let {
                runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()
            }.orEmpty().map { w ->
                if (w.id == walletId) w.copy(name = clean) else w
            }
            prefs[Keys.walletsJson] = json.encodeToString(current)
        }
    }

    suspend fun updateWalletBalance(walletId: String, balanceSompi: Long) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletsJson]?.let {
                runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()
            }.orEmpty()
            var changed = false
            val updated = current.map { w ->
                if (w.id == walletId && w.cachedBalanceSompi != balanceSompi) {
                    changed = true
                    w.copy(cachedBalanceSompi = balanceSompi)
                } else {
                    w
                }
            }
            if (changed) {
                prefs[Keys.walletsJson] = json.encodeToString(updated)
            }
        }
    }

    suspend fun updateWalletDiscovery(
        walletId: String,
        highestUsedReceive: Int,
        highestUsedChange: Int,
        receiveIndex: Int,
        changeIndex: Int,
        cachedBalanceSompi: Long? = null,
        markScanned: Boolean = true,
    ) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletsJson]?.let {
                runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()
            }.orEmpty()
            val updated = current.map { w ->
                if (w.id == walletId) {
                    w.copy(
                        highestUsedReceiveIndex = maxOf(w.highestUsedReceiveIndex, highestUsedReceive),
                        highestUsedChangeIndex = maxOf(w.highestUsedChangeIndex, highestUsedChange),
                        receiveIndex = maxOf(w.receiveIndex, receiveIndex),
                        changeIndex = maxOf(w.changeIndex, changeIndex),
                        cachedBalanceSompi = cachedBalanceSompi ?: w.cachedBalanceSompi,
                        hasCompletedInitialScan = w.hasCompletedInitialScan || markScanned,
                    )
                } else {
                    w
                }
            }
            prefs[Keys.walletsJson] = json.encodeToString(updated)
        }
    }

    suspend fun deleteWallet(walletId: String) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletsJson]?.let {
                runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == walletId }
            prefs[Keys.walletsJson] = json.encodeToString(current)
            if (prefs[Keys.activeWalletId] == walletId) {
                val next = current.firstOrNull()?.id
                if (next != null) prefs[Keys.activeWalletId] = next else prefs.remove(Keys.activeWalletId)
            }
            prefs[Keys.walletGoalsKasJson]?.let { raw ->
                val goals = runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrNull().orEmpty().toMutableMap()
                if (goals.remove(walletId) != null) prefs[Keys.walletGoalsKasJson] = json.encodeToString(goals)
            }
            prefs[Keys.walletCustomMonthlyDcaJson]?.let { raw ->
                val dcas = runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrNull().orEmpty().toMutableMap()
                if (dcas.remove(walletId) != null) prefs[Keys.walletCustomMonthlyDcaJson] = json.encodeToString(dcas)
            }
            prefs[Keys.activatedCovenantWalletIdsJson]?.let { raw ->
                val ids = runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull().orEmpty().toMutableSet()
                if (ids.remove(walletId)) prefs[Keys.activatedCovenantWalletIdsJson] = json.encodeToString(ids)
            }
        }
    }

    suspend fun setWalletGoalKas(walletId: String, targetKas: Double?) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletGoalsKasJson]?.let {
                runCatching { json.decodeFromString<Map<String, Double>>(it) }.getOrNull()
            }.orEmpty().toMutableMap()
            if (targetKas == null || !targetKas.isFinite() || targetKas <= 0.0) {
                current.remove(walletId)
            } else {
                current[walletId] = targetKas
            }
            prefs[Keys.walletGoalsKasJson] = json.encodeToString(current)
        }
    }

    suspend fun setWalletCustomMonthlyDca(walletId: String, monthlyAmount: Double?) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.walletCustomMonthlyDcaJson]?.let {
                runCatching { json.decodeFromString<Map<String, Double>>(it) }.getOrNull()
            }.orEmpty().toMutableMap()
            if (monthlyAmount == null || !monthlyAmount.isFinite() || monthlyAmount <= 0.0) {
                current.remove(walletId)
            } else {
                current[walletId] = monthlyAmount
            }
            prefs[Keys.walletCustomMonthlyDcaJson] = json.encodeToString(current)
        }
    }

    suspend fun saveContact(contact: ContactRecord) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.contactsJson]?.let {
                runCatching { json.decodeFromString<List<ContactRecord>>(it) }.getOrNull()
            }.orEmpty().toMutableList()
            val idx = current.indexOfFirst { it.id == contact.id }
            if (idx >= 0) current[idx] = contact else current.add(contact)
            current.sortBy { it.name.lowercase(Locale.ROOT) }
            prefs[Keys.contactsJson] = json.encodeToString(current)
        }
    }

    suspend fun deleteContact(contactId: String) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.contactsJson]?.let {
                runCatching { json.decodeFromString<List<ContactRecord>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == contactId }
            prefs[Keys.contactsJson] = json.encodeToString(current)
        }
    }

    suspend fun setTxNote(txId: String, note: String) {
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.txNotesJson]?.let {
                runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
            }.orEmpty().toMutableMap()
            val clean = note.trim()
            if (clean.isEmpty()) current.remove(txId) else current[txId] = clean
            prefs[Keys.txNotesJson] = json.encodeToString(current)
        }
    }

    suspend fun setNodeConfig(config: NodeConfig) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.nodeConfigJson] = json.encodeToString(config)
        }
    }

    suspend fun setFiatCurrency(currency: FiatCurrency) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.fiatCurrency] = currency.code
        }
    }

    suspend fun setKasOnlyPrivacyMode(enabled: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.kasOnlyPrivacy] = enabled
        }
    }

    suspend fun setHideBalances(hidden: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.hideBalances] = hidden
        }
    }

    suspend fun setBiometricsEnabled(enabled: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.biometricsEnabled] = enabled
        }
    }

    suspend fun setAuthOnLaunch(enabled: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.authOnLaunch] = enabled
        }
    }

    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.autoLockTimeout] = timeout.name
        }
    }

    suspend fun setIncomingNotificationsEnabled(enabled: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.incomingNotifications] = enabled
        }
    }

    /**
     * Increments the persisted app-launch counter and returns true if the every-5-launches
     * donation / Covenants backup prompt should be shown.
     */
    suspend fun recordAppLaunchAndShouldShowDonationPopup(): Boolean {
        var shouldShow = false
        appContext.walletMetaDataStore.edit { prefs ->
            val nextCount = (prefs[Keys.appLaunchCount] ?: 0) + 1
            prefs[Keys.appLaunchCount] = nextCount
            val optedOut = prefs[Keys.donationPopupOptOut] ?: false
            val activeId = prefs[Keys.activeWalletId]
            val activatedSet = prefs[Keys.activatedCovenantWalletIdsJson]?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
            }
            val covActivated = if (activatedSet != null) {
                !activeId.isNullOrBlank() && activeId in activatedSet
            } else {
                prefs[Keys.covenantsActivated] ?: false
            }
            val covReminderOptOut = prefs[Keys.covenantsReminderOptOut] ?: false
            shouldShow = shouldShowLaunchPopup(
                launchCount = nextCount,
                donationOptedOut = optedOut,
                covenantsActivated = covActivated,
                covenantsReminderOptOut = covReminderOptOut,
            )
        }
        return shouldShow
    }

    suspend fun setCovenantsReminderOptOut(optOut: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.covenantsReminderOptOut] = optOut
        }
    }

    suspend fun setCovenantsActivatedForWallet(walletId: String, activated: Boolean) {
        if (walletId.isBlank()) return
        appContext.walletMetaDataStore.edit { prefs ->
            val currentSet = prefs[Keys.activatedCovenantWalletIdsJson]?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
            }?.toMutableSet() ?: run {
                // One-time migration from legacy global boolean if present
                val legacy = prefs[Keys.covenantsActivated] ?: false
                val curActive = prefs[Keys.activeWalletId]
                if (legacy && !curActive.isNullOrBlank()) mutableSetOf(curActive) else mutableSetOf()
            }
            if (activated) {
                currentSet.add(walletId)
            } else {
                currentSet.remove(walletId)
            }
            prefs[Keys.activatedCovenantWalletIdsJson] = json.encodeToString(currentSet)
            prefs.remove(Keys.covenantsActivated)
        }
    }

    suspend fun setCovenantsActivated(activated: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            val activeId = prefs[Keys.activeWalletId]
                ?: prefs[Keys.walletsJson]?.let {
                    runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()?.firstOrNull()?.id
                }
            if (!activeId.isNullOrBlank()) {
                val currentSet = prefs[Keys.activatedCovenantWalletIdsJson]?.let { raw ->
                    runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
                }?.toMutableSet() ?: mutableSetOf()
                if (activated) currentSet.add(activeId) else currentSet.remove(activeId)
                prefs[Keys.activatedCovenantWalletIdsJson] = json.encodeToString(currentSet)
                prefs.remove(Keys.covenantsActivated)
            }
        }
    }

    suspend fun setMainnetCovenantsEnabled(enabled: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.mainnetCovenantsEnabled] = enabled
        }
    }

    suspend fun setCovenantsAutoSyncUriForWallet(walletId: String, uri: String?) {
        if (walletId.isBlank()) return
        appContext.walletMetaDataStore.edit { prefs ->
            val current = prefs[Keys.covenantsAutoSyncUrisJson]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
            }?.toMutableMap() ?: mutableMapOf()
            if (uri.isNullOrBlank()) {
                current.remove(walletId)
            } else {
                current[walletId] = uri
            }
            prefs[Keys.covenantsAutoSyncUrisJson] = json.encodeToString(current)
            prefs.remove(Keys.covenantsAutoSyncUri)
        }
    }

    suspend fun setCovenantsAutoSyncUri(uri: String?) {
        appContext.walletMetaDataStore.edit { prefs ->
            val activeId = prefs[Keys.activeWalletId]
                ?: prefs[Keys.walletsJson]?.let {
                    runCatching { json.decodeFromString<List<WalletRecord>>(it) }.getOrNull()?.firstOrNull()?.id
                }
            if (!activeId.isNullOrBlank()) {
                val current = prefs[Keys.covenantsAutoSyncUrisJson]?.let { raw ->
                    runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
                }?.toMutableMap() ?: mutableMapOf()
                if (uri.isNullOrBlank()) {
                    current.remove(activeId)
                } else {
                    current[activeId] = uri
                }
                prefs[Keys.covenantsAutoSyncUrisJson] = json.encodeToString(current)
                prefs.remove(Keys.covenantsAutoSyncUri)
            }
        }
    }

    suspend fun setDonationPopupOptOut(optOut: Boolean) {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.donationPopupOptOut] = optOut
        }
    }

    suspend fun markLagTipStepSeen() {
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.hasSeenLagTipStep] = true
        }
    }

    suspend fun getCachedTransactions(walletId: String, network: KaspaNetwork): List<WalletTx> =
        withContext(Dispatchers.IO) {
            val prefs = appContext.walletMetaDataStore.data.first()
            val raw = prefs[Keys.cachedTxsKey(walletId, network)] ?: return@withContext emptyList()
            runCatching { json.decodeFromString<List<WalletTx>>(raw) }.getOrDefault(emptyList())
        }

    /**
     * Persists a wallet's recent transactions locally (capped at 100 latest entries).
     * Never overwrites a non-empty cache with an empty list on transient network errors unless
     * [allowEmptyOverwrite] is explicitly set (e.g., when purging foreign cross-wallet ghosts).
     */
    suspend fun saveCachedTransactions(
        walletId: String,
        network: KaspaNetwork,
        txs: List<WalletTx>,
        allowEmptyOverwrite: Boolean = false,
    ) {
        if (txs.isEmpty() && !allowEmptyOverwrite) return
        val trimmed = txs.sortedByDescending { it.blockTimeMs }.take(100)
        appContext.walletMetaDataStore.edit { prefs ->
            prefs[Keys.cachedTxsKey(walletId, network)] = json.encodeToString(trimmed)
        }
    }

    /**
     * Generates a Kaspium-compatible UTF-8 CSV transaction report and returns a FileProvider Share Intent.
     */
    suspend fun exportTransactionsCsv(
        walletName: String,
        network: KaspaNetwork,
        transactions: List<WalletTx>,
        txNotes: Map<String, String>,
    ): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val csv = buildCsvContent(network, transactions, txNotes)
            val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val safeName = walletName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "kaspa_wallet" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(exportDir, "${safeName}_transactions_$stamp.csv")
            outFile.writeText(csv, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                outFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$walletName — Kaspa Transaction Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    companion object {
        /**
         * Pure gating rule for the every-5-launches modal:
         * fires on 5th, 10th, 15th... launch ONLY when at least one un-muted step remains
         * (either the developer donation prompt OR the Covenants backup reminder when activated).
         */
        fun shouldShowLaunchPopup(
            launchCount: Int,
            donationOptedOut: Boolean,
            covenantsActivated: Boolean,
            covenantsReminderOptOut: Boolean,
        ): Boolean {
            if (launchCount <= 0 || launchCount % 5 != 0) return false
            val showDonation = !donationOptedOut
            val showCovenantsReminder = covenantsActivated && !covenantsReminderOptOut
            return showDonation || showCovenantsReminder
        }

        fun buildCsvContent(
            network: KaspaNetwork,
            transactions: List<WalletTx>,
            txNotes: Map<String, String>,
        ): String {
            val kasSymbol = if (network.isTestnet) "TKAS" else "KAS"
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val sb = StringBuilder()
            sb.appendLine("Date,Sent Amount,Sent Currency,Received Amount,Received Currency,Fee Amount,Fee Currency,Label,Description,TxHash,Note")
            for (tx in transactions) {
                val date = fmt.format(Date(tx.blockTimeMs))
                val sent = when (tx.direction) {
                    TxDirection.SENT, TxDirection.BURN -> KasFormat.formatKas(tx.netAmountSompi, trimTrailingZeros = false)
                    TxDirection.MINER_DONATION -> KasFormat.formatKas(tx.feeSompi, trimTrailingZeros = false)
                    else -> ""
                }
                val received = if (tx.direction == TxDirection.RECEIVED) {
                    KasFormat.formatKas(tx.netAmountSompi, trimTrailingZeros = false)
                } else {
                    ""
                }
                val fee = if (tx.feeSompi > 0L) KasFormat.formatKas(tx.feeSompi, trimTrailingZeros = false) else ""
                val label = when (tx.direction) {
                    TxDirection.BURN -> "burn"
                    TxDirection.MINER_DONATION -> "miner_donation"
                    TxDirection.COMPOUND, TxDirection.SELF_TRANSFER -> "cost"
                    else -> ""
                }
                val desc = tx.direction.name.lowercase(Locale.ROOT)
                val note = csvEscape(txNotes[tx.transactionId].orEmpty())
                sb.appendLine(
                    listOf(
                        date,
                        sent,
                        if (sent.isNotEmpty()) kasSymbol else "",
                        received,
                        if (received.isNotEmpty()) kasSymbol else "",
                        fee,
                        if (fee.isNotEmpty()) kasSymbol else "",
                        label,
                        desc,
                        tx.transactionId,
                        note,
                    ).joinToString(","),
                )
            }
            return sb.toString()
        }

        private fun csvEscape(raw: String): String {
            if (raw.isEmpty()) return ""
            val needsQuotes = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
            return if (needsQuotes) "\"${raw.replace("\"", "\"\"")}\"" else raw
        }

        private fun defaultCurrencyForSystem(): FiatCurrency {
            val code = runCatching {
                java.util.Currency.getInstance(Locale.getDefault()).currencyCode
            }.getOrNull()
            return FiatCurrency.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: FiatCurrency.USD
        }
    }
}
