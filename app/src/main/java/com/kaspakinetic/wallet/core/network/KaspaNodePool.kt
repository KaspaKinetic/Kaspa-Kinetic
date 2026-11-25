package com.kaspakinetic.wallet.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.NodeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Persistence contract for last-known-good verified gRPC endpoints (up to 8 per network).
 */
interface NodePoolCacheStore {
    fun load(network: KaspaNetwork): List<NodeEndpoint>
    fun save(network: KaspaNetwork, endpoints: List<NodeEndpoint>)
}

class InMemoryNodePoolCacheStore : NodePoolCacheStore {
    private val map = ConcurrentHashMap<KaspaNetwork, List<NodeEndpoint>>()
    override fun load(network: KaspaNetwork): List<NodeEndpoint> = map[network].orEmpty()
    override fun save(network: KaspaNetwork, endpoints: List<NodeEndpoint>) {
        map[network] = endpoints.take(8)
    }
}

class SharedPreferencesNodePoolStore(context: Context) : NodePoolCacheStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(network: KaspaNetwork): List<NodeEndpoint> {
        val raw = prefs.getString(keyFor(network), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<NodeEndpoint>>(raw) }
            .getOrDefault(emptyList())
            .take(8)
    }

    override fun save(network: KaspaNetwork, endpoints: List<NodeEndpoint>) {
        val encoded = runCatching { json.encodeToString(endpoints.take(8)) }.getOrNull() ?: return
        prefs.edit().putString(keyFor(network), encoded).apply()
    }

    private fun keyFor(network: KaspaNetwork): String = "known_good_nodes_${network.id}"

    companion object {
        private const val PREFS_NAME = "kaspa_kinetic_node_pool"
    }
}

data class PooledNodeEntry(
    val endpoint: NodeEndpoint,
    val isSynced: Boolean = false,
    val isUtxoIndexed: Boolean = false,
    val networkName: String = "",
    val virtualDaaScore: Long = 0L,
    val latencyMs: Long = Long.MAX_VALUE,
    val probedAtMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val quarantineUntilMs: Long = 0L,
    val isTlsFallback: Boolean = false,
) {
    fun isHealthyActive(nowMs: Long): Boolean =
        isSynced &&
            isUtxoIndexed &&
            consecutiveFailures < QUARANTINE_STRIKES &&
            nowMs >= quarantineUntilMs &&
            probedAtMs > 0L

    companion object {
        const val QUARANTINE_STRIKES = 3
    }
}

private sealed class ProfileOutcome {
    data class Verified(val entry: PooledNodeEntry) : ProfileOutcome()
    data class Rejected(val reason: String) : ProfileOutcome()
}

/**
 * Clean-room decentralized gRPC node discovery, profiling, ranking, and failover engine
 * ("Automatic Scan") for Kaspa Kinetic, inspired by the MIT-licensed `POOLS_v2.md` design
 * and standard Rusty-Kaspa public DNS seed infrastructure.
 *
 * Key mobile-5G design choices:
 * - Wide IPv4-first DNS seeder resolution (up to 80 candidates) paired with a fast,
 *   high-concurrency ~2.2s raw TCP `:16110` pre-filter so closed P2P-only peers are discarded
 *   cheaply without allocating gRPC channels.
 * - Universal `GetInfo` (`1063`/`1064`) + `GetBlockDagInfo` profiling with 6.5s timeout
 *   accommodating international 250–400ms cellular RTT.
 * - Non-blocking ~4s cold-start race: returns the first verified `:16110` node immediately if
 *   one answers within 4s, or immediately promotes to Mainnet TLS gRPC (`toccata.kaspium.io:443`
 *   / `node.kaspium.io:443`) while continuing community discovery asynchronously in the background.
 */
