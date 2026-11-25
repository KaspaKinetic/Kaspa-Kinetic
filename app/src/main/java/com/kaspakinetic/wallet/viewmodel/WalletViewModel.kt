package com.kaspakinetic.wallet.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor
import com.kaspakinetic.wallet.core.covenants.CovenantCompiler
import com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine
import com.kaspakinetic.wallet.core.covenants.CovenantParams
import com.kaspakinetic.wallet.core.covenants.CovenantRepository
import com.kaspakinetic.wallet.core.covenants.CovenantStatus
import com.kaspakinetic.wallet.core.covenants.CovenantTemplateId
import com.kaspakinetic.wallet.core.covenants.KineticBackupEngine
import com.kaspakinetic.wallet.core.covenants.TrackedCovenant
import com.kaspakinetic.wallet.core.crypto.Bip39
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.AutoLockTimeout
import com.kaspakinetic.wallet.core.model.ContactRecord
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.DerivationKind
import com.kaspakinetic.wallet.core.model.DerivedAddress
import com.kaspakinetic.wallet.core.model.FeeEstimate
import com.kaspakinetic.wallet.core.model.FeePriority
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.GAP_LIMIT_DEFAULT
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.MinerDonationProgress
import com.kaspakinetic.wallet.core.model.NodeConfig
import com.kaspakinetic.wallet.core.model.NodeConnectionMode
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.model.PreparedTx
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.core.model.SyncStatus
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.model.UtxoReconciler
import com.kaspakinetic.wallet.core.model.WalletMode
import com.kaspakinetic.wallet.core.model.WalletRecord
import com.kaspakinetic.wallet.core.model.WalletTx
import com.kaspakinetic.wallet.core.network.BlockAddedStreamHandle
import com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer
import com.kaspakinetic.wallet.core.network.KaspaNetworkGateway
import com.kaspakinetic.wallet.core.network.KaspadProtoCodec
import com.kaspakinetic.wallet.core.network.LiveStreamHandle
import com.kaspakinetic.wallet.core.network.OkHttpKaspaNetworkGateway
import com.kaspakinetic.wallet.core.network.RateLimitedException
import com.kaspakinetic.wallet.core.explore.KaspaExploreRepository
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.core.service.MAX_MINER_DONATION_SPLITS
import com.kaspakinetic.wallet.core.service.MinerDonationController
import com.kaspakinetic.wallet.core.service.MinerDonationRequest
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx
import com.kaspakinetic.wallet.data.repository.WalletDataRepository
import com.kaspakinetic.wallet.data.repository.WalletPreferencesState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class WalletUiState(
    val prefsLoaded: Boolean = false,
    val prefs: WalletPreferencesState = WalletPreferencesState(),
    val hasPin: Boolean = false,
    val hasWalletPassword: Boolean = false,
    val isHardwareBackedVault: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.CONNECTING,
    val nodeHealth: NodeHealth? = null,
    val totalBalanceSompi: Long = 0L,
    val walletBalances: Map<String, Long> = emptyMap(),
    val receiveAddresses: List<DerivedAddress> = emptyList(),
    val changeAddresses: List<DerivedAddress> = emptyList(),
    val utxos: List<UtxoItem> = emptyList(),
    val selectedUtxoKeys: Set<String> = emptySet(),
    val transactions: List<WalletTx> = emptyList(),
    val feeEstimate: FeeEstimate = FeeEstimate(),
    val spotPrice: SpotPrice? = null,
    val isRefreshing: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveryStatusText: String? = null,
    val minerDonationProgress: MinerDonationProgress? = null,
    val snackbarMessage: String? = null,
    val lastSyncError: String? = null,
    val showRestFallbackWarning: Boolean = false,
) {
    val activeWallet: WalletRecord? get() = prefs.activeWallet
    val activeNetwork: KaspaNetwork get() = prefs.nodeConfig.network
    val currentReceiveAddress: String
        get() {
            val wallet = activeWallet ?: return ""
            val idx = wallet.receiveIndex.coerceAtLeast(0)
            return receiveAddresses.firstOrNull { it.index == idx }?.address
                ?: receiveAddresses.firstOrNull()?.address
                ?: KaspaAddress.convertNetworkPrefix(wallet.primaryAddressMainnet, activeNetwork)
        }
    val currentChangeAddress: String
        get() {
            val wallet = activeWallet ?: return currentReceiveAddress
            val idx = wallet.changeIndex.coerceAtLeast(0)
            return changeAddresses.firstOrNull { it.index == idx }?.address
                ?: currentReceiveAddress
        }
    val selectedUtxos: List<UtxoItem>
        get() = utxos.filter { it.outpointKey in selectedUtxoKeys }
}

enum class PostActivationScanPhase {
    IDLE,
    SCANNING,
    COMPLETED,
}

data class PostActivationScanState(
    val visible: Boolean = false,
    val phase: PostActivationScanPhase = PostActivationScanPhase.IDLE,
    val foundCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isScanning: Boolean get() = visible && phase == PostActivationScanPhase.SCANNING
    val dismissAllowed: Boolean get() = !isScanning

    companion object {
        fun startScanning(): PostActivationScanState = PostActivationScanState(
            visible = true,
            phase = PostActivationScanPhase.SCANNING,
            foundCount = 0,
            errorMessage = null,
        )

        fun completed(foundCount: Int, errorMessage: String? = null): PostActivationScanState =
            PostActivationScanState(
                visible = true,
                phase = PostActivationScanPhase.COMPLETED,
                foundCount = foundCount.coerceAtLeast(0),
                errorMessage = errorMessage,
            )

        fun dismissed(): PostActivationScanState = PostActivationScanState()
    }
}

