package com.kaspakinetic.wallet.core.network

import android.content.Context
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation
import com.kaspakinetic.wallet.core.model.FeeEstimate
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.NodeConfig
import com.kaspakinetic.wallet.core.model.NodeConnectionMode
import com.kaspakinetic.wallet.core.model.NodeEndpoint
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.TxInputSummary
import com.kaspakinetic.wallet.core.model.TxOutputSummary
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.model.WalletTx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RateLimitedException(
    val retryAfterSeconds: Long,
    message: String = "Rate limited by API (HTTP 429). Retry in ${retryAfterSeconds}s.",
) : IOException(message)

interface LiveStreamHandle {
    suspend fun setWatchedAddresses(addresses: List<String>, forceResubscribe: Boolean = false)
    suspend fun updateWatchedAddresses(addresses: List<String>)
    fun close()
}

data class HistoricalPayloadRecord(
    val txId: String,
    val payloadHex: String,
    val blockTimeMs: Long = 0L,
    val acceptingBlockBlueScore: Long = 0L,
)

interface BlockAddedStreamHandle {
    suspend fun close()
}

/**
 * Pure Kaspa L1 network gateway abstraction.
 * Routes live node operations (`GetServerInfo`, `GetUtxosByAddresses`, `GetFeeEstimate`,
 * `SubmitTransaction`, `NotifyVirtualDaaScoreChanged`, `NotifyUtxosChanged`) over a persistent
 * Kaspium-style bidirectional `kaspad` gRPC stream, using the REST indexer strictly on-demand
 * for historical transaction records with deduplication and `429` exponential backoff.
 */
interface KaspaNetworkGateway {
    fun isGrpcActive(config: NodeConfig): Boolean = false
    fun grpcLastActivityAgeMs(config: NodeConfig): Long = Long.MAX_VALUE
    fun isRestRateLimited(): Boolean = false
    fun clearRestBackoffOnUserRefresh() {}
    fun activePoolCount(config: NodeConfig): Int = 0
    fun activeGrpcEndpoint(config: NodeConfig): NodeEndpoint? = null
    fun onNetworkPathChanged() {}
    suspend fun refreshNodePoolIfNeeded(config: NodeConfig, force: Boolean = false): Int = 0
    suspend fun verifyOrReconnectGrpc(config: NodeConfig, forceRebuild: Boolean = false): NodeHealth? = null

    suspend fun checkNodeHealth(config: NodeConfig): NodeHealth
    suspend fun getLiveBlockDagScore(config: NodeConfig): Pair<Long, Long>?
    suspend fun getLiveBlockDagSnapshot(config: NodeConfig): KaspadGrpcClient.LiveDagSnapshot? = null
    suspend fun openLiveNodeStream(
        config: NodeConfig,
        addresses: List<String>,
        forceRebuild: Boolean = false,
        onDaaScore: (Long) -> Unit,
        onUtxosChanged: (added: List<UtxoItem>, removed: List<KaspadProtoCodec.RemovedUtxoRef>) -> Unit,
        onConnectionChanged: (Boolean) -> Unit,
    ): LiveStreamHandle?

    suspend fun openBlockAddedStream(
        config: NodeConfig,
        onBlockAdded: (KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit,
    ): BlockAddedStreamHandle? = null

    suspend fun getActiveAddresses(config: NodeConfig, addresses: List<String>): Set<String>
    suspend fun getUtxos(config: NodeConfig, addresses: List<String>): List<UtxoItem>
    /** Strictly queries the pinned live gRPC node (zero REST mixing) for covenant UTXO reads & confirmation barriers. */
    suspend fun getUtxosGrpcOnly(config: NodeConfig, addresses: List<String>): List<UtxoItem> = getUtxos(config, addresses)
    suspend fun getUtxosByCovenantIds(config: NodeConfig, covenantIds: List<String>): List<UtxoItem> = emptyList()
    /**
     * Strict variant of [getUtxosByCovenantIds] that throws on gRPC transport error / timeout instead
     * of returning `emptyList()`, so callers NEVER misinterpret a transient RPC failure as `Settled (0.00 KAS)`.
     */
    suspend fun getUtxosByCovenantIdsStrict(config: NodeConfig, covenantIds: List<String>): List<UtxoItem> =
        getUtxosByCovenantIds(config, covenantIds)
    suspend fun fetchHistoricalTxPayloads(
        config: NodeConfig,
        addresses: List<String>,
        limitPerAddress: Int = 100,
    ): List<Pair<String, String>> = emptyList()
    suspend fun fetchHistoricalTxPayloadsWithMeta(
        config: NodeConfig,
        addresses: List<String>,
        limitPerAddress: Int = 100,
    ): List<HistoricalPayloadRecord> = fetchHistoricalTxPayloads(config, addresses, limitPerAddress)
        .map { (txId, payloadHex) -> HistoricalPayloadRecord(txId = txId, payloadHex = payloadHex) }
    /**
     * Fetches the first output amount (in sompi) of [txId] from the REST indexer, used to self-heal
     * live stateful `Daily Spend Limit` vaults whose State-1+ spend tx predated `KKCV1` transition payloads.
     */
    suspend fun fetchTxFirstOutputAmountSompi(config: NodeConfig, txId: String): Long? = null
    suspend fun getTransactions(
        config: NodeConfig,
        addresses: List<String>,
        walletAddressSet: Set<String>,
        limitPerAddress: Int = 50,
    ): List<WalletTx>

    suspend fun getTransactionById(
        config: NodeConfig,
        transactionId: String,
        walletAddressSet: Set<String>,
    ): WalletTx?

    suspend fun getFeeEstimate(config: NodeConfig): FeeEstimate
    suspend fun submitTransaction(config: NodeConfig, submitJson: String): String
    suspend fun getSpotPrice(currency: FiatCurrency, privacyKasOnly: Boolean, isTestnet: Boolean): SpotPrice?
    fun closeConnections() {}
}

/**
 * Pure, testable mempool idempotency helper for Kaspa L1 transaction broadcasts:
 * if a node reports that the exact transaction ID we just built and broadcast is
 * `"already in the mempool"`, treat it as accepted so the UI transitions smoothly into
 * `"Confirming on Kaspa L1..."` instead of showing a misleading red error banner.
 */
object KaspaMempoolIdempotency {
    private val HEX_TXID_REGEX = Regex("\\b[0-9a-fA-F]{64}\\b")

    fun extractAlreadyInMempoolTxId(errorMessage: String?): String? {
        val msg = errorMessage ?: return null
        if (!msg.contains("already in the mempool", ignoreCase = true)) return null
        return HEX_TXID_REGEX.find(msg)?.value?.lowercase()
    }