class KaspaNodePool(
    private val appContext: Context? = null,
    private val cacheStore: NodePoolCacheStore = if (appContext != null) {
        SharedPreferencesNodePoolStore(appContext)
    } else {
        InMemoryNodePoolCacheStore()
    },
) {
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entriesByNetwork = ConcurrentHashMap<KaspaNetwork, ConcurrentHashMap<String, PooledNodeEntry>>()
    private val refreshMutex = Mutex()
    private val activeBgScanJobs = ConcurrentHashMap<KaspaNetwork, Job>()
    private val lastDnsAttemptMs = ConcurrentHashMap<KaspaNetwork, Long>()
    private val lastGossipAttemptMs = ConcurrentHashMap<KaspaNetwork, Long>()
    private val lastBackgroundRefreshMs = ConcurrentHashMap<KaspaNetwork, Long>()
    private val loadedNetworks = ConcurrentHashMap.newKeySet<KaspaNetwork>()

    private fun logDebug(msg: String) {
        runCatching { Log.d(LOG_TAG, msg) }
    }

    private fun tableFor(network: KaspaNetwork): ConcurrentHashMap<String, PooledNodeEntry> {
        val table = entriesByNetwork.computeIfAbsent(network) { ConcurrentHashMap() }
        if (loadedNetworks.add(network)) {
            cacheStore.load(network).forEach { ep ->
                table.putIfAbsent(ep.key, PooledNodeEntry(endpoint = ep))
            }
        }
        return table
    }

    fun isMeteredNetwork(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    /**
     * Forgives transient consecutive failure strikes and quarantines on all pooled nodes
     * whenever Android reports a Wi-Fi / 5G / VPN network path change so good nodes are never
     * permanently poisoned by an old path.
     */
    fun onNetworkPathChanged() {
        entriesByNetwork.values.forEach { table ->
            table.keys.forEach { key ->
                table.computeIfPresent(key) { _, existing ->
                    existing.copy(consecutiveFailures = 0, quarantineUntilMs = 0L)
                }
            }
        }
        lastBackgroundRefreshMs.clear()
        lastDnsAttemptMs.clear()
        logDebug("Network path changed — forgave node failure strikes & cleared cooldowns")
    }

    fun activeVerifiedCount(network: KaspaNetwork, nowMs: Long = System.currentTimeMillis()): Int {
        val table = tableFor(network)
        return table.values.count { !it.isTlsFallback && it.isHealthyActive(nowMs) }
    }

    fun rankedActiveEndpoints(
        network: KaspaNetwork,
        includeTlsFallback: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): List<NodeEndpoint> {
        val table = tableFor(network)
        val community = rankActiveNodes(
            entries = table.values.filter { !it.isTlsFallback },
            nowMs = nowMs,
        ).map { it.endpoint }

        if (!includeTlsFallback) return community

        val fallbacks = tlsFallbackEndpoints(network).filter { fb ->
            if (community.any { it.key == fb.key }) return@filter false
            val entry = table[fb.key]
            entry == null || nowMs >= entry.quarantineUntilMs
        }
        return community + fallbacks
    }

    /**
     * Picks the best available gRPC endpoint for Automatic Scan mode without stalling cold start:
     * 1. Immediately returns the #1 ranked verified community `:16110`/`:16210` node if available.
     * 2. Otherwise kicks off asynchronous 3-tier discovery in the background and races up to
     *    ~4 seconds (`COLD_START_COMMUNITY_RACE_MS`) for the very first verified community node.
     * 3. If no community `:16110` node locks in within ~4 seconds (e.g. port 16110 firewalled
     *    or still scanning on slow 5G), immediately returns `toccata.kaspium.io:443 (TLS)` or
     *    `node.kaspium.io:443 (TLS)` so Live gRPC connects right away while community discovery
     *    continues asynchronously in the background.
     */
    suspend fun getPreferredEndpoint(
        network: KaspaNetwork,
        excludeKeys: Set<String> = emptySet(),
    ): NodeEndpoint? {
        val now = System.currentTimeMillis()
        val existing = rankedActiveEndpoints(network, includeTlsFallback = false, nowMs = now)
            .firstOrNull { it.key !in excludeKeys }
        if (existing != null) return existing

        // Start background discovery if not already running; race up to ~4s for first verified :16110 node
        launchBackgroundScan(network, force = false)

        val deadlineMs = System.currentTimeMillis() + COLD_START_COMMUNITY_RACE_MS
        while (System.currentTimeMillis() < deadlineMs) {
            val found = rankedActiveEndpoints(network, includeTlsFallback = false, nowMs = System.currentTimeMillis())
                .firstOrNull { it.key !in excludeKeys }
            if (found != null) {
                logDebug("Cold-start race locked community node ${found.hostPort}")
                return found
            }
            val scanJob = activeBgScanJobs[network]
            if (scanJob == null || !scanJob.isActive) break
            delay(150L)
        }

        // One final check in case a node landed right at the deadline
        val afterRace = rankedActiveEndpoints(network, includeTlsFallback = false, nowMs = System.currentTimeMillis())
            .firstOrNull { it.key !in excludeKeys }
        if (afterRace != null) return afterRace

        // Hybrid TLS firewall fallback: try toccata.kaspium.io:443 and node.kaspium.io:443 before REST!
        val fallback = tlsFallbackEndpoints(network).firstOrNull { fb ->
            if (fb.key in excludeKeys) return@firstOrNull false
            val entry = tableFor(network)[fb.key]
            entry == null || System.currentTimeMillis() >= entry.quarantineUntilMs
        } ?: tlsFallbackEndpoints(network).firstOrNull { it.key !in excludeKeys }

        if (fallback != null) {
            logDebug("No verified community node within ${COLD_START_COMMUNITY_RACE_MS}ms — falling back to Live gRPC TLS ${fallback.hostPort}")
        }
        return fallback
    }

    fun launchBackgroundScan(network: KaspaNetwork, force: Boolean = false) {
        val existing = activeBgScanJobs[network]
        if (!force && existing?.isActive == true) return
        val job = bgScope.launch {
            runCatching { ensurePoolPopulated(network, force = force) }
        }
        activeBgScanJobs[network] = job
    }

    fun recordSuccess(
        network: KaspaNetwork,
        endpoint: NodeEndpoint,
        latencyMs: Long,
        virtualDaaScore: Long = 0L,
        networkName: String = "",
        isSynced: Boolean = true,
        isUtxoIndexed: Boolean = true,
    ) {
        val table = tableFor(network)
        val now = System.currentTimeMillis()
        val isFallback = isTlsFallbackEndpoint(network, endpoint)
        table.compute(endpoint.key) { _, prev ->
            PooledNodeEntry(
                endpoint = endpoint,
                isSynced = isSynced,
                isUtxoIndexed = isUtxoIndexed,
                networkName = networkName.ifBlank { prev?.networkName.orEmpty() },
                virtualDaaScore = if (virtualDaaScore > 0L) virtualDaaScore else (prev?.virtualDaaScore ?: 0L),
                latencyMs = latencyMs.coerceAtLeast(1L),
                probedAtMs = now,
                consecutiveFailures = 0,
                quarantineUntilMs = 0L,
                isTlsFallback = isFallback,
            )
        }
        if (!isFallback && isSynced && isUtxoIndexed) {
            persistBestKnownGood(network)
        }
    }

    fun recordFailure(network: KaspaNetwork, endpoint: NodeEndpoint) {
        val table = tableFor(network)
        val now = System.currentTimeMillis()
        val isFallback = isTlsFallbackEndpoint(network, endpoint)
        table.compute(endpoint.key) { _, prev ->
            val nextFailures = (prev?.consecutiveFailures ?: 0) + 1
            val quarantineMs = if (nextFailures >= PooledNodeEntry.QUARANTINE_STRIKES) {
                val shift = (nextFailures - PooledNodeEntry.QUARANTINE_STRIKES).coerceAtMost(6)
                (20_000L * 2.0.pow(shift.toDouble()).toLong()).coerceAtMost(30L * 60_000L)
            } else {
                0L
            }
            (prev ?: PooledNodeEntry(endpoint = endpoint, isTlsFallback = isFallback)).copy(
                consecutiveFailures = nextFailures,
                quarantineUntilMs = if (quarantineMs > 0L) now + quarantineMs else 0L,
            )
        }
    }

    /**
     * Runs the 3-tier Automatic Scan pipeline while respecting mobile data & battery budgets:
     * - Fast parallel TCP pre-filter discards closed P2P-only peers in <= 2.2s before creating any
     *   gRPC channels.
     * - Metered (cellular/5G) networks throttle gRPC probe concurrency to 3 and skip unnecessary
     *   re-scans once at least 2 verified community nodes are active.
     */
    suspend fun ensurePoolPopulated(network: KaspaNetwork, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val metered = isMeteredNetwork()
        val currentActive = activeVerifiedCount(network, now)
        val minIntervalMs = if (metered) METERED_REFRESH_COOLDOWN_MS else UNMETERED_REFRESH_COOLDOWN_MS

        if (!force) {
            val lastRun = lastBackgroundRefreshMs[network] ?: 0L
            if (currentActive >= TARGET_ACTIVE_NODES && now - lastRun < minIntervalMs) {
                return@withContext currentActive
            }
            if (metered && currentActive >= 2 && now - lastRun < minIntervalMs) {
                return@withContext currentActive
            }
        }

        refreshMutex.withLock {
            val lockNow = System.currentTimeMillis()
            val table = tableFor(network)
            val isMeteredNow = isMeteredNetwork()
            val tcpConcurrency = if (isMeteredNow) METERED_TCP_CONCURRENCY else WIFI_TCP_CONCURRENCY
            val grpcConcurrency = if (isMeteredNow) METERED_GRPC_CONCURRENCY else WIFI_GRPC_CONCURRENCY
            val failReasons = ConcurrentHashMap<String, AtomicInteger>()

            // Tier 1: Cold start — persisted last-known-good (up to 8) + Mainnet fallback IPs,
            // ALL fast TCP pre-filtered first so dead fallback IPs never stall startup!
            val tier1Candidates = buildList {
                addAll(cacheStore.load(network))
                table.values
                    .filter { !it.isTlsFallback && lockNow >= it.quarantineUntilMs }
                    .sortedBy { it.latencyMs }
                    .take(TARGET_ACTIVE_NODES)
                    .forEach { add(it.endpoint) }
                if (network == KaspaNetwork.MAINNET) {
                    MAINNET_FALLBACK_IPS.forEach { ip ->
                        add(NodeEndpoint(ip, MAINNET_GRPC_PORT, useTls = false))
                    }
                }
            }.distinctBy { it.key }

            if (tier1Candidates.isNotEmpty()) {
                val (tcpPass, grpcPass) = filterAndProfileBatch(
                    network = network,
                    candidates = tier1Candidates,
                    tcpConcurrency = tcpConcurrency,
                    grpcConcurrency = grpcConcurrency,
                    failReasons = failReasons,
                )
                logDebug("Tier1[${network.id}]: candidates=${tier1Candidates.size}, tcpPass=$tcpPass, grpcPass=$grpcPass")
            }

            // Tier 2: Wide IPv4-first Public Kaspa DNS Seeders (when active verified nodes < 8)
            var activeNow = activeVerifiedCount(network)
            val lastDns = lastDnsAttemptMs[network] ?: 0L
            if (activeNow < TARGET_ACTIVE_NODES && (force || lockNow - lastDns >= DNS_COOLDOWN_MS)) {
                lastDnsAttemptMs[network] = lockNow
                val dnsResolved = resolveDnsSeeders(network)
                val ipv4Count = dnsResolved.count { !it.host.contains(':') }
                val ipv6Count = dnsResolved.size - ipv4Count
                val alreadyHealthyKeys = table.values
                    .filter { it.isHealthyActive(System.currentTimeMillis()) }
                    .mapTo(HashSet()) { it.endpoint.key }
                val dnsCandidates = dnsResolved
                    .filter { it.key !in alreadyHealthyKeys }
                    .take(MAX_DNS_CANDIDATES)
                val (tcpPass, grpcPass) = if (dnsCandidates.isNotEmpty()) {
                    filterAndProfileBatch(
                        network = network,
                        candidates = dnsCandidates,
                        tcpConcurrency = tcpConcurrency,
                        grpcConcurrency = grpcConcurrency,
                        failReasons = failReasons,
                    )
                } else {
                    0 to 0
                }
                logDebug(
                    "Tier2-DNS[${network.id}]: resolved=${dnsResolved.size} (v4=$ipv4Count, v6=$ipv6Count), " +
                        "tested=${dnsCandidates.size}, tcpPass=$tcpPass, grpcPass=$grpcPass",
                )
            }

            // Tier 3: P2P Gossip Crawl via GetPeerAddresses (16111 -> 16110 / 16211 -> 16210)
            activeNow = activeVerifiedCount(network)
            val lastGossip = lastGossipAttemptMs[network] ?: 0L
            val allowGossip = !isMeteredNow || activeNow < 2
            if (allowGossip && activeNow < TARGET_ACTIVE_NODES && (force || lockNow - lastGossip >= GOSSIP_COOLDOWN_MS)) {
                lastGossipAttemptMs[network] = lockNow
                val seedNode = rankedActiveEndpoints(network, includeTlsFallback = false).firstOrNull()
                    ?: tlsFallbackEndpoints(network).firstOrNull()
                if (seedNode != null) {
                    val rawPeers = fetchGossipPeers(seedNode)
                    val knownKeys = table.keys.toSet()
                    val gossipCandidates = rawPeers
                        .mapNotNull { parseGossipPeerToGrpcEndpoint(it, network) }
                        .distinctBy { it.key }
                        .sortedBy { if (it.host.contains(':')) 1 else 0 } // IPv4 first
                        .filter { it.key !in knownKeys }
                        .take(MAX_GOSSIP_CANDIDATES)
                    val (tcpPass, grpcPass) = if (gossipCandidates.isNotEmpty()) {
                        filterAndProfileBatch(
                            network = network,
                            candidates = gossipCandidates,
                            tcpConcurrency = tcpConcurrency,
                            grpcConcurrency = grpcConcurrency,
                            failReasons = failReasons,
                        )
                    } else {
                        0 to 0
                    }
                    logDebug(
                        "Tier3-Gossip[${network.id}] via ${seedNode.hostPort}: rawPeers=${rawPeers.size}, " +
                            "mapped=${gossipCandidates.size}, tcpPass=$tcpPass, grpcPass=$grpcPass",
                    )
                }
            }

            val finalActive = activeVerifiedCount(network)
            val topReasons = failReasons.entries
                .sortedByDescending { it.value.get() }
                .take(4)
                .joinToString(", ") { "${it.key}=${it.value.get()}" }
                .ifEmpty { "none" }
            logDebug("ScanComplete[${network.id}]: activePool=$finalActive (metered=$isMeteredNow), topFailures=[$topReasons]")

            lastBackgroundRefreshMs[network] = System.currentTimeMillis()
            persistBestKnownGood(network)
            finalActive
        }
    }

    /**
     * Two-stage pipeline:
     * 1. Fast high-concurrency raw TCP `:16110` pre-filter (`2.2s` timeout).
     * 2. Immediately profiles each TCP-open candidate via gRPC (`GetInfo` + `GetBlockDagInfo`)
     *    and writes verified nodes into the live pool immediately so cold-start callers unblock
     *    on the very first healthy node!
     */
    private suspend fun filterAndProfileBatch(
        network: KaspaNetwork,
        candidates: List<NodeEndpoint>,
        tcpConcurrency: Int,
        grpcConcurrency: Int,
        failReasons: ConcurrentHashMap<String, AtomicInteger>,
    ): Pair<Int, Int> = coroutineScope {
        val tcpGate = Semaphore(tcpConcurrency.coerceAtLeast(1))
        val grpcGate = Semaphore(grpcConcurrency.coerceAtLeast(1))
        val table = tableFor(network)
        val tcpPassCount = AtomicInteger(0)
        val grpcPassCount = AtomicInteger(0)

        candidates.map { endpoint ->
            async(Dispatchers.IO) {
                val tcpOk = tcpGate.withPermit {
                    tcpPreFilter(endpoint.host, endpoint.port, TCP_PREFILTER_TIMEOUT_MS)
                }
                if (!tcpOk) {
                    failReasons.computeIfAbsent("tcp_closed") { AtomicInteger(0) }.incrementAndGet()
                    if (table.containsKey(endpoint.key)) {
                        recordFailure(network, endpoint)
                    }
                    return@async
                }
                tcpPassCount.incrementAndGet()

                // Cap excessive gRPC probes once we already hit our target pool size
                if (activeVerifiedCount(network) >= TARGET_ACTIVE_NODES + 2) {
                    return@async
                }

                val outcome = grpcGate.withPermit {
                    profileCandidate(endpoint, network)
                }
                when (outcome) {
                    is ProfileOutcome.Verified -> {
                        table[endpoint.key] = outcome.entry
                        grpcPassCount.incrementAndGet()
                    }
                    is ProfileOutcome.Rejected -> {
                        failReasons.computeIfAbsent(outcome.reason) { AtomicInteger(0) }.incrementAndGet()
                        if (table.containsKey(endpoint.key)) {
                            recordFailure(network, endpoint)
                        }
                    }
                }
            }
        }.awaitAll()

        tcpPassCount.get() to grpcPassCount.get()
    }

    private suspend fun resolveDnsSeeders(network: KaspaNetwork): List<NodeEndpoint> = coroutineScope {
        val hostnames = if (network.isTestnet) TESTNET10_DNS_SEEDERS else MAINNET_DNS_SEEDERS
        val port = if (network.isTestnet) TESTNET10_GRPC_PORT else MAINNET_GRPC_PORT
        val all = hostnames.map { hostname ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(DNS_LOOKUP_TIMEOUT_MS) {
                    runCatching {
                        InetAddress.getAllByName(hostname).mapNotNull { addr ->
                            if (isDisallowedIpAddress(addr)) return@mapNotNull null
                            val rawHost = addr.hostAddress?.substringBefore('%')?.trim() ?: return@mapNotNull null
                            NodeEndpoint(host = rawHost, port = port, useTls = false)
                        }
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.key }

        // Prioritize IPv4 ahead of IPv6 (more reliable across mobile 5G/NAT setups) while keeping IPv6 secondary
        val ipv4 = all.filter { !it.host.contains(':') }
        val ipv6 = all.filter { it.host.contains(':') }
        ipv4 + ipv6
    }

    private suspend fun fetchGossipPeers(endpoint: NodeEndpoint): List<String> = withContext(Dispatchers.IO) {
        val tempClient = KaspadGrpcClient(host = endpoint.host, port = endpoint.port, useTls = endpoint.useTls)
        try {
            tempClient.getPeerAddresses(timeoutMs = 6_000L)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        } finally {
            tempClient.close()
        }
    }

    /**
     * Profiles one TCP-reachable candidate node using universal `GetInfo` (`1063`/`1064`, falling
     * back to `GetServerInfo` if needed) + non-fatal `GetBlockDagInfo` (verifying network match
     * and freshness DAA score), and ALWAYS closes the temporary gRPC channel immediately in `finally`.
     */
    private suspend fun profileCandidate(endpoint: NodeEndpoint, network: KaspaNetwork): ProfileOutcome =
        withContext(Dispatchers.IO) {
            val probeClient = KaspadGrpcClient(host = endpoint.host, port = endpoint.port, useTls = endpoint.useTls)
            try {
                val infoResult = runCatching { probeClient.getInfo(timeoutMs = GRPC_PROBE_TIMEOUT_MS) }
                    .recoverCatching { probeClient.getServerInfo(timeoutMs = GRPC_PROBE_TIMEOUT_MS) }
                val info = infoResult.getOrElse { err ->
                    val msg = err.message.orEmpty().lowercase()
                    val reason = when {
                        msg.contains("timed out") || err is kotlinx.coroutines.TimeoutCancellationException -> "grpc_timeout"
                        msg.contains("unavailable") || msg.contains("reset") || msg.contains("refused") -> "grpc_unavailable"
                        else -> "grpc_error"
                    }
                    return@withContext ProfileOutcome.Rejected(reason)
                }
                if (!info.reachable) return@withContext ProfileOutcome.Rejected("unreachable")
                if (!info.isSynced) return@withContext ProfileOutcome.Rejected("not_synced")
                if (!info.isUtxoIndexed) return@withContext ProfileOutcome.Rejected("no_utxo_index")

                // GetBlockDagInfo is heavier; treat transient DAG timeout as non-fatal once GetInfo
                // already proved synced + utxoindex, but strictly reject wrong-network nodes!
                val dag = runCatching { probeClient.getBlockDagInfoFull(timeoutMs = DAG_PROBE_TIMEOUT_MS) }.getOrNull()
                val reportedNetwork = (dag?.first?.takeIf { it.isNotBlank() } ?: info.networkName).trim()
                if (reportedNetwork.isNotBlank() && !matchesKaspaNetwork(reportedNetwork, network)) {
                    return@withContext ProfileOutcome.Rejected("wrong_network")
                }
                val daa = dag?.second?.takeIf { it > 0L } ?: info.virtualDaaScore
                ProfileOutcome.Verified(
                    PooledNodeEntry(
                        endpoint = endpoint,
                        isSynced = true,
                        isUtxoIndexed = true,
                        networkName = reportedNetwork,
                        virtualDaaScore = daa,
                        latencyMs = info.latencyMs.coerceAtLeast(1L),
                        probedAtMs = System.currentTimeMillis(),
                        consecutiveFailures = 0,
                        quarantineUntilMs = 0L,
                        isTlsFallback = isTlsFallbackEndpoint(network, endpoint),
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                ProfileOutcome.Rejected("probe_exception")
            } finally {
                // Critical mobile hygiene: never leave idle probe channels open!
                probeClient.close()
            }
        }

    private fun persistBestKnownGood(network: KaspaNetwork) {
        val top = rankedActiveEndpoints(network, includeTlsFallback = false).take(TARGET_ACTIVE_NODES)
        if (top.isNotEmpty()) {
            cacheStore.save(network, top)
        }
    }

    companion object {
        private const val LOG_TAG = "KaspaNodePool"

        const val MAINNET_GRPC_PORT = 16110
        const val MAINNET_P2P_PORT = 16111
        const val TESTNET10_GRPC_PORT = 16210
        const val TESTNET10_P2P_PORT = 16211

        const val TARGET_ACTIVE_NODES = 8
        const val MAX_DNS_CANDIDATES = 80
        const val MAX_GOSSIP_CANDIDATES = 48
        const val COLD_START_COMMUNITY_RACE_MS = 4_000L
        const val DAA_LAG_THRESHOLD = 100L
        const val DAA_PER_SECOND = 10L
        const val PROFILE_FRESHNESS_TTL_MS = 10L * 60_000L

        private const val WIFI_TCP_CONCURRENCY = 16
        private const val METERED_TCP_CONCURRENCY = 12
        private const val WIFI_GRPC_CONCURRENCY = 5
        private const val METERED_GRPC_CONCURRENCY = 3
        private const val TCP_PREFILTER_TIMEOUT_MS = 2_200
        private const val GRPC_PROBE_TIMEOUT_MS = 6_500L
        private const val DAG_PROBE_TIMEOUT_MS = 6_000L
        private const val DNS_LOOKUP_TIMEOUT_MS = 5_000L
        private const val DNS_COOLDOWN_MS = 60_000L
        private const val GOSSIP_COOLDOWN_MS = 60_000L
        private const val UNMETERED_REFRESH_COOLDOWN_MS = 30_000L
        private const val METERED_REFRESH_COOLDOWN_MS = 90_000L

        val MAINNET_TLS_FALLBACKS: List<NodeEndpoint> = listOf(
            NodeEndpoint(host = "toccata.kaspium.io", port = 443, useTls = true),
            NodeEndpoint(host = "node.kaspium.io", port = 443, useTls = true),
        )

        val MAINNET_DNS_SEEDERS: List<String> = listOf(
            "n.seeder1.kaspad.net",
            "n.seeder2.kaspad.net",
            "n.seeder3.kaspad.net",
            "n.seeder4.kaspad.net",
            "kaspadns.kaspacalc.net",
            "n-mainnet.kaspa.ws",
            "kaspa.aspectron.org",
        )

        val MAINNET_FALLBACK_IPS: List<String> = listOf(
            "212.227.144.45",
            "87.236.31.226",
            "82.66.82.52",
            "72.28.135.10",
            "23.118.8.164",
            "218.81.36.168",
        )

        val TESTNET10_DNS_SEEDERS: List<String> = listOf(
            "seeder1-tn.kaspad.net",
            "dnsseeder-kaspa-testnet.x-con.at",
            "n-testnet-10.kaspa.ws",
        )

        fun tlsFallbackEndpoints(network: KaspaNetwork): List<NodeEndpoint> =
            if (network == KaspaNetwork.MAINNET) MAINNET_TLS_FALLBACKS else emptyList()

        fun isTlsFallbackEndpoint(network: KaspaNetwork, endpoint: NodeEndpoint): Boolean =
            tlsFallbackEndpoints(network).any { it.key == endpoint.key }

        fun mainnetTlsFallback(network: KaspaNetwork): NodeEndpoint? =
            tlsFallbackEndpoints(network).firstOrNull()

        /**
         * Raw ~2.2s TCP socket pre-filter discarding unreachable/firewalled peers cheaply before
         * allocating any OkHttp gRPC channel or HTTP/2 thread pool.
         */
        fun tcpPreFilter(host: String, port: Int, timeoutMs: Int = TCP_PREFILTER_TIMEOUT_MS): Boolean {
            val cleanHost = host.trim().trim('[', ']')
            if (cleanHost.isEmpty() || port !in 1..65535) return false
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                true
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { socket.close() }
            }
        }

        /**
         * Verifies a reported `kaspad` network name strictly matches the active [KaspaNetwork].
         */
        fun matchesKaspaNetwork(reportedName: String, expected: KaspaNetwork): Boolean {
            val norm = reportedName.trim().lowercase()
            if (norm.isEmpty()) return false
            return when (expected) {
                KaspaNetwork.MAINNET -> norm == "mainnet" || norm == "kaspa-mainnet"
                KaspaNetwork.TESTNET_10 -> norm == "testnet-10" || norm == "kaspa-testnet-10" || norm.contains("testnet-10")
            }
        }

        /**
         * Parses a gossiped P2P peer string (`ip:16111` or `[ipv6]:16111`), rejects loopback,
         * RFC1918 private, CGNAT, link-local, ULA, and multicast IPs, and maps the P2P port
         * strictly to the corresponding gRPC port (`16111 -> 16110`, `16211 -> 16210`).
         */
        fun parseGossipPeerToGrpcEndpoint(rawAddr: String, network: KaspaNetwork): NodeEndpoint? {
            val trimmed = rawAddr.trim()
            if (trimmed.isEmpty() || trimmed.length > 260 || trimmed.any { it.isWhitespace() }) return null

            val host: String
            val portStr: String
            if (trimmed.startsWith('[')) {
                val closeBracket = trimmed.indexOf("]:")
                if (closeBracket <= 1) return null
                host = trimmed.substring(1, closeBracket)
                portStr = trimmed.substring(closeBracket + 2)
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon <= 0 || trimmed.indexOf(':') != lastColon) return null // reject unbracketed IPv6
                host = trimmed.substring(0, lastColon)
                portStr = trimmed.substring(lastColon + 1)
            }

            val rawPort = portStr.toIntOrNull() ?: return null
            val grpcPort = when (network) {
                KaspaNetwork.MAINNET -> when (rawPort) {
                    MAINNET_P2P_PORT, MAINNET_GRPC_PORT -> MAINNET_GRPC_PORT
                    else -> return null
                }
                KaspaNetwork.TESTNET_10 -> when (rawPort) {
                    TESTNET10_P2P_PORT, TESTNET10_GRPC_PORT -> TESTNET10_GRPC_PORT
                    else -> return null
                }
            }

            // Require numeric IP literal from P2P gossip (no arbitrary DNS rebinding from untrusted peers)
            val parsedIp = parseNumericIpLiteral(host) ?: return null
            if (isDisallowedIpAddress(parsedIp)) return null

            val normalizedHost = parsedIp.hostAddress?.substringBefore('%') ?: return null
            return NodeEndpoint(host = normalizedHost, port = grpcPort, useTls = false)
        }

        private fun parseNumericIpLiteral(host: String): InetAddress? {
            val isIpv4Literal = host.count { it == '.' } == 3 &&
                host.split('.').all { seg -> seg.isNotEmpty() && seg.all { it.isDigit() } && (seg.toIntOrNull() ?: -1) in 0..255 }
            val isIpv6Literal = host.contains(':') &&
                host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            if (!isIpv4Literal && !isIpv6Literal) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }

        fun isDisallowedIpAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return true
            }
            val bytes = addr.address
            if (addr is Inet4Address && bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return true
                if (b0 == 100 && (b1 in 64..127)) return true
                if (b0 == 169 && b1 == 254) return true
                if (b0 == 172 && (b1 in 16..31)) return true
                if (b0 == 192 && b1 == 168) return true
            } else if (addr is Inet6Address && bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                if ((b0 and 0xFE) == 0xFC) return true
            }
            return false
        }

        /**
         * Ranks healthy active nodes by lowest latency while demoting any node lagging more than
         * [DAA_LAG_THRESHOLD] (100 DAA ≈ 10 seconds at 10 BPS) behind the median projected DAA score.
         */
        fun rankActiveNodes(
            entries: Collection<PooledNodeEntry>,
            nowMs: Long = System.currentTimeMillis(),
        ): List<PooledNodeEntry> {
            val active = entries.filter { it.isHealthyActive(nowMs) }
            if (active.isEmpty()) return emptyList()

            fun projectedDaa(entry: PooledNodeEntry): Long? {
                if (entry.virtualDaaScore <= 0L || entry.probedAtMs <= 0L) return null
                val ageMs = (nowMs - entry.probedAtMs).coerceAtLeast(0L)
                if (ageMs > PROFILE_FRESHNESS_TTL_MS) return null
                return entry.virtualDaaScore + (ageMs / 1000L) * DAA_PER_SECOND
            }

            val projectedScores = active.mapNotNull(::projectedDaa).sorted()
            val medianDaa = if (projectedScores.isNotEmpty()) {
                projectedScores[projectedScores.size / 2]
            } else {
                null
            }

            return active.sortedWith(
                compareBy<PooledNodeEntry>(
                    { entry ->
                        val daa = projectedDaa(entry)
                        medianDaa != null && daa != null && (medianDaa - daa) > DAA_LAG_THRESHOLD
                    },
                    { it.latencyMs },
                ),
            )
        }
    }
}
