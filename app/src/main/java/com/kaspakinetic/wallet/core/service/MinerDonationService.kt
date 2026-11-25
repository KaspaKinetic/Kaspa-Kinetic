package com.kaspakinetic.wallet.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kaspakinetic.wallet.MainActivity
import com.kaspakinetic.wallet.R
import com.kaspakinetic.wallet.core.crypto.Bip39
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.DerivedAddress
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.MinerDonationProgress
import com.kaspakinetic.wallet.core.model.NodeConfig
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.model.WalletRecord
import com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer
import com.kaspakinetic.wallet.core.network.KaspaNetworkGateway
import com.kaspakinetic.wallet.core.network.OkHttpKaspaNetworkGateway
import com.kaspakinetic.wallet.core.network.RateLimitedException
import com.kaspakinetic.wallet.core.security.SecureVault
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val MAX_MINER_DONATION_SPLITS: Int = 8_640_000

data class MinerDonationRequest(
    val wallet: WalletRecord,
    val nodeConfig: NodeConfig,
    val selfAddress: String,
    val derivedAddresses: List<DerivedAddress>,
    val spendableUtxos: List<UtxoItem>,
    val totalDonationSompi: Long,
    val splits: Int,
    val minFeeRate: Double,
    val runInBackground: Boolean,
    val notificationTitle: String,
    val notificationStopLabel: String,
    val networkGateway: KaspaNetworkGateway? = null,
    val onSplitCompleted: (
        txId: String,
        feeSompi: Long,
        spentOutpoints: Set<String>,
        nextChangeUtxo: UtxoItem,
    ) -> Unit = { _, _, _, _ -> },
    val onFinishedRefresh: () -> Unit = {},
)

/**
 * Process-wide coordinator for sequential chained Miner Donation splits (up to 8,640,000 blocks).
 * Works seamlessly both in-app and via [MinerDonationService] foreground notification.
 */
object MinerDonationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<MinerDonationProgress?>(null)
    val progress: StateFlow<MinerDonationProgress?> = _progress.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRequest: MinerDonationRequest? = null

    @Volatile
    private var onProgressChangedListener: ((MinerDonationProgress?) -> Unit)? = null

    internal fun setServiceListener(listener: ((MinerDonationProgress?) -> Unit)?) {
        onProgressChangedListener = listener
    }

    internal fun currentNotificationLabels(): Pair<String, String> {
        val req = activeRequest
        return (req?.notificationTitle ?: "Donate to Kaspa Miners") to (req?.notificationStopLabel ?: "Stop")
    }

    fun start(context: Context, request: MinerDonationRequest) {
        val safeSplits = request.splits.coerceIn(1, MAX_MINER_DONATION_SPLITS)
        val feePerSplit = request.totalDonationSompi / safeSplits
        if (feePerSplit < 1_000L) return
        val totalAvail = request.spendableUtxos.sumOf { it.amountSompi }
        if (totalAvail - request.totalDonationSompi < DUST_LIMIT_SOMPI) return

        activeJob?.cancel()
        activeRequest = request

        val initial = MinerDonationProgress(
            totalSplits = safeSplits,
            completedSplits = 0,
            totalDonatedSompi = 0L,
            targetTotalSompi = request.totalDonationSompi,
            txIds = emptyList(),
            isRunning = true,
            isCancelled = false,
        )
        emitProgress(initial)

        if (request.runInBackground) {
            runCatching {
                val intent = Intent(context.applicationContext, MinerDonationService::class.java).apply {
                    action = MinerDonationService.ACTION_START
                }
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        val appContext = context.applicationContext
        val vault = SecureVault(appContext)
        val network: KaspaNetworkGateway = request.networkGateway ?: OkHttpKaspaNetworkGateway()

        activeJob = scope.launch {
            val selfDerived = request.derivedAddresses.firstOrNull { it.address == request.selfAddress }
            val selfSpkHex = KaspaAddress.decode(request.selfAddress)
                ?.toScriptPublicKey()
                ?.let { KaspaCrypto.bytesToHex(it) }
                ?: run {
                    emitProgress(initial.copy(isRunning = false, errorMessage = "Invalid wallet address"))
                    return@launch
                }

            var rollingUtxos = request.spendableUtxos
            val recentTxIds = ArrayDeque<String>(200)
            var donatedSoFar = 0L
            val addrMap = request.derivedAddresses.associateBy { it.address }

            try {
                for (step in 1..safeSplits) {
                    if (!isActive) break
                    val remainingSplits = safeSplits - step + 1
                    val sliceFee = if (remainingSplits == 1) {
                        (request.totalDonationSompi - donatedSoFar).coerceAtLeast(1_000L)
                    } else {
                        feePerSplit
                    }

                    val prepared = KaspaMassAndTx.prepareMinerDonationSplit(
                        network = request.nodeConfig.network,
                        availableUtxos = rollingUtxos,
                        selfAddress = request.selfAddress,
                        donationFeeSompi = sliceFee,
                        minNetworkFeeRate = request.minFeeRate,
                    )

                    val signed = vault.withDecryptedSecret(request.wallet.id) { mnemonic, passphrase ->
                        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
                        try {
                            KaspaMassAndTx.signPreparedTransaction(prepared) { addr ->
                                val d = addrMap[addr] ?: selfDerived
                                if (d != null) {
                                    KaspaCrypto.derivePrivateKeyFromSeed(seed, d.chain, d.index, request.wallet.derivationKind)
                                } else {
                                    KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, request.wallet.derivationKind)
                                }
                            }
                        } finally {
                            seed.fill(0)
                        }
                    }

                    val txId = submitWithBackoff(
                        network = network,
                        nodeConfig = request.nodeConfig,
                        submitJson = signed.submitJson,
                        fallbackTxId = signed.transactionId,
                    )
                    if (recentTxIds.size >= 200) {
                        recentTxIds.removeFirst()
                    }
                    recentTxIds.addLast(txId)
                    donatedSoFar += prepared.feeSompi

                    val nextChangeUtxo = UtxoItem(
                        address = request.selfAddress,
                        transactionId = txId,
                        index = 0,
                        amountSompi = prepared.amountSompi,
                        scriptPublicKeyHex = selfSpkHex,
                        scriptPublicKeyVersion = 0,
                        blockDaaScore = 0L,
                        isCoinbase = false,
                    )
                    val spentKeys = prepared.selectedUtxos.map { it.outpointKey }.toSet()
                    rollingUtxos = rollingUtxos.filterNot { it.outpointKey in spentKeys } + nextChangeUtxo

                    request.onSplitCompleted(txId, prepared.feeSompi, spentKeys, nextChangeUtxo)

                    emitProgress(
                        MinerDonationProgress(
                            totalSplits = safeSplits,
                            completedSplits = step,
                            totalDonatedSompi = donatedSoFar,
                            targetTotalSompi = request.totalDonationSompi,
                            txIds = recentTxIds.toList(),
                            isRunning = step < safeSplits,
                            isCancelled = false,
                        ),
                    )

                    if (step < safeSplits) {
                        // Clean pacing: 320ms over persistent gRPC, or 1,200ms on REST fallback to avoid 429s
                        val paceMs = if (network.isGrpcActive(request.nodeConfig)) 320L else 1_200L
                        delay(paceMs)
                    }
                }
            } catch (ce: CancellationException) {
                _progress.update { p ->
                    p?.copy(isRunning = false, isCancelled = true)
                }
                onProgressChangedListener?.invoke(_progress.value)
            } catch (e: Exception) {
                _progress.update { p ->
                    p?.copy(isRunning = false, errorMessage = KaspaErrorSanitizer.sanitize(e.message ?: "Broadcast interrupted"))
                }
                onProgressChangedListener?.invoke(_progress.value)
            } finally {
                request.onFinishedRefresh()
                if (request.runInBackground) {
                    runCatching {
                        val stopIntent = Intent(appContext, MinerDonationService::class.java).apply {
                            action = MinerDonationService.ACTION_STOP_SERVICE_ONLY
                        }
                        appContext.startService(stopIntent)
                    }
                }
            }
        }
    }

    private suspend fun submitWithBackoff(
        network: KaspaNetworkGateway,
        nodeConfig: NodeConfig,
        submitJson: String,
        fallbackTxId: String,
    ): String {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 5) {
            try {
                return network.submitTransaction(nodeConfig, submitJson).ifBlank { fallbackTxId }
            } catch (ce: CancellationException) {
                throw ce
            } catch (rl: RateLimitedException) {
                lastError = rl
                attempt++
                if (attempt >= 5) throw rl
                // Try promoting back to gRPC right away so we escape REST 429s cleanly
                runCatching { network.verifyOrReconnectGrpc(nodeConfig, forceRebuild = true) }
                val waitMs = if (network.isGrpcActive(nodeConfig)) {
                    500L
                } else {
                    (rl.retryAfterSeconds.coerceIn(2L, 30L)) * 1_000L
                }
                delay(waitMs)
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                // If the previous attempt actually reached kaspad before a 5G socket drop,
                // kaspad returns "already in the mempool" — treat this split as accepted!
                if (msg.contains("already in the mempool", ignoreCase = true) ||
                    msg.contains("already accepted", ignoreCase = true)
                ) {
                    return fallbackTxId
                }
                // Do not retry permanent consensus rejections
                val isPermanent = msg.contains("double spend", ignoreCase = true) ||
                    msg.contains("orphan", ignoreCase = true) ||
                    msg.contains("dust", ignoreCase = true) ||
                    msg.contains("mass", ignoreCase = true)
                if (isPermanent || attempt >= 4) throw e
                attempt++
                delay(800L * (1L shl (attempt - 1)).coerceAtMost(8_000L))
                // Rebuild gRPC channel cleanly if stream dropped mid-batch
                runCatching { network.verifyOrReconnectGrpc(nodeConfig, forceRebuild = true) }
            }
        }
        throw (lastError ?: IllegalStateException("Broadcast failed after retries"))
    }

    fun cancel(context: Context? = null) {
        activeJob?.cancel()
        activeJob = null
        _progress.update { p ->
            p?.copy(isRunning = false, isCancelled = true)
        }
        onProgressChangedListener?.invoke(_progress.value)
        if (context != null) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, MinerDonationService::class.java))
            }
        }
    }

    fun dismiss(context: Context? = null) {
        if (_progress.value?.isRunning == true) {
            cancel(context)
        }
        emitProgress(null)
    }

    private fun emitProgress(p: MinerDonationProgress?) {
        _progress.value = p
        onProgressChangedListener?.invoke(p)
    }
}