    fun isAlreadyInMempoolForTxId(errorMessage: String?, expectedTxId: String): Boolean {
        val msg = errorMessage ?: return false
        if (!msg.contains("already in the mempool", ignoreCase = true)) return false
        val cleanExpected = expectedTxId.trim().lowercase()
        val mentionedTxIds = HEX_TXID_REGEX.findAll(msg).map { it.value.lowercase() }.toSet()
        if (cleanExpected.length == 64 && mentionedTxIds.isNotEmpty()) {
            return cleanExpected in mentionedTxIds
        }
        return true
    }

    suspend fun resolveIdempotentSubmit(
        expectedTxId: String,
        submitBlock: suspend () -> String,
    ): String {
        return try {
            submitBlock().ifBlank { expectedTxId }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (isAlreadyInMempoolForTxId(t.message, expectedTxId)) {
                expectedTxId
            } else {
                throw t
            }
        }
    }
}

class OkHttpKaspaNetworkGateway(
    appContext: Context? = null,
    private val nodePool: KaspaNodePool = KaspaNodePool(appContext),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : KaspaNetworkGateway {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Persistent gRPC client pinned to the active endpoint for the current network + mode
    private val grpcLock = Any()
    @Volatile private var activeGrpcScopeKey: String? = null
    @Volatile private var activeEndpointValue: NodeEndpoint? = null
    @Volatile private var activeGrpcClient: KaspadGrpcClient? = null

    // Shared REST 429 exponential backoff state (respects Retry-After!)
    private val restBackoffUntilMs = AtomicLong(0L)
    private val consecutive429s = AtomicInteger(0)

    // In-flight deduplication for on-demand REST GET queries
    private val inflightRestGets = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()

    private fun scopeKeyFor(config: NodeConfig, endpoint: NodeEndpoint): String =
        "${config.network.id}|${config.effectiveConnectionMode.name}|${endpoint.key}"

    private fun fixedEndpointFor(config: NodeConfig): NodeEndpoint {
        val (host, port, tls) = config.resolvedGrpcEndpoint()
        return NodeEndpoint(host = host, port = port, useTls = tls)
    }

    private fun tlsEndpointsFor(config: NodeConfig): List<NodeEndpoint> {
        val mainFallbacks = KaspaNodePool.tlsFallbackEndpoints(config.network)
        return if (mainFallbacks.isNotEmpty()) mainFallbacks else listOf(fixedEndpointFor(config))
    }

    private fun currentClientIfCompatible(config: NodeConfig): Pair<KaspadGrpcClient, NodeEndpoint>? {
        val client = activeGrpcClient ?: return null
        val ep = activeEndpointValue ?: return null
        val expectedScope = scopeKeyFor(config, ep)
        if (activeGrpcScopeKey != expectedScope) return null
        when (config.effectiveConnectionMode) {
            NodeConnectionMode.CUSTOM -> {
                if (ep.key != fixedEndpointFor(config).key) return null
            }
            NodeConnectionMode.TLS_ONLY -> {
                if (tlsEndpointsFor(config).none { it.key == ep.key }) return null
            }
            NodeConnectionMode.AUTOMATIC_SCAN -> Unit
        }
        return client to ep
    }

    private fun getOrCreateClientForEndpoint(config: NodeConfig, endpoint: NodeEndpoint): KaspadGrpcClient {
        val key = scopeKeyFor(config, endpoint)
        synchronized(grpcLock) {
            val existing = activeGrpcClient
            if (existing != null && activeGrpcScopeKey == key) {
                return existing
            }
            existing?.close()
            val created = KaspadGrpcClient(host = endpoint.host, port = endpoint.port, useTls = endpoint.useTls)
            activeGrpcScopeKey = key
            activeEndpointValue = endpoint
            activeGrpcClient = created
            return created
        }
    }

    private suspend fun selectEndpoint(config: NodeConfig, excludeKeys: Set<String> = emptySet()): NodeEndpoint? {
        return when (config.effectiveConnectionMode) {
            NodeConnectionMode.CUSTOM -> {
                val fixed = fixedEndpointFor(config)
                if (fixed.key in excludeKeys) null else fixed
            }
            NodeConnectionMode.TLS_ONLY -> {
                val current = currentClientIfCompatible(config)
                if (current != null && current.first.isConnected && current.second.key !in excludeKeys) {
                    current.second
                } else {
                    tlsEndpointsFor(config).firstOrNull { it.key !in excludeKeys }
                }
            }
            NodeConnectionMode.AUTOMATIC_SCAN -> {
                // Reuse the currently sticky active endpoint when connected and not excluded
                val current = currentClientIfCompatible(config)
                if (current != null && current.first.isConnected && current.second.key !in excludeKeys) {
                    current.second
                } else {
                    nodePool.getPreferredEndpoint(config.network, excludeKeys = excludeKeys)
                }
            }
        }
    }

    /**
     * Executes a gRPC operation with automatic node-pool failover:
     * - [NodeConnectionMode.AUTOMATIC_SCAN]: verified community `:16110` nodes -> Mainnet TLS
     *   endpoints (`toccata.kaspium.io:443`, `node.kaspium.io:443`).
     * - [NodeConnectionMode.TLS_ONLY]: fails over cleanly across Mainnet TLS endpoints.
     * - Consensus rejections (`Broadcast rejected:`) are never retried across nodes.
     */
    private suspend fun <T> withGrpcFailover(
        config: NodeConfig,
        maxAttempts: Int = when (config.effectiveConnectionMode) {
            NodeConnectionMode.AUTOMATIC_SCAN -> 5
            NodeConnectionMode.TLS_ONLY -> tlsEndpointsFor(config).size.coerceAtLeast(1)
            NodeConnectionMode.CUSTOM -> 1
        },
        block: suspend (KaspadGrpcClient, NodeEndpoint) -> T,
    ): Pair<T, NodeEndpoint> {
        val tried = LinkedHashSet<String>()
        var lastError: Throwable? = null
        val attempts = maxAttempts.coerceAtLeast(1)

        for (i in 0 until attempts) {
            val endpoint = selectEndpoint(config, excludeKeys = tried)
                ?: break
            tried.add(endpoint.key)
            val grpc = getOrCreateClientForEndpoint(config, endpoint)
            val start = System.currentTimeMillis()
            try {
                val value = block(grpc, endpoint)
                if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    nodePool.recordSuccess(config.network, endpoint, latencyMs = latency)
                }
                return value to endpoint
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (rejected: IllegalStateException) {
                // Propagate consensus rejections (e.g. double spend, orphan, dust) directly without failover
                if (rejected.message?.startsWith("Broadcast rejected:") == true) {
                    throw rejected
                }
                lastError = rejected
                if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    nodePool.recordFailure(config.network, endpoint)
                }
            } catch (t: Throwable) {
                lastError = t
                if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    nodePool.recordFailure(config.network, endpoint)
                }
            }
        }
        throw (lastError ?: IOException("No reachable gRPC nodes available"))
    }