data class CovenantsUiState(
    val walletId: String = "",
    val covenants: List<TrackedCovenant> = emptyList(),
    val vaultCreatedAtMs: Long = 0L,
    val vaultLastUpdatedAtMs: Long = 0L,
    val isSyncing: Boolean = false,
    val isScanningChain: Boolean = false,
    val confirmingCovenantKeys: Set<String> = emptySet(),
    val externalAutoSyncWarning: String? = null,
    val lastSyncMessage: String? = null,
    val postActivationScan: PostActivationScanState = PostActivationScanState(),
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = SecureVault(application)
    private val dataRepo = WalletDataRepository(application)
    private val covenantRepo = CovenantRepository(application)
    private val network: KaspaNetworkGateway = OkHttpKaspaNetworkGateway(appContext = application.applicationContext)
    val exploreRepository: KaspaExploreRepository = KaspaExploreRepository()

    private val _covenantsState = MutableStateFlow(CovenantsUiState())
    val covenantsState: StateFlow<CovenantsUiState> = _covenantsState.asStateFlow()

    /**
     * Live monotonic chain-time anchor fed by gRPC `GetBlockDagInfo.pastMedianTime` and
     * `NotifyVirtualDaaScoreChanged`. Kept OUTSIDE [WalletUiState] so 10 BPS DAG ticks never
     * trigger full-screen recompositions, while covenant countdowns advance smoothly every second.
     */
    private val _chainTimeAnchor = MutableStateFlow(ChainTimeAnchor())
    val chainTimeAnchor: StateFlow<ChainTimeAnchor> = _chainTimeAnchor.asStateFlow()

    private var debouncedCovenantPushSyncJob: Job? = null
    private var activeCovenantLoadJob: Job? = null
    private var postActivationScanJob: Job? = null
    private var restFallbackWarningDebounceJob: Job? = null
    private val covenantStoreMutex = kotlinx.coroutines.sync.Mutex()
    private val covenantRefreshSingleFlightLock = Any()
    private var activeCovenantRefreshDeferred: kotlinx.coroutines.CompletableDeferred<List<TrackedCovenant>>? = null
    private val confirmationBarrierJobs = HashMap<String, Job>()

    private fun updateChainTimeAnchor(pastMedianTimeMs: Long = 0L, virtualDaaScore: Long = 0L) {
        if (pastMedianTimeMs <= 0L && virtualDaaScore <= 0L) return
        val elapsed = SystemClock.elapsedRealtime()
        _chainTimeAnchor.update { current ->
            current.mergeUpdate(
                newPastMedianTimeMs = pastMedianTimeMs,
                newVirtualDaaScore = virtualDaaScore,
                nowElapsedRealtimeMs = elapsed,
            )
        }
    }

    /**
     * Fetches the real, non-extrapolated gRPC server `pastMedianTime` & `virtualDaaScore` on demand
     * (used by the 2-second `Finalizing on Kaspa L1...` finality gate until server PMT strictly crosses
     * `unlockTimeMs`).
     */
    suspend fun pollServerBlockDagAnchorNow(): ChainTimeAnchor = withContext(Dispatchers.IO) {
        val snap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            network.getLiveBlockDagSnapshot(_uiState.value.prefs.nodeConfig)
        }.getOrNull()
        if (snap != null) {
            updateChainTimeAnchor(
                pastMedianTimeMs = snap.pastMedianTimeMs,
                virtualDaaScore = snap.virtualDaaScore,
            )
        }
        _chainTimeAnchor.value
    }

    fun currentEstimatedPastMedianTimeMs(): Long =
        _chainTimeAnchor.value.estimatePastMedianTimeMs(SystemClock.elapsedRealtime())

    /**
     * Submits a covenant transaction with mempool idempotency: if the node replies that the exact
     * [expectedTxId] we just built is `"already in the mempool"`, treat it as accepted so the caller
     * transitions smoothly into `"Confirming on Kaspa L1..."` instead of showing a red error card.
     */
    private suspend fun submitCovenantTransactionIdempotent(
        config: NodeConfig,
        submitJson: String,
        expectedTxId: String,
    ): String = com.kaspakinetic.wallet.core.network.KaspaMempoolIdempotency.resolveIdempotentSubmit(
        expectedTxId = expectedTxId,
    ) {
        network.submitTransaction(config, submitJson)
    }

    private fun collectAllWatchedAddresses(
        state: WalletUiState,
        covState: CovenantsUiState = _covenantsState.value,
    ): List<String> {
        val walletAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }
        val activeNet = state.activeNetwork
        val covenantAddrs = covState.covenants
            .filter { it.network == activeNet && it.liveStatus != CovenantStatus.SETTLED }
            .map { it.currentP2shAddress }
            .filter { it.isNotBlank() }
        return (walletAddrs + covenantAddrs).distinct()
    }

    private fun syncWatchedAddressesOnLiveStream() {
        val addrs = collectAllWatchedAddresses(_uiState.value, _covenantsState.value)
        if (addrs.isNotEmpty()) {
            viewModelScope.launch {
                wrpcHandle?.setWatchedAddresses(addrs, forceResubscribe = false)
            }
        }
    }

    private fun scheduleDebouncedCovenantPushSync(walletId: String) {
        debouncedCovenantPushSyncJob?.cancel()
        debouncedCovenantPushSyncJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250L)
            if (_uiState.value.activeWallet?.id != walletId) return@launch
            refreshCovenantsLiveStateSuspend()
        }
    }

    /**
     * Post-broadcast confirmation barrier: keeps [canonicalKey] in [CovenantsUiState.confirmingCovenantKeys]
     * (disabling follow-up spends and showing "Confirming on Kaspa L1...") until the exact same connected
     * gRPC node confirms the resulting UTXO (or confirms full spend on settlement), preventing orphan errors.
     */
    private fun launchCovenantConfirmationBarrier(
        walletId: String,
        canonicalKey: String,
        expectedNewTxId: String,
        expectingSettled: Boolean,
    ) {
        _covenantsState.update { it.copy(confirmingCovenantKeys = it.confirmingCovenantKeys + canonicalKey) }
        confirmationBarrierJobs[canonicalKey]?.cancel()
        confirmationBarrierJobs[canonicalKey] = viewModelScope.launch(Dispatchers.IO) {
            try {
                syncWatchedAddressesOnLiveStream()
                for (attempt in 0 until 22) {
                    delay(if (attempt == 0) 800L else 950L)
                    if (_uiState.value.activeWallet?.id != walletId) break
                    if (attempt == 1) {
                        pollUtxosAndTransactionsQuietly(forceFullTx = false)
                    }
                    val refreshed = refreshCovenantsLiveStateSuspend()
                    val latest = refreshed.firstOrNull { it.canonicalKey == canonicalKey } ?: break
                    val confirmed = if (expectingSettled) {
                        latest.liveStatus == CovenantStatus.SETTLED || latest.liveBalanceSompi <= 0L
                    } else {
                        latest.liveUtxoTxId.equals(expectedNewTxId, ignoreCase = true) &&
                            latest.liveStatus != CovenantStatus.UNCONFIRMED &&
                            latest.liveUtxoBlockDaaScore > 0L
                    }
                    if (confirmed) break
                }
            } finally {
                _covenantsState.update { it.copy(confirmingCovenantKeys = it.confirmingCovenantKeys - canonicalKey) }
                confirmationBarrierJobs.remove(canonicalKey)
            }
        }
    }

    suspend fun openLiveBlockDagStream(
        onBlockAdded: (KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit,
    ): BlockAddedStreamHandle? {
        return network.openBlockAddedStream(
            config = _uiState.value.prefs.nodeConfig,
            onBlockAdded = onBlockAdded,
        )
    }

    fun setWalletGoal(walletId: String, targetKas: Double?, customMonthlyDca: Double?) {
        viewModelScope.launch {
            dataRepo.setWalletGoalKas(walletId, targetKas)
            dataRepo.setWalletCustomMonthlyDca(walletId, customMonthlyDca)
        }
    }

    fun clearWalletGoal(walletId: String) {
        viewModelScope.launch {
            dataRepo.setWalletGoalKas(walletId, null)
            dataRepo.setWalletCustomMonthlyDca(walletId, null)
        }
    }

    /** Lifecycle-safe explorer lookup executed in `viewModelScope` (never cancelled by transient sheet teardown). */
    suspend fun resolveExplorerQuerySafe(
        network: KaspaNetwork,
        rawQuery: String,
    ): Result<com.kaspakinetic.wallet.core.explore.ExplorerTarget> =
        viewModelScope.async(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                exploreRepository.resolveSearchQuery(network, rawQuery)
            }
        }.await()

    suspend fun fetchExplorerTxSafe(
        network: KaspaNetwork,
        txId: String,
    ): Result<com.kaspakinetic.wallet.core.explore.ExplorerTxDetail> =
        viewModelScope.async(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                exploreRepository.fetchTransactionDetail(network, txId)
            }
        }.await()

    suspend fun fetchExplorerBlockSafe(
        network: KaspaNetwork,
        blockHash: String,
    ): Result<com.kaspakinetic.wallet.core.explore.ExplorerBlockDetail> =
        viewModelScope.async(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                exploreRepository.fetchBlockDetail(network, blockHash)
            }
        }.await()

    suspend fun fetchExplorerAddressSafe(
        network: KaspaNetwork,
        address: String,
        beforeTimestampMs: Long? = null,
    ): Result<com.kaspakinetic.wallet.core.explore.ExplorerAddressDetail> =
        viewModelScope.async(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                exploreRepository.fetchAddressDetail(network, address, beforeTimestampMs = beforeTimestampMs)
            }
        }.await()

    suspend fun refreshExplorerLiveFeedSafe(
        network: KaspaNetwork,
    ): Result<List<com.kaspakinetic.wallet.core.explore.LiveTxFeedItem>> =
        viewModelScope.async(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                exploreRepository.fetchLiveTransactionsFeed(network)
            }
        }.await()

    private val _uiState = MutableStateFlow(
        WalletUiState(
            hasPin = vault.hasPin(),
            hasWalletPassword = vault.hasWalletPassword(),
            isHardwareBackedVault = vault.isHardwareBacked(),
        ),
    )
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val _prefsLoaded = MutableStateFlow(false)
    val prefsLoaded: StateFlow<Boolean> = _prefsLoaded.asStateFlow()

    /**
     * Live 1-second DAA / latency telemetry kept outside [uiState] so periodic node ticks only
     * recompose [NodeStatusSheet] instead of invalidating MainWalletScreen's LayerBackdrop every second
     * (Kinetic Aero Section 11.D Rule #22).
     */
    private val _liveNodeHealth = MutableStateFlow<NodeHealth?>(null)
    val liveNodeHealth: StateFlow<NodeHealth?> = _liveNodeHealth.asStateFlow()

    private var syncJob: Job? = null
    private var autoDiscoveryJob: Job? = null
    private var inactiveBalancesJob: Job? = null
    private var liveLoopJob: Job? = null
    private var targetedTxSyncJob: Job? = null
    private var debouncedUtxoVerifyJob: Job? = null
    private var postSendPollJob: Job? = null
    private var wrpcHandle: LiveStreamHandle? = null
    private val activeWalletEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    private val covenantActionMutex = kotlinx.coroutines.sync.Mutex()
    private var isInForeground: Boolean = true
    private var lastSyncedWalletKey: String? = null
    private var lastUtxoFingerprint: String = ""
    private var hasBaselineUtxos: Boolean = false
    private var pendingTxRefreshPolls: Int = 0
    private var lastConnectivityRecoverMs: Long = 0L
    private var shownRestWarningThisEpisode: Boolean = false
    private val utxoReconciler = UtxoReconciler()
    private val pendingVerifyLock = Any()
    private val pendingVerifyAddresses = LinkedHashSet<String>()

    private fun isCurrentScope(capturedEpoch: Long, walletId: String, net: KaspaNetwork): Boolean =
        isScopeValid(
            capturedEpoch = capturedEpoch,
            currentEpoch = activeWalletEpoch.get(),
            capturedWalletId = walletId,
            activeWalletId = _uiState.value.activeWallet?.id,
            capturedNetwork = net,
            activeNetwork = _uiState.value.activeNetwork,
        )

    private fun cancelAllWalletScopedJobs() {
        syncJob?.cancel()
        autoDiscoveryJob?.cancel()
        targetedTxSyncJob?.cancel()
        debouncedUtxoVerifyJob?.cancel()
        postSendPollJob?.cancel()
        synchronized(pendingVerifyLock) {
            pendingVerifyAddresses.clear()
        }
    }

    init {
        viewModelScope.launch {
            MinerDonationController.progress.collect { progress ->
                _uiState.update { it.copy(minerDonationProgress = progress) }
            }
        }
        viewModelScope.launch {
            dataRepo.state.collect { prefs ->
                val prevWalletId = _uiState.value.prefs.activeWalletId
                val prevNet = _uiState.value.prefs.nodeConfig
                val prevCurrency = _uiState.value.prefs.fiatCurrency
                val prevPrivacy = _uiState.value.prefs.kasOnlyPrivacyMode
                val active = prefs.activeWallet
                val switchedWallet = prevWalletId != prefs.activeWalletId
                val walletOrNetworkChanged = switchedWallet || prevNet.network != prefs.nodeConfig.network
                val nodeEndpointChanged = prevNet != prefs.nodeConfig

                if (walletOrNetworkChanged) {
                    activeWalletEpoch.incrementAndGet()
                    cancelAllWalletScopedJobs()
                    utxoReconciler.reset()
                    network.clearRestBackoffOnUserRefresh()
                }

                _uiState.update { state ->
                    // Always preserve persisted per-wallet balances so switching wallets never drops to 0.00 KAS
                    val mergedBalances = state.walletBalances.toMutableMap()
                    prefs.wallets.forEach { w ->
                        if (w.id !in mergedBalances || w.cachedBalanceSompi > 0L) {
                            mergedBalances[w.id] = w.cachedBalanceSompi
                        }
                    }
                    state.copy(
                        prefsLoaded = true,
                        prefs = prefs,
                        walletBalances = mergedBalances,
                        totalBalanceSompi = if (switchedWallet && active != null) {
                            mergedBalances[active.id] ?: active.cachedBalanceSompi
                        } else if (!state.prefsLoaded && active != null) {
                            active.cachedBalanceSompi
                        } else {
                            state.totalBalanceSompi
                        },
                        // Immediately isolate derived addresses, UTXOs, and transactions on wallet/network switch
                        // so live gRPC subscriptions & async push handlers can NEVER see the previous wallet's addresses.
                        receiveAddresses = if (walletOrNetworkChanged) emptyList() else state.receiveAddresses,
                        changeAddresses = if (walletOrNetworkChanged) emptyList() else state.changeAddresses,
                        utxos = if (walletOrNetworkChanged) emptyList() else state.utxos,
                        transactions = if (walletOrNetworkChanged) emptyList() else state.transactions,
                        selectedUtxoKeys = if (walletOrNetworkChanged) emptySet() else state.selectedUtxoKeys,
                        spotPrice = if (prefs.kasOnlyPrivacyMode || prefs.nodeConfig.network.isTestnet) null else state.spotPrice,
                    )
                }

                if (!_prefsLoaded.value) _prefsLoaded.value = true

                val syncKey = "${active?.id}|${prefs.nodeConfig.network.id}|${prefs.nodeConfig.effectiveConnectionMode.name}|${prefs.nodeConfig.effectiveGrpcDisplay}|${prefs.nodeConfig.effectiveRestUrl}"
                if (active != null && (syncKey != lastSyncedWalletKey || prevWalletId != prefs.activeWalletId || prevNet != prefs.nodeConfig)) {
                    lastSyncedWalletKey = syncKey
                    lastUtxoFingerprint = ""
                    hasBaselineUtxos = false
                    pendingTxRefreshPolls = 0
                    if (!walletOrNetworkChanged) {
                        targetedTxSyncJob?.cancel()
                        debouncedUtxoVerifyJob?.cancel()
                        postSendPollJob?.cancel()
                    }
                    if (nodeEndpointChanged || walletOrNetworkChanged) {
                        shownRestWarningThisEpisode = false
                        _uiState.update { it.copy(showRestFallbackWarning = false) }
                    }
                    val capturedEpoch = activeWalletEpoch.get()
                    rebuildAddressesAndRefresh(
                        wallet = active,
                        prefs = prefs,
                        resetForNewScope = walletOrNetworkChanged,
                        capturedEpoch = capturedEpoch,
                    )
                    triggerBackgroundAddressDiscovery(active, prefs, capturedEpoch = capturedEpoch)
                    refreshInactiveWalletBalancesQuietly(prefs)
                    restartLiveSyncLoop(forceRebuild = nodeEndpointChanged, capturedEpoch = capturedEpoch)
                    loadAndRefreshCovenantsForActiveWallet(active.id, prefs.isCovenantsActivatedForWallet(active.id))
                } else if (active != null && (prevCurrency != prefs.fiatCurrency || prevPrivacy != prefs.kasOnlyPrivacyMode)) {
                    refreshSpotPriceOnly(prefs)
                }
            }
        }
    }

    override fun onCleared() {
        stopLiveSyncLoop()
        cancelAllWalletScopedJobs()
        inactiveBalancesJob?.cancel()
        network.closeConnections()
        super.onCleared()
    }

    fun setAppInForeground(foreground: Boolean) {
        if (isInForeground == foreground) return
        isInForeground = foreground
        if (foreground) {
            if (_uiState.value.activeWallet != null) {
                // Force clean gRPC health-check/channel rebuild on foreground resume so half-open
                // 5G NAT sockets never stall at "Connecting" or stay stuck on REST fallback!
                restartLiveSyncLoop(forceRebuild = true)
            }
        } else {
            // Keep the live gRPC stream alive when a background Miner Donation foreground service is active
            val donationRunning = MinerDonationController.progress.value?.isRunning == true
            if (!donationRunning) {
                stopLiveSyncLoop()
            }
        }
    }

    /**
     * Triggered by Android [android.net.ConnectivityManager.NetworkCallback] whenever 5G/Wi-Fi
     * connectivity is gained or switches transports. Immediately rebuilds any stale gRPC socket
     * and promotes out of REST fallback without waiting for timers.
     */
    fun onNetworkConnectivityChanged() {
        // Forgive old-path node failures immediately (Wi-Fi <-> 5G <-> VPN transition)
        network.onNetworkPathChanged()
        if (!isInForeground || _uiState.value.activeWallet == null) return
        val now = System.currentTimeMillis()
        if (now - lastConnectivityRecoverMs < 1_800L) return
        lastConnectivityRecoverMs = now
        val config = _uiState.value.prefs.nodeConfig
        val healthy = network.isGrpcActive(config) && network.grpcLastActivityAgeMs(config) < 12_000L
        if (!healthy || wrpcHandle == null) {
            restartLiveSyncLoop(forceRebuild = true)
        }
    }

    private fun stopLiveSyncLoop() {
        liveLoopJob?.cancel()
        liveLoopJob = null
        debouncedUtxoVerifyJob?.cancel()
        debouncedUtxoVerifyJob = null
        wrpcHandle?.close()
        wrpcHandle = null
    }

    fun dismissRestFallbackWarning() {
        restFallbackWarningDebounceJob?.cancel()
        restFallbackWarningDebounceJob = null
        _uiState.update { it.copy(showRestFallbackWarning = false) }
    }

    /**
     * Debounced (1.5s) REST fallback notice: only shows the warning if gRPC is STILL genuinely
     * disconnected after the grace window, preventing transient 1-second flashes during navigation
     * or reconnect handshakes.
     */
    private fun triggerRestFallbackWarningOnce() {
        if (shownRestWarningThisEpisode) return
        if (restFallbackWarningDebounceJob?.isActive == true) return
        restFallbackWarningDebounceJob = viewModelScope.launch {
            delay(1_500L)
            val cfg = _uiState.value.prefs.nodeConfig
            if (!shownRestWarningThisEpisode && !network.isGrpcActive(cfg)) {
                shownRestWarningThisEpisode = true
                _uiState.update { it.copy(showRestFallbackWarning = true) }
            }
        }
    }

    private fun publishGrpcConnectedHealth(config: NodeConfig, daaScore: Long? = null, latencyMs: Long? = null) {
        // Live gRPC is back — cancel any pending debounced REST warning & reset episode guard!
        restFallbackWarningDebounceJob?.cancel()
        restFallbackWarningDebounceJob = null
        shownRestWarningThisEpisode = false
        val prev = _liveNodeHealth.value ?: _uiState.value.nodeHealth ?: NodeHealth(
            reachable = true,
            isSynced = true,
            isUtxoIndexed = true,
            networkName = config.network.id,
        )
        val activeEp = network.activeGrpcEndpoint(config)
        val isTls = activeEp?.useTls ?: config.resolvedGrpcEndpoint().third
        val epDisplay = activeEp?.hostPort ?: config.resolvedGrpcEndpoint().let { (h, p, _) -> "$h:$p" }
        val poolCount = network.activePoolCount(config)
        val transportLabel = when (config.effectiveConnectionMode) {
            NodeConnectionMode.AUTOMATIC_SCAN -> if (isTls) {
                "Auto Scan • TLS Fallback"
            } else {
                "Auto Scan • Plaintext gRPC"
            }
            NodeConnectionMode.TLS_ONLY -> "TLS Only • gRPC (TLS)"
            NodeConnectionMode.CUSTOM -> "Custom • gRPC (${if (isTls) "TLS" else "Plaintext"})"
        }
        val updated = prev.copy(
            reachable = true,
            isSynced = true,
            isUtxoIndexed = true,
            virtualDaaScore = daaScore?.takeIf { it > 0L } ?: prev.virtualDaaScore,
            latencyMs = latencyMs?.takeIf { it > 0L } ?: prev.latencyMs,
            transportUsed = transportLabel,
            activeEndpoint = epDisplay,
            isTls = isTls,
            activePoolCount = poolCount,
            errorMessage = null,
        )
        _liveNodeHealth.value = updated
        _uiState.update { state ->
            val needStateUpdate = state.syncStatus != SyncStatus.SYNCED ||
                state.lastSyncError != null ||
                state.showRestFallbackWarning ||
                state.nodeHealth?.transportUsed != updated.transportUsed ||
                state.nodeHealth?.activeEndpoint != updated.activeEndpoint ||
                state.nodeHealth?.activePoolCount != updated.activePoolCount
            if (needStateUpdate) {
                state.copy(
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncError = null,
                    showRestFallbackWarning = false,
                    nodeHealth = updated,
                )
            } else {
                state
            }
        }
    }

    private fun restartLiveSyncLoop(
        forceRebuild: Boolean = false,
        capturedEpoch: Long = activeWalletEpoch.get(),
    ) {
        stopLiveSyncLoop()
        if (!isInForeground) return
        val prefs = _uiState.value.prefs
        val active = prefs.activeWallet ?: return
        val loopNet = prefs.nodeConfig.network

        liveLoopJob = viewModelScope.launch {
            var grpcConnected = false

            suspend fun connectAndSubscribeGrpc(
                currentPrefs: WalletPreferencesState,
                addrs: List<String>,
                rebuildChannel: Boolean,
            ): Boolean {
                if (!isCurrentScope(capturedEpoch, active.id, loopNet)) return false
                wrpcHandle?.close()
                wrpcHandle = null
                val handle = network.openLiveNodeStream(
                    config = currentPrefs.nodeConfig,
                    addresses = addrs,
                    forceRebuild = rebuildChannel,
                    onDaaScore = { daa ->
                        updateChainTimeAnchor(virtualDaaScore = daa)
                        publishGrpcConnectedHealth(currentPrefs.nodeConfig, daaScore = daa)
                    },
                    onUtxosChanged = { added, removed ->
                        viewModelScope.launch {
                            handleGrpcUtxosChangedPush(capturedEpoch, active.id, loopNet, added, removed)
                        }
                    },
                    onConnectionChanged = { connected ->
                        grpcConnected = connected
                        if (connected) {
                            publishGrpcConnectedHealth(currentPrefs.nodeConfig)
                        } else if (_uiState.value.syncStatus == SyncStatus.SYNCED) {
                            _uiState.update { it.copy(syncStatus = SyncStatus.CONNECTING) }
                        }
                    },
                )
                if (!isCurrentScope(capturedEpoch, active.id, loopNet)) {
                    handle?.close()
                    return false
                }
                wrpcHandle = handle
                grpcConnected = handle != null && network.isGrpcActive(currentPrefs.nodeConfig)
                if (grpcConnected) {
                    publishGrpcConnectedHealth(currentPrefs.nodeConfig)
                    // Seed live consensus pastMedianTime immediately upon gRPC connect
                    launch(Dispatchers.IO) {
                        val snap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                            network.getLiveBlockDagSnapshot(currentPrefs.nodeConfig)
                        }.getOrNull()
                        if (snap != null) {
                            updateChainTimeAnchor(
                                pastMedianTimeMs = snap.pastMedianTimeMs,
                                virtualDaaScore = snap.virtualDaaScore,
                            )
                        }
                    }
                }
                return grpcConnected
            }

            // Wait cleanly until the active wallet's own receive addresses have been derived
            // so we NEVER subscribe NotifyUtxosChanged to an empty or previous wallet's address list.
            var initialAddrs = if (_uiState.value.receiveAddresses.isNotEmpty()) {
                collectAllWatchedAddresses(_uiState.value, _covenantsState.value)
            } else {
                emptyList()
            }
            var waitSteps = 0
            while (initialAddrs.isEmpty() && waitSteps < 30 && isActive) {
                delay(50L)
                if (!isCurrentScope(capturedEpoch, active.id, loopNet)) return@launch
                if (_uiState.value.receiveAddresses.isNotEmpty()) {
                    initialAddrs = collectAllWatchedAddresses(_uiState.value, _covenantsState.value)
                }
                waitSteps++
            }
            if (!isCurrentScope(capturedEpoch, active.id, loopNet)) return@launch

            // 1. Primary: Persistent bidirectional kaspad gRPC push stream (with Automatic Scan pool)
            val initialConnected = connectAndSubscribeGrpc(prefs, initialAddrs, rebuildChannel = forceRebuild)
            if (initialConnected && forceRebuild) {
                // Immediately catch up any UTXOs/txs that arrived while backgrounded or offline
                pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = capturedEpoch)
            }

            // Expand Automatic Scan node pool gently in the background without blocking startup
            if (prefs.nodeConfig.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                launch {
                    val count = runCatching { network.refreshNodePoolIfNeeded(prefs.nodeConfig, force = false) }.getOrDefault(0)
                    if (count > 0 && grpcConnected) {
                        publishGrpcConnectedHealth(prefs.nodeConfig)
                    }
                }
            }

            // 2. Maintenance, liveness watchdog, & automatic exponential-backoff recovery loop:
            // - While Live gRPC is active: ZERO REST polling! Keeps watched addresses synced and
            //   verifies stream liveness if silent >25s (catches silent 5G NAT drops immediately).
            // - Whenever gRPC drops or app is in Public REST Fallback: automatically retries gRPC
            //   with exponential backoff (2s -> 4s -> 8s -> 15s -> 30s) until it promotes itself
            //   seamlessly back to Live gRPC and re-subscribes all addresses!
            var reconnectBackoffMs = 2_000L
            var nextReconnectAttemptAtMs = if (initialConnected) Long.MAX_VALUE else System.currentTimeMillis() + reconnectBackoffMs
            var nextRestFallbackAtMs = System.currentTimeMillis() + 15_000L
            var lastWatchdogCheckMs = System.currentTimeMillis()
            var lastPmtAnchorRefreshMs = System.currentTimeMillis()
            var lastSpotRefreshMs = System.currentTimeMillis()

            while (isActive && isInForeground) {
                delay(2_000L)
                val now = System.currentTimeMillis()
                val currentPrefs = _uiState.value.prefs
                if (!isCurrentScope(capturedEpoch, active.id, loopNet)) break

                val currentAddrs = collectAllWatchedAddresses(_uiState.value, _covenantsState.value)
                val transportIsRest = _liveNodeHealth.value?.transportUsed?.contains("REST", ignoreCase = true) == true
                val liveActive = grpcConnected && wrpcHandle != null && network.isGrpcActive(currentPrefs.nodeConfig)

                if (liveActive && !transportIsRest) {
                    reconnectBackoffMs = 2_000L
                    nextReconnectAttemptAtMs = Long.MAX_VALUE
                    if (currentAddrs.isNotEmpty()) {
                        wrpcHandle?.setWatchedAddresses(currentAddrs, forceResubscribe = false)
                    }
                    // Silently re-anchor pastMedianTime over gRPC every ~20s so consensus countdowns never drift
                    if (now - lastPmtAnchorRefreshMs >= 20_000L) {
                        lastPmtAnchorRefreshMs = now
                        launch(Dispatchers.IO) {
                            val snap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                                network.getLiveBlockDagSnapshot(currentPrefs.nodeConfig)
                            }.getOrNull()
                            if (snap != null) {
                                updateChainTimeAnchor(
                                    pastMedianTimeMs = snap.pastMedianTimeMs,
                                    virtualDaaScore = snap.virtualDaaScore,
                                )
                            }
                        }
                    }
                    // Liveness watchdog: if no gRPC frame arrived in >25s, verify & rebuild if half-open
                    if (now - lastWatchdogCheckMs >= 15_000L) {
                        lastWatchdogCheckMs = now
                        val ageMs = network.grpcLastActivityAgeMs(currentPrefs.nodeConfig)
                        if (ageMs > 25_000L) {
                            val ping = network.verifyOrReconnectGrpc(currentPrefs.nodeConfig, forceRebuild = false)
                            if (ping != null && ping.reachable) {
                                publishGrpcConnectedHealth(currentPrefs.nodeConfig, ping.virtualDaaScore, ping.latencyMs)
                                if (currentAddrs.isNotEmpty()) {
                                    wrpcHandle?.setWatchedAddresses(currentAddrs, forceResubscribe = false)
                                }
                            } else {
                                grpcConnected = false
                                nextReconnectAttemptAtMs = now + reconnectBackoffMs
                            }
                        }
                    }
                } else {
                    // Disconnected or stuck in Public REST Fallback: auto-promote back to Live gRPC!
                    if (nextReconnectAttemptAtMs == Long.MAX_VALUE) {
                        nextReconnectAttemptAtMs = now + reconnectBackoffMs
                    }
                    if (now >= nextReconnectAttemptAtMs) {
                        val recovered = connectAndSubscribeGrpc(currentPrefs, currentAddrs, rebuildChannel = true)
                        if (recovered) {
                            reconnectBackoffMs = 2_000L
                            nextReconnectAttemptAtMs = Long.MAX_VALUE
                            // Reconcile UTXOs immediately over freshly restored Live gRPC!
                            pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = capturedEpoch)
                        } else {
                            reconnectBackoffMs = (reconnectBackoffMs * 2L).coerceAtMost(30_000L)
                            nextReconnectAttemptAtMs = now + reconnectBackoffMs
                        }
                    }

                    // Polite 15-second REST fallback ONLY while gRPC is down and REST is not rate-limited
                    if (!grpcConnected && now >= nextRestFallbackAtMs) {
                        nextRestFallbackAtMs = now + 15_000L
                        triggerRestFallbackWarningOnce()
                        if (network.isRestRateLimited()) {
                            _uiState.update {
                                it.copy(
                                    syncStatus = SyncStatus.CONNECTING,
                                    lastSyncError = "Rate limited by REST API (429). Waiting to retry...",
                                )
                            }
                        } else {
                            pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = capturedEpoch)
                        }
                    }
                }

                // Refresh spot price and gently maintain Automatic Scan pool (metered-throttled) every ~60s
                if (now - lastSpotRefreshMs >= 60_000L) {
                    lastSpotRefreshMs = now
                    refreshSpotPriceOnly(currentPrefs)
                    if (currentPrefs.nodeConfig.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                        launch {
                            val count = runCatching { network.refreshNodePoolIfNeeded(currentPrefs.nodeConfig, force = false) }.getOrDefault(0)
                            if (count > 0 && grpcConnected) {
                                publishGrpcConnectedHealth(currentPrefs.nodeConfig)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles real-time `NotifyUtxosChanged` gRPC push events directly from `kaspad`:
     * 1. Atomically reconciles `removed` and `added` UTXOs via [utxoReconciler] (zero doubling,
     *    zero duplicate `Block DAA: 0` pending entries, immediate Total Balance update).
     * 2. Immediately triggers on-demand transaction history sync with short retries if the REST
     *    indexer lags by a second.
     * 3. Schedules a Kaspium-style debounced (`650ms`) authoritative gRPC `GetUtxosByAddresses`
     *    verification for affected addresses so rapid multi-split bursts reconcile 100% cleanly.
     */
    private suspend fun handleGrpcUtxosChangedPush(
        capturedEpoch: Long,
        walletId: String,
        targetNet: KaspaNetwork,
        added: List<UtxoItem>,
        removed: List<KaspadProtoCodec.RemovedUtxoRef>,
    ) {
        if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return
        val state = _uiState.value
        val wallet = state.activeWallet ?: return
        if (wallet.id != walletId) return
        val prefs = state.prefs

        val allAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }.distinct()
        val walletAddrSet = allAddrs.toSet()
        // Never process push events before this wallet's own addresses are derived — prevents cross-wallet bleed!
        if (walletAddrSet.isEmpty()) return

        val prevUtxos = state.utxos
        val nextUtxos = utxoReconciler.applyUtxosChangedPush(
            added = added,
            removed = removed,
            walletAddressSet = walletAddrSet,
        )
        val balByAddr = nextUtxos.groupBy { it.address }.mapValues { (_, list) -> list.sumOf { it.amountSompi } }
        val totalSompi = nextUtxos.sumOf { it.amountSompi }

        lastUtxoFingerprint = utxoFingerprint(nextUtxos)
        hasBaselineUtxos = true

        val knownTxIds = state.transactions.mapTo(HashSet()) { it.transactionId }
        val optimisticTxs = synthesizeDetectedTxsFromUtxoDiff(
            prevUtxos = prevUtxos,
            newUtxos = nextUtxos,
            existingTxIds = knownTxIds,
            nowMs = System.currentTimeMillis(),
        )

        var mergedSnapshot: List<WalletTx> = state.transactions
        if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return
        _uiState.update { s ->
            val balances = s.walletBalances.toMutableMap()
            balances[wallet.id] = totalSompi
            val mergedTxs = if (optimisticTxs.isNotEmpty()) {
                mergeTransactions(s.transactions, optimisticTxs)
            } else {
                s.transactions
            }
            mergedSnapshot = mergedTxs
            s.copy(
                syncStatus = SyncStatus.SYNCED,
                lastSyncError = null,
                totalBalanceSompi = totalSompi,
                walletBalances = balances,
                receiveAddresses = s.receiveAddresses.map { a ->
                    val b = balByAddr[a.address] ?: 0L
                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                },
                changeAddresses = s.changeAddresses.map { a ->
                    val b = balByAddr[a.address] ?: 0L
                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                },
                utxos = nextUtxos,
                transactions = mergedTxs,
            )
        }
        if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return
        dataRepo.updateWalletBalance(wallet.id, totalSompi)
        if (mergedSnapshot.isNotEmpty()) {
            dataRepo.saveCachedTransactions(wallet.id, targetNet, mergedSnapshot)
        }

        val addedTxIds = added
            .filter { it.address in walletAddrSet }
            .map { it.transactionId }
            .filter { isValidRealTxId(it) }
            .distinct()
        val touchedAddrs = (added.map { it.address } + removed.map { it.address })
            .filter { it.isNotBlank() && it in walletAddrSet }
            .distinct()
        if (addedTxIds.isNotEmpty() || touchedAddrs.isNotEmpty()) {
            launchTargetedTxSync(
                capturedEpoch = capturedEpoch,
                walletId = wallet.id,
                config = prefs.nodeConfig,
                targetTxIds = addedTxIds,
                touchedAddresses = touchedAddrs,
                walletAddrSet = walletAddrSet,
            )
        }
        if (touchedAddrs.isNotEmpty()) {
            scheduleDebouncedAuthoritativeUtxoVerify(capturedEpoch, wallet.id, targetNet, touchedAddrs)
        }

        // Real-time gRPC covenant push detection: if any added/removed UTXO touches an active
        // covenant P2SH address, covenantId, OR tracked live outpoint (`txId:index`), immediately
        // trigger a debounced gRPC covenant sync (catches external Heir / Recipient claims even
        // when `removed.address` is omitted by the node!).
        val activeNet = state.activeNetwork
        val activeCovs = _covenantsState.value.covenants.filter { it.network == activeNet }
        if (activeCovs.isNotEmpty()) {
            val covAddrSet = activeCovs.mapTo(HashSet()) { it.currentP2shAddress.lowercase() }
            val covIdSet = activeCovs.mapNotNullTo(HashSet()) { it.covenantIdHex?.lowercase() }
            val covOutpointSet = activeCovs.mapNotNullTo(HashSet()) { cov ->
                val txId = cov.liveUtxoTxId?.takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                val idx = cov.liveUtxoIndex ?: return@mapNotNullTo null
                "$txId:$idx"
            }
            val covTouched = added.any { u ->
                u.address.lowercase() in covAddrSet ||
                    (!u.covenantId.isNullOrBlank() && u.covenantId.lowercase() in covIdSet)
            } || removed.any { r ->
                r.address.lowercase() in covAddrSet || r.outpointKey in covOutpointSet
            }
            if (covTouched) {
                scheduleDebouncedCovenantPushSync(wallet.id)
            }
        }
    }

    /**
     * Mirrors Kaspium's `utxosChangedDebouncedProvider` -> `UtxosNotifier.refresh(addresses)`:
     * after a burst of push events settles (`650ms`), queries gRPC `GetUtxosByAddresses` for the
     * affected addresses and reconciles through [utxoReconciler] so balance & UTXOs stay exact.
     */
    private fun scheduleDebouncedAuthoritativeUtxoVerify(
        capturedEpoch: Long,
        walletId: String,
        targetNet: KaspaNetwork,
        touchedAddrs: List<String>,
    ) {
        synchronized(pendingVerifyLock) {
            pendingVerifyAddresses.addAll(touchedAddrs)
        }
        debouncedUtxoVerifyJob?.cancel()
        debouncedUtxoVerifyJob = viewModelScope.launch {
            delay(650L)
            if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return@launch
            val addrsToVerify = synchronized(pendingVerifyLock) {
                val copy = pendingVerifyAddresses.toList()
                pendingVerifyAddresses.clear()
                copy
            }
            if (addrsToVerify.isEmpty()) return@launch
            val state = _uiState.value
            val wallet = state.activeWallet ?: return@launch
            if (wallet.id != walletId) return@launch
            val walletAddrSet = (state.receiveAddresses + state.changeAddresses).map { it.address }.toSet()
            val safeAddrs = addrsToVerify.filter { it in walletAddrSet }
            if (safeAddrs.isEmpty()) return@launch
            val fetched = runCatching { network.getUtxos(state.prefs.nodeConfig, safeAddrs) }.getOrNull() ?: return@launch
            if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return@launch

            val reconciled = utxoReconciler.applyAddressSubsetSnapshot(
                refreshedAddresses = safeAddrs.toSet(),
                snapshotForAddresses = fetched,
            )
            val balByAddr = reconciled.groupBy { it.address }.mapValues { (_, list) -> list.sumOf { it.amountSompi } }
            val totalSompi = reconciled.sumOf { it.amountSompi }
            lastUtxoFingerprint = utxoFingerprint(reconciled)
            hasBaselineUtxos = true

            _uiState.update { s ->
                val balances = s.walletBalances.toMutableMap()
                balances[wallet.id] = totalSompi
                s.copy(
                    totalBalanceSompi = totalSompi,
                    walletBalances = balances,
                    receiveAddresses = s.receiveAddresses.map { a ->
                        val b = balByAddr[a.address] ?: 0L
                        a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                    },
                    changeAddresses = s.changeAddresses.map { a ->
                        val b = balByAddr[a.address] ?: 0L
                        a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                    },
                    utxos = reconciled,
                )
            }
            if (!isCurrentScope(capturedEpoch, walletId, targetNet)) return@launch
            dataRepo.updateWalletBalance(wallet.id, totalSompi)

            val missingTxIds = findUnindexedUtxoTxIds(reconciled, _uiState.value.transactions)
            if (missingTxIds.isNotEmpty()) {
                val missingAddrs = reconciled
                    .filter { it.transactionId in missingTxIds }
                    .map { it.address }
                    .filter { it in walletAddrSet }
                    .distinct()
                launchTargetedTxSync(
                    capturedEpoch = capturedEpoch,
                    walletId = wallet.id,
                    config = state.prefs.nodeConfig,
                    targetTxIds = missingTxIds,
                    touchedAddresses = missingAddrs,
                    walletAddrSet = walletAddrSet,
                )
            }
        }
    }

    private fun utxoFingerprint(utxos: List<UtxoItem>): String =
        utxos.map { "${it.outpointKey}:${it.amountSompi}:${it.blockDaaScore}" }.sorted().joinToString("|")

    private suspend fun pollUtxosAndTransactionsQuietly(
        forceFullTx: Boolean,
        expectedEpoch: Long = activeWalletEpoch.get(),
    ) {
        val state = _uiState.value
        val wallet = state.activeWallet ?: return
        val prefs = state.prefs
        val targetNet = prefs.nodeConfig.network
        if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return
        val allAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }.distinct()
        if (allAddrs.isEmpty()) return
        val walletAddrSet = allAddrs.toSet()

        val utxosAttempt = runCatching { network.getUtxos(prefs.nodeConfig, allAddrs) }
        val rawUtxos = utxosAttempt.getOrNull()
        if (rawUtxos == null) {
            if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return
            val err = utxosAttempt.exceptionOrNull()
            if (err is RateLimitedException) {
                _uiState.update {
                    it.copy(
                        syncStatus = SyncStatus.CONNECTING,
                        lastSyncError = KaspaErrorSanitizer.sanitize(err.message, 429),
                    )
                }
            } else if (!network.isGrpcActive(prefs.nodeConfig)) {
                _uiState.update {
                    it.copy(
                        syncStatus = SyncStatus.OFFLINE,
                        lastSyncError = KaspaErrorSanitizer.sanitize(err?.message),
                    )
                }
            }
            return
        }
        if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return

        val utxos = utxoReconciler.applyFullSnapshot(
            snapshot = rawUtxos,
            walletAddressSet = walletAddrSet,
        )
        val prevUtxos = state.utxos
        val fingerprint = utxoFingerprint(utxos)
        val changed = fingerprint != lastUtxoFingerprint

        val prevKeys = prevUtxos.mapTo(HashSet()) { it.outpointKey }
        val newKeys = utxos.mapTo(HashSet()) { it.outpointKey }
        val addedUtxos = utxos.filter { it.outpointKey !in prevKeys }
        val spentUtxos = prevUtxos.filter { it.outpointKey !in newKeys }

        val knownTxIds = state.transactions.mapTo(HashSet()) { it.transactionId }
        val optimisticTxs = if (changed && hasBaselineUtxos) {
            synthesizeDetectedTxsFromUtxoDiff(
                prevUtxos = prevUtxos,
                newUtxos = utxos,
                existingTxIds = knownTxIds,
                nowMs = System.currentTimeMillis(),
            )
        } else {
            emptyList()
        }

        if (changed) {
            lastUtxoFingerprint = fingerprint
            hasBaselineUtxos = true
            pendingTxRefreshPolls = 4
        }

        val unindexedUtxoTxIds = findUnindexedUtxoTxIds(utxos, state.transactions)
        val hasPendingOptimistic = state.transactions.any { !it.isAccepted && it.inputs.isEmpty() && it.outputs.isEmpty() }
        val shouldFetchTxs = changed || forceFullTx || pendingTxRefreshPolls > 0 || unindexedUtxoTxIds.isNotEmpty() || hasPendingOptimistic

        if (!changed && !shouldFetchTxs) return

        val balByAddr = utxos.groupBy { it.address }.mapValues { (_, list) -> list.sumOf { it.amountSompi } }
        val totalSompi = utxos.sumOf { it.amountSompi }

        if (changed) {
            // Publish balance, UTXOs, AND any instant UTXO-diff detected tx in the EXACT SAME frame!
            _uiState.update { s ->
                val balances = s.walletBalances.toMutableMap()
                balances[wallet.id] = totalSompi
                val mergedTxs = if (optimisticTxs.isNotEmpty()) {
                    mergeTransactions(s.transactions, optimisticTxs)
                } else {
                    s.transactions
                }
                s.copy(
                    syncStatus = SyncStatus.SYNCED,
                    totalBalanceSompi = totalSompi,
                    walletBalances = balances,
                    receiveAddresses = s.receiveAddresses.map { a ->
                        val b = balByAddr[a.address] ?: 0L
                        a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                    },
                    changeAddresses = s.changeAddresses.map { a ->
                        val b = balByAddr[a.address] ?: 0L
                        a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                    },
                    utxos = utxos,
                    transactions = mergedTxs,
                )
            }
            if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return
            dataRepo.updateWalletBalance(wallet.id, totalSompi)

            // Fire an immediate lightweight targeted lookup for ONLY the changed txIds / touched addresses
            // so full confirmed tx records replace optimistic entries in ~300-900ms without rate-limiting!
            val touchedAddrs = (addedUtxos.map { it.address } + spentUtxos.map { it.address })
                .filter { it in walletAddrSet }
                .distinct()
            val addedTxIds = (addedUtxos.map { it.transactionId } + unindexedUtxoTxIds)
                .filter { isValidRealTxId(it) }
                .distinct()
            launchTargetedTxSync(
                capturedEpoch = expectedEpoch,
                walletId = wallet.id,
                config = prefs.nodeConfig,
                targetTxIds = addedTxIds,
                touchedAddresses = touchedAddrs,
                walletAddrSet = walletAddrSet,
            )
            return
        }

        if (pendingTxRefreshPolls > 0) {
            pendingTxRefreshPolls--
        }

        // For periodic or follow-up polls, query only relevant/touched addresses first so we stay fast & gentle
        val extraTouchedAddrs = buildSet {
            prevUtxos.forEach { add(it.address) }
            utxos.forEach { add(it.address) }
            state.currentReceiveAddress.takeIf { it.isNotBlank() }?.let { add(it) }
            state.currentChangeAddress.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val txQueryAddrs = (selectTxQueryAddresses(state.receiveAddresses, state.changeAddresses, balByAddr) + extraTouchedAddrs)
            .filter { it in walletAddrSet }
            .distinct()

        // First try fast direct tx-by-id lookup for any unindexed UTXO txIds
        val directTxs = if (unindexedUtxoTxIds.isNotEmpty()) {
            coroutineScope {
                unindexedUtxoTxIds.take(6).map { txId ->
                    async {
                        runCatching { network.getTransactionById(prefs.nodeConfig, txId, walletAddrSet) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        } else {
            emptyList()
        }

        val fetchedList = runCatching {
            network.getTransactions(prefs.nodeConfig, txQueryAddrs, walletAddrSet, limitPerAddress = 50)
        }.getOrNull()

        if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return

        val combinedIncoming = filterTransactionsForWallet(
            txs = buildList {
                if (fetchedList != null) addAll(fetchedList)
                addAll(directTxs)
            },
            ownedAddresses = walletAddrSet,
        )
        if (combinedIncoming.isNotEmpty()) {
            var updatedList: List<WalletTx> = emptyList()
            _uiState.update { s ->
                val merged = mergeTransactions(s.transactions, combinedIncoming)
                updatedList = merged
                if (merged == s.transactions) s else s.copy(transactions = merged)
            }
            if (!isCurrentScope(expectedEpoch, wallet.id, targetNet)) return
            if (updatedList.isNotEmpty()) {
                dataRepo.saveCachedTransactions(wallet.id, targetNet, updatedList)
                val fullConfirmedIds = updatedList
                    .filter { it.isAccepted && (it.inputs.isNotEmpty() || it.outputs.isNotEmpty()) }
                    .mapTo(HashSet()) { it.transactionId }
                if (utxos.all { it.transactionId.isBlank() || it.transactionId in fullConfirmedIds }) {
                    pendingTxRefreshPolls = 0
                }
            }
        }
    }

    /**
     * Unthrottled targeted fast-fetch for newly detected incoming/outgoing transactions:
     * queries ONLY the specific new txId(s) via `/transactions/{id}` and ONLY the 1–2 touched
     * addresses with short backoff retries (`0ms`, `500ms`, `1400ms`, `3000ms`, `5000ms`) so full
     * confirmed tx records land immediately without hammering rate-limited bulk address endpoints.
     */
    private fun launchTargetedTxSync(
        capturedEpoch: Long,
        walletId: String,
        config: NodeConfig,
        targetTxIds: List<String>,
        touchedAddresses: List<String>,
        walletAddrSet: Set<String>,
    ) {
        if (walletAddrSet.isEmpty()) return
        val safeTouched = touchedAddresses.filter { it in walletAddrSet }.distinct()
        val safeTxIds = targetTxIds.filter { isValidRealTxId(it) }.distinct()
        if (safeTouched.isEmpty() && safeTxIds.isEmpty()) return

        targetedTxSyncJob?.cancel()
        targetedTxSyncJob = viewModelScope.launch {
            val delaysMs = listOf(0L, 500L, 1_400L, 3_000L, 5_000L)
            for (waitMs in delaysMs) {
                if (waitMs > 0L) delay(waitMs)
                if (!isCurrentScope(capturedEpoch, walletId, config.network)) return@launch

                val fetchedRaw = coroutineScope {
                    val byIdJobs = safeTxIds.take(6).map { txId ->
                        async {
                            runCatching { network.getTransactionById(config, txId, walletAddrSet) }.getOrNull()
                        }
                    }
                    val byAddrJob = if (safeTouched.isNotEmpty()) {
                        async {
                            runCatching {
                                network.getTransactions(config, safeTouched.take(4), walletAddrSet, limitPerAddress = 20)
                            }.getOrNull().orEmpty()
                        }
                    } else null

                    buildList {
                        addAll(byIdJobs.awaitAll().filterNotNull())
                        if (byAddrJob != null) addAll(byAddrJob.await())
                    }
                }

                if (!isCurrentScope(capturedEpoch, walletId, config.network)) return@launch

                val fetched = filterTransactionsForWallet(fetchedRaw, walletAddrSet)
                if (fetched.isNotEmpty()) {
                    var mergedSnapshot: List<WalletTx> = emptyList()
                    _uiState.update { s ->
                        val merged = mergeTransactions(s.transactions, fetched)
                        mergedSnapshot = merged
                        if (merged == s.transactions) s else s.copy(transactions = merged)
                    }
                    if (!isCurrentScope(capturedEpoch, walletId, config.network)) return@launch
                    if (mergedSnapshot.isNotEmpty()) {
                        dataRepo.saveCachedTransactions(walletId, config.network, mergedSnapshot)
                    }

                    val allTargetsResolved = safeTxIds.isNotEmpty() && safeTxIds.all { id ->
                        mergedSnapshot.any { it.transactionId == id && it.isAccepted && (it.inputs.isNotEmpty() || it.outputs.isNotEmpty()) }
                    }
                    if (allTargetsResolved) {
                        pendingTxRefreshPolls = 0
                        return@launch
                    }
                }
            }
        }
    }

    private fun selectTxQueryAddresses(
        rx: List<DerivedAddress>,
        ch: List<DerivedAddress>,
        balByAddr: Map<String, Long>,
    ): List<String> {
        val active = (rx + ch).filter { it.isUsed || (balByAddr[it.address] ?: 0L) > 0L || it.index == 0 }
        val chosen = if (active.isNotEmpty()) active else (rx.take(2) + ch.take(1))
        return chosen.map { it.address }.distinct()
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // -------------------------------------------------------------------------
    // Wallet Creation, Import (with Legacy 12-word Detection), & Watch-Only
    // -------------------------------------------------------------------------

    suspend fun createHotWallet(
        name: String,
        mnemonic: String,
        passphrase: String = "",
        derivationKind: DerivationKind = DerivationKind.STANDARD_111111,
    ): Result<WalletRecord> = runCatching {
        val cleanPhrase = Bip39.normalizePhrase(mnemonic)
        require(Bip39.validateMnemonic(cleanPhrase)) { "Invalid BIP-39 recovery phrase" }
        val walletId = UUID.randomUUID().toString()
        val (primaryAddr, kpub) = withContext(Dispatchers.Default) {
            val seed = Bip39.mnemonicToSeed(cleanPhrase, passphrase)
            try {
                val addr = KaspaCrypto.deriveAddressFromSeed(
                    seed = seed,
                    chain = 0,
                    index = 0,
                    kind = derivationKind,
                    network = KaspaNetwork.MAINNET,
                )
                val kp = if (derivationKind != DerivationKind.LEGACY_972_KDX) {
                    KaspaCrypto.deriveKpubFromSeed(seed, derivationKind, KaspaNetwork.MAINNET)
                } else {
                    null
                }
                addr to kp
            } finally {
                seed.fill(0)
            }
        }
        vault.storeWalletSecret(walletId, cleanPhrase, passphrase)
        val record = WalletRecord(
            id = walletId,
            name = name.trim().ifEmpty { "Kaspa Wallet" },
            mode = WalletMode.HOT_HD,
            derivationKind = derivationKind,
            hasPassphrase = passphrase.isNotEmpty(),
            primaryAddressMainnet = primaryAddr,
            kpubMainnet = kpub,
        )
        dataRepo.upsertWallet(record, selectAsActive = true)
        record
    }

    /**
     * Imports a 12/15/18/21/24-word seed phrase and automatically checks for Legacy 12-word
     * (`m/44'/972`) activity if the mnemonic is 12 words and standard `m/44'/111111'` has no history.
     */
    suspend fun importSeedWalletWithAutoDetect(
        name: String,
        mnemonic: String,
        passphrase: String = "",
    ): Result<Pair<WalletRecord, Boolean>> = runCatching {
        val cleanPhrase = Bip39.normalizePhrase(mnemonic)
        require(Bip39.validateMnemonic(cleanPhrase)) { "Invalid BIP-39 recovery phrase" }
        val words = cleanPhrase.split(' ')
        var chosenKind = DerivationKind.STANDARD_111111
        var detectedLegacy = false

        if (words.size == 12) {
            val cfg = _uiState.value.prefs.nodeConfig
            val probeAddrs = withContext(Dispatchers.Default) {
                val seed = Bip39.mnemonicToSeed(cleanPhrase, passphrase)
                try {
                    val std = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, cfg.network)
                    val kdx = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.LEGACY_972_KDX, cfg.network)
                    val bip972 = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.LEGACY_972_BIP44, cfg.network)
                    Triple(std, kdx, bip972)
                } finally {
                    seed.fill(0)
                }
            }
            val activeSet = runCatching {
                network.getActiveAddresses(cfg, listOf(probeAddrs.first, probeAddrs.second, probeAddrs.third))
            }.getOrDefault(emptySet())
            if (probeAddrs.first !in activeSet) {
                if (probeAddrs.second in activeSet) {
                    chosenKind = DerivationKind.LEGACY_972_KDX
                    detectedLegacy = true
                } else if (probeAddrs.third in activeSet) {
                    chosenKind = DerivationKind.LEGACY_972_BIP44
                    detectedLegacy = true
                }
            }
        }

        val record = createHotWallet(
            name = name,
            mnemonic = cleanPhrase,
            passphrase = passphrase,
            derivationKind = chosenKind,
        ).getOrThrow()
        record to detectedLegacy
    }

    suspend fun importKpubWallet(
        name: String,
        kpubInput: String,
    ): Result<WalletRecord> = runCatching {
        val normalizedKpub = withContext(Dispatchers.Default) {
            KaspaCrypto.normalizeKpubToMainnet(kpubInput.trim())
        }
        val primaryMainnet = withContext(Dispatchers.Default) {
            KaspaCrypto.deriveAddressFromKpub(normalizedKpub, chain = 0, index = 0, network = KaspaNetwork.MAINNET)
        }
        val record = WalletRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Watch-Only Vault" },
            mode = WalletMode.WATCH_KPUB,
            derivationKind = DerivationKind.STANDARD_111111,
            primaryAddressMainnet = primaryMainnet,
            kpubMainnet = normalizedKpub,
        )
        dataRepo.upsertWallet(record, selectAsActive = true)
        record
    }

    suspend fun importWatchAddressWallet(
        name: String,
        addressInput: String,
    ): Result<WalletRecord> = runCatching {
        val decoded = KaspaAddress.decode(addressInput.trim())
            ?: throw IllegalArgumentException("Invalid Kaspa address")
        val mainnetAddr = KaspaAddress.encodeAddress(KaspaNetwork.MAINNET.hrp, decoded.version, decoded.payload)
        val record = WalletRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Watch Address" },
            mode = WalletMode.WATCH_ADDRESS,
            primaryAddressMainnet = mainnetAddr,
            kpubMainnet = null,
        )
        dataRepo.upsertWallet(record, selectAsActive = true)
        record
    }

    fun selectWallet(walletId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedUtxoKeys = emptySet()) }
            dataRepo.selectWallet(walletId)
        }
    }

    fun renameWallet(walletId: String, newName: String) {
        viewModelScope.launch {
            dataRepo.renameWallet(walletId, newName)
        }
    }

    fun deleteWallet(walletId: String) {
        viewModelScope.launch {
            vault.deleteWalletSecret(walletId)
            covenantRepo.deleteVault(walletId)
            dataRepo.deleteWallet(walletId)
        }
    }

    // -------------------------------------------------------------------------
    // Derivation & Sync Engine (Never Stalls on Syncing)
    // -------------------------------------------------------------------------

    private suspend fun deriveAddressesForWallet(
        wallet: WalletRecord,
        net: KaspaNetwork,
    ): Pair<List<DerivedAddress>, List<DerivedAddress>> = withContext(Dispatchers.Default) {
        if (wallet.mode == WalletMode.WATCH_ADDRESS) {
            val addr = KaspaAddress.convertNetworkPrefix(wallet.primaryAddressMainnet, net)
            return@withContext listOf(
                DerivedAddress(address = addr, chain = 0, index = 0, derivationPath = "watch-only"),
            ) to emptyList()
        }

        val receiveCount = maxOf(wallet.receiveIndex + 1, wallet.highestUsedReceiveIndex + 2, 1).coerceAtMost(120)
        val changeCount = maxOf(wallet.changeIndex + 1, wallet.highestUsedChangeIndex + 2, 1).coerceAtMost(120)

        val kpub = wallet.kpubMainnet
        if (kpub != null && wallet.derivationKind != DerivationKind.LEGACY_972_KDX) {
            val account = KaspaCrypto.HdNode.fromExtendedPublicKey(kpub)
            val rxChain = account.deriveChild(0)
            val chChain = account.deriveChild(1)
            val rx = (0 until receiveCount).map { idx ->
                val leaf = rxChain.deriveChild(idx.toLong())
                DerivedAddress(
                    address = KaspaAddress.encodePubKey(leaf.xOnlyPublicKey, net),
                    chain = 0,
                    index = idx,
                    derivationPath = KaspaCrypto.addressPath(wallet.derivationKind, 0, idx),
                    isUsed = idx <= wallet.highestUsedReceiveIndex,
                )
            }
            val ch = (0 until changeCount).map { idx ->
                val leaf = chChain.deriveChild(idx.toLong())
                DerivedAddress(
                    address = KaspaAddress.encodePubKey(leaf.xOnlyPublicKey, net),
                    chain = 1,
                    index = idx,
                    derivationPath = KaspaCrypto.addressPath(wallet.derivationKind, 1, idx),
                    isUsed = idx <= wallet.highestUsedChangeIndex,
                )
            }
            return@withContext rx to ch
        }

        // Legacy KDX or hot wallet without cached kpub
        if (wallet.mode == WalletMode.HOT_HD && vault.hasWalletSecret(wallet.id)) {
            return@withContext vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                try {
                    val rx = (0 until receiveCount).map { idx ->
                        DerivedAddress(
                            address = KaspaCrypto.deriveAddressFromSeed(seed, 0, idx, wallet.derivationKind, net),
                            chain = 0,
                            index = idx,
                            derivationPath = KaspaCrypto.addressPath(wallet.derivationKind, 0, idx),
                            isUsed = idx <= wallet.highestUsedReceiveIndex,
                        )
                    }
                    val ch = (0 until changeCount).map { idx ->
                        DerivedAddress(
                            address = KaspaCrypto.deriveAddressFromSeed(seed, 1, idx, wallet.derivationKind, net),
                            chain = 1,
                            index = idx,
                            derivationPath = KaspaCrypto.addressPath(wallet.derivationKind, 1, idx),
                            isUsed = idx <= wallet.highestUsedChangeIndex,
                        )
                    }
                    rx to ch
                } finally {
                    seed.fill(0)
                }
            }
        }

        val fallbackAddr = KaspaAddress.convertNetworkPrefix(wallet.primaryAddressMainnet, net)
        listOf(DerivedAddress(fallbackAddr, 0, 0, "m/44'/111111'/0'/0/0")) to emptyList()
    }

    fun refreshWallet() {
        val prefs = _uiState.value.prefs
        val active = prefs.activeWallet ?: return
        // User-initiated Refresh ALWAYS clears any lingering REST 429 cooldown from prior rapid switches.
        network.clearRestBackoffOnUserRefresh()
        pendingTxRefreshPolls = 4
        rebuildAddressesAndRefresh(
            wallet = active,
            prefs = prefs,
            resetForNewScope = false,
            capturedEpoch = activeWalletEpoch.get(),
        )
    }

    private fun rebuildAddressesAndRefresh(
        wallet: WalletRecord,
        prefs: WalletPreferencesState,
        resetForNewScope: Boolean = false,
        capturedEpoch: Long = activeWalletEpoch.get(),
    ) {
        val targetNet = prefs.nodeConfig.network
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
            // Immediately hydrate persisted transactions for this wallet so switching wallets
            // or cold-launching shows Recent Transactions instantly and never flashes empty!
            val cachedTxs = dataRepo.getCachedTransactions(wallet.id, targetNet)
            if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
            _uiState.update { s ->
                val baseTxs = if (resetForNewScope) cachedTxs else mergeTransactions(s.transactions, cachedTxs)
                s.copy(
                    isRefreshing = true,
                    syncStatus = SyncStatus.CONNECTING,
                    lastSyncError = null,
                    transactions = baseTxs,
                    utxos = if (resetForNewScope) emptyList() else s.utxos,
                )
            }
            try {
                val (rx, ch) = deriveAddressesForWallet(wallet, targetNet)
                if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch

                val allAddrs = (rx + ch).map { it.address }.distinct()
                val walletAddrSet = allAddrs.toSet()

                // Self-healing anti-ghost scrub: purge any cross-wallet or 0.00 KAS ghost rows
                // that may have been persisted into this wallet's cache before this fix.
                val cleanedCached = filterTransactionsForWallet(cachedTxs, walletAddrSet)
                if (cleanedCached.size != cachedTxs.size) {
                    dataRepo.saveCachedTransactions(
                        walletId = wallet.id,
                        network = targetNet,
                        txs = cleanedCached,
                        allowEmptyOverwrite = true,
                    )
                }
                _uiState.update { s ->
                    val cleanedCurrent = if (resetForNewScope) {
                        cleanedCached
                    } else {
                        filterTransactionsForWallet(mergeTransactions(s.transactions, cleanedCached), walletAddrSet)
                    }
                    s.copy(
                        receiveAddresses = rx,
                        changeAddresses = ch,
                        transactions = cleanedCurrent,
                    )
                }

                // Immediately register all active receive + change addresses on the live gRPC stream
                // so UTXO push notifications fire for every address (including change / return addresses).
                wrpcHandle?.setWatchedAddresses(allAddrs, forceResubscribe = false)

                // Fire health, UTXOs, fee estimate, and spot price concurrently for snappy startup!
                var utxoError: Throwable? = null
                val (health, utxosResult, feeEst, spot) = coroutineScope {
                    val hDef = async { network.checkNodeHealth(prefs.nodeConfig) }
                    val uDef = async {
                        runCatching { network.getUtxos(prefs.nodeConfig, allAddrs) }
                            .onFailure { utxoError = it }
                            .getOrNull()
                    }
                    val fDef = async { runCatching { network.getFeeEstimate(prefs.nodeConfig) }.getOrDefault(FeeEstimate()) }
                    val sDef = async {
                        runCatching {
                            network.getSpotPrice(
                                currency = prefs.fiatCurrency,
                                privacyKasOnly = prefs.kasOnlyPrivacyMode,
                                isTestnet = targetNet.isTestnet,
                            )
                        }.getOrNull()
                    }
                    Quad(hDef.await(), uDef.await(), fDef.await(), sDef.await())
                }

                if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch

                if (!health.reachable) {
                    _liveNodeHealth.value = health
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            syncStatus = SyncStatus.OFFLINE,
                            nodeHealth = health,
                            lastSyncError = health.errorMessage?.let { msg -> KaspaErrorSanitizer.sanitize(msg) },
                        )
                    }
                    return@launch
                }

                val utxoFetchOk = utxosResult != null
                val utxos = if (utxosResult != null) {
                    utxoReconciler.applyFullSnapshot(utxosResult, walletAddrSet)
                } else {
                    _uiState.value.utxos
                }
                val balByAddr = utxos.groupBy { it.address }.mapValues { (_, list) -> list.sumOf { it.amountSompi } }
                val updatedRx = rx.map { a -> a.copy(balanceSompi = balByAddr[a.address] ?: 0L) }
                val updatedCh = ch.map { a -> a.copy(balanceSompi = balByAddr[a.address] ?: 0L) }
                val totalSompi = if (utxoFetchOk) utxos.sumOf { it.amountSompi } else _uiState.value.totalBalanceSompi
                if (utxoFetchOk) {
                    lastUtxoFingerprint = utxoFingerprint(utxos)
                    hasBaselineUtxos = true
                }
                _liveNodeHealth.value = health
                if (health.transportUsed.contains("REST", ignoreCase = true) && !network.isGrpcActive(prefs.nodeConfig)) {
                    triggerRestFallbackWarningOnce()
                }

                val computedSyncStatus = when {
                    !utxoFetchOk && utxoError is RateLimitedException -> SyncStatus.CONNECTING
                    !utxoFetchOk -> SyncStatus.OFFLINE
                    health.isSynced -> SyncStatus.SYNCED
                    else -> SyncStatus.CONNECTING
                }
                val computedSyncError = if (!utxoFetchOk) {
                    KaspaErrorSanitizer.sanitize(utxoError?.message ?: "UTXO fetch failed")
                } else {
                    null
                }

                // Immediately show balance & UTXOs while transactions load in parallel.
                // Never claim green Synced or overwrite cached balance when UTXO fetch failed/rate-limited!
                _uiState.update { state ->
                    val balances = state.walletBalances.toMutableMap()
                    if (utxoFetchOk) {
                        balances[wallet.id] = totalSompi
                    }
                    state.copy(
                        syncStatus = computedSyncStatus,
                        nodeHealth = health,
                        lastSyncError = computedSyncError,
                        totalBalanceSompi = totalSompi,
                        walletBalances = balances,
                        receiveAddresses = updatedRx,
                        changeAddresses = updatedCh,
                        utxos = utxos,
                        feeEstimate = feeEst,
                        spotPrice = spot,
                    )
                }
                if (utxoFetchOk) {
                    dataRepo.updateWalletBalance(wallet.id, totalSompi)
                }

                // Always include active receive/change tip addresses and every address currently holding a UTXO.
                val extraQueryAddrs = buildSet {
                    utxos.forEach { add(it.address) }
                    updatedRx.getOrNull(wallet.receiveIndex)?.address?.let { add(it) }
                    updatedCh.getOrNull(wallet.changeIndex)?.address?.let { add(it) }
                }
                val txQueryAddrs = (selectTxQueryAddresses(updatedRx, updatedCh, balByAddr) + extraQueryAddrs)
                    .filter { it in walletAddrSet }
                    .distinct()
                val unindexedUtxoTxIds = findUnindexedUtxoTxIds(utxos, _uiState.value.transactions)

                var txFetchError: Throwable? = null
                val (bulkTxs, directUtxoTxs) = coroutineScope {
                    val bulkDef = async {
                        runCatching {
                            network.getTransactions(prefs.nodeConfig, txQueryAddrs, walletAddrSet, limitPerAddress = 50)
                        }.onFailure { txFetchError = it }.getOrNull()
                    }
                    val directDef = async {
                        if (unindexedUtxoTxIds.isNotEmpty()) {
                            unindexedUtxoTxIds.take(8).map { txId ->
                                async {
                                    runCatching {
                                        network.getTransactionById(prefs.nodeConfig, txId, walletAddrSet)
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        } else {
                            emptyList()
                        }
                    }
                    bulkDef.await() to directDef.await()
                }

                if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch

                val validFetched = filterTransactionsForWallet(
                    txs = buildList {
                        if (bulkTxs != null) addAll(bulkTxs)
                        addAll(directUtxoTxs)
                    },
                    ownedAddresses = walletAddrSet,
                )

                var mergedFinal: List<WalletTx> = emptyList()
                _uiState.update { s ->
                    val baseSafe = filterTransactionsForWallet(s.transactions, walletAddrSet)
                    val mergedNetwork = if (validFetched.isNotEmpty()) {
                        mergeTransactions(baseSafe, validFetched)
                    } else {
                        mergeTransactions(baseSafe, emptyList())
                    }
                    // If a live gRPC UTXO (e.g. freshly received 50 KAS right before switching wallets)
                    // has not been returned by REST yet, synthesize an instant incoming tx row so
                    // Recent Transactions NEVER freezes behind Total Balance!
                    val existingIds = mergedNetwork.mapTo(HashSet()) { it.transactionId }
                    val missingUtxoOptimistic = if (utxoFetchOk && utxos.isNotEmpty()) {
                        synthesizeMissingUtxoTxs(
                            utxos = utxos,
                            existingTxIds = existingIds,
                            nowMs = System.currentTimeMillis(),
                        )
                    } else {
                        emptyList()
                    }
                    val merged = if (missingUtxoOptimistic.isNotEmpty()) {
                        mergeTransactions(mergedNetwork, missingUtxoOptimistic)
                    } else {
                        mergedNetwork
                    }
                    mergedFinal = merged
                    val nextError = s.lastSyncError ?: if (txFetchError is RateLimitedException && validFetched.isEmpty()) {
                        KaspaErrorSanitizer.sanitize(txFetchError?.message)
                    } else {
                        null
                    }
                    s.copy(
                        isRefreshing = false,
                        transactions = merged,
                        lastSyncError = nextError,
                    )
                }
                if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
                if (mergedFinal.isNotEmpty()) {
                    dataRepo.saveCachedTransactions(wallet.id, targetNet, mergedFinal)
                }

                // If any live UTXO txId is still missing full confirmed details (e.g. REST indexer
                // lagged by 1-3 seconds right after sending from Wallet A -> Wallet B), immediately
                // run targeted fast-retry sync so confirmed details replace the optimistic row cleanly!
                val stillMissingIds = findUnindexedUtxoTxIds(utxos, mergedFinal)
                if (utxoFetchOk && stillMissingIds.isNotEmpty()) {
                    val missingAddrs = utxos
                        .filter { it.transactionId in stillMissingIds }
                        .map { it.address }
                        .filter { it in walletAddrSet }
                        .distinct()
                    launchTargetedTxSync(
                        capturedEpoch = capturedEpoch,
                        walletId = wallet.id,
                        config = prefs.nodeConfig,
                        targetTxIds = stillMissingIds,
                        touchedAddresses = missingAddrs,
                        walletAddrSet = walletAddrSet,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (isCurrentScope(capturedEpoch, wallet.id, targetNet)) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            syncStatus = SyncStatus.OFFLINE,
                            lastSyncError = KaspaErrorSanitizer.sanitize(e.message ?: "Sync error"),
                        )
                    }
                }
            }
        }
    }

    /**
     * Automatically discovers active HD receive (`0/i`) and change (`1/i`) addresses in the
     * background on launch, import, and wallet switch so users always see their true full balance
     * without ever having to tap manual Address Discovery.
     */
    private fun triggerBackgroundAddressDiscovery(
        wallet: WalletRecord,
        prefs: WalletPreferencesState,
        capturedEpoch: Long = activeWalletEpoch.get(),
    ) {
        if (wallet.mode == WalletMode.WATCH_ADDRESS) return
        val targetNet = prefs.nodeConfig.network
        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = viewModelScope.launch {
            try {
                suspend fun scanFrom(chain: Int, startIndex: Int, initialHighest: Int): Int {
                    var start = startIndex.coerceAtLeast(0)
                    var highestUsed = initialHighest
                    while (start < 200) {
                        if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return highestUsed
                        val batchAddrs = deriveBatch(wallet, targetNet, chain, start, GAP_LIMIT_DEFAULT)
                        val activeSet = network.getActiveAddresses(prefs.nodeConfig, batchAddrs)
                        var hitInBatch = false
                        batchAddrs.forEachIndexed { offset, addr ->
                            if (addr in activeSet) {
                                hitInBatch = true
                                highestUsed = maxOf(highestUsed, start + offset)
                            }
                        }
                        if (!hitInBatch) break
                        start += GAP_LIMIT_DEFAULT
                    }
                    return highestUsed
                }

                // Always scan from 0 on first scan; on subsequent launches scan beyond highest used
                val startRx = if (!wallet.hasCompletedInitialScan) 0 else (wallet.highestUsedReceiveIndex + 1).coerceAtLeast(0)
                val startCh = if (!wallet.hasCompletedInitialScan) 0 else (wallet.highestUsedChangeIndex + 1).coerceAtLeast(0)

                val (foundRx, foundCh) = coroutineScope {
                    val rxDef = async { scanFrom(0, startRx, wallet.highestUsedReceiveIndex) }
                    val chDef = async { scanFrom(1, startCh, wallet.highestUsedChangeIndex) }
                    rxDef.await() to chDef.await()
                }

                if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch

                val nextRxIdx = maxOf(wallet.receiveIndex, foundRx + 1)
                val nextChIdx = maxOf(wallet.changeIndex, foundCh + 1)
                val discoveredNew = foundRx > wallet.highestUsedReceiveIndex ||
                    foundCh > wallet.highestUsedChangeIndex ||
                    !wallet.hasCompletedInitialScan

                if (discoveredNew) {
                    val updatedWallet = wallet.copy(
                        highestUsedReceiveIndex = maxOf(wallet.highestUsedReceiveIndex, foundRx),
                        highestUsedChangeIndex = maxOf(wallet.highestUsedChangeIndex, foundCh),
                        receiveIndex = nextRxIdx,
                        changeIndex = nextChIdx,
                        hasCompletedInitialScan = true,
                    )
                    val (rx, ch) = deriveAddressesForWallet(updatedWallet, targetNet)
                    if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
                    val allAddrs = (rx + ch).map { it.address }.distinct()
                    val walletAddrSet = allAddrs.toSet()
                    wrpcHandle?.setWatchedAddresses(allAddrs, forceResubscribe = false)
                    val rawUtxos = runCatching { network.getUtxos(prefs.nodeConfig, allAddrs) }.getOrNull()
                    if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
                    val utxos = if (rawUtxos != null) {
                        utxoReconciler.applyFullSnapshot(rawUtxos, walletAddrSet)
                    } else {
                        null
                    }
                    val totalSompi = utxos?.sumOf { it.amountSompi }
                    dataRepo.updateWalletDiscovery(
                        walletId = wallet.id,
                        highestUsedReceive = updatedWallet.highestUsedReceiveIndex,
                        highestUsedChange = updatedWallet.highestUsedChangeIndex,
                        receiveIndex = updatedWallet.receiveIndex,
                        changeIndex = updatedWallet.changeIndex,
                        cachedBalanceSompi = totalSompi,
                        markScanned = true,
                    )
                    if (isCurrentScope(capturedEpoch, wallet.id, targetNet) && utxos != null && totalSompi != null) {
                        val balByAddr = utxos.groupBy { it.address }.mapValues { (_, list) -> list.sumOf { it.amountSompi } }
                        val updatedRx = rx.map { a -> a.copy(balanceSompi = balByAddr[a.address] ?: 0L) }
                        val updatedCh = ch.map { a -> a.copy(balanceSompi = balByAddr[a.address] ?: 0L) }
                        lastUtxoFingerprint = utxoFingerprint(utxos)
                        hasBaselineUtxos = true
                        val txQueryAddrs = (selectTxQueryAddresses(updatedRx, updatedCh, balByAddr) + utxos.map { it.address })
                            .filter { it in walletAddrSet }
                            .distinct()
                        val rawTxs = runCatching {
                            network.getTransactions(prefs.nodeConfig, txQueryAddrs, walletAddrSet, limitPerAddress = 50)
                        }.getOrNull()
                        if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
                        val validTxs = filterTransactionsForWallet(rawTxs.orEmpty(), walletAddrSet)
                        var mergedFinal: List<WalletTx> = emptyList()
                        _uiState.update { s ->
                            val balances = s.walletBalances.toMutableMap()
                            balances[wallet.id] = totalSompi
                            val baseSafe = filterTransactionsForWallet(s.transactions, walletAddrSet)
                            val merged = if (validTxs.isNotEmpty()) {
                                mergeTransactions(baseSafe, validTxs)
                            } else {
                                mergeTransactions(baseSafe, emptyList())
                            }
                            mergedFinal = merged
                            s.copy(
                                totalBalanceSompi = totalSompi,
                                walletBalances = balances,
                                receiveAddresses = updatedRx,
                                changeAddresses = updatedCh,
                                utxos = utxos,
                                transactions = merged,
                            )
                        }
                        if (!isCurrentScope(capturedEpoch, wallet.id, targetNet)) return@launch
                        if (mergedFinal.isNotEmpty()) {
                            dataRepo.saveCachedTransactions(wallet.id, targetNet, mergedFinal)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal cancellation when switching wallets
            } catch (_: Exception) {
                // Non-fatal background scan failure
            }
        }
    }

    /**
     * Quietly refreshes persisted balances for non-active wallets so the Side Drawer switcher
     * always reflects accurate balances for every wallet.
     */
    private fun refreshInactiveWalletBalancesQuietly(prefs: WalletPreferencesState) {
        val activeId = prefs.activeWallet?.id
        val others = prefs.wallets.filter { it.id != activeId }
        if (others.isEmpty()) return
        inactiveBalancesJob?.cancel()
        inactiveBalancesJob = viewModelScope.launch {
            for (other in others) {
                runCatching {
                    val (rx, ch) = deriveAddressesForWallet(other, prefs.nodeConfig.network)
                    val addrs = (rx + ch).map { it.address }.distinct()
                    if (addrs.isEmpty()) return@runCatching
                    val utxos = network.getUtxos(prefs.nodeConfig, addrs)
                    val total = utxos.sumOf { it.amountSompi }
                    dataRepo.updateWalletBalance(other.id, total)
                    _uiState.update { s ->
                        val next = s.walletBalances.toMutableMap()
                        next[other.id] = total
                        s.copy(walletBalances = next)
                    }
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun refreshSpotPriceOnly(prefs: WalletPreferencesState) {
        viewModelScope.launch {
            val spot = runCatching {
                network.getSpotPrice(
                    currency = prefs.fiatCurrency,
                    privacyKasOnly = prefs.kasOnlyPrivacyMode,
                    isTestnet = prefs.nodeConfig.network.isTestnet,
                )
            }.getOrNull()
            _uiState.update { it.copy(spotPrice = spot) }
        }
    }

    suspend fun testNodeConnection(config: NodeConfig): NodeHealth =
        network.checkNodeHealth(config)

    // -------------------------------------------------------------------------
    // Address Rotation & Kaspium 20-Gap-Limit HD Address Discovery
    // -------------------------------------------------------------------------

    fun generateNewReceiveAddress() {
        val wallet = _uiState.value.activeWallet ?: return
        if (wallet.mode == WalletMode.WATCH_ADDRESS) return
        viewModelScope.launch {
            val updated = wallet.copy(receiveIndex = wallet.receiveIndex + 1)
            dataRepo.upsertWallet(updated, selectAsActive = true)
            rebuildAddressesAndRefresh(updated, _uiState.value.prefs)
        }
    }

    fun runAddressDiscovery() {
        val wallet = _uiState.value.activeWallet ?: return
        if (wallet.mode == WalletMode.WATCH_ADDRESS) return
        val prefs = _uiState.value.prefs
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, discoveryStatusText = "0") }
            try {
                var highestRx = wallet.highestUsedReceiveIndex
                var highestCh = wallet.highestUsedChangeIndex
                var foundCount = 0

                suspend fun scanChain(chain: Int): Int {
                    var start = 0
                    var highestUsed = if (chain == 0) highestRx else highestCh
                    while (start < 200) {
                        val batchAddrs = deriveBatch(wallet, prefs.nodeConfig.network, chain, start, GAP_LIMIT_DEFAULT)
                        val activeSet = network.getActiveAddresses(prefs.nodeConfig, batchAddrs)
                        var hitInBatch = false
                        batchAddrs.forEachIndexed { offset, addr ->
                            if (addr in activeSet) {
                                hitInBatch = true
                                foundCount++
                                highestUsed = maxOf(highestUsed, start + offset)
                            }
                        }
                        _uiState.update { it.copy(discoveryStatusText = "$foundCount") }
                        if (!hitInBatch) break
                        start += GAP_LIMIT_DEFAULT
                    }
                    return highestUsed
                }

                highestRx = scanChain(0)
                highestCh = scanChain(1)

                val updated = wallet.copy(
                    highestUsedReceiveIndex = highestRx,
                    highestUsedChangeIndex = highestCh,
                    receiveIndex = maxOf(wallet.receiveIndex, highestRx + 1),
                    changeIndex = maxOf(wallet.changeIndex, highestCh + 1),
                )
                dataRepo.upsertWallet(updated, selectAsActive = true)
                _uiState.update { it.copy(isDiscovering = false, discoveryStatusText = "$foundCount") }
                rebuildAddressesAndRefresh(updated, prefs)
            } catch (e: Exception) {
                _uiState.update { it.copy(isDiscovering = false, discoveryStatusText = null) }
                showMessage(e.message ?: "Discovery failed")
            }
        }
    }

    private suspend fun deriveBatch(
        wallet: WalletRecord,
        net: KaspaNetwork,
        chain: Int,
        start: Int,
        count: Int,
    ): List<String> = withContext(Dispatchers.Default) {
        val kpub = wallet.kpubMainnet
        if (kpub != null && wallet.derivationKind != DerivationKind.LEGACY_972_KDX) {
            val chainNode = KaspaCrypto.HdNode.fromExtendedPublicKey(kpub).deriveChild(chain.toLong())
            return@withContext (start until start + count).map { idx ->
                KaspaAddress.encodePubKey(chainNode.deriveChild(idx.toLong()).xOnlyPublicKey, net)
            }
        }
        vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
            val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
            try {
                (start until start + count).map { idx ->
                    KaspaCrypto.deriveAddressFromSeed(seed, chain, idx, wallet.derivationKind, net)
                }
            } finally {
                seed.fill(0)
            }
        }
    }

    // -------------------------------------------------------------------------
    // UTXO Selection, Standard Send, Compound, PSKT, Burn, & Miner Donations
    // -------------------------------------------------------------------------

    fun toggleUtxoSelection(outpointKey: String) {
        _uiState.update { state ->
            val next = state.selectedUtxoKeys.toMutableSet()
            if (!next.add(outpointKey)) next.remove(outpointKey)
            state.copy(selectedUtxoKeys = next)
        }
    }

    fun clearUtxoSelection() {
        _uiState.update { it.copy(selectedUtxoKeys = emptySet()) }
    }

    fun prepareSendTransaction(
        recipientAddress: String,
        amountSompi: Long,
        feePriority: FeePriority,
        sendAll: Boolean = false,
        useSelectedUtxos: Boolean = false,
        isBurn: Boolean = false,
    ): Result<PreparedTx> = runCatching {
        val state = _uiState.value
        val feeRate = state.feeEstimate.rateFor(feePriority)
        val customPool = if (useSelectedUtxos && state.selectedUtxos.isNotEmpty()) state.selectedUtxos else null
        KaspaMassAndTx.prepareSend(
            network = state.activeNetwork,
            availableUtxos = state.utxos,
            recipientAddress = recipientAddress.trim(),
            changeAddress = state.currentChangeAddress,
            amountSompi = amountSompi,
            feeRate = feeRate,
            sendAll = sendAll,
            customSelectedUtxos = customPool,
            isBurn = isBurn,
        )
    }

    fun prepareCompoundTransaction(
        destinationAddress: String,
        feePriority: FeePriority,
        useSelectedOnly: Boolean,
        customFeeSompi: Long? = null,
    ): Result<PreparedTx> = runCatching {
        val state = _uiState.value
        val pool = if (useSelectedOnly && state.selectedUtxos.size >= 2) {
            state.selectedUtxos
        } else {
            state.utxos.filter { !it.isCoinbase }
        }
        KaspaMassAndTx.prepareCompound(
            network = state.activeNetwork,
            utxosToCompound = pool,
            destinationAddress = destinationAddress,
            feeRate = state.feeEstimate.rateFor(feePriority),
            overrideFeeSompi = customFeeSompi,
        )
    }

    fun exportUnsignedPskt(prepared: PreparedTx): String =
        KaspaMassAndTx.exportUnsignedPsktJson(prepared, _uiState.value.activeNetwork)

    suspend fun signAndBroadcast(prepared: PreparedTx): Result<String> = runCatching {
        val state = _uiState.value
        val wallet = state.activeWallet ?: error("No active wallet")
        require(!wallet.isWatchOnly) { "Watch-Only wallets cannot sign transactions" }
        val addrIndexMap = (state.receiveAddresses + state.changeAddresses).associateBy { it.address }

        val signed = vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
            val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
            try {
                KaspaMassAndTx.signPreparedTransaction(prepared) { inputAddr ->
                    val derived = addrIndexMap[inputAddr]
                    if (derived != null) {
                        KaspaCrypto.derivePrivateKeyFromSeed(seed, derived.chain, derived.index, wallet.derivationKind)
                    } else {
                        KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, wallet.derivationKind)
                    }
                }
            } finally {
                seed.fill(0)
            }
        }
        val broadcastTxId = network.submitTransaction(state.prefs.nodeConfig, signed.submitJson)
        val finalTxId = broadcastTxId.ifBlank { signed.transactionId }
        clearUtxoSelection()

        // Immediately record an optimistic outgoing tx in Recent Transactions at millisecond 0!
        val optimisticDirection = when {
            prepared.isBurn -> TxDirection.BURN
            prepared.isMinerDonation -> TxDirection.MINER_DONATION
            prepared.isCompound -> TxDirection.COMPOUND
            else -> TxDirection.SENT
        }
        val optimisticOut = WalletTx(
            transactionId = finalTxId,
            blockTimeMs = System.currentTimeMillis(),
            isAccepted = false,
            direction = optimisticDirection,
            netAmountSompi = if (prepared.isMinerDonation) prepared.feeSompi else prepared.amountSompi,
            feeSompi = prepared.feeSompi,
            mass = signed.mass,
            inputs = prepared.selectedUtxos.map { u ->
                com.kaspakinetic.wallet.core.model.TxInputSummary(
                    previousOutpointHash = u.transactionId,
                    previousOutpointIndex = u.index,
                    address = u.address,
                    amountSompi = u.amountSompi,
                )
            },
            outputs = signed.outputs,
            counterpartyAddress = prepared.recipientAddress,
        )
        val allWalletAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }.toMutableSet()
        if (prepared.changeAddress.isNotBlank()) {
            allWalletAddrs.add(prepared.changeAddress)
        }
        wrpcHandle?.setWatchedAddresses(allWalletAddrs.toList(), forceResubscribe = false)

        // Immediately record spent input outpoints & any returning change outputs in UtxoReconciler
        // so spent inputs are hidden immediately and Total Balance NEVER double-counts!
        val spentKeys = prepared.selectedUtxos.map { it.outpointKey }
        val optimisticChangeUtxos = signed.outputs
            .filter { it.address in allWalletAddrs && it.amountSompi > 0L }
            .map { out ->
                UtxoItem(
                    address = out.address,
                    transactionId = finalTxId,
                    index = out.index,
                    amountSompi = out.amountSompi,
                    scriptPublicKeyHex = out.scriptPublicKeyHex,
                    scriptPublicKeyVersion = 0,
                    blockDaaScore = 0L,
                    isCoinbase = false,
                )
            }
        val reconciledUtxos = utxoReconciler.recordLocalBroadcast(
            spentOutpoints = spentKeys,
            optimisticChangeOutputs = optimisticChangeUtxos,
        )
        val balByAddr = reconciledUtxos.groupBy { it.address }.mapValues { (_, l) -> l.sumOf { it.amountSompi } }
        val totalSompi = reconciledUtxos.sumOf { it.amountSompi }
        lastUtxoFingerprint = utxoFingerprint(reconciledUtxos)
        hasBaselineUtxos = true

        _uiState.update { s ->
            val balances = s.walletBalances.toMutableMap()
            balances[wallet.id] = totalSompi
            s.copy(
                totalBalanceSompi = totalSompi,
                walletBalances = balances,
                receiveAddresses = s.receiveAddresses.map { a ->
                    val b = balByAddr[a.address] ?: 0L
                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                },
                changeAddresses = s.changeAddresses.map { a ->
                    val b = balByAddr[a.address] ?: 0L
                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                },
                utxos = reconciledUtxos,
                transactions = mergeTransactions(s.transactions, listOf(optimisticOut)),
            )
        }
        dataRepo.updateWalletBalance(wallet.id, totalSompi)
        dataRepo.saveCachedTransactions(wallet.id, state.activeNetwork, _uiState.value.transactions)

        val sendEpoch = activeWalletEpoch.get()
        val sendNet = state.activeNetwork
        // Immediately poll UTXOs and target-fetch this exact txId for fast confirmation upgrade
        launchTargetedTxSync(
            capturedEpoch = sendEpoch,
            walletId = wallet.id,
            config = state.prefs.nodeConfig,
            targetTxIds = listOf(finalTxId),
            touchedAddresses = (prepared.selectedUtxos.map { it.address } + prepared.changeAddress).distinct(),
            walletAddrSet = allWalletAddrs,
        )
        postSendPollJob?.cancel()
        postSendPollJob = viewModelScope.launch {
            delay(300L)
            if (!isCurrentScope(sendEpoch, wallet.id, sendNet)) return@launch
            pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = sendEpoch)
            delay(1_200L)
            if (!isCurrentScope(sendEpoch, wallet.id, sendNet)) return@launch
            pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = sendEpoch)
        }
        finalTxId
    }

    // -------------------------------------------------------------------------
    // Fun Stuff #2: Donate to Kaspa Miners (Sequential Chained Fee-Donation Engine)
    // -------------------------------------------------------------------------

    fun startMinerDonation(
        totalDonationSompi: Long,
        splits: Int,
        runInBackground: Boolean = false,
        notificationTitle: String = "Donate to Kaspa Miners",
        notificationStopLabel: String = "Stop",
    ) {
        val state = _uiState.value
        val wallet = state.activeWallet ?: return
        if (wallet.isWatchOnly) {
            showMessage("Watch-Only wallet cannot sign miner donations")
            return
        }
        val safeSplits = splits.coerceIn(1, MAX_MINER_DONATION_SPLITS)
        val feePerSplit = totalDonationSompi / safeSplits
        if (feePerSplit < 1_000L) {
            showMessage("Donation per split must be at least 0.00001 KAS")
            return
        }
        val spendable = state.utxos.filter { !it.isCoinbase }
        val totalAvail = spendable.sumOf { it.amountSompi }
        if (totalAvail - totalDonationSompi < DUST_LIMIT_SOMPI) {
            showMessage("Insufficient balance to keep change above dust limit")
            return
        }

        val selfReturnAddress = state.currentReceiveAddress
        val allDerived = state.receiveAddresses + state.changeAddresses
        // Immediately register all active addresses including the return address on the live gRPC push stream
        // so returning change UTXOs appear instantaneously without lingering at 0.00 KAS!
        viewModelScope.launch {
            val watchList = (allDerived.map { it.address } + selfReturnAddress).filter { it.isNotBlank() }.distinct()
            wrpcHandle?.setWatchedAddresses(watchList, forceResubscribe = false)
        }

        val donationEpoch = activeWalletEpoch.get()
        val donationNet = state.activeNetwork
        MinerDonationController.start(
            context = getApplication(),
            request = MinerDonationRequest(
                wallet = wallet,
                nodeConfig = state.prefs.nodeConfig,
                selfAddress = selfReturnAddress,
                derivedAddresses = allDerived,
                spendableUtxos = spendable,
                totalDonationSompi = totalDonationSompi,
                splits = safeSplits,
                minFeeRate = state.feeEstimate.lowRate,
                runInBackground = runInBackground,
                notificationTitle = notificationTitle,
                notificationStopLabel = notificationStopLabel,
                networkGateway = network,
                onSplitCompleted = { txId, feeSompi, spentOutpoints, nextChangeUtxo ->
                    viewModelScope.launch {
                        if (!isCurrentScope(donationEpoch, wallet.id, donationNet)) return@launch
                        // Atomically record spent input outpoints + optimistic change UTXO so
                        // confirmed spent inputs NEVER coexist with Block DAA: 0 pending change!
                        val reconciledUtxos = utxoReconciler.recordLocalBroadcast(
                            spentOutpoints = spentOutpoints,
                            optimisticChangeOutputs = listOf(nextChangeUtxo),
                        )
                        val balByAddr = reconciledUtxos.groupBy { it.address }.mapValues { (_, l) -> l.sumOf { it.amountSompi } }
                        val totalSompi = reconciledUtxos.sumOf { it.amountSompi }
                        lastUtxoFingerprint = utxoFingerprint(reconciledUtxos)
                        hasBaselineUtxos = true
                        val donationTx = if (isValidRealTxId(txId)) {
                            listOf(
                                WalletTx(
                                    transactionId = txId,
                                    blockTimeMs = System.currentTimeMillis(),
                                    isAccepted = false,
                                    direction = TxDirection.MINER_DONATION,
                                    netAmountSompi = feeSompi,
                                    feeSompi = feeSompi,
                                    counterpartyAddress = selfReturnAddress,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                        var mergedTxs: List<WalletTx> = emptyList()
                        _uiState.update { s ->
                            val balances = s.walletBalances.toMutableMap()
                            balances[wallet.id] = totalSompi
                            mergedTxs = mergeTransactions(s.transactions, donationTx)
                            s.copy(
                                totalBalanceSompi = totalSompi,
                                walletBalances = balances,
                                receiveAddresses = s.receiveAddresses.map { a ->
                                    val b = balByAddr[a.address] ?: 0L
                                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                                },
                                changeAddresses = s.changeAddresses.map { a ->
                                    val b = balByAddr[a.address] ?: 0L
                                    a.copy(balanceSompi = b, isUsed = a.isUsed || b > 0L)
                                },
                                utxos = reconciledUtxos,
                                transactions = mergedTxs,
                            )
                        }
                        dataRepo.updateWalletBalance(wallet.id, totalSompi)
                        if (mergedTxs.isNotEmpty()) {
                            dataRepo.saveCachedTransactions(wallet.id, state.activeNetwork, mergedTxs)
                        }
                    }
                },
                onFinishedRefresh = {
                    viewModelScope.launch {
                        delay(800L)
                        if (!isCurrentScope(donationEpoch, wallet.id, donationNet)) return@launch
                        // Reconcile via gRPC UTXOs + targeted REST tx lookup for the return address
                        pollUtxosAndTransactionsQuietly(forceFullTx = false, expectedEpoch = donationEpoch)
                        if (!isCurrentScope(donationEpoch, wallet.id, donationNet)) return@launch
                        val latest = _uiState.value
                        val walletAddrs = (latest.receiveAddresses + latest.changeAddresses).map { it.address }.toSet()
                        launchTargetedTxSync(
                            capturedEpoch = donationEpoch,
                            walletId = wallet.id,
                            config = latest.prefs.nodeConfig,
                            targetTxIds = latest.minerDonationProgress?.txIds?.takeLast(6).orEmpty(),
                            touchedAddresses = listOf(selfReturnAddress),
                            walletAddrSet = walletAddrs,
                        )
                    }
                },
            ),
        )
    }

    fun cancelMinerDonation() {
        MinerDonationController.cancel(getApplication())
    }

    fun dismissMinerDonationProgress() {
        MinerDonationController.dismiss(getApplication())
    }

    // -------------------------------------------------------------------------
    // Extended Public Key (kpub), Sign/Verify Message, Seed Reveal, CSV, Contacts, Notes
    // -------------------------------------------------------------------------

    fun getActiveKpubForNetwork(): String? {
        val wallet = _uiState.value.activeWallet ?: return null
        val kpubMainnet = wallet.kpubMainnet ?: return null
        return runCatching {
            KaspaCrypto.convertKpubForNetwork(kpubMainnet, _uiState.value.activeNetwork)
        }.getOrNull()
    }

    suspend fun revealActiveSeedPhrase(): Result<String> = runCatching {
        val wallet = _uiState.value.activeWallet ?: error("No active wallet")
        require(!wallet.isWatchOnly) { "Watch-Only wallets have no recovery phrase" }
        vault.withDecryptedSecret(wallet.id) { mnemonic, _ -> mnemonic }
    }

    suspend fun signPersonalMessage(address: String, message: String): Result<Pair<String, String>> = runCatching {
        val state = _uiState.value
        val wallet = state.activeWallet ?: error("No active wallet")
        require(!wallet.isWatchOnly) { "Watch-Only wallets cannot sign messages" }
        val derived = (state.receiveAddresses + state.changeAddresses).firstOrNull { it.address == address }
            ?: DerivedAddress(address, 0, 0, "m/44'/111111'/0'/0/0")
        vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
            val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
            try {
                val privKey = KaspaCrypto.derivePrivateKeyFromSeed(seed, derived.chain, derived.index, wallet.derivationKind)
                try {
                    val digestHex = KaspaCrypto.bytesToHex(KaspaCrypto.hashPersonalMessage(message))
                    val sigHex = KaspaCrypto.signPersonalMessage(privKey, message)
                    sigHex to digestHex
                } finally {
                    privKey.fill(0)
                }
            } finally {
                seed.fill(0)
            }
        }
    }

    fun verifyPersonalMessage(address: String, message: String, signatureHex: String): Boolean =
        KaspaCrypto.verifyPersonalMessage(address.trim(), message, signatureHex.trim())

    suspend fun exportCsvShareIntent(): Result<Intent> {
        val state = _uiState.value
        val name = state.activeWallet?.name ?: "KaspaKinetic"
        return dataRepo.exportTransactionsCsv(
            walletName = name,
            network = state.activeNetwork,
            transactions = state.transactions,
            txNotes = state.prefs.txNotes,
        )
    }

    fun saveContact(contact: ContactRecord) {
        viewModelScope.launch { dataRepo.saveContact(contact) }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch { dataRepo.deleteContact(contactId) }
    }

    fun saveTxNote(txId: String, note: String) {
        viewModelScope.launch { dataRepo.setTxNote(txId, note) }
    }

    // -------------------------------------------------------------------------
    // Preferences & Security Actions (All PIN crypto runs off main thread!)
    // -------------------------------------------------------------------------

    fun setNodeConfig(config: NodeConfig) {
        viewModelScope.launch { dataRepo.setNodeConfig(config) }
    }

    fun setFiatCurrency(currency: FiatCurrency) {
        viewModelScope.launch { dataRepo.setFiatCurrency(currency) }
    }

    fun setKasOnlyPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { dataRepo.setKasOnlyPrivacyMode(enabled) }
    }

    fun toggleHideBalances() {
        val next = !_uiState.value.prefs.hideBalances
        viewModelScope.launch { dataRepo.setHideBalances(next) }
    }

    fun setBiometricsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataRepo.setBiometricsEnabled(enabled) }
    }

    fun setAuthOnLaunch(enabled: Boolean) {
        viewModelScope.launch { dataRepo.setAuthOnLaunch(enabled) }
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        viewModelScope.launch { dataRepo.setAutoLockTimeout(timeout) }
    }

    fun setIncomingNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataRepo.setIncomingNotificationsEnabled(enabled) }
    }

    suspend fun savePin(pin: String): Result<Unit> = runCatching {
        vault.setPin(pin)
        _uiState.update { it.copy(hasPin = true) }
    }

    suspend fun verifyPin(pin: String): Boolean = vault.verifyPin(pin)

    suspend fun removePin(): Result<Unit> = runCatching {
        vault.removePin()
        dataRepo.setAuthOnLaunch(false)
        _uiState.update { it.copy(hasPin = false) }
    }

    suspend fun setWalletPassword(password: String): Result<Unit> = runCatching {
        vault.setWalletPassword(password)
        _uiState.update { it.copy(hasWalletPassword = true) }
    }

    suspend fun verifyWalletPassword(password: String): Boolean =
        vault.verifyWalletPassword(password)

    suspend fun removeWalletPassword(): Result<Unit> = runCatching {
        vault.removeWalletPassword()
        _uiState.update { it.copy(hasWalletPassword = false) }
    }

    suspend fun recordAppLaunchAndShouldShowDonationPopup(): Boolean =
        dataRepo.recordAppLaunchAndShouldShowDonationPopup()

    fun setDonationPopupOptOut(optOut: Boolean) {
        viewModelScope.launch { dataRepo.setDonationPopupOptOut(optOut) }
    }

    fun setCovenantsReminderOptOut(optOut: Boolean) {
        viewModelScope.launch { dataRepo.setCovenantsReminderOptOut(optOut) }
    }

    fun markLagTipStepSeen() {
        viewModelScope.launch { dataRepo.markLagTipStepSeen() }
    }

    // -------------------------------------------------------------------------
    // Stage 3 (v3) — Native Kaspa L1 SilverScript Covenants & `.kinetic` Vault
    // -------------------------------------------------------------------------

    fun activateCovenants() {
        viewModelScope.launch {
            val wallet = _uiState.value.activeWallet ?: return@launch
            dataRepo.setCovenantsActivatedForWallet(wallet.id, true)
            loadAndRefreshCovenantsForActiveWallet(wallet.id, activated = true)
        }
    }

    fun setMainnetCovenantsEnabled(enabled: Boolean) {
        viewModelScope.launch { dataRepo.setMainnetCovenantsEnabled(enabled) }
    }

    fun setCovenantsAutoSyncUri(uri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = _uiState.value.activeWallet ?: return@launch
            dataRepo.setCovenantsAutoSyncUriForWallet(wallet.id, uri)
            _covenantsState.update { it.copy(externalAutoSyncWarning = null) }
            if (!uri.isNullOrBlank()) {
                covenantStoreMutex.withLock {
                    if (_uiState.value.activeWallet?.id != wallet.id) return@withLock
                    val current = covenantRepo.loadVault(wallet.id)
                    val seedKey = deriveSeedBackupKeyOrNull(wallet)
                    try {
                        val (saved, warn) = covenantRepo.saveVaultAndAutoSnapshot(
                            walletId = wallet.id,
                            walletName = wallet.name,
                            primaryAddressMainnet = wallet.primaryAddressMainnet,
                            covenants = current.covenants,
                            existingCreatedAtMs = current.createdAtMs,
                            seedBackupKey32 = seedKey,
                            externalAutoSyncUri = uri,
                            touchUpdatedTimestamp = false,
                        )
                        if (_uiState.value.activeWallet?.id == wallet.id) {
                            _covenantsState.update {
                                it.copy(
                                    walletId = wallet.id,
                                    covenants = saved.covenants,
                                    vaultCreatedAtMs = saved.createdAtMs,
                                    vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                    externalAutoSyncWarning = warn,
                                )
                            }
                        }
                    } finally {
                        seedKey?.fill(0)
                    }
                }
            }
        }
    }

    fun defaultKineticBackupFilename(): String {
        val wallet = _uiState.value.activeWallet
        val cs = _covenantsState.value
        val now = System.currentTimeMillis()
        val created = cs.vaultCreatedAtMs.takeIf { it > 0L } ?: wallet?.createdAtMs ?: now
        val updated = cs.vaultLastUpdatedAtMs.takeIf { it > 0L } ?: now
        return KineticBackupEngine.buildDefaultKineticFilename(
            walletName = wallet?.name ?: "Kaspa_Wallet",
            createdAtMs = created,
            lastUpdatedAtMs = updated,
        )
    }

    fun activeWalletOwnerPubKeyHex(): String {
        val addr = _uiState.value.currentReceiveAddress.ifBlank {
            _uiState.value.activeWallet?.primaryAddressMainnet.orEmpty()
        }
        val decoded = KaspaAddress.decode(addr)
        return if (decoded != null && decoded.payload.size == 32) {
            KaspaCrypto.bytesToHex(decoded.payload)
        } else {
            ""
        }
    }

    /**
     * Derives and persists a brand-new unused HD receive address for the active wallet so every
     * tap of "Use Fresh My Address" gives the user a fresh, privacy-preserving Kaspa address!
     */
    suspend fun deriveFreshUnusedReceiveAddress(): Result<String> = withContext(Dispatchers.Default) {
        com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            val state = _uiState.value
            val wallet = state.activeWallet ?: error("No active wallet")
            val net = state.activeNetwork
            if (wallet.mode == WalletMode.WATCH_ADDRESS) {
                return@runCatchingNonCancellation KaspaAddress.convertNetworkPrefix(wallet.primaryAddressMainnet, net)
            }
            val usedOrSeen = maxOf(
                wallet.receiveIndex,
                wallet.highestUsedReceiveIndex,
                state.receiveAddresses.maxOfOrNull { it.index } ?: 0,
            )
            val nextIdx = (usedOrSeen + 1).coerceAtMost(119)
            val kpub = wallet.kpubMainnet
            val derived: DerivedAddress = if (kpub != null && wallet.derivationKind != DerivationKind.LEGACY_972_KDX) {
                val account = KaspaCrypto.HdNode.fromExtendedPublicKey(kpub)
                val leaf = account.deriveChild(0).deriveChild(nextIdx.toLong())
                DerivedAddress(
                    address = KaspaAddress.encodePubKey(leaf.xOnlyPublicKey, net),
                    chain = 0,
                    index = nextIdx,
                    derivationPath = "m/44'/111111'/0'/0/$nextIdx",
                )
            } else {
                require(!wallet.isWatchOnly) { "Cannot derive fresh address for this Watch-Only wallet" }
                vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                    try {
                        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, nextIdx, wallet.derivationKind)
                        val pub = try {
                            KaspaCrypto.xOnlyPublicKey(priv)
                        } finally {
                            priv.fill(0)
                        }
                        DerivedAddress(
                            address = KaspaAddress.encodePubKey(pub, net),
                            chain = 0,
                            index = nextIdx,
                            derivationPath = "m/44'/111111'/0'/0/$nextIdx",
                        )
                    } finally {
                        seed.fill(0)
                    }
                }
            }
            dataRepo.updateWalletDiscovery(
                walletId = wallet.id,
                highestUsedReceive = wallet.highestUsedReceiveIndex,
                highestUsedChange = wallet.highestUsedChangeIndex,
                receiveIndex = nextIdx,
                changeIndex = wallet.changeIndex,
            )
            _uiState.update { cur ->
                val updatedList = (cur.receiveAddresses.filterNot { it.index == nextIdx } + derived).sortedBy { it.index }
                cur.copy(receiveAddresses = updatedList)
            }
            derived.address
        }
    }

    private fun loadAndRefreshCovenantsForActiveWallet(walletId: String, activated: Boolean) {
        // Cancel any in-flight load/push sync from the previous wallet immediately so zero state leaks!
        activeCovenantLoadJob?.cancel()
        debouncedCovenantPushSyncJob?.cancel()
        if (_covenantsState.value.walletId != walletId) {
            _covenantsState.update {
                CovenantsUiState(walletId = walletId)
            }
        }
        activeCovenantLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = covenantStoreMutex.withLock {
                if (_uiState.value.activeWallet?.id != walletId) return@withLock null
                val store = covenantRepo.loadVault(walletId)
                if (_uiState.value.activeWallet?.id != walletId) return@withLock null
                _covenantsState.update {
                    it.copy(
                        walletId = walletId,
                        covenants = store.covenants,
                        vaultCreatedAtMs = store.createdAtMs,
                        vaultLastUpdatedAtMs = store.lastUpdatedAtMs,
                    )
                }
                store
            } ?: return@launch

            if (loaded.covenants.isNotEmpty()) {
                syncWatchedAddressesOnLiveStream()
            }
            if (activated && loaded.covenants.isNotEmpty()) {
                refreshCovenantsLiveStateSuspend()
            }
        }
    }

    private suspend fun deriveSeedBackupKeyOrNull(wallet: WalletRecord): ByteArray? {
        if (wallet.isWatchOnly) return null
        return runCatching {
            vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                try {
                    KaspaCrypto.deriveCovenantBackupKey(seed, wallet.derivationKind)
                } finally {
                    seed.fill(0)
                }
            }
        }.getOrNull()
    }

    suspend fun deriveCovenantSubKeyForActiveWallet(index: Int): Result<Pair<String, String>> =
        withContext(Dispatchers.Default) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                require(!wallet.isWatchOnly) { "Watch-Only wallet cannot derive sub-keys" }
                vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                    try {
                        val (priv, pub32) = KaspaCrypto.deriveCovenantSubKey(seed, index, wallet.derivationKind)
                        priv.fill(0)
                        val pubHex = KaspaCrypto.bytesToHex(pub32)
                        val addr = KaspaAddress.encodePubKey(pub32, state.activeNetwork)
                        pubHex to addr
                    } finally {
                        seed.fill(0)
                    }
                }
            }
        }

    /**
     * Launches the Post-Activation BlockDAG Covenant Scan inside `viewModelScope` using the exact
     * same [scanChainForKkcv1Covenants] pipeline as the Backup tab — completely immune to
     * Composable navigation/recomposition cancellations, with a 28s safety timeout so poor
     * connectivity finishes cleanly instead of spinning forever.
     */
    fun startPostActivationBlockDagScan() {
        val wallet = _uiState.value.activeWallet
        if (wallet == null || wallet.isWatchOnly) {
            _covenantsState.update {
                it.copy(postActivationScan = PostActivationScanState.completed(foundCount = 0))
            }
            return
        }
        if (postActivationScanJob?.isActive == true) return
        _covenantsState.update {
            it.copy(postActivationScan = PostActivationScanState.startScanning())
        }
        postActivationScanJob = viewModelScope.launch(Dispatchers.IO) {
            val res = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                kotlinx.coroutines.withTimeout(28_000L) {
                    scanChainForKkcv1Covenants().getOrThrow()
                }
            }
            res.onSuccess { rep ->
                _covenantsState.update {
                    it.copy(
                        postActivationScan = PostActivationScanState.completed(
                            foundCount = rep.discoveredCount,
                        ),
                    )
                }
            }.onFailure { err ->
                val msg = if (err is kotlinx.coroutines.TimeoutCancellationException) {
                    "Scan timed out. You can retry anytime from the Backup tab."
                } else {
                    err.message ?: "BlockDAG scan failed"
                }
                _covenantsState.update {
                    it.copy(
                        postActivationScan = PostActivationScanState.completed(
                            foundCount = 0,
                            errorMessage = msg,
                        ),
                    )
                }
            }
        }
    }

    fun dismissPostActivationScanDialog() {
        val cur = _covenantsState.value.postActivationScan
        if (!cur.dismissAllowed) return
        _covenantsState.update {
            it.copy(postActivationScan = PostActivationScanState.dismissed())
        }
    }

    fun refreshCovenantsLiveState() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshCovenantsLiveStateSuspend()
        }
    }

    /**
     * Live gRPC-only covenant UTXO, `pastMedianTime`, & DAA score sync:
     * - Single-flight coalesced: concurrent Hub entry + Refresh clicks share ONE live gRPC query
     *   without cancelling each other or flashing REST fallback.
     * - Reuses the singleton active `kaspad` gRPC channel (`getUtxosByCovenantIdsStrict` +
     *   `getUtxosGrpcOnly` + `getLiveBlockDagSnapshot`), NEVER touching public REST while gRPC
     *   is connected.
     * - Immediately settles confirmed/restored covenants whenever live gRPC succeeds with 0 UTXOs,
     *   while preserving `Active` state on genuine transport timeouts/errors.
     */
    suspend fun refreshCovenantsLiveStateSuspend(
        candidateRecipesByKey: Map<String, List<TrackedCovenant>> = emptyMap(),
    ): List<TrackedCovenant> {
        val useSingleFlight = candidateRecipesByKey.isEmpty()
        var myDeferred: kotlinx.coroutines.CompletableDeferred<List<TrackedCovenant>>? = null
        if (useSingleFlight) {
            val existing = synchronized(covenantRefreshSingleFlightLock) {
                val cur = activeCovenantRefreshDeferred
                if (cur != null && cur.isActive) {
                    cur
                } else {
                    val created = kotlinx.coroutines.CompletableDeferred<List<TrackedCovenant>>()
                    activeCovenantRefreshDeferred = created
                    myDeferred = created
                    null
                }
            }
            if (existing != null) {
                return existing.await()
            }
        }

        return try {
            val result = executeRefreshCovenantsLiveStateInternal(candidateRecipesByKey)
            myDeferred?.complete(result)
            result
        } catch (ce: kotlinx.coroutines.CancellationException) {
            myDeferred?.cancel(ce)
            throw ce
        } catch (t: Throwable) {
            myDeferred?.completeExceptionally(t)
            throw t
        } finally {
            if (myDeferred != null) {
                synchronized(covenantRefreshSingleFlightLock) {
                    if (activeCovenantRefreshDeferred === myDeferred) {
                        activeCovenantRefreshDeferred = null
                    }
                }
            }
        }
    }

    private suspend fun executeRefreshCovenantsLiveStateInternal(
        candidateRecipesByKey: Map<String, List<TrackedCovenant>>,
    ): List<TrackedCovenant> = withContext(Dispatchers.IO) {
        val state = _uiState.value
        val wallet = state.activeWallet ?: return@withContext _covenantsState.value.covenants
        val targetWalletId = wallet.id
        val currentList = covenantStoreMutex.withLock {
            val mem = _covenantsState.value
            if (mem.walletId == targetWalletId && mem.covenants.isNotEmpty()) {
                mem.covenants
            } else {
                val disk = covenantRepo.loadVault(targetWalletId)
                if (_uiState.value.activeWallet?.id == targetWalletId && disk.covenants.isNotEmpty()) {
                    _covenantsState.update {
                        it.copy(
                            walletId = targetWalletId,
                            covenants = disk.covenants,
                            vaultCreatedAtMs = disk.createdAtMs,
                            vaultLastUpdatedAtMs = disk.lastUpdatedAtMs,
                        )
                    }
                }
                disk.covenants
            }
        }
        if (currentList.isEmpty()) {
            // Still refresh server pastMedianTime anchor quietly if needed
            pollServerBlockDagAnchorNow()
            return@withContext emptyList()
        }

        _covenantsState.update { it.copy(isSyncing = true) }
        try {
            val activeNet = state.activeNetwork
            val netCovenants = currentList.filter { it.network == activeNet && it.walletId == targetWalletId }
            if (netCovenants.isEmpty()) return@withContext currentList

            val covIds = netCovenants.mapNotNull { it.covenantIdHex }.filter { it.length == 64 }.distinct()
            val candidateDestAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }
            val p2shAddrs = LinkedHashSet<String>().apply {
                netCovenants.forEach { if (it.currentP2shAddress.isNotBlank()) add(it.currentP2shAddress) }
            }

            // 1. Try KIP-20 `GetUtxosByCovenantIds` FIRST (instant exact match on `--covenant-index` nodes).
            var covIdQueryOk = true
            val covUtxos = if (covIds.isNotEmpty()) {
                val res = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getUtxosByCovenantIdsStrict(state.prefs.nodeConfig, covIds)
                }
                if (res.isSuccess) {
                    res.getOrDefault(emptyList())
                } else {
                    val err = res.exceptionOrNull()
                    android.util.Log.w("KaspaCovenants", "getUtxosByCovenantIdsStrict failed (falling back to batched multi-state address probe): ${err?.message}", err)
                    covIdQueryOk = false
                    emptyList()
                }
            } else {
                emptyList()
            }

            // 2. UNIVERSAL MULTI-STATE LOOKAHEAD PROBE (works on 100% of `--utxoindex` nodes, WITH
            //    or WITHOUT `--covenant-index`!): Whenever `getUtxosByCovenantIds` fails OR returns
            //    0 UTXOs for any stateful covenant, deterministically include all candidate evolved
            //    P2SH addresses (Cycles 0..24 for Subscriptions, Stage 0+1 for Daily Spend & Clawback)
            //    in ONE batched gRPC `getUtxosByAddresses` call!
            val matchedCovIdsFromIdQuery = covUtxos.mapNotNullTo(HashSet()) { it.covenantId?.lowercase() }
            val lookaheadByKey = HashMap<String, Set<String>>()
            for (cov in netCovenants) {
                val idLower = cov.covenantIdHex?.lowercase()
                val needsLookahead = !covIdQueryOk || (idLower != null && idLower !in matchedCovIdsFromIdQuery)
                if (needsLookahead) {
                    val addrs = CovenantCompiler.buildMultiStateLookaheadAddresses(
                        covenant = cov,
                        candidateDestAddresses = candidateDestAddrs,
                        subscriptionStartCycleOffset = 0,
                        subscriptionCycleCount = 25,
                    )
                    lookaheadByKey[cov.canonicalKey] = addrs
                    p2shAddrs.addAll(addrs)
                }
            }

            // 3. Pin covenant UTXO reads strictly to live gRPC! NEVER dump live covenant refreshes onto
            //    public REST while gRPC is connected; only touch REST if gRPC is genuinely offline!
            var addrQueryOk = true
            val addrList = p2shAddrs.toList()
            val addrUtxosMutable = ArrayList<UtxoItem>()
            if (addrList.isNotEmpty()) {
                val grpcOnly = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getUtxosGrpcOnly(state.prefs.nodeConfig, addrList)
                }
                if (grpcOnly.isSuccess) {
                    addrUtxosMutable.addAll(grpcOnly.getOrDefault(emptyList()))
                } else {
                    val grpcErr = grpcOnly.exceptionOrNull()
                    android.util.Log.w("KaspaCovenants", "getUtxosGrpcOnly failed: ${grpcErr?.message}", grpcErr)
                    if (network.isGrpcActive(state.prefs.nodeConfig)) {
                        // gRPC channel IS connected — do NOT hit public REST or flash fallback banner!
                        addrQueryOk = false
                    } else {
                        // Truly offline from gRPC: polite fallback to REST
                        val fallback = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                            network.getUtxos(state.prefs.nodeConfig, addrList)
                        }
                        if (fallback.isSuccess) {
                            addrUtxosMutable.addAll(fallback.getOrDefault(emptyList()))
                        } else {
                            addrQueryOk = false
                        }
                    }
                }
            }

            // If `getUtxosByCovenantIds` failed (no `--covenant-index` on node) BUT our batched
            // `getUtxosByAddresses` call on the same live gRPC node SUCCEEDED cleanly, we know 100%
            // the node is healthy and simply lacks `--covenant-index`!
            val covIndexUnsupportedOnNode = !covIdQueryOk && addrQueryOk && addrList.isNotEmpty()

            // Fetch live DAG snapshot first so high-cycle Subscription catch-up uses real server pastMedianTime
            val dagSnap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                network.getLiveBlockDagSnapshot(state.prefs.nodeConfig)
            }.getOrNull()
            if (dagSnap != null) {
                updateChainTimeAnchor(
                    pastMedianTimeMs = dagSnap.pastMedianTimeMs,
                    virtualDaaScore = dagSnap.virtualDaaScore,
                )
            }
            val virtualDaa = dagSnap?.virtualDaaScore?.takeIf { it > 0L }
                ?: _liveNodeHealth.value?.virtualDaaScore?.takeIf { it > 0L }
                ?: state.nodeHealth?.virtualDaaScore?.takeIf { it > 0L }
                ?: 0L
            // Use strictly real non-extrapolated server pastMedianTime for status reconciliation!
            val serverPmtMs = dagSnap?.pastMedianTimeMs?.takeIf { it >= CovenantCompiler.LOCK_TIME_THRESHOLD }
                ?: _chainTimeAnchor.value.pastMedianTimeMs.takeIf { it >= CovenantCompiler.LOCK_TIME_THRESHOLD }
                ?: currentEstimatedPastMedianTimeMs()

            // High-cycle Subscription catch-up (BOTH `Yours` AND `Shared` `.kpass` vaults!):
            // If a Recurring Allowance was not found in batch 1 (`k = 0..24`), continue stepping in
            // batches of 25 up to 500 cycles max while `nextDueTimeMs + k * interval <= serverPmtMs`
            // so even months-old Cycle-0 `.kpass` passes catch up cleanly on `--utxoindex`-only nodes!
            if (addrQueryOk) {
                val foundAddrsSoFar = addrUtxosMutable.mapTo(HashSet()) { it.address }
                val refTimeMs = maxOf(serverPmtMs, System.currentTimeMillis())
                val maxCycles = 500
                var startOffset = 25
                while (startOffset < maxCycles) {
                    val extraSubAddrs = LinkedHashSet<String>()
                    for (cov in netCovenants) {
                        if (cov.templateId != CovenantTemplateId.RECURRING_ALLOWANCE) continue
                        if (!CovenantCompiler.isKeylessTimeBasedSubscription(cov.params)) continue
                        val idLower = cov.covenantIdHex?.lowercase()
                        if (idLower != null && idLower in matchedCovIdsFromIdQuery) continue
                        val probedSoFar = lookaheadByKey[cov.canonicalKey].orEmpty()
                        if (probedSoFar.any { it in foundAddrsSoFar }) continue
                        val interval = cov.params.intervalMs.coerceAtLeast(60_000L)
                        if (cov.params.nextDueTimeMs + startOffset.toLong() * interval <= refTimeMs + interval) {
                            val nextBatch = CovenantCompiler.buildMultiStateLookaheadAddresses(
                                covenant = cov,
                                candidateDestAddresses = candidateDestAddrs,
                                subscriptionStartCycleOffset = startOffset,
                                subscriptionCycleCount = 25,
                            )
                            lookaheadByKey[cov.canonicalKey] = probedSoFar + nextBatch
                            extraSubAddrs.addAll(nextBatch - probedSoFar)
                        }
                    }
                    if (extraSubAddrs.isEmpty()) break
                    val batchRes = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                        if (network.isGrpcActive(state.prefs.nodeConfig)) {
                            network.getUtxosGrpcOnly(state.prefs.nodeConfig, extraSubAddrs.toList())
                        } else {
                            network.getUtxos(state.prefs.nodeConfig, extraSubAddrs.toList())
                        }
                    }
                    if (batchRes.isSuccess) {
                        val batchUtxos = batchRes.getOrDefault(emptyList())
                        addrUtxosMutable.addAll(batchUtxos)
                        batchUtxos.forEach { foundAddrsSoFar.add(it.address) }
                    } else {
                        break
                    }
                    startOffset += 25
                }
            }

            val mergedUtxos = LinkedHashMap<String, UtxoItem>().apply {
                for (u in addrUtxosMutable) put(u.outpointKey, u)
                for (u in covUtxos) put(u.outpointKey, u)
            }.values.toList()

            // Self-healing Daily Spend Limit rescue: if any live DAILY_SPEND_LIMIT UTXO has drifted
            // away from our stored redeemScript hash (e.g. cold restore of an existing Mainnet vault
            // whose prior spend tx predated KKCV1 transition payloads), inspect outputs[0].amount of
            // liveUtxo.transactionId so BLAKE2b reconciliation recovers the exact state bit-for-bit!
            val candidateSpentByKey = HashMap<String, List<Long>>()
            val covById = mergedUtxos
                .filter { !it.covenantId.isNullOrBlank() }
                .groupBy { it.covenantId!!.lowercase() }
            for (cov in netCovenants) {
                if (cov.templateId != CovenantTemplateId.DAILY_SPEND_LIMIT) continue
                val idLower = cov.covenantIdHex?.lowercase() ?: continue
                val livePrimary = covById[idLower]?.maxByOrNull { it.amountSompi } ?: continue
                if (!CovenantCompiler.matchesLiveUtxoScriptHash(cov.currentRedeemScriptHex, cov.network, livePrimary)) {
                    val out0Amt = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                        network.fetchTxFirstOutputAmountSompi(state.prefs.nodeConfig, livePrimary.transactionId)
                    }.getOrNull()
                    if (out0Amt != null && out0Amt > 0L) {
                        candidateSpentByKey[cov.canonicalKey] = listOf(out0Amt)
                    }
                }
            }

            val reconciledNet = CovenantMergeEngine.reconcileWithChainUtxos(
                covenants = netCovenants,
                liveUtxos = mergedUtxos,
                currentVirtualDaaScore = virtualDaa,
                pastMedianTimeMs = serverPmtMs,
                covenantIdQuerySucceeded = covIdQueryOk,
                addressQuerySucceeded = addrQueryOk,
                covenantIndexUnsupportedOnNode = covIndexUnsupportedOnNode,
                candidateSpentByKey = candidateSpentByKey,
                candidateDestAddresses = candidateDestAddrs,
                candidateRecipesByKey = candidateRecipesByKey,
                candidateLookaheadAddrsByKey = lookaheadByKey,
            )

            // Guard commit under covenantStoreMutex: abort cleanly if user switched wallets during I/O,
            // and merge additively with the latest disk/memory store so concurrent imports are NEVER lost!
            covenantStoreMutex.withLock {
                if (_uiState.value.activeWallet?.id != targetWalletId) {
                    return@withLock _covenantsState.value.covenants
                }
                val latestDisk = covenantRepo.loadVault(targetWalletId)
                val baseList = latestDisk.covenants.ifEmpty {
                    if (_covenantsState.value.walletId == targetWalletId) _covenantsState.value.covenants else emptyList()
                }
                val mergedAll = CovenantMergeEngine.mergeAdditive(
                    existing = baseList,
                    incoming = reconciledNet,
                    targetWalletId = targetWalletId,
                ).merged

                val seedKey = deriveSeedBackupKeyOrNull(wallet)
                try {
                    val (saved, warn) = covenantRepo.saveVaultAndAutoSnapshot(
                        walletId = targetWalletId,
                        walletName = wallet.name,
                        primaryAddressMainnet = wallet.primaryAddressMainnet,
                        covenants = mergedAll,
                        existingCreatedAtMs = latestDisk.createdAtMs.takeIf { it > 0L } ?: _covenantsState.value.vaultCreatedAtMs,
                        seedBackupKey32 = seedKey,
                        externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(targetWalletId),
                        touchUpdatedTimestamp = false,
                    )
                    if (_uiState.value.activeWallet?.id == targetWalletId) {
                        _covenantsState.update {
                            it.copy(
                                walletId = targetWalletId,
                                covenants = saved.covenants,
                                vaultCreatedAtMs = saved.createdAtMs,
                                vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                externalAutoSyncWarning = warn ?: it.externalAutoSyncWarning,
                            )
                        }
                        syncWatchedAddressesOnLiveStream()
                    }
                    saved.covenants
                } finally {
                    seedKey?.fill(0)
                }
            }
        } finally {
            _covenantsState.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * Runs a live gRPC cryptographic proof check for the "Verify On-Chain" sheet:
     * 1. Queries connected kaspad gRPC node for fresh covenant UTXOs + virtual DAA score
     * 2. Re-hashes the stored SilverScript redeem script (`BLAKE2b-256`) and confirms exact Bech32 P2SH match
     * 3. Builds exact Mainnet / Testnet-10 `kaspa.stream` & `explorer.kaspa.org` inspection URLs
     */
    suspend fun verifyCovenantOnChain(covenant: TrackedCovenant): Result<com.kaspakinetic.wallet.core.covenants.OnChainCovenantProof> =
        withContext(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val refreshed = refreshCovenantsLiveStateSuspend()
                val latest = refreshed.firstOrNull { it.canonicalKey == covenant.canonicalKey } ?: covenant
                val liveDaa = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getLiveBlockDagScore(state.prefs.nodeConfig)?.first
                }.getOrNull()
                    ?: _liveNodeHealth.value?.virtualDaaScore
                    ?: state.nodeHealth?.virtualDaaScore
                    ?: 0L

                val scriptVerified = CovenantCompiler.verifyRedeemScriptProof(
                    redeemScriptHex = latest.currentRedeemScriptHex,
                    expectedP2shAddress = latest.currentP2shAddress,
                    network = latest.network,
                )
                val txId = latest.liveUtxoTxId?.takeIf { it.length == 64 }
                    ?: latest.deployTxId.takeIf { it.length == 64 }
                    ?: ""
                val isLive = latest.liveBalanceSompi > 0L && !latest.liveUtxoTxId.isNullOrBlank() &&
                    latest.liveStatus != CovenantStatus.SETTLED
                val transport = _liveNodeHealth.value?.transportUsed
                    ?: state.nodeHealth?.transportUsed
                    ?: "gRPC"

                com.kaspakinetic.wallet.core.covenants.OnChainCovenantProof(
                    covenant = latest,
                    isLiveOnChain = isLive,
                    scriptHashVerified = scriptVerified,
                    latestTxId = txId,
                    confirmedBlockDaaScore = latest.liveUtxoBlockDaaScore,
                    currentVirtualDaaScore = liveDaa,
                    confirmedBalanceSompi = latest.liveBalanceSompi,
                    strayUnboundBalanceSompi = latest.strayUnboundBalanceSompi,
                    covenantIdHex = latest.covenantIdHex,
                    p2shAddress = latest.currentP2shAddress,
                    kaspaStreamUrl = CovenantCompiler.kaspaStreamUrl(txId, latest.currentP2shAddress, latest.network),
                    explorerKaspaOrgUrl = CovenantCompiler.explorerKaspaOrgUrl(txId, latest.currentP2shAddress, latest.network),
                    nodeTransport = transport,
                )
            }
        }

    /**
     * Fetches fresh spendable wallet UTXOs directly from the pinned live gRPC node before building
     * any covenant deploy or top-up transaction so stale cached/REST UTXOs never cause orphan errors.
     */
    private suspend fun fetchFreshWalletFundingUtxos(state: WalletUiState): List<UtxoItem> {
        val addrs = (state.receiveAddresses + state.changeAddresses).map { it.address }.distinct()
        if (addrs.isEmpty()) return state.utxos.filter { !it.isCoinbase }
        val fresh = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            network.getUtxosGrpcOnly(state.prefs.nodeConfig, addrs)
        }.getOrElse {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                network.getUtxos(state.prefs.nodeConfig, addrs)
            }.getOrNull()
        }
        val source = if (fresh != null && fresh.isNotEmpty()) fresh else state.utxos
        return source.filter { !it.isCoinbase && it.amountSompi > 0L }.sortedByDescending { it.amountSompi }
    }

    suspend fun deployCovenant(
        templateId: CovenantTemplateId,
        label: String,
        note: String,
        params: CovenantParams,
        depositSompi: Long,
    ): Result<TrackedCovenant> = withContext(Dispatchers.IO) {
        covenantActionMutex.withLock {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                require(!wallet.isWatchOnly) { "Watch-Only wallets cannot deploy covenants" }
                val activeNet = state.activeNetwork

                val addrIndexMap = (state.receiveAddresses + state.changeAddresses).associateBy { it.address }
                // Persist exact BIP-32 derivation metadata for the vault's owner signing key in BOTH .kinetic and KKCV1!
                val ownerDerived = addrIndexMap[params.ownerAddress]
                    ?: (state.receiveAddresses + state.changeAddresses).firstOrNull { d ->
                        val dec = KaspaAddress.decode(d.address)
                        dec != null && KaspaCrypto.bytesToHex(dec.payload).equals(params.ownerPubKeyHex, ignoreCase = true)
                    }
                val baseParamsWithPath = if (ownerDerived != null && params.ownerDerivationPath.isBlank()) {
                    params.copy(
                        ownerDerivationPath = ownerDerived.derivationPath,
                        ownerDerivationChain = ownerDerived.chain,
                        ownerDerivationIndex = ownerDerived.index,
                    )
                } else {
                    params
                }

                // Guarantee every newly deployed covenant carries a unique 16-byte CSPRNG salt so two vaults
                // with identical user settings NEVER collide on the same P2SH address!
                val saltedParams = if (baseParamsWithPath.vaultSaltHex.isBlank()) {
                    baseParamsWithPath.copy(vaultSaltHex = CovenantCompiler.generateVaultSaltHex())
                } else {
                    baseParamsWithPath
                }

                val compiled = CovenantCompiler.compile(templateId, saltedParams, activeNet)
                val changeAddr = state.currentChangeAddress

                // Fetch fresh live UTXOs over gRPC right before building so we never spend a stale outpoint!
                val spendable = fetchFreshWalletFundingUtxos(state)
                require(spendable.isNotEmpty()) { "No spendable KAS UTXOs available to fund covenant" }

                val (preparedTx, seedBackupKey) = vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                    try {
                        val backupKey = KaspaCrypto.deriveCovenantBackupKey(seed, wallet.derivationKind)
                        val genesisUtxo = spendable.first()
                        val provisionalCovId = if (templateId.isStatefulKip20) {
                            KaspaMassAndTx.computeCovenantId(
                                genesisTxIdHex = genesisUtxo.transactionId,
                                genesisOutputIndex = genesisUtxo.index,
                                authOutputs = listOf(
                                    0 to KaspaMassAndTx.TxOutput(
                                        valueSompi = depositSompi,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = compiled.p2shScriptPubKey,
                                        address = compiled.p2shAddress,
                                    ),
                                ),
                            )
                        } else {
                            null
                        }
                        val kkcv1Bytes = KineticBackupEngine.encodeKkcv1Payload(
                            recipe = KineticBackupEngine.Kkcv1Recipe(
                                t = templateId.id,
                                n = activeNet.id,
                                l = label.trim().ifBlank { templateId.contractName },
                                c = provisionalCovId,
                                p = saltedParams,
                            ),
                            seedBackupKey32 = backupKey,
                        )
                        val tx = CovenantCompiler.buildAndSignDeployTxV1(
                            bundle = compiled,
                            amountSompi = depositSompi,
                            fundingUtxos = spendable,
                            changeAddress = changeAddr,
                            network = activeNet,
                            kkcv1Payload = kkcv1Bytes,
                            keyLookup = { inputAddr ->
                                val derived = addrIndexMap[inputAddr]
                                if (derived != null) {
                                    KaspaCrypto.derivePrivateKeyFromSeed(seed, derived.chain, derived.index, wallet.derivationKind)
                                } else {
                                    KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, wallet.derivationKind)
                                }
                            },
                        )
                        tx to backupKey
                    } finally {
                        seed.fill(0)
                    }
                }

                try {
                    val finalTxId = submitCovenantTransactionIdempotent(
                        config = state.prefs.nodeConfig,
                        submitJson = preparedTx.submitJson,
                        expectedTxId = preparedTx.txId,
                    )
                    val now = System.currentTimeMillis()
                    val redeemHex = KaspaCrypto.bytesToHex(compiled.redeemScript)
                    val canonicalKey = CovenantCompiler.canonicalKeyFor(
                        network = activeNet,
                        templateId = templateId,
                        covenantIdHex = preparedTx.covenantIdHex,
                        redeemScriptHashHex = compiled.redeemScriptHashHex,
                    )
                    val tracked = TrackedCovenant(
                        canonicalKey = canonicalKey,
                        walletId = wallet.id,
                        network = activeNet,
                        templateId = templateId,
                        label = label.trim().ifBlank { templateId.contractName },
                        note = note.trim(),
                        covenantIdHex = preparedTx.covenantIdHex,
                        currentP2shAddress = compiled.p2shAddress,
                        currentRedeemScriptHex = redeemHex,
                        redeemScriptHashHex = compiled.redeemScriptHashHex,
                        silSource = compiled.silSource,
                        abiArtifact = compiled.abiArtifact,
                        params = saltedParams,
                        knownRedeemScriptsHex = listOf(redeemHex),
                        deployTxId = finalTxId,
                        createdAtMs = now,
                        lastUpdatedAtMs = now,
                        liveBalanceSompi = depositSompi,
                        liveUtxoTxId = finalTxId,
                        liveUtxoIndex = 0,
                        liveStatus = CovenantStatus.UNCONFIRMED,
                        localBroadcastAtMs = now,
                        lastChainVerifiedAtMs = now,
                    )
                    covenantStoreMutex.withLock {
                        val (saved, _, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                            walletId = wallet.id,
                            walletName = wallet.name,
                            primaryAddressMainnet = wallet.primaryAddressMainnet,
                            incoming = listOf(tracked),
                            seedBackupKey32 = seedBackupKey,
                            externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                        )
                        if (_uiState.value.activeWallet?.id == wallet.id) {
                            _covenantsState.update {
                                it.copy(
                                    walletId = wallet.id,
                                    covenants = saved.covenants,
                                    vaultCreatedAtMs = saved.createdAtMs,
                                    vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                    externalAutoSyncWarning = warn,
                                )
                            }
                        }
                    }
                    // Post-broadcast confirmation barrier: subscribe P2SH immediately & wait until live gRPC confirms UTXO
                    launchCovenantConfirmationBarrier(
                        walletId = wallet.id,
                        canonicalKey = canonicalKey,
                        expectedNewTxId = finalTxId,
                        expectingSettled = false,
                    )
                    tracked
                } finally {
                    seedBackupKey.fill(0)
                }
            }
        }
    }

    /**
     * Executes a SilverScript covenant entry-point spend or in-app top-up:
     * - Serialized via `covenantActionMutex` (prevents double-submit / "is already in mempool")
     * - Fetches fresh live UTXOs & DAA score over gRPC right before signing (prevents "is an orphan")
     * - Verified locally by `KaspaScriptVm` before broadcast
     * - Immediately syncs Active / Settled state and atomically refreshes `.kinetic` backup
     */
    suspend fun executeCovenantAction(
        covenant: TrackedCovenant,
        entryName: String,
        recipientAddress: String,
        spendAmountSompi: Long? = null,
        topUpExtraSompi: Long = 0L,
        useSubKeyIndex: Int? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        covenantActionMutex.withLock {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                val activeNet = state.activeNetwork
                require(covenant.network == activeNet) {
                    "Switch wallet network to ${covenant.network.id} to execute this covenant"
                }

                // 1. Pre-action fresh gRPC UTXO, pastMedianTime, & DAA fetch from the exact same live node!
                val refreshedList = refreshCovenantsLiveStateSuspend()
                val liveCov = refreshedList.firstOrNull { it.canonicalKey == covenant.canonicalKey } ?: covenant
                val dagSnap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getLiveBlockDagSnapshot(state.prefs.nodeConfig)
                }.getOrNull()
                if (dagSnap != null) {
                    updateChainTimeAnchor(
                        pastMedianTimeMs = dagSnap.pastMedianTimeMs,
                        virtualDaaScore = dagSnap.virtualDaaScore,
                    )
                }
                val livePmtMs = dagSnap?.pastMedianTimeMs?.takeIf { it >= CovenantCompiler.LOCK_TIME_THRESHOLD }
                    ?: _chainTimeAnchor.value.pastMedianTimeMs.takeIf { it >= CovenantCompiler.LOCK_TIME_THRESHOLD }
                    ?: currentEstimatedPastMedianTimeMs()
                val virtualDaa = dagSnap?.virtualDaaScore?.takeIf { it > 0L }
                    ?: _liveNodeHealth.value?.virtualDaaScore?.takeIf { it > 0L }
                    ?: state.nodeHealth?.virtualDaaScore ?: 0L

                val covTxId = liveCov.liveUtxoTxId
                    ?: error("No active on-chain UTXO found for this covenant yet. Wait 1–2 seconds for confirmation and tap Refresh.")
                val covIdx = liveCov.liveUtxoIndex ?: 0
                val covBalance = liveCov.liveBalanceSompi
                require(covBalance >= DUST_LIMIT_SOMPI) { "Covenant UTXO balance is below dust limit" }
                val covSpkHex = KaspaCrypto.bytesToHex(
                    KaspaAddress.decode(liveCov.currentP2shAddress)?.toScriptPublicKey()
                        ?: error("Invalid covenant P2SH address"),
                )
                val covUtxo = UtxoItem(
                    address = liveCov.currentP2shAddress,
                    transactionId = covTxId,
                    index = covIdx,
                    amountSompi = covBalance,
                    scriptPublicKeyHex = covSpkHex,
                    scriptPublicKeyVersion = 0,
                    blockDaaScore = liveCov.liveUtxoBlockDaaScore,
                    isCoinbase = false,
                    covenantId = liveCov.covenantIdHex,
                )

                // Fast-path for KEYLESS 0-KAS claimable paths (Subscription withdraw & Dead Man's Switch heirClaim):
                // Requires ZERO wallet unlock, ZERO signing key, and ZERO wallet KAS balance!
                val isKeylessClaim = (entryName == "withdraw" &&
                    liveCov.templateId == CovenantTemplateId.RECURRING_ALLOWANCE &&
                    CovenantCompiler.isKeylessTimeBasedSubscription(liveCov.params)) ||
                    (entryName == "heirClaim" &&
                        liveCov.templateId == CovenantTemplateId.DEAD_MANS_SWITCH &&
                        CovenantCompiler.isKeylessDeadMansSwitch(liveCov.params))

                if (isKeylessClaim) {
                    val (prepared, updatedCov) = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
                        covenant = liveCov,
                        covenantUtxo = covUtxo,
                        pastMedianTimeMs = livePmtMs,
                        preferredEntry = entryName,
                        virtualDaaScore = virtualDaa,
                    ) ?: error("Finalizing on Kaspa L1 — waiting for Kaspa consensus to confirm the unlock timer.")

                    val finalTxId = try {
                        submitCovenantTransactionIdempotent(
                            config = state.prefs.nodeConfig,
                            submitJson = prepared.submitJson,
                            expectedTxId = prepared.txId,
                        )
                    } catch (e: Exception) {
                        if (e.message?.contains("orphan", ignoreCase = true) == true) {
                            viewModelScope.launch(Dispatchers.IO) { refreshCovenantsLiveStateSuspend() }
                        }
                        throw e
                    }
                    val expectingSettled = updatedCov.liveStatus == CovenantStatus.SETTLED
                    val now = System.currentTimeMillis()
                    val finalCov = updatedCov.copy(
                        liveUtxoTxId = if (expectingSettled) null else finalTxId,
                        liveUtxoBlockDaaScore = 0L,
                        liveStatus = if (expectingSettled) CovenantStatus.SETTLED else CovenantStatus.UNCONFIRMED,
                        localBroadcastAtMs = if (expectingSettled) 0L else now,
                        lastUpdatedAtMs = now,
                        lastChainVerifiedAtMs = now,
                    )
                    val seedKey = deriveSeedBackupKeyOrNull(wallet)
                    try {
                        covenantStoreMutex.withLock {
                            val (saved, _, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                                walletId = wallet.id,
                                walletName = wallet.name,
                                primaryAddressMainnet = wallet.primaryAddressMainnet,
                                incoming = listOf(finalCov),
                                seedBackupKey32 = seedKey,
                                externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                            )
                            if (_uiState.value.activeWallet?.id == wallet.id) {
                                _covenantsState.update {
                                    it.copy(
                                        walletId = wallet.id,
                                        covenants = saved.covenants,
                                        vaultCreatedAtMs = saved.createdAtMs,
                                        vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                        externalAutoSyncWarning = warn ?: it.externalAutoSyncWarning,
                                    )
                                }
                            }
                        }
                    } finally {
                        seedKey?.fill(0)
                    }
                    launchCovenantConfirmationBarrier(
                        walletId = wallet.id,
                        canonicalKey = liveCov.canonicalKey,
                        expectedNewTxId = finalTxId,
                        expectingSettled = expectingSettled,
                    )
                    return@runCatchingNonCancellation finalTxId
                }

                require(!wallet.isWatchOnly) { "Watch-Only wallets cannot sign covenant spends" }

                val ageDaa = if (virtualDaa > 0L && liveCov.liveUtxoBlockDaaScore > 0L) {
                    (virtualDaa - liveCov.liveUtxoBlockDaaScore).coerceAtLeast(0L)
                } else {
                    0L
                }

                // Automatically switch Daily Spend Limit to `spendAfterReset` once windowDaa has elapsed!
                val effectiveEntryName = if (
                    entryName == "spendWithinLimit" &&
                    liveCov.templateId == CovenantTemplateId.DAILY_SPEND_LIMIT &&
                    liveCov.params.spentInWindowSompi > 0L &&
                    ageDaa >= liveCov.params.windowDaa &&
                    liveCov.abiArtifact.contracts[liveCov.templateId.contractName]?.entries?.containsKey("spendAfterReset") == true
                ) {
                    "spendAfterReset"
                } else {
                    entryName
                }

                val isTopUp = effectiveEntryName == "topUp"
                val destAddr = recipientAddress.trim().ifBlank { state.currentReceiveAddress }
                val decodedDest = KaspaAddress.decode(destAddr)
                    ?: error("Invalid destination Kaspa address")
                val destSpk = decodedDest.toScriptPublicKey()

                var nextParams = liveCov.params
                var nextBundle: com.kaspakinetic.wallet.core.covenants.CompiledCovenantBundle? = null
                var lockTime = 0L
                var sequence = 0L
                val outputs = ArrayList<KaspaMassAndTx.TxOutput>()
                var extraUtxos: List<UtxoItem> = emptyList()

                // Owner state-transition transactions carry an updated seed-encrypted KKCV1 payload
                // so future cold restores recover the latest active state directly from BlockDAG!
                val stateTransitionPayloadBudgetBytes = 640

                if (isTopUp) {
                    // Universal In-App Top Up for ALL 7 Covenants:
                    // Full exact `topUpExtraSompi` is added into the vault; miner fee is paid strictly from wallet funding UTXOs!
                    require(topUpExtraSompi >= DUST_LIMIT_SOMPI) { "Top-up amount must be above dust limit" }
                    val spendable = fetchFreshWalletFundingUtxos(state)
                    var feeSompi = CovenantCompiler.estimateSpendFeeSompi(
                        covenant = liveCov,
                        covenantUtxo = covUtxo,
                        entryName = "topUp",
                        outputAmountsSompi = listOf(covBalance + topUpExtraSompi, 100_000_000L),
                        hasCovenantOutput = liveCov.templateId.isStatefulKip20,
                        extraFundingUtxos = spendable.take(2),
                        payloadSizeBytes = stateTransitionPayloadBudgetBytes,
                    )
                    var acc = 0L
                    val picked = ArrayList<UtxoItem>()
                    for (u in spendable) {
                        picked.add(u)
                        acc += u.amountSompi
                        feeSompi = CovenantCompiler.estimateSpendFeeSompi(
                            covenant = liveCov,
                            covenantUtxo = covUtxo,
                            entryName = "topUp",
                            outputAmountsSompi = listOf(covBalance + topUpExtraSompi, (acc - topUpExtraSompi).coerceAtLeast(DUST_LIMIT_SOMPI)),
                            hasCovenantOutput = liveCov.templateId.isStatefulKip20,
                            extraFundingUtxos = picked,
                            payloadSizeBytes = stateTransitionPayloadBudgetBytes,
                        )
                        if (acc >= topUpExtraSompi + feeSompi) break
                    }
                    require(acc >= topUpExtraSompi + feeSompi) {
                        "Insufficient wallet KAS for top-up + network fee (${feeSompi / com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS.toDouble()} KAS)"
                    }
                    extraUtxos = picked
                    val newVaultBalance = covBalance + topUpExtraSompi
                    outputs.add(
                        KaspaMassAndTx.TxOutput(
                            valueSompi = newVaultBalance,
                            scriptPublicKeyVersion = 0,
                            scriptPublicKey = KaspaCrypto.hexToBytes(covSpkHex),
                            address = liveCov.currentP2shAddress,
                            covenant = if (liveCov.templateId.isStatefulKip20 && !liveCov.covenantIdHex.isNullOrBlank()) {
                                KaspaMassAndTx.CovenantBinding(0, liveCov.covenantIdHex)
                            } else {
                                null
                            },
                        ),
                    )
                    val walletChange = acc - topUpExtraSompi - feeSompi
                    if (walletChange >= DUST_LIMIT_SOMPI) {
                        val chAddr = state.currentChangeAddress
                        val chSpk = KaspaAddress.decode(chAddr)?.toScriptPublicKey() ?: destSpk
                        outputs.add(KaspaMassAndTx.TxOutput(walletChange, 0, chSpk, chAddr))
                    }
                } else {
                    val carriesStateTransitionPayload = effectiveEntryName in setOf(
                        "initiateUnvault",
                        "checkIn",
                        "spendWithinLimit",
                        "spendAfterReset",
                    )
                    val isSingleOutputAction = effectiveEntryName !in setOf("spendWithinLimit", "spendAfterReset", "withdraw", "spend")
                    val feeSompi = if (isSingleOutputAction) {
                        CovenantCompiler.estimateSpendFeeSompi(
                            covenant = liveCov,
                            covenantUtxo = covUtxo,
                            entryName = effectiveEntryName,
                            outputAmountsSompi = listOf(covBalance),
                            hasCovenantOutput = effectiveEntryName in setOf("initiateUnvault", "checkIn"),
                            payloadSizeBytes = if (carriesStateTransitionPayload) stateTransitionPayloadBudgetBytes else 0,
                        )
                    } else {
                        // Legacy `withdraw` estimate (Cold Whitelist & Daily Spend Limit use exact computeSpendAndFeePlan below!)
                        val splitA = (spendAmountSompi ?: (covBalance / 2L)).coerceAtLeast(DUST_LIMIT_SOMPI)
                        val splitB = (covBalance - splitA).coerceAtLeast(DUST_LIMIT_SOMPI)
                        CovenantCompiler.estimateSpendFeeSompi(
                            covenant = liveCov,
                            covenantUtxo = covUtxo,
                            entryName = effectiveEntryName,
                            outputAmountsSompi = listOf(splitA, splitB),
                            hasCovenantOutput = effectiveEntryName == "withdraw",
                        )
                    }

                    when (effectiveEntryName) {
                        "initiateUnvault" -> {
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small for unvault fee" }
                            nextParams = liveCov.params.copy(vaultStage = 1, pendingUnvaultDestAddress = destAddr)
                            nextBundle = CovenantCompiler.compile(liveCov.templateId, nextParams, activeNet)
                            outputs.add(
                                KaspaMassAndTx.TxOutput(
                                    valueSompi = outAmt,
                                    scriptPublicKeyVersion = 0,
                                    scriptPublicKey = nextBundle.p2shScriptPubKey,
                                    address = nextBundle.p2shAddress,
                                    covenant = liveCov.covenantIdHex?.let { KaspaMassAndTx.CovenantBinding(0, it) },
                                ),
                            )
                        }
                        "completeUnvault" -> {
                            sequence = liveCov.params.unvaultDelayDaa
                            val targetAddr = liveCov.params.pendingUnvaultDestAddress.ifBlank { destAddr }
                            val targetSpk = KaspaAddress.decode(targetAddr)?.toScriptPublicKey() ?: destSpk
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, targetSpk, targetAddr))
                        }
                        "alarmClawback" -> {
                            val recAddr = liveCov.params.recoveryAddress.ifBlank { destAddr }
                            val recSpk = KaspaAddress.decode(recAddr)?.toScriptPublicKey() ?: destSpk
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, recSpk, recAddr))
                        }
                        "checkIn" -> {
                            // Preserve full vault inheritance balance on Check-In by paying the small
                            // miner fee from the owner's normal wallet funding UTXOs whenever available
                            // (falling back to vault balance ONLY if owner wallet has no spendable funding UTXOs).
                            val spendableFunding = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                                fetchFreshWalletFundingUtxos(state)
                            }.getOrDefault(emptyList())
                            val pickedFunding = ArrayList<UtxoItem>()
                            var fundingAcc = 0L
                            var fundingFee = 0L
                            for (u in spendableFunding) {
                                pickedFunding.add(u)
                                fundingAcc += u.amountSompi
                                fundingFee = CovenantCompiler.estimateSpendFeeSompi(
                                    covenant = liveCov,
                                    covenantUtxo = covUtxo,
                                    entryName = "checkIn",
                                    outputAmountsSompi = listOf(covBalance, (fundingAcc - 350_000L).coerceAtLeast(DUST_LIMIT_SOMPI)),
                                    hasCovenantOutput = true,
                                    extraFundingUtxos = pickedFunding,
                                    payloadSizeBytes = stateTransitionPayloadBudgetBytes,
                                )
                                if (fundingAcc >= fundingFee) break
                            }
                            val payFeeFromWallet = pickedFunding.isNotEmpty() && fundingAcc >= fundingFee
                            val vaultOutAmt = if (payFeeFromWallet) {
                                extraUtxos = pickedFunding
                                covBalance
                            } else {
                                val rem = covBalance - feeSompi
                                require(rem >= DUST_LIMIT_SOMPI) { "Balance too small for check-in fee" }
                                rem
                            }
                            if (CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(liveCov.params)) {
                                // Legacy v3.2-v3.7 CLTV Dead Man's Switch: advance inactivityDeadlineMs into successor P2SH
                                val durMs = liveCov.params.inactivityDurationMs.coerceAtLeast(60_000L)
                                nextParams = liveCov.params.copy(
                                    inactivityDeadlineMs = livePmtMs + durMs,
                                )
                                nextBundle = CovenantCompiler.compile(liveCov.templateId, nextParams, activeNet)
                                outputs.add(
                                    KaspaMassAndTx.TxOutput(
                                        valueSompi = vaultOutAmt,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = nextBundle.p2shScriptPubKey,
                                        address = nextBundle.p2shAddress,
                                        covenant = liveCov.covenantIdHex?.let { KaspaMassAndTx.CovenantBinding(0, it) },
                                    ),
                                )
                            } else {
                                // v3.8 Permanent Fixed-Address CSV Dead Man's Switch:
                                // Self-spend to the EXACT SAME P2SH SPK & covenant_id, resetting UTXO blockDaaScore
                                // while keeping the vault balance intact and heir `.kpass` valid forever!
                                outputs.add(
                                    KaspaMassAndTx.TxOutput(
                                        valueSompi = vaultOutAmt,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = KaspaCrypto.hexToBytes(covSpkHex),
                                        address = liveCov.currentP2shAddress,
                                        covenant = liveCov.covenantIdHex?.let { KaspaMassAndTx.CovenantBinding(0, it) },
                                    ),
                                )
                            }
                            if (payFeeFromWallet) {
                                val walletChange = fundingAcc - fundingFee
                                if (walletChange >= DUST_LIMIT_SOMPI) {
                                    val chAddr = state.currentChangeAddress
                                    val chSpk = KaspaAddress.decode(chAddr)?.toScriptPublicKey() ?: destSpk
                                    outputs.add(KaspaMassAndTx.TxOutput(walletChange, 0, chSpk, chAddr))
                                }
                            }
                        }
                        "heirClaim" -> {
                            sequence = liveCov.params.inactivityDaa
                            val heirAddr = liveCov.params.heirAddress.ifBlank { destAddr }
                            val heirSpk = KaspaAddress.decode(heirAddr)?.toScriptPublicKey() ?: destSpk
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, heirSpk, heirAddr))
                        }
                        "spendWithinLimit", "spendAfterReset" -> {
                            val isReset = effectiveEntryName == "spendAfterReset"
                            if (isReset) sequence = liveCov.params.windowDaa
                            val allowedRemaining = if (isReset) {
                                liveCov.params.dailyCapSompi
                            } else {
                                (liveCov.params.dailyCapSompi - liveCov.params.spentInWindowSompi).coerceAtLeast(0L)
                            }
                            require(allowedRemaining >= DUST_LIMIT_SOMPI) {
                                "Spend amount exceeds remaining window limit"
                            }
                            val requested = spendAmountSompi ?: minOf(allowedRemaining, covBalance)
                            require(requested >= DUST_LIMIT_SOMPI) { "Spend amount below dust limit" }
                            require(requested <= allowedRemaining) { "Spend amount exceeds remaining window limit" }
                            val plan = CovenantCompiler.computeSpendAndFeePlan(
                                covenant = liveCov,
                                covenantUtxo = covUtxo,
                                entryName = effectiveEntryName,
                                requestedSpendSompi = requested,
                                maxAllowedSpendSompi = allowedRemaining,
                                allowCovenantChangeOutput = true,
                                payloadSizeBytes = stateTransitionPayloadBudgetBytes,
                            )
                            outputs.add(KaspaMassAndTx.TxOutput(plan.spendSompi, 0, destSpk, destAddr))
                            if (!plan.isFullSweep && plan.changeSompi >= DUST_LIMIT_SOMPI) {
                                nextParams = liveCov.params.copy(
                                    spentInWindowSompi = if (isReset) plan.spendSompi else (liveCov.params.spentInWindowSompi + plan.spendSompi),
                                    windowStartDaa = if (isReset || liveCov.params.windowStartDaa == 0L) virtualDaa else liveCov.params.windowStartDaa,
                                )
                                nextBundle = CovenantCompiler.compile(liveCov.templateId, nextParams, activeNet)
                                outputs.add(
                                    KaspaMassAndTx.TxOutput(
                                        valueSompi = plan.changeSompi,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = nextBundle.p2shScriptPubKey,
                                        address = nextBundle.p2shAddress,
                                        covenant = liveCov.covenantIdHex?.let { KaspaMassAndTx.CovenantBinding(0, it) },
                                    ),
                                )
                            }
                        }
                        "ownerReclaim" -> {
                            if (liveCov.templateId == CovenantTemplateId.DAILY_SPEND_LIMIT) {
                                when {
                                    liveCov.params.fullWithdrawUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD -> {
                                        lockTime = liveCov.params.fullWithdrawUnlockTimeMs
                                    }
                                    liveCov.params.fullWithdrawUnlockDaa > 0L -> {
                                        lockTime = liveCov.params.fullWithdrawUnlockDaa
                                    }
                                    else -> {
                                        sequence = liveCov.params.windowDaa
                                    }
                                }
                            }
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, destSpk, destAddr))
                        }
                        "spend" -> {
                            // Cold Whitelist: pre-validate destination against approved cold addresses BEFORE VM execution!
                            if (liveCov.templateId == CovenantTemplateId.COLD_WHITELIST &&
                                !CovenantCompiler.isDestinationWhitelisted(liveCov, destAddr)
                            ) {
                                error(
                                    "This address is not on your approved cold whitelist. " +
                                        "Funds in this vault can only be sent to one of your pre-approved cold addresses.",
                                )
                            }
                            // Exact 1-output full sweep OR 2-output partial spend (zero KIP-9 dust-output fee explosion!)
                            val plan = CovenantCompiler.computeSpendAndFeePlan(
                                covenant = liveCov,
                                covenantUtxo = covUtxo,
                                entryName = "spend",
                                requestedSpendSompi = spendAmountSompi ?: covBalance,
                                maxAllowedSpendSompi = Long.MAX_VALUE,
                                allowCovenantChangeOutput = true,
                                payloadSizeBytes = 0,
                            )
                            outputs.add(KaspaMassAndTx.TxOutput(plan.spendSompi, 0, destSpk, destAddr))
                            if (!plan.isFullSweep && plan.changeSompi >= DUST_LIMIT_SOMPI) {
                                outputs.add(
                                    KaspaMassAndTx.TxOutput(
                                        valueSompi = plan.changeSompi,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = KaspaCrypto.hexToBytes(covSpkHex),
                                        address = liveCov.currentP2shAddress,
                                    ),
                                )
                            }
                        }
                        "unlock" -> {
                            lockTime = if (liveCov.params.unlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                                liveCov.params.unlockTimeMs
                            } else {
                                liveCov.params.unlockDaa
                            }
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, destSpk, destAddr))
                        }
                        "unlockFallback" -> {
                            lockTime = if (liveCov.params.fallbackUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                                liveCov.params.fallbackUnlockTimeMs
                            } else {
                                liveCov.params.fallbackUnlockDaa
                            }
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, destSpk, destAddr))
                        }
                        "withdraw" -> {
                            sequence = liveCov.params.periodDaa
                            val recAddr = liveCov.params.recipientAddress.ifBlank { destAddr }
                            val recSpk = KaspaAddress.decode(recAddr)?.toScriptPublicKey() ?: destSpk
                            val pullAmt = minOf(liveCov.params.allowanceSompi, covBalance - feeSompi)
                            require(pullAmt >= DUST_LIMIT_SOMPI) { "Allowance balance too small after fee" }
                            val rem = covBalance - pullAmt - feeSompi
                            outputs.add(KaspaMassAndTx.TxOutput(pullAmt, 0, recSpk, recAddr))
                            if (rem >= DUST_LIMIT_SOMPI) {
                                outputs.add(
                                    KaspaMassAndTx.TxOutput(
                                        valueSompi = rem,
                                        scriptPublicKeyVersion = 0,
                                        scriptPublicKey = KaspaCrypto.hexToBytes(covSpkHex),
                                        address = liveCov.currentP2shAddress,
                                        covenant = liveCov.covenantIdHex?.let { KaspaMassAndTx.CovenantBinding(0, it) },
                                    ),
                                )
                            }
                        }
                        else -> {
                            // unlockGoal, cancelReclaim
                            val outAmt = covBalance - feeSompi
                            require(outAmt >= DUST_LIMIT_SOMPI) { "Balance too small after fee" }
                            outputs.add(KaspaMassAndTx.TxOutput(outAmt, 0, destSpk, destAddr))
                        }
                    }
                }

                val addrIndexMap = (state.receiveAddresses + state.changeAddresses).associateBy { it.address }
                val targetSignerPubKeyHex = if (
                    effectiveEntryName == "alarmClawback" &&
                    liveCov.templateId == CovenantTemplateId.CLAWBACK_VAULT &&
                    liveCov.params.alarmPubKeyHex.isNotBlank()
                ) {
                    liveCov.params.alarmPubKeyHex
                } else {
                    liveCov.params.ownerPubKeyHex
                }
                val continuesCovenantOut = outputs.any { out ->
                    out.covenant != null ||
                        out.address == liveCov.currentP2shAddress ||
                        (nextBundle != null && out.address == nextBundle.p2shAddress)
                }
                val attachStateTransitionKkcv1 = continuesCovenantOut && (
                    isTopUp || effectiveEntryName in setOf(
                        "initiateUnvault",
                        "checkIn",
                        "spendWithinLimit",
                        "spendAfterReset",
                    )
                )

                val (prepared, backupKey, resolvedOwnerKeyMeta) = vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                    try {
                        val bKey = KaspaCrypto.deriveCovenantBackupKey(seed, wallet.derivationKind)
                        val hintDerived = addrIndexMap[liveCov.params.ownerAddress]
                        val resolvedKey = if (useSubKeyIndex != null && useSubKeyIndex >= 0) {
                            val (subPriv, subPub) = KaspaCrypto.deriveCovenantSubKey(seed, useSubKeyIndex, wallet.derivationKind)
                            if (KaspaCrypto.bytesToHex(subPub).equals(targetSignerPubKeyHex, ignoreCase = true)) {
                                KaspaCrypto.ResolvedSigningKey(
                                    privateKey32 = subPriv,
                                    xOnlyPubKey32 = subPub,
                                    derivationPath = "m/44'/111111'/98'/0'/$useSubKeyIndex'",
                                    chain = 98,
                                    index = useSubKeyIndex,
                                )
                            } else {
                                subPriv.fill(0)
                                KaspaCrypto.resolveSigningKeyForPubKey(
                                    seed = seed,
                                    targetXOnlyPubKeyHex = targetSignerPubKeyHex,
                                    kind = wallet.derivationKind,
                                    hintPath = liveCov.params.ownerDerivationPath.ifBlank { hintDerived?.derivationPath.orEmpty() },
                                    hintChain = liveCov.params.ownerDerivationChain.takeIf { it >= 0 } ?: hintDerived?.chain ?: -1,
                                    hintIndex = liveCov.params.ownerDerivationIndex.takeIf { it >= 0 } ?: hintDerived?.index ?: -1,
                                )
                            }
                        } else {
                            KaspaCrypto.resolveSigningKeyForPubKey(
                                seed = seed,
                                targetXOnlyPubKeyHex = targetSignerPubKeyHex,
                                kind = wallet.derivationKind,
                                hintPath = liveCov.params.ownerDerivationPath.ifBlank { hintDerived?.derivationPath.orEmpty() },
                                hintChain = liveCov.params.ownerDerivationChain.takeIf { it >= 0 } ?: hintDerived?.chain ?: -1,
                                hintIndex = liveCov.params.ownerDerivationIndex.takeIf { it >= 0 } ?: hintDerived?.index ?: -1,
                            )
                        } ?: error("Signing key not found in this wallet for pubkey ${targetSignerPubKeyHex.take(12)}...")

                        val ownerMeta = if (
                            targetSignerPubKeyHex.equals(liveCov.params.ownerPubKeyHex, ignoreCase = true)
                        ) {
                            Triple(resolvedKey.derivationPath, resolvedKey.chain, resolvedKey.index)
                        } else {
                            null
                        }
                        val paramsForKkcv1 = if (ownerMeta != null) {
                            nextParams.copy(
                                ownerDerivationPath = ownerMeta.first,
                                ownerDerivationChain = ownerMeta.second,
                                ownerDerivationIndex = ownerMeta.third,
                            )
                        } else {
                            nextParams
                        }
                        val transitionPayload = if (attachStateTransitionKkcv1) {
                            KineticBackupEngine.encodeKkcv1Payload(
                                recipe = KineticBackupEngine.Kkcv1Recipe(
                                    t = liveCov.templateId.id,
                                    n = activeNet.id,
                                    l = liveCov.label,
                                    c = liveCov.covenantIdHex,
                                    p = paramsForKkcv1,
                                ),
                                seedBackupKey32 = bKey,
                            )
                        } else {
                            ByteArray(0)
                        }

                        try {
                            val tx = CovenantCompiler.buildSignAndVerifySpendTxV1(
                                covenant = liveCov,
                                covenantUtxo = covUtxo,
                                entryName = effectiveEntryName,
                                outputs = outputs,
                                lockTime = lockTime,
                                sequence = sequence,
                                virtualDaaScore = virtualDaa,
                                currentTimeMs = livePmtMs,
                                signerPrivateKeys = listOf(resolvedKey.privateKey32),
                                extraFundingUtxos = extraUtxos,
                                extraKeyLookup = { addr ->
                                    val d = addrIndexMap[addr]
                                    if (d != null) {
                                        KaspaCrypto.derivePrivateKeyFromSeed(seed, d.chain, d.index, wallet.derivationKind)
                                    } else {
                                        val dec = KaspaAddress.decode(addr)
                                        if (dec != null && dec.payload.size == 32) {
                                            KaspaCrypto.resolveSigningKeyForPubKey(
                                                seed = seed,
                                                targetXOnlyPubKeyHex = KaspaCrypto.bytesToHex(dec.payload),
                                                kind = wallet.derivationKind,
                                            )?.privateKey32
                                        } else {
                                            null
                                        }
                                    }
                                },
                                payload = transitionPayload,
                            )
                            Triple(tx, bKey, ownerMeta)
                        } finally {
                            resolvedKey.privateKey32.fill(0)
                        }
                    } finally {
                        seed.fill(0)
                    }
                }

                // Persist discovered BIP-32 owner key metadata permanently into the saved covenant!
                if (resolvedOwnerKeyMeta != null) {
                    nextParams = nextParams.copy(
                        ownerDerivationPath = resolvedOwnerKeyMeta.first,
                        ownerDerivationChain = resolvedOwnerKeyMeta.second,
                        ownerDerivationIndex = resolvedOwnerKeyMeta.third,
                    )
                }

                try {
                    val finalTxId = try {
                        submitCovenantTransactionIdempotent(
                            config = state.prefs.nodeConfig,
                            submitJson = prepared.submitJson,
                            expectedTxId = prepared.txId,
                        )
                    } catch (e: Exception) {
                        // If the node rejects with `is an orphan`, the covenant was likely already
                        // spent/drained by another wallet (e.g. Heir / Recipient) — immediately
                        // reconcile live gRPC state so UI flips straight to Settled or updated UTXO!
                        if (e.message?.contains("orphan", ignoreCase = true) == true) {
                            viewModelScope.launch(Dispatchers.IO) { refreshCovenantsLiveStateSuspend() }
                        }
                        throw e
                    }
                    val now = System.currentTimeMillis()
                    val nextOutIdx = outputs.indexOfFirst { out ->
                        out.covenant != null ||
                            out.address == liveCov.currentP2shAddress ||
                            (nextBundle != null && out.address == nextBundle.p2shAddress)
                    }
                    val continuesCovenant = nextOutIdx >= 0
                    val updatedCov = if (continuesCovenant) {
                        val nextOut = outputs[nextOutIdx]
                        val nextRedeemHex = nextBundle?.let { KaspaCrypto.bytesToHex(it.redeemScript) }
                            ?: liveCov.currentRedeemScriptHex
                        liveCov.copy(
                            currentP2shAddress = nextOut.address,
                            currentRedeemScriptHex = nextRedeemHex,
                            redeemScriptHashHex = nextBundle?.redeemScriptHashHex ?: liveCov.redeemScriptHashHex,
                            abiArtifact = nextBundle?.abiArtifact ?: liveCov.abiArtifact,
                            params = nextParams,
                            knownRedeemScriptsHex = (liveCov.knownRedeemScriptsHex + nextRedeemHex).distinct(),
                            lastUpdatedAtMs = now,
                            liveBalanceSompi = nextOut.valueSompi,
                            liveUtxoTxId = finalTxId,
                            liveUtxoIndex = nextOutIdx,
                            liveUtxoBlockDaaScore = 0L,
                            liveStatus = CovenantStatus.UNCONFIRMED,
                            localBroadcastAtMs = now,
                            lastChainVerifiedAtMs = now,
                        )
                    } else {
                        liveCov.copy(
                            lastUpdatedAtMs = now,
                            liveBalanceSompi = 0L,
                            liveUtxoTxId = null,
                            liveUtxoIndex = null,
                            liveUtxoBlockDaaScore = 0L,
                            liveStatus = CovenantStatus.SETTLED,
                            localBroadcastAtMs = 0L,
                            lastChainVerifiedAtMs = now,
                        )
                    }
                    covenantStoreMutex.withLock {
                        val (saved, _, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                            walletId = wallet.id,
                            walletName = wallet.name,
                            primaryAddressMainnet = wallet.primaryAddressMainnet,
                            incoming = listOf(updatedCov),
                            seedBackupKey32 = backupKey,
                            externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                        )
                        if (_uiState.value.activeWallet?.id == wallet.id) {
                            _covenantsState.update {
                                it.copy(
                                    walletId = wallet.id,
                                    covenants = saved.covenants,
                                    vaultCreatedAtMs = saved.createdAtMs,
                                    vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                    externalAutoSyncWarning = warn,
                                )
                            }
                        }
                    }
                    launchCovenantConfirmationBarrier(
                        walletId = wallet.id,
                        canonicalKey = liveCov.canonicalKey,
                        expectedNewTxId = finalTxId,
                        expectingSettled = !continuesCovenant,
                    )
                    finalTxId
                } finally {
                    backupKey.fill(0)
                }
            }
        }
    }

    /**
     * Sweeps any stray plain/unbound UTXO that was accidentally sent externally straight to a
     * stateful covenant's P2SH address back to the owner's wallet cleanly.
     */
    suspend fun rescueStrayUnboundUtxo(covenant: TrackedCovenant): Result<String> = withContext(Dispatchers.IO) {
        covenantActionMutex.withLock {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                require(!wallet.isWatchOnly) { "Watch-Only wallets cannot sign rescue transactions" }
                val addrUtxos = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getUtxosGrpcOnly(state.prefs.nodeConfig, listOf(covenant.currentP2shAddress))
                }.getOrElse {
                    network.getUtxos(state.prefs.nodeConfig, listOf(covenant.currentP2shAddress))
                }
                val stray = addrUtxos.firstOrNull { it.covenantId.isNullOrBlank() && it.amountSompi >= DUST_LIMIT_SOMPI }
                    ?: error("No stray unbound UTXO found at this covenant address.")

                val rescueEntry = when (covenant.templateId) {
                    CovenantTemplateId.DEAD_MANS_SWITCH -> "ownerReclaim"
                    CovenantTemplateId.RECURRING_ALLOWANCE -> "cancelReclaim"
                    CovenantTemplateId.CLAWBACK_VAULT -> "alarmClawback"
                    CovenantTemplateId.COLD_WHITELIST -> "spend"
                    CovenantTemplateId.HODL_TIMELOCK -> "unlock"
                    CovenantTemplateId.GOAL_PIGGY_BANK -> "unlockFallback"
                    CovenantTemplateId.DAILY_SPEND_LIMIT -> "ownerReclaim"
                }
                val destAddr = when (covenant.templateId) {
                    CovenantTemplateId.CLAWBACK_VAULT -> covenant.params.recoveryAddress.ifBlank { state.currentReceiveAddress }
                    CovenantTemplateId.COLD_WHITELIST -> covenant.params.whitelistAddresses.firstOrNull().orEmpty().ifBlank { state.currentReceiveAddress }
                    else -> state.currentReceiveAddress
                }
                val destSpk = KaspaAddress.decode(destAddr)?.toScriptPublicKey() ?: error("Invalid destination address")
                val fee = CovenantCompiler.estimateSpendFeeSompi(
                    covenant = covenant,
                    covenantUtxo = stray,
                    entryName = rescueEntry,
                    outputAmountsSompi = listOf((stray.amountSompi - CovenantCompiler.COVENANT_TX_FEE_BUFFER_SOMPI).coerceAtLeast(DUST_LIMIT_SOMPI)),
                    hasCovenantOutput = false,
                )
                val outAmt = stray.amountSompi - fee
                require(outAmt >= DUST_LIMIT_SOMPI) { "Stray UTXO balance is too small to cover Toccata network fee." }
                val dagSnap = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    network.getLiveBlockDagSnapshot(state.prefs.nodeConfig)
                }.getOrNull()
                if (dagSnap != null) {
                    updateChainTimeAnchor(
                        pastMedianTimeMs = dagSnap.pastMedianTimeMs,
                        virtualDaaScore = dagSnap.virtualDaaScore,
                    )
                }
                val liveDaa = dagSnap?.virtualDaaScore ?: 0L
                val livePmtMs = dagSnap?.pastMedianTimeMs?.takeIf { it > 0L }
                    ?: currentEstimatedPastMedianTimeMs()
                val lockTime = when (covenant.templateId) {
                    CovenantTemplateId.HODL_TIMELOCK -> if (covenant.params.unlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                        covenant.params.unlockTimeMs
                    } else {
                        covenant.params.unlockDaa
                    }
                    CovenantTemplateId.GOAL_PIGGY_BANK -> if (covenant.params.fallbackUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                        covenant.params.fallbackUnlockTimeMs
                    } else {
                        covenant.params.fallbackUnlockDaa
                    }
                    CovenantTemplateId.DAILY_SPEND_LIMIT -> if (covenant.params.fullWithdrawUnlockTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD) {
                        covenant.params.fullWithdrawUnlockTimeMs
                    } else {
                        covenant.params.fullWithdrawUnlockDaa
                    }
                    else -> 0L
                }
                val addrIndexMap = (state.receiveAddresses + state.changeAddresses).associateBy { it.address }
                val targetRescuePubKeyHex = if (
                    rescueEntry == "alarmClawback" &&
                    covenant.templateId == CovenantTemplateId.CLAWBACK_VAULT &&
                    covenant.params.alarmPubKeyHex.isNotBlank()
                ) {
                    covenant.params.alarmPubKeyHex
                } else {
                    covenant.params.ownerPubKeyHex
                }
                val prepared = vault.withDecryptedSecret(wallet.id) { mnemonic, passphrase ->
                    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                    try {
                        val hintDerived = addrIndexMap[covenant.params.ownerAddress]
                        val resolved = KaspaCrypto.resolveSigningKeyForPubKey(
                            seed = seed,
                            targetXOnlyPubKeyHex = targetRescuePubKeyHex,
                            kind = wallet.derivationKind,
                            hintPath = covenant.params.ownerDerivationPath.ifBlank { hintDerived?.derivationPath.orEmpty() },
                            hintChain = covenant.params.ownerDerivationChain.takeIf { it >= 0 } ?: hintDerived?.chain ?: -1,
                            hintIndex = covenant.params.ownerDerivationIndex.takeIf { it >= 0 } ?: hintDerived?.index ?: -1,
                        ) ?: error("Signing key not found in this wallet for pubkey ${targetRescuePubKeyHex.take(12)}...")
                        try {
                            CovenantCompiler.buildSignAndVerifySpendTxV1(
                                covenant = covenant.copy(covenantIdHex = null),
                                covenantUtxo = stray,
                                entryName = rescueEntry,
                                outputs = listOf(KaspaMassAndTx.TxOutput(outAmt, 0, destSpk, destAddr)),
                                lockTime = lockTime,
                                virtualDaaScore = liveDaa,
                                currentTimeMs = livePmtMs,
                                signerPrivateKeys = listOf(resolved.privateKey32),
                            )
                        } finally {
                            resolved.privateKey32.fill(0)
                        }
                    } finally {
                        seed.fill(0)
                    }
                }
                val txId = submitCovenantTransactionIdempotent(
                    config = state.prefs.nodeConfig,
                    submitJson = prepared.submitJson,
                    expectedTxId = prepared.txId,
                )
                launchCovenantConfirmationBarrier(
                    walletId = wallet.id,
                    canonicalKey = covenant.canonicalKey,
                    expectedNewTxId = txId,
                    expectingSettled = false,
                )
                txId
            }
        }
    }

    suspend fun exportKineticBackupToUri(uri: Uri, passphrase: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                val cs = _covenantsState.value
                val now = System.currentTimeMillis()
                val created = cs.vaultCreatedAtMs.takeIf { it > 0L } ?: wallet.createdAtMs
                val updated = cs.vaultLastUpdatedAtMs.takeIf { it > 0L } ?: now
                val payload = KineticBackupEngine.KineticVaultPayload(
                    walletId = wallet.id,
                    walletName = wallet.name,
                    walletFingerprint = KineticBackupEngine.walletFingerprint(wallet.primaryAddressMainnet),
                    createdAtMs = created,
                    lastUpdatedAtMs = updated,
                    covenants = cs.covenants,
                )
                val seedKey = if (passphrase.isNullOrBlank()) deriveSeedBackupKeyOrNull(wallet) else null
                val encryptedBytes = try {
                    KineticBackupEngine.encryptKineticBackup(
                        payload = payload,
                        seedBackupKey32 = seedKey,
                        passphrase = passphrase?.takeIf { it.isNotBlank() },
                    )
                } finally {
                    seedKey?.fill(0)
                }
                covenantRepo.writeBytesToUri(uri, encryptedBytes).getOrThrow()
                defaultKineticBackupFilename()
            }
        }

    suspend fun inspectKineticBackupUri(uri: Uri): Result<KineticBackupEngine.KineticEnvelope> =
        withContext(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val bytes = covenantRepo.readBytesFromUri(uri).getOrThrow()
                KineticBackupEngine.inspectKineticHeader(bytes)
            }
        }

    suspend fun restoreKineticBackupFromUri(
        uri: Uri,
        passphrase: String? = null,
    ): Result<CovenantMergeEngine.MergeReport> = withContext(Dispatchers.IO) {
        com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            val state = _uiState.value
            val wallet = state.activeWallet ?: error("No active wallet")
            val bytes = covenantRepo.readBytesFromUri(uri).getOrThrow()
            val seedKey = deriveSeedBackupKeyOrNull(wallet)
            try {
                val decoded = KineticBackupEngine.decryptKineticBackup(
                    rawBytes = bytes,
                    seedBackupKey32 = seedKey,
                    passphrase = passphrase,
                )
                val report = covenantStoreMutex.withLock {
                    val (saved, mergeReport, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                        walletId = wallet.id,
                        walletName = wallet.name,
                        primaryAddressMainnet = wallet.primaryAddressMainnet,
                        incoming = decoded.covenants,
                        incomingCreatedAtMs = decoded.createdAtMs,
                        seedBackupKey32 = seedKey,
                        externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                    )
                    if (_uiState.value.activeWallet?.id == wallet.id) {
                        _covenantsState.update {
                            it.copy(
                                walletId = wallet.id,
                                covenants = saved.covenants,
                                vaultCreatedAtMs = saved.createdAtMs,
                                vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                externalAutoSyncWarning = warn,
                            )
                        }
                    }
                    mergeReport
                }
                dataRepo.setCovenantsActivatedForWallet(wallet.id, true)
                syncWatchedAddressesOnLiveStream()
                refreshCovenantsLiveState()
                report
            } finally {
                seedKey?.fill(0)
            }
        }
    }

    /**
     * Scans historical wallet transactions via archival REST API (`api.kaspa.org` / `api-tn10.kaspa.org`
     * or custom node) for seed-encrypted `KKCV1` covenant payloads, merges them conflict-free, and
     * verifies every discovered covenant against live chain UTXOs.
     */
    suspend fun scanChainForKkcv1Covenants(): Result<CovenantMergeEngine.MergeReport> =
        withContext(Dispatchers.IO) {
            _covenantsState.update { it.copy(isScanningChain = true) }
            try {
                com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                    val state = _uiState.value
                    val wallet = state.activeWallet ?: error("No active wallet")
                    require(!wallet.isWatchOnly) {
                        "Automatic KKCV1 decryption requires the wallet seed. Use Restore .kinetic Backup for Watch-Only wallets."
                    }
                    val walletAddrs = (state.receiveAddresses + state.changeAddresses).map { it.address }
                    val knownCovenantAddrs = _covenantsState.value.covenants
                        .filter { it.walletId == wallet.id && it.network == state.activeNetwork }
                        .map { it.currentP2shAddress }
                        .filter { it.isNotBlank() }
                    val allAddrs = (walletAddrs + knownCovenantAddrs).distinct()
                    // Ordered strictly newest-to-oldest (by acceptingBlockBlueScore desc, blockTimeMs desc)!
                    val payloads = network.fetchHistoricalTxPayloadsWithMeta(
                        config = state.prefs.nodeConfig,
                        addresses = allAddrs,
                        limitPerAddress = 100,
                    )
                    val seedKey = deriveSeedBackupKeyOrNull(wallet)
                        ?: error("Could not derive wallet covenant backup key")
                    try {
                        val recoveredNewestFirst = ArrayList<TrackedCovenant>()
                        val recipesByKey = LinkedHashMap<String, MutableList<TrackedCovenant>>()
                        val seenTxIds = HashSet<String>()
                        fun ingestPayloads(records: List<com.kaspakinetic.wallet.core.network.HistoricalPayloadRecord>) {
                            for (rec in records) {
                                if (!seenTxIds.add(rec.txId)) continue
                                val bytes = runCatching { KaspaCrypto.hexToBytes(rec.payloadHex) }.getOrNull() ?: continue
                                val recipe = KineticBackupEngine.tryDecodeKkcv1Payload(bytes, seedKey) ?: continue
                                val reconstructed = KineticBackupEngine.reconstructFromKkcv1(recipe, wallet.id, rec.txId)
                                    ?.copy(lastUpdatedAtMs = rec.blockTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis())
                                    ?: continue
                                recoveredNewestFirst.add(reconstructed)
                                recipesByKey.getOrPut(reconstructed.canonicalKey) { ArrayList() }.add(reconstructed)
                            }
                        }
                        ingestPayloads(payloads)

                        // Follow-up hop on newly discovered stateful P2SH addresses so state-transition
                        // transactions (Check-In, Unvault, Daily Spend) that spent P2SH -> P2SH without
                        // touching wallet P2PK addresses are also recovered!
                        val scannedSet = allAddrs.toMutableSet()
                        val discoveredP2sh = recoveredNewestFirst
                            .filter { it.templateId.isStatefulKip20 && it.currentP2shAddress.isNotBlank() }
                            .map { it.currentP2shAddress }
                            .filter { it !in scannedSet }
                            .distinct()
                        if (discoveredP2sh.isNotEmpty()) {
                            val hopPayloads = network.fetchHistoricalTxPayloadsWithMeta(
                                config = state.prefs.nodeConfig,
                                addresses = discoveredP2sh,
                                limitPerAddress = 50,
                            )
                            ingestPayloads(hopPayloads)
                        }
                        // Ensure every canonicalKey's candidate list is sorted strictly newest-to-oldest
                        for (list in recipesByKey.values) {
                            list.sortByDescending { it.lastUpdatedAtMs }
                        }
                        val sortedNewestFirst = recoveredNewestFirst.sortedByDescending { it.lastUpdatedAtMs }

                        // Merge oldest-to-newest so newest state wins in combinePair, AND pass full newest-first
                        // recipe history into refreshCovenantsLiveStateSuspend for exact live UTXO hash matching!
                        val report = covenantStoreMutex.withLock {
                            val (saved, mergeReport, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                                walletId = wallet.id,
                                walletName = wallet.name,
                                primaryAddressMainnet = wallet.primaryAddressMainnet,
                                incoming = sortedNewestFirst.asReversed(),
                                seedBackupKey32 = seedKey,
                                externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                            )
                            if (_uiState.value.activeWallet?.id == wallet.id) {
                                _covenantsState.update {
                                    it.copy(
                                        walletId = wallet.id,
                                        covenants = saved.covenants,
                                        vaultCreatedAtMs = saved.createdAtMs,
                                        vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                        externalAutoSyncWarning = warn,
                                    )
                                }
                            }
                            mergeReport
                        }
                        syncWatchedAddressesOnLiveStream()
                        refreshCovenantsLiveStateSuspend(candidateRecipesByKey = recipesByKey)
                        report.copy(discoveredCount = recipesByKey.size)
                    } finally {
                        seedKey.fill(0)
                    }
                }
            } finally {
                _covenantsState.update { it.copy(isScanningChain = false) }
            }
        }

    suspend fun importSharedPassJson(rawJson: String): Result<TrackedCovenant> = withContext(Dispatchers.IO) {
        com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            val state = _uiState.value
            val wallet = state.activeWallet ?: error("No active wallet")
            val pass = KineticBackupEngine.parseSharedPassJson(rawJson)
            val tracked = KineticBackupEngine.toTrackedCovenant(pass, wallet.id)
            val seedKey = deriveSeedBackupKeyOrNull(wallet)
            try {
                covenantStoreMutex.withLock {
                    val (saved, _, warn) = covenantRepo.mergeIncomingAndAutoSnapshot(
                        walletId = wallet.id,
                        walletName = wallet.name,
                        primaryAddressMainnet = wallet.primaryAddressMainnet,
                        incoming = listOf(tracked),
                        seedBackupKey32 = seedKey,
                        externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                    )
                    if (_uiState.value.activeWallet?.id == wallet.id) {
                        _covenantsState.update {
                            it.copy(
                                walletId = wallet.id,
                                covenants = saved.covenants,
                                vaultCreatedAtMs = saved.createdAtMs,
                                vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                externalAutoSyncWarning = warn,
                            )
                        }
                    }
                }
                syncWatchedAddressesOnLiveStream()
                refreshCovenantsLiveState()
                tracked
            } finally {
                seedKey?.fill(0)
            }
        }
    }

    /**
     * Permanently removes a single imported shared pass (`isSharedPassImport == true`) from the active
     * wallet's local device list. Never affects on-chain funds or covenants owned by this wallet.
     */
    suspend fun removeSharedCovenant(canonicalKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        covenantActionMutex.withLock {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val state = _uiState.value
                val wallet = state.activeWallet ?: error("No active wallet")
                val seedKey = deriveSeedBackupKeyOrNull(wallet)
                try {
                    covenantStoreMutex.withLock {
                        val currentDisk = covenantRepo.loadVault(wallet.id)
                        val existing = currentDisk.covenants.ifEmpty { _covenantsState.value.covenants }
                        val target = existing.firstOrNull { it.canonicalKey == canonicalKey }
                            ?: error("Shared pass not found")
                        require(target.isSharedPassImport) { "Only imported shared passes can be removed from the Shared list." }

                        val remaining = filterRemoveSharedPass(
                            covenants = existing,
                            walletId = wallet.id,
                            canonicalKey = canonicalKey,
                        )
                        val (saved, warn) = covenantRepo.saveVaultAndAutoSnapshot(
                            walletId = wallet.id,
                            walletName = wallet.name,
                            primaryAddressMainnet = wallet.primaryAddressMainnet,
                            covenants = remaining,
                            existingCreatedAtMs = currentDisk.createdAtMs,
                            seedBackupKey32 = seedKey,
                            externalAutoSyncUri = _uiState.value.prefs.covenantsAutoSyncUriForWallet(wallet.id),
                            touchUpdatedTimestamp = true,
                        )
                        if (_uiState.value.activeWallet?.id == wallet.id) {
                            _covenantsState.update {
                                it.copy(
                                    walletId = wallet.id,
                                    covenants = saved.covenants,
                                    vaultCreatedAtMs = saved.createdAtMs,
                                    vaultLastUpdatedAtMs = saved.lastUpdatedAtMs,
                                    confirmingCovenantKeys = it.confirmingCovenantKeys - canonicalKey,
                                    externalAutoSyncWarning = warn ?: it.externalAutoSyncWarning,
                                )
                            }
                        }
                    }
                    syncWatchedAddressesOnLiveStream()
                } finally {
                    seedKey?.fill(0)
                }
            }
        }
    }

    fun defaultKpassFilename(covenant: TrackedCovenant): String {
        val kind = when (covenant.templateId) {
            CovenantTemplateId.DEAD_MANS_SWITCH -> KineticBackupEngine.SharedPassKind.HEIR_CLAIM_PASS
            CovenantTemplateId.RECURRING_ALLOWANCE -> KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS
            else -> error("Only Dead Man's Switch and Recurring Allowance / Subscription can be shared via .kpass.")
        }
        return KineticBackupEngine.buildDefaultKpassFilename(
            kind = kind,
            label = covenant.label,
            createdAtMs = covenant.createdAtMs,
        )
    }

    suspend fun exportKpassToUri(covenant: TrackedCovenant, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val rawJson = buildSharedPassForCovenant(covenant).getOrThrow()
                covenantRepo.writeBytesToUri(uri, rawJson.toByteArray(Charsets.UTF_8)).getOrThrow()
                defaultKpassFilename(covenant)
            }
        }

    suspend fun createShareableKpassCacheFile(covenant: TrackedCovenant): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
                val rawJson = buildSharedPassForCovenant(covenant).getOrThrow()
                val fileName = defaultKpassFilename(covenant)
                val dir = java.io.File(getApplication<Application>().cacheDir, "kpass").apply { mkdirs() }
                val file = java.io.File(dir, fileName)
                file.writeBytes(rawJson.toByteArray(Charsets.UTF_8))
                file
            }
        }

    suspend fun importKpassFromUri(uri: Uri): Result<TrackedCovenant> = withContext(Dispatchers.IO) {
        com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            val bytes = covenantRepo.readBytesFromUri(uri).getOrThrow()
            importSharedPassJson(bytes.toString(Charsets.UTF_8)).getOrThrow()
        }
    }

    suspend fun buildSharedPassForCovenant(
        covenant: TrackedCovenant,
    ): Result<String> = withContext(Dispatchers.Default) {
        com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation {
            val kind = when (covenant.templateId) {
                CovenantTemplateId.DEAD_MANS_SWITCH -> KineticBackupEngine.SharedPassKind.HEIR_CLAIM_PASS
                CovenantTemplateId.RECURRING_ALLOWANCE -> KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS
                else -> error("Only Dead Man's Switch and Recurring Allowance / Subscription can be shared via .kpass.")
            }
            val pass = KineticBackupEngine.SharedCovenantPass(
                kind = kind,
                network = covenant.network,
                templateId = covenant.templateId,
                label = covenant.label,
                covenantIdHex = covenant.covenantIdHex,
                p2shAddress = covenant.currentP2shAddress,
                redeemScriptHex = covenant.currentRedeemScriptHex,
                abiArtifact = covenant.abiArtifact,
                params = covenant.params,
            )
            KineticBackupEngine.exportSharedPassJson(pass)
        }
    }

    companion object {
        /**
         * Pure helper for removing a single imported shared pass from a wallet's covenant list
         * without touching owned covenants or other wallets' items.
         */
        fun filterRemoveSharedPass(
            covenants: List<TrackedCovenant>,
            walletId: String,
            canonicalKey: String,
        ): List<TrackedCovenant> = covenants.filterNot { cov ->
            cov.walletId == walletId && cov.canonicalKey == canonicalKey && cov.isSharedPassImport
        }
        fun isValidRealTxId(txId: String): Boolean =
            txId.length == 64 && txId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /**
         * Monotonic wallet/network switch guard ensuring late-arriving async responses from a
         * previous wallet or network can NEVER mutate the newly active wallet's UI state or cache.
         */
        fun isScopeValid(
            capturedEpoch: Long,
            currentEpoch: Long,
            capturedWalletId: String,
            activeWalletId: String?,
            capturedNetwork: KaspaNetwork,
            activeNetwork: KaspaNetwork,
        ): Boolean =
            capturedEpoch == currentEpoch &&
                activeWalletId == capturedWalletId &&
                activeNetwork == capturedNetwork

        /**
         * Strictly filters transactions to those that actually belong to [ownedAddresses],
         * purging any cross-wallet bleed or `Received 0.00 KAS` ghost records.
         */
        fun filterTransactionsForWallet(
            txs: List<WalletTx>,
            ownedAddresses: Set<String>,
        ): List<WalletTx> {
            if (ownedAddresses.isEmpty()) return emptyList()
            return txs.filter { tx ->
                if (tx.transactionId.startsWith("pending_")) return@filter false
                val hasIoDetails = tx.inputs.isNotEmpty() || tx.outputs.isNotEmpty()
                if (hasIoDetails) {
                    tx.inputs.any { it.address != null && it.address in ownedAddresses } ||
                        tx.outputs.any { it.address in ownedAddresses }
                } else {
                    // Optimistic/pending placeholder: reject 0-sompi ghosts and foreign receive targets
                    if (tx.netAmountSompi <= 0L && tx.feeSompi <= 0L) return@filter false
                    if (tx.direction == TxDirection.RECEIVED &&
                        tx.counterpartyAddress.isNotBlank() &&
                        tx.counterpartyAddress !in ownedAddresses
                    ) {
                        return@filter false
                    }
                    true
                }
            }
        }

        /**
         * Returns distinct valid 64-char UTXO transaction IDs that do NOT yet have a confirmed
         * full transaction record (with inputs/outputs) in [existingTxs].
         */
        fun findUnindexedUtxoTxIds(
            utxos: List<UtxoItem>,
            existingTxs: List<WalletTx>,
        ): List<String> {
            val confirmedFullIds = existingTxs
                .filter { it.isAccepted && (it.inputs.isNotEmpty() || it.outputs.isNotEmpty()) }
                .mapTo(HashSet()) { it.transactionId }
            return utxos
                .map { it.transactionId }
                .filter { isValidRealTxId(it) && it !in confirmedFullIds }
                .distinct()
        }

        /**
         * Synthesizes immediate incoming [WalletTx] entries for live wallet UTXOs whose real
         * 64-char `transactionId` is missing from [existingTxIds] (e.g. right after switching back
         * to a receiving wallet before the REST indexer finishes indexing the block).
         */
        fun synthesizeMissingUtxoTxs(
            utxos: List<UtxoItem>,
            existingTxIds: Set<String>,
            nowMs: Long,
        ): List<WalletTx> {
            val out = mutableListOf<WalletTx>()
            utxos.groupBy { it.transactionId }.forEach { (txId, utxoGroup) ->
                if (!isValidRealTxId(txId) || txId in existingTxIds) return@forEach
                val sumSompi = utxoGroup.sumOf { it.amountSompi }
                if (sumSompi <= 0L) return@forEach
                out.add(
                    WalletTx(
                        transactionId = txId,
                        blockTimeMs = nowMs,
                        isAccepted = false,
                        direction = TxDirection.RECEIVED,
                        netAmountSompi = sumSompi,
                        feeSompi = 0L,
                        counterpartyAddress = utxoGroup.first().address,
                    ),
                )
            }
            return out
        }

        /**
         * Safely merges newly fetched or optimistically detected transactions into [existing]:
         * - NEVER wipes an existing non-empty list if [incoming] is empty.
         * - Upgrades optimistic/pending placeholders once full confirmed records arrive.
         * - Never downgrades an already confirmed full record with a sparser placeholder.
         * - Automatically purges any legacy synthetic `pending_*` entries.
         */
        fun mergeTransactions(existing: List<WalletTx>, incoming: List<WalletTx>): List<WalletTx> {
            val cleanedExisting = existing.filterNot { it.transactionId.startsWith("pending_") }
            if (incoming.isEmpty()) return cleanedExisting
            val merged = LinkedHashMap<String, WalletTx>(cleanedExisting.size + incoming.size)
            for (tx in cleanedExisting) {
                merged[tx.transactionId] = tx
            }
            for (tx in incoming) {
                if (tx.transactionId.startsWith("pending_")) continue
                val prev = merged[tx.transactionId]
                merged[tx.transactionId] = when {
                    prev == null -> tx
                    isRicherTx(candidate = tx, current = prev) -> tx
                    else -> prev
                }
            }
            return merged.values.sortedWith(
                compareByDescending<WalletTx> { it.blockTimeMs }.thenBy { it.transactionId },
            )
        }

        private fun isRicherTx(candidate: WalletTx, current: WalletTx): Boolean {
            val candFull = candidate.inputs.isNotEmpty() || candidate.outputs.isNotEmpty()
            val currFull = current.inputs.isNotEmpty() || current.outputs.isNotEmpty()
            if (candidate.isAccepted && !current.isAccepted) return true
            if (!candidate.isAccepted && current.isAccepted && currFull) return false
            if (candFull && !currFull) return true
            if (!candFull && currFull) return false
            if ((candidate.acceptingBlockBlueScore ?: 0L) > (current.acceptingBlockBlueScore ?: 0L)) return true
            return candFull
        }

        /**
         * Synthesizes immediate pending [WalletTx] entries directly from a live UTXO diff ONLY
         * when a real 64-char hex transactionId is available (never creating broken synthetic
         * `pending_spend_*` rows with 0 inputs / 0 outputs).
         */
        fun synthesizeDetectedTxsFromUtxoDiff(
            prevUtxos: List<UtxoItem>,
            newUtxos: List<UtxoItem>,
            existingTxIds: Set<String>,
            nowMs: Long,
        ): List<WalletTx> {
            val prevKeys = prevUtxos.mapTo(HashSet()) { it.outpointKey }
            val newKeys = newUtxos.mapTo(HashSet()) { it.outpointKey }
            val added = newUtxos.filter { it.outpointKey !in prevKeys }
            val spent = prevUtxos.filter { it.outpointKey !in newKeys }
            if (added.isEmpty() && spent.isEmpty()) return emptyList()

            val out = mutableListOf<WalletTx>()
            if (spent.isEmpty() && added.isNotEmpty()) {
                // Pure incoming receive(s) grouped by real transactionId
                added.groupBy { it.transactionId }.forEach { (txId, utxoGroup) ->
                    if (isValidRealTxId(txId) && txId !in existingTxIds) {
                        out.add(
                            WalletTx(
                                transactionId = txId,
                                blockTimeMs = nowMs,
                                isAccepted = false,
                                direction = TxDirection.RECEIVED,
                                netAmountSompi = utxoGroup.sumOf { it.amountSompi },
                                feeSompi = 0L,
                                counterpartyAddress = utxoGroup.first().address,
                            ),
                        )
                    }
                }
            } else if (spent.isNotEmpty()) {
                // Only synthesize if we have a real 64-char txId from a change/self-transfer UTXO;
                // never fabricate synthetic `pending_spend_*` entries!
                val detectedTxId = added.firstOrNull { isValidRealTxId(it.transactionId) }?.transactionId
                    ?: return emptyList()
                if (detectedTxId !in existingTxIds) {
                    val spentSum = spent.sumOf { it.amountSompi }
                    val addedSum = added.sumOf { it.amountSompi }
                    val netSpent = (spentSum - addedSum).coerceAtLeast(0L)
                    val isSelf = netSpent < 100_000L && addedSum > 0L
                    out.add(
                        WalletTx(
                            transactionId = detectedTxId,
                            blockTimeMs = nowMs,
                            isAccepted = false,
                            direction = if (isSelf) TxDirection.SELF_TRANSFER else TxDirection.SENT,
                            netAmountSompi = if (isSelf) addedSum else netSpent,
                            feeSompi = 0L,
                            counterpartyAddress = (added.firstOrNull() ?: spent.first()).address,
                        ),
                    )
                }
            }
            return out
        }
    }
}