/**
 * Foreground Service keeping long-running Miner Donation splits alive in the background with a
 * live progress notification and an interactive Stop button.
 */
class MinerDonationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MinerDonationController.cancel(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE_ONLY -> {
                MinerDonationController.setServiceListener(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val current = MinerDonationController.progress.value
                val notif = buildNotification(current)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }

                var lastUpdateMs = 0L
                MinerDonationController.setServiceListener { progress ->
                    val now = System.currentTimeMillis()
                    if (progress == null || !progress.isRunning) {
                        MinerDonationController.setServiceListener(null)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (now - lastUpdateMs >= 800L || progress.completedSplits == progress.totalSplits) {
                        lastUpdateMs = now
                        val mgr = getSystemService(NotificationManager::class.java)
                        mgr?.notify(NOTIFICATION_ID, buildNotification(progress))
                    }
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        MinerDonationController.setServiceListener(null)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Miner Donations",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Live progress for background Kaspa miner donation transactions"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: MinerDonationProgress?): Notification {
        val (title, stopLabel) = MinerDonationController.currentNotificationLabels()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_MINER_DONATION, true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, MinerDonationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val completed = progress?.completedSplits ?: 0
        val total = progress?.totalSplits ?: 1
        val donatedText = KasFormat.formatKas(progress?.totalDonatedSompi ?: 0L)
        val targetText = KasFormat.formatKas(progress?.targetTotalSompi ?: 0L)
        val contentText = "$completed / $total • $donatedText / $targetText KAS"

        // Scale large split counts cleanly into 0..10000 for Android ProgressBar
        val scaledMax = 10_000
        val scaledVal = if (total > 0) ((completed.toDouble() / total.toDouble()) * scaledMax).toInt().coerceIn(0, scaledMax) else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(scaledMax, scaledVal, false)
            .setContentIntent(openPending)
            .addAction(0, stopLabel, stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "miner_donation_channel"
        const val NOTIFICATION_ID = 4411
        const val ACTION_START = "com.kaspakinetic.wallet.action.START_MINER_DONATION"
        const val ACTION_STOP = "com.kaspakinetic.wallet.action.STOP_MINER_DONATION"
        const val ACTION_STOP_SERVICE_ONLY = "com.kaspakinetic.wallet.action.STOP_SERVICE_ONLY"
        const val EXTRA_OPEN_MINER_DONATION = "open_miner_donation"
    }
}