    override fun isGrpcActive(config: NodeConfig): Boolean {
        val pair = currentClientIfCompatible(config) ?: return false
        return pair.first.isConnected
    }

    override fun grpcLastActivityAgeMs(config: NodeConfig): Long {
        val pair = currentClientIfCompatible(config) ?: return Long.MAX_VALUE
        return pair.first.lastActivityAgeMs()
    }

    override fun activePoolCount(config: NodeConfig): Int =
        if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
            nodePool.activeVerifiedCount(config.network)
        } else {
            if (isGrpcActive(config)) 1 else 0
        }

    override fun activeGrpcEndpoint(config: NodeConfig): NodeEndpoint? {
        currentClientIfCompatible(config)?.second?.let { return it }
        return if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
            nodePool.rankedActiveEndpoints(config.network, includeTlsFallback = true).firstOrNull()
        } else {
            fixedEndpointFor(config)
        }
    }

    override fun onNetworkPathChanged() {
        nodePool.onNetworkPathChanged()
    }

    override suspend fun refreshNodePoolIfNeeded(config: NodeConfig, force: Boolean): Int {
        if (config.effectiveConnectionMode != NodeConnectionMode.AUTOMATIC_SCAN) {
            return if (isGrpcActive(config)) 1 else 0
        }
        return nodePool.ensurePoolPopulated(config.network, force = force)
    }

    override suspend fun verifyOrReconnectGrpc(config: NodeConfig, forceRebuild: Boolean): NodeHealth? =
        withContext(Dispatchers.IO) {
            runCatchingNonCancellation {
                val (health, endpoint) = withGrpcFailover(config) { grpc, _ ->
                    if (forceRebuild) {
                        grpc.forceReconnect(timeoutMs = 8_000L)
                    } else {
                        grpc.verifyOrReconnect(timeoutMs = 8_000L)
                    }
                }
                decorateHealth(config, health, endpoint)
            }.getOrNull()
        }

    override fun isRestRateLimited(): Boolean = System.currentTimeMillis() < restBackoffUntilMs.get()

    /**
     * Resets transient REST 429 backoff on explicit wallet switch or manual user Refresh so a
     * prior wallet's bulk history fetch can never lock out the newly active wallet's Recent Transactions.
     */
    override fun clearRestBackoffOnUserRefresh() {
        restBackoffUntilMs.set(0L)
        consecutive429s.set(0)
    }

    override fun closeConnections() {
        synchronized(grpcLock) {
            activeGrpcClient?.close()
            activeGrpcClient = null
            activeGrpcScopeKey = null
            activeEndpointValue = null
        }
    }

    private fun decorateHealth(config: NodeConfig, health: NodeHealth, endpoint: NodeEndpoint): NodeHealth {
        val proto = if (endpoint.useTls) "TLS" else "Plaintext"
        val poolCount = activePoolCount(config)
        val transportLabel = when (config.effectiveConnectionMode) {
            NodeConnectionMode.AUTOMATIC_SCAN -> if (endpoint.useTls) {
                "Auto Scan • TLS Fallback"
            } else {
                "Auto Scan • Plaintext gRPC"
            }
            NodeConnectionMode.TLS_ONLY -> "TLS Only • gRPC ($proto)"
            NodeConnectionMode.CUSTOM -> "Custom • gRPC ($proto)"
        }
        return health.copy(
            transportUsed = transportLabel,
            activeEndpoint = endpoint.hostPort,
            isTls = endpoint.useTls,
            activePoolCount = poolCount,
        )
    }

    override suspend fun checkNodeHealth(config: NodeConfig): NodeHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        // 1. Primary gRPC health check (community :16110 -> Mainnet TLS fallback before REST!)
        // Strictly use runCatchingNonCancellation so cancelled jobs NEVER fall through to REST fallback!
        val grpcRes = runCatchingNonCancellation {
            val (health, endpoint) = withGrpcFailover(config) { grpc, _ ->
                runCatchingNonCancellation { grpc.getServerInfo(timeoutMs = 7_500L) }
                    .recoverCatching { err ->
                        if (err is kotlinx.coroutines.CancellationException) throw err
                        grpc.getInfo(timeoutMs = 7_500L)
                    }
                    .getOrThrow()
            }
            decorateHealth(config, health, endpoint)
        }
        grpcRes.getOrNull()?.let { health ->
            if (health.reachable) return@withContext health
        }

        // 2. Fallback to REST health check ONLY if gRPC is unreachable
        val base = config.effectiveRestUrl
        try {
            val (kaspad, dag) = coroutineScope {
                val kaspadDef = async { runCatching { getJson("$base/info/kaspad")?.jsonObject }.getOrNull() }
                val dagDef = async { runCatching { getJson("$base/info/blockdag")?.jsonObject }.getOrNull() }
                kaspadDef.await() to dagDef.await()
            }
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (kaspad == null && dag == null) {
                val errMsg = if (isRestRateLimited()) {
                    "REST API rate limited (HTTP 429)"
                } else {
                    KaspaErrorSanitizer.sanitize(grpcRes.exceptionOrNull()?.message ?: "Node unreachable")
                }
                return@withContext NodeHealth(
                    reachable = false,
                    isSynced = false,
                    isUtxoIndexed = false,
                    latencyMs = latency,
                    transportUsed = "gRPC / REST",
                    errorMessage = errMsg,
                    activePoolCount = activePoolCount(config),
                )
            }
            val isSynced = kaspad?.get("isSynced")?.jsonPrimitive?.booleanOrNull ?: true
            val isUtxoIndexed = kaspad?.get("isUtxoIndexed")?.jsonPrimitive?.booleanOrNull ?: true
            val serverVersion = kaspad?.get("serverVersion")?.jsonPrimitive?.content.orEmpty()
            val networkName = dag?.get("networkName")?.jsonPrimitive?.content ?: config.network.id
            val daaScore = dag?.get("virtualDaaScore")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            NodeHealth(
                reachable = true,
                isSynced = isSynced,
                isUtxoIndexed = isUtxoIndexed,
                networkName = networkName,
                virtualDaaScore = daaScore,
                serverVersion = serverVersion,
                latencyMs = latency,
                transportUsed = if (config.effectiveConnectionMode == NodeConnectionMode.CUSTOM) {
                    "Custom REST Fallback (TLS)"
                } else {
                    "Public REST Fallback (HTTPS)"
                },
                activeEndpoint = base.removePrefix("https://").removePrefix("http://"),
                isTls = base.startsWith("https://", ignoreCase = true),
                activePoolCount = activePoolCount(config),
            )
        } catch (e: Exception) {
            NodeHealth(
                reachable = false,
                isSynced = false,
                isUtxoIndexed = false,
                latencyMs = System.currentTimeMillis() - start,
                transportUsed = "gRPC / REST",
                errorMessage = KaspaErrorSanitizer.sanitize(e.message),
                activePoolCount = activePoolCount(config),
            )
        }
    }

    override suspend fun getLiveBlockDagSnapshot(config: NodeConfig): KaspadGrpcClient.LiveDagSnapshot? =
        withContext(Dispatchers.IO) {
            // Primary: gRPC GetBlockDagInfo (decodes exact consensus pastMedianTime field 6 + virtualDaaScore field 9!)
            val grpcSnap = runCatchingNonCancellation {
                withGrpcFailover(config) { grpc, _ -> grpc.getBlockDagSnapshot(timeoutMs = 6_000L) }.first
            }.getOrNull()
            if (grpcSnap != null && grpcSnap.virtualDaaScore > 0L) return@withContext grpcSnap

            // Never touch REST if gRPC is active or REST is rate-limited
            if (isGrpcActive(config) || isRestRateLimited()) return@withContext null
            val start = System.currentTimeMillis()
            val base = config.effectiveRestUrl
            val dag = runCatching { getJson("$base/info/blockdag")?.jsonObject }.getOrNull() ?: return@withContext null
            val score = dag["virtualDaaScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: dag["virtualDaaScore"]?.jsonPrimitive?.longOrNull
                ?: return@withContext null
            val pmt = dag["pastMedianTime"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: dag["pastMedianTime"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val netName = dag["networkName"]?.jsonPrimitive?.content.orEmpty()
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            KaspadGrpcClient.LiveDagSnapshot(
                networkName = netName,
                virtualDaaScore = score,
                pastMedianTimeMs = pmt,
                latencyMs = latency,
            )
        }

    override suspend fun getLiveBlockDagScore(config: NodeConfig): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val snap = getLiveBlockDagSnapshot(config) ?: return@withContext null
        snap.virtualDaaScore to snap.latencyMs
    }

    override suspend fun openLiveNodeStream(
        config: NodeConfig,
        addresses: List<String>,
        forceRebuild: Boolean,
        onDaaScore: (Long) -> Unit,
        onUtxosChanged: (added: List<UtxoItem>, removed: List<KaspadProtoCodec.RemovedUtxoRef>) -> Unit,
        onConnectionChanged: (Boolean) -> Unit,
    ): LiveStreamHandle? = withContext(Dispatchers.IO) {
        val tried = LinkedHashSet<String>()
        val maxAttempts = when (config.effectiveConnectionMode) {
            NodeConnectionMode.AUTOMATIC_SCAN -> 5
            NodeConnectionMode.TLS_ONLY -> tlsEndpointsFor(config).size.coerceAtLeast(1)
            NodeConnectionMode.CUSTOM -> 1
        }

        for (attempt in 0 until maxAttempts) {
            val endpoint = selectEndpoint(config, excludeKeys = tried) ?: break
            tried.add(endpoint.key)
            val grpc = getOrCreateClientForEndpoint(config, endpoint)
            grpc.setListeners(
                onDaaScore = onDaaScore,
                onUtxosChanged = onUtxosChanged,
                onConnectionChanged = onConnectionChanged,
            )
            val start = System.currentTimeMillis()
            val ok = runCatching {
                val info = if (forceRebuild || attempt > 0) {
                    grpc.forceReconnect(timeoutMs = 7_500L)
                } else {
                    grpc.verifyOrReconnect(timeoutMs = 7_500L)
                }
                if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    if (!info.isSynced || !info.isUtxoIndexed) {
                        throw IOException("Node is not synced or missing UTXO index")
                    }
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    nodePool.recordSuccess(
                        network = config.network,
                        endpoint = endpoint,
                        latencyMs = latency,
                        virtualDaaScore = info.virtualDaaScore,
                        networkName = info.networkName,
                        isSynced = info.isSynced,
                        isUtxoIndexed = info.isUtxoIndexed,
                    )
                }
                if (info.virtualDaaScore > 0L) onDaaScore(info.virtualDaaScore)
                grpc.subscribeVirtualDaaScoreChanged()
                grpc.setWatchedAddresses(addresses, forceResubscribe = true)
                true
            }.getOrDefault(false)

            if (ok) {
                onConnectionChanged(true)
                return@withContext object : LiveStreamHandle {
                    override suspend fun setWatchedAddresses(addresses: List<String>, forceResubscribe: Boolean) {
                        runCatching { grpc.setWatchedAddresses(addresses, forceResubscribe = forceResubscribe) }
                    }

                    override suspend fun updateWatchedAddresses(addresses: List<String>) {
                        runCatching { grpc.updateWatchedAddresses(addresses) }
                    }

                    override fun close() {
                        grpc.setListeners(null, null, null)
                    }
                }
            } else {
                grpc.setListeners(null, null, null)
                if (config.effectiveConnectionMode == NodeConnectionMode.AUTOMATIC_SCAN) {
                    nodePool.recordFailure(config.network, endpoint)
                }
            }
        }

        onConnectionChanged(false)
        null
    }

    override suspend fun openBlockAddedStream(
        config: NodeConfig,
        onBlockAdded: (KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit,
    ): BlockAddedStreamHandle? = withContext(Dispatchers.IO) {
        val existing = currentClientIfCompatible(config)?.first
        val grpc = if (existing != null && existing.isConnected) {
            existing
        } else {
            val endpoint = selectEndpoint(config) ?: return@withContext null
            getOrCreateClientForEndpoint(config, endpoint)
        }
        val subscribed = runCatching {
            grpc.verifyOrReconnect(timeoutMs = 7_500L)
            grpc.setBlockAddedListener(onBlockAdded)
            grpc.subscribeBlockAdded()
            true
        }.getOrDefault(false)
        if (!subscribed) {
            grpc.setBlockAddedListener(null)
            return@withContext null
        }
        object : BlockAddedStreamHandle {
            override suspend fun close() {
                runCatching { grpc.unsubscribeBlockAdded() }
            }
        }
    }

    override suspend fun getActiveAddresses(
        config: NodeConfig,
        addresses: List<String>,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptySet()
        val active = linkedSetOf<String>()

        // 1. Primary: Check balances over gRPC (unlimited, zero 429s)
        val grpcBalances = runCatching {
            withGrpcFailover(config) { grpc, _ -> grpc.getBalancesByAddresses(addresses) }.first
        }.getOrNull()
        grpcBalances?.forEach { (addr, bal) ->
            if (bal > 0L) active.add(addr)
        }

        // 2. Also check historical used-but-empty addresses via REST /addresses/active if not rate-limited
        if (!isRestRateLimited()) {
            val base = config.effectiveRestUrl
            addresses.chunked(100).forEach { batch ->
                val body = buildJsonObject {
                    put("addresses", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
                }.toString()
                val response = runCatching { postJson("$base/addresses/active", body) }.getOrNull()
                if (response is JsonArray) {
                    for (el in response) {
                        val obj = el.jsonObject
                        val addr = obj["address"]?.jsonPrimitive?.content ?: continue
                        val isAct = obj["active"]?.jsonPrimitive?.booleanOrNull == true
                        if (isAct) active.add(addr)
                    }
                }
            }
        }
        active
    }

    override suspend fun getUtxosGrpcOnly(
        config: NodeConfig,
        addresses: List<String>,
    ): List<UtxoItem> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()
        val first = runCatchingNonCancellation {
            withGrpcFailover(config) { grpc, _ -> grpc.getUtxosByAddresses(addresses) }.first
        }
        if (first.isSuccess) return@withContext first.getOrThrow()
        // Brief grace window + 1 gRPC retry before ever bubbling up a transient hiccup
        kotlinx.coroutines.delay(350L)
        withGrpcFailover(config) { grpc, _ -> grpc.getUtxosByAddresses(addresses) }.first
    }

    override suspend fun getUtxos(
        config: NodeConfig,
        addresses: List<String>,
    ): List<UtxoItem> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()

        // 1. Primary: gRPC GetUtxosByAddresses with automatic node-pool failover (zero api.kaspa.org rate limits!)
        // Strictly use runCatchingNonCancellation so cancelled jobs NEVER fall through to REST!
        val grpcAttempt = runCatchingNonCancellation {
            withGrpcFailover(config) { grpc, _ -> grpc.getUtxosByAddresses(addresses) }.first
        }
        if (grpcAttempt.isSuccess) {
            return@withContext grpcAttempt.getOrThrow()
        }

        // 2. Fallback to REST ONLY if gRPC failed; never silently swallow 429 / network errors into emptyList!
        val base = config.effectiveRestUrl
        val out = ArrayList<UtxoItem>()
        val seen = HashSet<String>()
        var succeededBatches = 0
        var lastError: Throwable? = grpcAttempt.exceptionOrNull()

        addresses.distinct().chunked(50).forEach { batch ->
            val body = buildJsonObject {
                put("addresses", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val batchAttempt = runCatching { postJson("$base/addresses/utxos", body) }
            val batchResp = batchAttempt.getOrNull()
            if (batchResp is JsonArray) {
                succeededBatches++
                parseUtxoArray(batchResp, out, seen)
            } else {
                lastError = batchAttempt.exceptionOrNull() ?: lastError
                if (lastError is RateLimitedException) throw lastError as RateLimitedException
            }
        }
        if (succeededBatches == 0) {
            throw (lastError ?: IOException("Failed to fetch UTXOs from gRPC and REST"))
        }
        out.sortedByDescending { it.amountSompi }
    }

    private fun parseUtxoArray(arr: JsonArray, out: MutableList<UtxoItem>, seen: MutableSet<String>) {
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val addr = obj["address"]?.jsonPrimitive?.content ?: continue
            val outpoint = obj["outpoint"] as? JsonObject ?: continue
            val txId = outpoint["transactionId"]?.jsonPrimitive?.content ?: continue
            val idx = outpoint["index"]?.jsonPrimitive?.intOrNull
                ?: outpoint["index"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: continue
            val key = "$txId:$idx"
            if (!seen.add(key)) continue
            val entry = obj["utxoEntry"] as? JsonObject ?: continue
            val amount = entry["amount"]?.jsonPrimitive?.longOrNull
                ?: entry["amount"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: continue
            if (amount <= 0L) continue
            val daa = entry["blockDaaScore"]?.jsonPrimitive?.longOrNull
                ?: entry["blockDaaScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 0L
            val isCoinbase = entry["isCoinbase"]?.jsonPrimitive?.booleanOrNull ?: false
            val spkObj = entry["scriptPublicKey"] as? JsonObject
            val spkHex = spkObj?.get("scriptPublicKey")?.jsonPrimitive?.content
                ?: KaspaAddress.decode(addr)?.toScriptPublicKey()?.let {
                    com.kaspakinetic.wallet.core.crypto.KaspaCrypto.bytesToHex(it)
                }
                ?: continue
            val spkVer = spkObj?.get("version")?.jsonPrimitive?.intOrNull ?: 0
            out.add(
                UtxoItem(
                    address = addr,
                    transactionId = txId,
                    index = idx,
                    amountSompi = amount,
                    scriptPublicKeyHex = spkHex,
                    scriptPublicKeyVersion = spkVer,
                    blockDaaScore = daa,
                    isCoinbase = isCoinbase,
                ),
            )
        }
    }

    override suspend fun getTransactions(
        config: NodeConfig,
        addresses: List<String>,
        walletAddressSet: Set<String>,
        limitPerAddress: Int,
    ): List<WalletTx> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()
        ensureRestNotBackedOff()

        val base = config.effectiveRestUrl
        val byId = LinkedHashMap<String, WalletTx>()
        val burnAddr = KaspaAddress.burnAddress(config.network)

        val uniqueAddrs = addresses.distinct().take(60)
        // Polite chunk size (3) + in-flight deduplication so on-demand REST history never trips 429s
        var rateLimited: RateLimitedException? = null
        val results = ArrayList<JsonArray?>()
        for (chunk in uniqueAddrs.chunked(3)) {
            if (isRestRateLimited()) break
            val chunkRes = coroutineScope {
                chunk.map { addr ->
                    async {
                        val enc = URLEncoder.encode(addr, "UTF-8")
                        val url = "$base/addresses/$enc/full-transactions?limit=$limitPerAddress&offset=0&resolve_previous_outpoints=light"
                        try {
                            val resp = getJsonDeduplicated(url)
                            when (resp) {
                                is JsonArray -> resp
                                is JsonObject -> resp["transactions"] as? JsonArray
                                else -> null
                            }
                        } catch (rl: RateLimitedException) {
                            rateLimited = rl
                            null
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            results.addAll(chunkRes)
            if (rateLimited != null) break
        }
        val succeeded = results.filterNotNull()
        if (succeeded.isEmpty() && uniqueAddrs.isNotEmpty()) {
            throw (rateLimited ?: IOException("Failed to fetch transaction history from indexer"))
        }

        for (arr in succeeded) {
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val tx = parseWalletTx(obj, walletAddressSet, burnAddr) ?: continue
                if (!byId.containsKey(tx.transactionId)) {
                    byId[tx.transactionId] = tx
                }
            }
        }
        byId.values.sortedByDescending { it.blockTimeMs }
    }

    override suspend fun getTransactionById(
        config: NodeConfig,
        transactionId: String,
        walletAddressSet: Set<String>,
    ): WalletTx? = withContext(Dispatchers.IO) {
        val cleanId = transactionId.trim()
        if (cleanId.isEmpty() || isRestRateLimited()) return@withContext null
        val base = config.effectiveRestUrl
        val enc = URLEncoder.encode(cleanId, "UTF-8")
        val url = "$base/transactions/$enc?inputs=true&outputs=true&resolve_previous_outpoints=light"
        val resp = runCatching { getJsonDeduplicated(url) as? JsonObject }.getOrNull() ?: return@withContext null
        val burnAddr = KaspaAddress.burnAddress(config.network)
        parseWalletTx(resp, walletAddressSet, burnAddr)
    }

    internal fun parseWalletTx(
        obj: JsonObject,
        walletAddressSet: Set<String>,
        burnAddr: String,
    ): WalletTx? {
        if (walletAddressSet.isEmpty()) return null
        val txId = obj["transaction_id"]?.jsonPrimitive?.content ?: return null
        val blockTime = obj["block_time"]?.jsonPrimitive?.longOrNull ?: 0L
        val isAccepted = obj["is_accepted"]?.jsonPrimitive?.booleanOrNull ?: false
        val blueScore = obj["accepting_block_blue_score"]?.jsonPrimitive?.longOrNull
        val mass = obj["mass"]?.jsonPrimitive?.longOrNull ?: 0L

        val inputs = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { iEl ->
            val iObj = iEl as? JsonObject ?: return@mapNotNull null
            TxInputSummary(
                previousOutpointHash = iObj["previous_outpoint_hash"]?.jsonPrimitive?.content.orEmpty(),
                previousOutpointIndex = iObj["previous_outpoint_index"]?.jsonPrimitive?.intOrNull
                    ?: iObj["previous_outpoint_index"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: 0,
                address = iObj["previous_outpoint_address"]?.jsonPrimitive?.content,
                amountSompi = iObj["previous_outpoint_amount"]?.jsonPrimitive?.longOrNull
                    ?: iObj["previous_outpoint_amount"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }

        val outputs = (obj["outputs"] as? JsonArray).orEmpty().mapIndexedNotNull { oIdx, oEl ->
            val oObj = oEl as? JsonObject ?: return@mapIndexedNotNull null
            TxOutputSummary(
                index = oObj["index"]?.jsonPrimitive?.intOrNull ?: oIdx,
                address = oObj["script_public_key_address"]?.jsonPrimitive?.content.orEmpty(),
                amountSompi = oObj["amount"]?.jsonPrimitive?.longOrNull
                    ?: oObj["amount"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 0L,
                scriptPublicKeyHex = oObj["script_public_key"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

        val touchesAnyInput = inputs.any { it.address != null && it.address in walletAddressSet }
        val touchesAnyOutput = outputs.any { it.address in walletAddressSet }
        // Hard anti-ghost guard: if a transaction touches ZERO inputs AND ZERO outputs of this
        // wallet's exact owned-address snapshot, drop it instead of classifying as `Received 0.00 KAS`!
        if (!touchesAnyInput && !touchesAnyOutput) {
            return null
        }

        val totalIn = inputs.sumOf { it.amountSompi ?: 0L }
        val totalOut = outputs.sumOf { it.amountSompi }
        val fee = if (inputs.isNotEmpty() && totalIn >= totalOut) totalIn - totalOut else 0L

        val myIn = inputs.filter { it.address != null && it.address in walletAddressSet }
            .sumOf { it.amountSompi ?: 0L }
        val myOut = outputs.filter { it.address in walletAddressSet }
            .sumOf { it.amountSompi }

        val allInputsMine = inputs.isNotEmpty() && inputs.all { it.address in walletAddressSet }
        val allOutputsMine = outputs.isNotEmpty() && outputs.all { it.address in walletAddressSet }
        val hasBurnOutput = outputs.any { it.address.equals(burnAddr, ignoreCase = true) }

        val direction = when {
            hasBurnOutput && allInputsMine -> TxDirection.BURN
            allInputsMine && allOutputsMine && fee >= 1_000_000L && inputs.size == 1 && outputs.size == 1 ->
                TxDirection.MINER_DONATION
            allInputsMine && allOutputsMine && inputs.size > 1 && outputs.size == 1 ->
                TxDirection.COMPOUND
            allInputsMine && allOutputsMine -> TxDirection.SELF_TRANSFER
            myOut >= myIn -> TxDirection.RECEIVED
            else -> TxDirection.SENT
        }

        val netAmount = when (direction) {
            TxDirection.RECEIVED -> (myOut - myIn).coerceAtLeast(0L)
            TxDirection.BURN -> outputs.filter { it.address.equals(burnAddr, ignoreCase = true) }
                .sumOf { it.amountSompi }
            TxDirection.SENT, TxDirection.COVENANT -> (myIn - myOut - fee).coerceAtLeast(0L)
            TxDirection.MINER_DONATION -> fee
            TxDirection.COMPOUND, TxDirection.SELF_TRANSFER -> myOut
        }

        val counterparty = when (direction) {
            TxDirection.RECEIVED -> inputs.firstOrNull { it.address !in walletAddressSet }?.address
                ?: outputs.firstOrNull { it.address in walletAddressSet }?.address.orEmpty()
            TxDirection.BURN -> burnAddr
            TxDirection.SENT -> outputs.firstOrNull { it.address !in walletAddressSet }?.address
                ?: outputs.firstOrNull()?.address.orEmpty()
            else -> outputs.firstOrNull()?.address.orEmpty()
        }

        return WalletTx(
            transactionId = txId,
            blockTimeMs = blockTime,
            isAccepted = isAccepted,
            acceptingBlockBlueScore = blueScore,
            direction = direction,
            netAmountSompi = netAmount,
            feeSompi = fee,
            mass = mass,
            inputs = inputs,
            outputs = outputs,
            counterpartyAddress = counterparty,
        )
    }

    override suspend fun getFeeEstimate(config: NodeConfig): FeeEstimate = withContext(Dispatchers.IO) {
        // 1. Primary: gRPC GetFeeEstimate (zero REST load!)
        val grpcFee = runCatching {
            withGrpcFailover(config) { grpc, _ -> grpc.getFeeEstimate() }.first
        }.getOrNull()
        if (grpcFee != null) return@withContext grpcFee

        // 2. Fallback to REST if not rate-limited
        if (isRestRateLimited()) return@withContext FeeEstimate(lowRate = 1.0, normalRate = 1.0, priorityRate = 2.0)
        val base = config.effectiveRestUrl
        val obj = runCatching { getJson("$base/info/fee-estimate")?.jsonObject }.getOrNull()
            ?: return@withContext FeeEstimate(lowRate = 1.0, normalRate = 1.0, priorityRate = 2.0)

        val priorityBucket = obj["priorityBucket"] as? JsonObject
        val priorityRate = priorityBucket?.get("feerate")?.jsonPrimitive?.doubleOrNull?.coerceAtLeast(1.0) ?: 1.0
        val prioritySec = priorityBucket?.get("estimatedSeconds")?.jsonPrimitive?.doubleOrNull ?: 1.0

        val normalBuckets = obj["normalBuckets"] as? JsonArray
        val normalRate = (normalBuckets?.firstOrNull() as? JsonObject)
            ?.get("feerate")?.jsonPrimitive?.doubleOrNull?.coerceAtLeast(1.0) ?: priorityRate

        val lowBuckets = obj["lowBuckets"] as? JsonArray
        val lowRate = (lowBuckets?.firstOrNull() as? JsonObject)
            ?.get("feerate")?.jsonPrimitive?.doubleOrNull?.coerceAtLeast(1.0) ?: 1.0

        FeeEstimate(
            lowRate = lowRate,
            normalRate = normalRate,
            priorityRate = maxOf(priorityRate, normalRate),
            priorityEstSeconds = prioritySec,
        )
    }

    override suspend fun submitTransaction(config: NodeConfig, submitJson: String): String =
        withContext(Dispatchers.IO) {
            // 1. Primary: Submit directly over persistent kaspad gRPC stream with pool failover (zero api.kaspa.org 429s!)
            val grpcAttempt = runCatching {
                withGrpcFailover(config) { grpc, _ -> grpc.submitTransaction(submitJson) }.first
            }
            if (grpcAttempt.isSuccess) {
                return@withContext grpcAttempt.getOrThrow()
            }
            val grpcErr = grpcAttempt.exceptionOrNull()
            // If kaspad explicitly rejected the tx (e.g. double-spend / orphan / invalid mass), surface cleanly without retrying REST
            if (grpcErr is IllegalStateException && grpcErr.message?.startsWith("Broadcast rejected:") == true) {
                throw grpcErr
            }

            // CRITICAL TOCCATA GUARD: Transaction v1 covenants commit `computeBudget` & `covenant` bindings.
            // Public REST gateways (`api-tn10.kaspa.org` / `api.kaspa.org`) can strip `computeBudget` on v1
            // submissions, causing nodes to reject with "script units exceeded". NEVER silently fall back to REST!
            val txVersion = runCatching {
                json.parseToJsonElement(submitJson).jsonObject["transaction"]
                    ?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull ?: 0
            }.getOrDefault(0)
            if (txVersion >= 1) {
                val detail = grpcErr?.message?.takeIf { it.isNotBlank() } ?: "No gRPC peer reachable"
                throw IllegalStateException(
                    "Transaction v1 covenants require a live gRPC node connection (REST fallback disabled to preserve compute_budget). Please ensure gRPC is connected in Node Settings. Details: $detail",
                )
            }

            // 2. Fallback to REST POST /transactions for legacy v0 transactions if gRPC transport failed
            ensureRestNotBackedOff()
            val base = config.effectiveRestUrl
            val request = Request.Builder()
                .url("$base/transactions")
                .post(submitJson.toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("User-Agent", "KaspaKinetic/1.0")
                .build()
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.code == 429) {
                    val waitSec = recordRest429(resp.header("Retry-After"))
                    throw RateLimitedException(waitSec)
                }
                if (!resp.isSuccessful) {
                    val rawDetail = runCatching {
                        val errObj = json.parseToJsonElement(bodyStr).jsonObject
                        errObj["error"]?.jsonPrimitive?.content
                            ?: errObj["detail"]?.jsonPrimitive?.content
                            ?: bodyStr
                    }.getOrDefault(bodyStr)
                    val clean = KaspaErrorSanitizer.sanitize(rawDetail, resp.code)
                    throw IllegalStateException("Broadcast rejected (${resp.code}): $clean")
                }
                consecutive429s.set(0)
                val parsed = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                parsed?.get("transactionId")?.jsonPrimitive?.content
                    ?: parsed?.get("transaction_id")?.jsonPrimitive?.content
                    ?: bodyStr.trim().trim('"')
            }
        }

    override suspend fun getSpotPrice(
        currency: FiatCurrency,
        privacyKasOnly: Boolean,
        isTestnet: Boolean,
    ): SpotPrice? = withContext(Dispatchers.IO) {
        // Strict privacy rule: ZERO external price requests when KAS Only Privacy Mode is ON
        if (privacyKasOnly || isTestnet) return@withContext null

        val codeLower = currency.code.lowercase()
        // 1. Try CoinGecko for exact currency + 24h change
        val cg = runCatching {
            val url = "https://api.coingecko.com/api/v3/simple/price?ids=kaspa&vs_currencies=$codeLower&include_24hr_change=true"
            val root = getExternalJson(url)?.jsonObject
            val kaspa = root?.get("kaspa")?.jsonObject
            val price = kaspa?.get(codeLower)?.jsonPrimitive?.doubleOrNull
            val change = kaspa?.get("${codeLower}_24h_change")?.jsonPrimitive?.doubleOrNull
            if (price != null && price.isFinite() && price > 0.0) {
                SpotPrice(currency = currency, price = price, change24hPercent = change)
            } else {
                null
            }
        }.getOrNull()
        if (cg != null) return@withContext cg

        // 2. Fallback to api.kaspa.org/info/price only when not rate-limited
        if (isRestRateLimited()) return@withContext null
        if (currency == FiatCurrency.USD) {
            return@withContext runCatching {
                val root = getJson("https://api.kaspa.org/info/price")?.jsonObject
                val price = root?.get("price")?.jsonPrimitive?.doubleOrNull
                if (price != null && price.isFinite() && price > 0.0) {
                    SpotPrice(currency = FiatCurrency.USD, price = price, change24hPercent = null)
                } else {
                    null
                }
            }.getOrNull()
        }
        if (currency == FiatCurrency.BTC) {
            return@withContext runCatching {
                val kasUsd = getJson("https://api.kaspa.org/info/price")?.jsonObject
                    ?.get("price")?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                val btcUsd = runCatching {
                    getExternalJson("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")?.jsonObject
                        ?.get("price")?.jsonPrimitive?.content?.toDoubleOrNull()
                }.getOrNull() ?: runCatching {
                    getExternalJson("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd")?.jsonObject
                        ?.get("bitcoin")?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull
                }.getOrNull() ?: return@runCatching null
                if (kasUsd > 0.0 && btcUsd > 0.0) {
                    SpotPrice(currency = FiatCurrency.BTC, price = kasUsd / btcUsd, change24hPercent = null)
                } else {
                    null
                }
            }.getOrNull()
        }
        null
    }

    // -------------------------------------------------------------------------
    // REST Helpers with 429 Exponential Backoff & In-Flight Deduplication
    // -------------------------------------------------------------------------

    private fun ensureRestNotBackedOff() {
        val now = System.currentTimeMillis()
        val until = restBackoffUntilMs.get()
        if (now < until) {
            val remainSec = ((until - now + 999L) / 1_000L).coerceAtLeast(1L)
            throw RateLimitedException(remainSec)
        }
    }

    private fun recordRest429(retryAfterHeader: String?): Long {
        val strikes = consecutive429s.incrementAndGet()
        val headerSec = retryAfterHeader?.trim()?.toLongOrNull()?.coerceIn(2L, 120L)
        val expSec = (5L * (1L shl (strikes - 1).coerceAtMost(4))).coerceAtMost(60L)
        val waitSec = maxOf(headerSec ?: 0L, expSec)
        restBackoffUntilMs.set(System.currentTimeMillis() + waitSec * 1_000L)
        return waitSec
    }

    private suspend fun getJsonDeduplicated(url: String): JsonElement? {
        ensureRestNotBackedOff()
        val existing = inflightRestGets[url]
        if (existing != null) {
            return existing.await()
        }
        val created = CompletableDeferred<JsonElement?>()
        val race = inflightRestGets.putIfAbsent(url, created)
        if (race != null) {
            return race.await()
        }
        return try {
            val res = getJson(url)
            created.complete(res)
            res
        } catch (t: Throwable) {
            created.completeExceptionally(t)
            throw t
        } finally {
            inflightRestGets.remove(url, created)
        }
    }

    private fun getJson(url: String): JsonElement? {
        ensureRestNotBackedOff()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KaspaKinetic/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 429) {
                val waitSec = recordRest429(resp.header("Retry-After"))
                throw RateLimitedException(waitSec)
            }
            if (!resp.isSuccessful) {
                val body = resp.body?.string()
                throw IOException(KaspaErrorSanitizer.sanitize(body, resp.code))
            }
            consecutive429s.set(0)
            val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text)
        }
    }

    private fun postJson(url: String, body: String): JsonElement? {
        ensureRestNotBackedOff()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .header("User-Agent", "KaspaKinetic/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 429) {
                val waitSec = recordRest429(resp.header("Retry-After"))
                throw RateLimitedException(waitSec)
            }
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()
                throw IOException(KaspaErrorSanitizer.sanitize(errBody, resp.code))
            }
            consecutive429s.set(0)
            val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text)
        }
    }

    override suspend fun getUtxosByCovenantIds(config: NodeConfig, covenantIds: List<String>): List<UtxoItem> =
        runCatchingNonCancellation { getUtxosByCovenantIdsStrict(config, covenantIds) }.getOrDefault(emptyList())

    override suspend fun getUtxosByCovenantIdsStrict(config: NodeConfig, covenantIds: List<String>): List<UtxoItem> =
        withContext(Dispatchers.IO) {
            if (covenantIds.isEmpty()) return@withContext emptyList()
            val cleanIds = covenantIds.map { it.trim().lowercase() }.filter { it.length == 64 }.distinct()
            if (cleanIds.isEmpty()) return@withContext emptyList()
            val first = runCatchingNonCancellation {
                withGrpcFailover(config, maxAttempts = 2) { grpc, _ ->
                    grpc.getUtxosByCovenantIds(cleanIds)
                }.first
            }
            if (first.isSuccess) return@withContext first.getOrThrow()
            kotlinx.coroutines.delay(350L)
            withGrpcFailover(config, maxAttempts = 2) { grpc, _ ->
                grpc.getUtxosByCovenantIds(cleanIds)
            }.first
        }

    /**
     * Scans archival REST indexer (`api.kaspa.org` / `api-tn10.kaspa.org` or custom REST node)
     * for historical transactions on [addresses] carrying non-empty hex `payload` fields
     * (used to recover seed-encrypted `KKCV1` covenant creation & state-transition recipes).
     * Returns `(transactionId, payloadHex)` pairs ordered newest-to-oldest.
     */
    override suspend fun fetchHistoricalTxPayloads(
        config: NodeConfig,
        addresses: List<String>,
        limitPerAddress: Int,
    ): List<Pair<String, String>> =
        fetchHistoricalTxPayloadsWithMeta(config, addresses, limitPerAddress)
            .map { it.txId to it.payloadHex }

    override suspend fun fetchHistoricalTxPayloadsWithMeta(
        config: NodeConfig,
        addresses: List<String>,
        limitPerAddress: Int,
    ): List<HistoricalPayloadRecord> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()
        val base = config.effectiveRestUrl
        val found = LinkedHashMap<String, HistoricalPayloadRecord>()
        for (addr in addresses.distinct().take(60)) {
            val encoded = URLEncoder.encode(addr, "UTF-8")
            val url = "$base/addresses/$encoded/full-transactions?limit=$limitPerAddress&resolve_previous_outpoints=no"
            val el = runCatching { getJsonDeduplicated(url) }.getOrNull() ?: continue
            val arr = el as? JsonArray ?: continue
            for (item in arr) {
                val obj = item as? JsonObject ?: continue
                val txId = obj["transaction_id"]?.jsonPrimitive?.content.orEmpty()
                val payloadHex = obj["payload"]?.jsonPrimitive?.content.orEmpty().trim()
                if (txId.isNotBlank() && payloadHex.length >= 12) {
                    val blockTime = obj["block_time"]?.jsonPrimitive?.longOrNull ?: 0L
                    val blueScore = obj["accepting_block_blue_score"]?.jsonPrimitive?.longOrNull ?: 0L
                    val existing = found[txId]
                    if (existing == null || blueScore > existing.acceptingBlockBlueScore || blockTime > existing.blockTimeMs) {
                        found[txId] = HistoricalPayloadRecord(
                            txId = txId,
                            payloadHex = payloadHex,
                            blockTimeMs = blockTime,
                            acceptingBlockBlueScore = blueScore,
                        )
                    }
                }
            }
        }
        // Sort strictly newest-to-oldest so latest state-transition KKCV1 recipes always win first!
        found.values.sortedWith(
            compareByDescending<HistoricalPayloadRecord> { it.acceptingBlockBlueScore }
                .thenByDescending { it.blockTimeMs },
        )
    }

    override suspend fun fetchTxFirstOutputAmountSompi(config: NodeConfig, txId: String): Long? =
        withContext(Dispatchers.IO) {
            val cleanId = txId.trim().lowercase()
            if (cleanId.length != 64) return@withContext null
            val base = config.effectiveRestUrl
            val url = "$base/transactions/$cleanId?inputs=false&outputs=true&resolve_previous_outpoints=no"
            val obj = runCatching { getJsonDeduplicated(url) as? JsonObject }.getOrNull() ?: return@withContext null
            val outputs = obj["outputs"] as? JsonArray ?: return@withContext null
            val firstOut = outputs.firstOrNull() as? JsonObject ?: return@withContext null
            firstOut["amount"]?.jsonPrimitive?.longOrNull
        }

    private fun getExternalJson(url: String): JsonElement? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KaspaKinetic/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text)
        }
    }
}
