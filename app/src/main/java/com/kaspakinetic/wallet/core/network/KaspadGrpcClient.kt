package com.kaspakinetic.wallet.core.network

import com.kaspakinetic.wallet.core.model.FeeEstimate
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.model.UtxoItem
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, strictly idempotent wrapper around a gRPC client request [StreamObserver].
 *
 * Prevents `java.lang.IllegalStateException: call already half-closed` inside
 * `DelayedClientCall.drainPendingCalls` on `grpc-default-executor-*` when concurrent timeouts,
 * reconnects, or `close()` calls race to tear down a stream before connection completes.
 */
internal class GuardedStreamObserver<T>(
    private val delegate: StreamObserver<T>,
) {
    private val halfClosed = AtomicBoolean(false)

    val isHalfClosed: Boolean get() = halfClosed.get()

    @Synchronized
    fun send(value: T): Boolean {
        if (halfClosed.get()) return false
        return try {
            delegate.onNext(value)
            true
        } catch (t: Throwable) {
            halfClosed.set(true)
            throw t
        }
    }

    /**
     * Completes (half-closes) the underlying stream at most once across all threads.
     * Returns `true` if this call actually invoked `delegate.onCompleted()`, `false` if already closed.
     */
    @Synchronized
    fun completeOnce(): Boolean {
        if (!halfClosed.compareAndSet(false, true)) return false
        runCatching { delegate.onCompleted() }
        return true
    }

    /**
     * Marks the stream as already closed from the server/transport side so future teardown
     * will not attempt a redundant `onCompleted()` call.
     */
    @Synchronized
    fun markServerClosed() {
        halfClosed.set(true)
    }
}

/**
 * Kaspium-style persistent bidirectional `kaspad` gRPC client over `/protowire.RPC/MessageStream`.
 *
 * Maintains a single HTTP/2 gRPC stream multiplexing:
 * - Request/Response RPCs (`GetServerInfo`, `GetBlockDagInfo`, `GetBalancesByAddresses`,
 *   `GetUtxosByAddresses`, `GetFeeEstimate`, `SubmitTransaction`)
 * - Real-time push notifications (`NotifyVirtualDaaScoreChanged`, `NotifyUtxosChanged`, `NotifyBlockAdded`)
 * - Conservative HTTP/2 keepalive (`90s / 20s`, `keepAliveWithoutCalls = false`) avoiding
 *   `ENHANCE_YOUR_CALM`, paired with clean channel rebuild on 5G/app-switch drops
 * - Serialized `SubmitTransaction` calls with automatic mid-batch reconnect retry
 */
class KaspadGrpcClient(
    val host: String,
    val port: Int,
    val useTls: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val connectMutex = Mutex()
    private val submitMutex = Mutex()
    private val nextId = AtomicLong(1L)
    private val streamGeneration = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val lastInboundFrameMs = AtomicLong(0L)

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<KaspadProtoCodec.DecodedResponse>>()
    private val watchedAddresses = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var channel: ManagedChannel? = null
    @Volatile private var requestObserver: GuardedStreamObserver<ByteArray>? = null
    @Volatile private var reconnectJob: Job? = null

    @Volatile private var daaListener: ((Long) -> Unit)? = null
    @Volatile private var utxoListener: ((List<UtxoItem>, List<KaspadProtoCodec.RemovedUtxoRef>) -> Unit)? = null
    @Volatile private var blockAddedListener: ((KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit)? = null
    @Volatile private var connectionListener: ((Boolean) -> Unit)? = null
    @Volatile private var subscribeDaaActive: Boolean = false
    @Volatile private var subscribeBlockAddedActive: Boolean = false

    val isConnected: Boolean get() = connected.get() && !closed.get() && requestObserver?.isHalfClosed == false

    fun lastActivityAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastInboundFrameMs.get()
        return if (last <= 0L) Long.MAX_VALUE else (nowMs - last).coerceAtLeast(0L)
    }

    fun setListeners(
        onDaaScore: ((Long) -> Unit)?,
        onUtxosChanged: ((List<UtxoItem>, List<KaspadProtoCodec.RemovedUtxoRef>) -> Unit)?,
        onConnectionChanged: ((Boolean) -> Unit)?,
    ) {
        daaListener = onDaaScore
        utxoListener = onUtxosChanged
        connectionListener = onConnectionChanged
    }

    fun setBlockAddedListener(listener: ((KaspadProtoCodec.LiveBlockHeaderInfo) -> Unit)?) {
        blockAddedListener = listener
    }

    suspend fun ensureConnected() {
        if (closed.get()) throw IOException("gRPC client is closed")
        if (requestObserver != null && connected.get()) return
        connectMutex.withLock {
            if (closed.get()) throw IOException("gRPC client is closed")
            if (requestObserver != null && connected.get()) return
            openStreamLocked(rebuildChannel = false)
        }
    }

    /**
     * Tears down any stale/half-open HTTP/2 channel (common after Android app switch or 5G
     * handoff), builds a fresh `ManagedChannel` + `MessageStream`, proves end-to-end liveness
     * via `GetServerInfo`, and automatically restores all push subscriptions.
     */
    suspend fun forceReconnect(timeoutMs: Long = 8_000L): NodeHealth {
        if (closed.get()) throw IOException("gRPC client is closed")
        reconnectJob?.cancel()
        reconnectJob = null
        connectMutex.withLock {
            if (closed.get()) throw IOException("gRPC client is closed")
            teardownStreamAndChannelLocked(IOException("Rebuilding gRPC channel"))
            openStreamLocked(rebuildChannel = true)
        }
        val health = getServerInfo(timeoutMs = timeoutMs)
        restoreSubscriptions()
        return health
    }

    /**
     * Verifies live connectivity with a quick `GetServerInfo` check; if the stream is closed,
     * half-open, or unresponsive, transparently rebuilds the channel and re-subscribes.
     */
    suspend fun verifyOrReconnect(timeoutMs: Long = 8_000L): NodeHealth {
        if (closed.get()) throw IOException("gRPC client is closed")
        if (isConnected) {
            val check = runCatching { getServerInfo(timeoutMs = timeoutMs) }
            if (check.isSuccess) {
                return check.getOrThrow()
            }
        }
        return forceReconnect(timeoutMs = timeoutMs)
    }

    private fun openStreamLocked(rebuildChannel: Boolean) {
        if (rebuildChannel) {
            val oldCh = channel
            channel = null
            runCatching { oldCh?.shutdownNow() }
        }
        val ch = channel?.takeIf { !it.isShutdown && !it.isTerminated } ?: buildChannel().also { channel = it }
        runCatching { ch.resetConnectBackoff() }

        val gen = streamGeneration.incrementAndGet()
        val call = ch.newCall(MESSAGE_STREAM_METHOD, CallOptions.DEFAULT)
        val responseObserver = object : StreamObserver<ByteArray> {
            override fun onNext(value: ByteArray) {
                if (streamGeneration.get() != gen) return
                lastInboundFrameMs.set(System.currentTimeMillis())
                val decoded = runCatching { KaspadProtoCodec.decodeResponse(value) }.getOrNull() ?: return
                if (!connected.getAndSet(true)) {
                    connectionListener?.invoke(true)
                }
                // Dispatch push notifications unconditionally by payload type so notifications are
                // never dropped even if a server/proxy populates a non-zero correlation ID.
                when (val p = decoded.payload) {
                    is KaspadProtoCodec.Payload.VirtualDaaScoreChanged -> {
                        if (p.virtualDaaScore > 0L) daaListener?.invoke(p.virtualDaaScore)
                        return
                    }
                    is KaspadProtoCodec.Payload.BlockAdded -> {
                        blockAddedListener?.invoke(p.block)
                        return
                    }
                    is KaspadProtoCodec.Payload.UtxosChanged -> {
                        if (p.added.isNotEmpty() || p.removed.isNotEmpty()) {
                            utxoListener?.invoke(p.added, p.removed)
                        }
                        return
                    }
                    else -> Unit
                }
                if (decoded.id != 0L) {
                    pending.remove(decoded.id)?.complete(decoded)
                }
            }

            override fun onError(t: Throwable) {
                if (streamGeneration.get() != gen) return
                handleStreamClosed(gen, t, rebuildOnReconnect = true)
            }

            override fun onCompleted() {
                if (streamGeneration.get() != gen) return
                handleStreamClosed(gen, IOException("gRPC stream completed"), rebuildOnReconnect = false)
            }
        }
        requestObserver = GuardedStreamObserver(ClientCalls.asyncBidiStreamingCall(call, responseObserver))
        // Mark stream open for outbound RPCs; connectionListener fires true once handshake/frames confirm
        connected.set(true)
    }

    private fun buildChannel(): ManagedChannel {
        val cleanHost = host.trim().trim('[', ']')
        val builder = OkHttpChannelBuilder.forAddress(cleanHost, port)
            // Conservative HTTP/2 keepalive: keeps mobile 5G NAT mappings alive without
            // tripping server/proxy ENHANCE_YOUR_CALM (too_many_pings) limits.
            .keepAliveTime(90L, TimeUnit.SECONDS)
            .keepAliveTimeout(20L, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false)
            .maxInboundMessageSize(32 * 1024 * 1024)
        if (useTls) {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }
        return builder.build()
    }

    private fun teardownStreamAndChannelLocked(cause: Throwable) {
        streamGeneration.incrementAndGet()
        connected.set(false)
        val obs = requestObserver
        requestObserver = null
        obs?.completeOnce()
        val oldCh = channel
        channel = null
        runCatching { oldCh?.shutdownNow() }
        failAllPending(cause)
    }

    private fun failAllPending(cause: Throwable) {
        val err = IOException(KaspaErrorSanitizer.sanitize(cause.message ?: "gRPC stream disconnected"), cause)
        pending.entries.forEach { (k, def) ->
            if (pending.remove(k, def)) {
                def.completeExceptionally(err)
            }
        }
    }

    private fun handleStreamClosed(gen: Long, cause: Throwable, rebuildOnReconnect: Boolean) {
        if (streamGeneration.get() != gen) return
        val wasConnected = connected.getAndSet(false)
        val obs = requestObserver
        requestObserver = null
        obs?.markServerClosed()
        if (rebuildOnReconnect) {
            val oldCh = channel
            channel = null
            runCatching { oldCh?.shutdownNow() }
        }
        if (wasConnected) {
            connectionListener?.invoke(false)
        }
        failAllPending(cause)
        if (!closed.get() && (subscribeDaaActive || subscribeBlockAddedActive || watchedAddresses.isNotEmpty())) {
            scheduleReconnect()
        }
    }

    private suspend fun restoreSubscriptions() {
        if (subscribeDaaActive) {
            runCatching { subscribeVirtualDaaScoreChanged() }
        }
        if (subscribeBlockAddedActive) {
            runCatching { subscribeBlockAdded() }
        }
        val addrs = watchedAddresses.toList()
        if (addrs.isNotEmpty()) {
            runCatching { sendSubscribeUtxosChanged(addrs, stop = false) }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || closed.get()) return
        reconnectJob = scope.launch {
            var backoffMs = 1_500L
            while (isActive && !closed.get() && !isConnected) {
                delay(backoffMs)
                if (closed.get()) return@launch
                val ok = runCatching {
                    forceReconnect(timeoutMs = 8_000L)
                    true
                }.getOrDefault(false)
                if (ok) {
                    connectionListener?.invoke(true)
                    return@launch
                }
                backoffMs = (backoffMs * 2L).coerceAtMost(20_000L)
            }
        }
    }

    private suspend fun callRaw(
        timeoutMs: Long = 12_000L,
        encode: (Long) -> ByteArray,
    ): KaspadProtoCodec.DecodedResponse {
        ensureConnected()
        val genAtStart = streamGeneration.get()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<KaspadProtoCodec.DecodedResponse>()
        pending[id] = deferred
        val payload = encode(id)
        val obsAtStart = requestObserver
        try {
            sendMutex.withLock {
                val obs = requestObserver ?: throw IOException("gRPC stream not open")
                if (!obs.send(payload)) {
                    throw IOException("gRPC stream already half-closed")
                }
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            if (t !is CancellationException && t !is IllegalStateException) {
                // Reset stream & channel on transport/timeout failures using idempotent half-close
                // so concurrent threads can never trigger DelayedClientCall "call already half-closed".
                obsAtStart?.completeOnce()
                handleStreamClosed(genAtStart, t, rebuildOnReconnect = true)
            }
            throw t
        }
    }

    val endpointHostPort: String
        get() {
            val clean = host.trim().trim('[', ']')
            val formatted = if (clean.contains(':')) "[$clean]" else clean
            return "$formatted:$port"
        }

    /**
     * Universal `GetInfo` RPC (`1063`/`1064`) supported across all kaspad & rusty-kaspa versions.
     */
    suspend fun getInfo(timeoutMs: Long = 8_000L): NodeHealth {
        val start = System.currentTimeMillis()
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetInfoRequest(id) }
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        val info = resp.payload as? KaspadProtoCodec.Payload.ServerInfo
            ?: throw IOException("Unexpected gRPC response for GetInfo")
        if (info.error != null) throw IOException(info.error)
        val proto = if (useTls) "TLS" else "Plaintext"
        return info.health.copy(
            latencyMs = latency,
            transportUsed = "Live gRPC ($proto)",
            activeEndpoint = endpointHostPort,
            isTls = useTls,
        )
    }

    suspend fun getServerInfo(timeoutMs: Long = 8_000L): NodeHealth {
        val start = System.currentTimeMillis()
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetServerInfoRequest(id) }
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        val info = resp.payload as? KaspadProtoCodec.Payload.ServerInfo
            ?: throw IOException("Unexpected gRPC response for GetServerInfo")
        if (info.error != null) throw IOException(info.error)
        val proto = if (useTls) "TLS" else "Plaintext"
        return info.health.copy(
            latencyMs = latency,
            transportUsed = "Live gRPC ($proto)",
            activeEndpoint = endpointHostPort,
            isTls = useTls,
        )
    }

    data class LiveDagSnapshot(
        val networkName: String,
        val virtualDaaScore: Long,
        val pastMedianTimeMs: Long,
        val latencyMs: Long,
    )

    suspend fun getBlockDagSnapshot(timeoutMs: Long = 8_000L): LiveDagSnapshot {
        val start = System.currentTimeMillis()
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetBlockDagInfoRequest(id) }
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        val dag = resp.payload as? KaspadProtoCodec.Payload.BlockDagInfo
            ?: throw IOException("Unexpected gRPC response for GetBlockDagInfo")
        if (dag.error != null) throw IOException(dag.error)
        return LiveDagSnapshot(
            networkName = dag.networkName,
            virtualDaaScore = dag.virtualDaaScore,
            pastMedianTimeMs = dag.pastMedianTime,
            latencyMs = latency,
        )
    }

    suspend fun getBlockDagInfoFull(timeoutMs: Long = 8_000L): Triple<String, Long, Long> {
        val snap = getBlockDagSnapshot(timeoutMs)
        return Triple(snap.networkName, snap.virtualDaaScore, snap.latencyMs)
    }

    suspend fun getPeerAddresses(timeoutMs: Long = 6_000L): List<String> {
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetPeerAddressesRequest(id) }
        val peers = resp.payload as? KaspadProtoCodec.Payload.PeerAddresses
            ?: throw IOException("Unexpected gRPC response for GetPeerAddresses")
        if (peers.error != null) throw IOException(peers.error)
        return peers.addresses
    }

    suspend fun getBlockDagInfo(timeoutMs: Long = 8_000L): Pair<Long, Long> {
        val start = System.currentTimeMillis()
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetBlockDagInfoRequest(id) }
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        val dag = resp.payload as? KaspadProtoCodec.Payload.BlockDagInfo
            ?: throw IOException("Unexpected gRPC response for GetBlockDagInfo")
        if (dag.error != null) throw IOException(dag.error)
        return dag.virtualDaaScore to latency
    }

    suspend fun getBalancesByAddresses(addresses: List<String>, timeoutMs: Long = 12_000L): Map<String, Long> {
        if (addresses.isEmpty()) return emptyMap()
        val merged = LinkedHashMap<String, Long>()
        addresses.distinct().chunked(200).forEach { batch ->
            val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetBalancesByAddressesRequest(id, batch) }
            val result = resp.payload as? KaspadProtoCodec.Payload.Balances
                ?: throw IOException("Unexpected gRPC response for GetBalancesByAddresses")
            if (result.error != null) throw IOException(result.error)
            merged.putAll(result.balances)
        }
        return merged
    }

    suspend fun getUtxosByAddresses(addresses: List<String>, timeoutMs: Long = 15_000L): List<UtxoItem> {
        if (addresses.isEmpty()) return emptyList()
        val out = ArrayList<UtxoItem>()
        val seen = HashSet<String>()
        addresses.distinct().chunked(200).forEach { batch ->
            val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetUtxosByAddressesRequest(id, batch) }
            val result = resp.payload as? KaspadProtoCodec.Payload.Utxos
                ?: throw IOException("Unexpected gRPC response for GetUtxosByAddresses")
            if (result.error != null) throw IOException(result.error)
            for (u in result.utxos) {
                if (seen.add(u.outpointKey)) out.add(u)
            }
        }
        return out.sortedByDescending { it.amountSompi }
    }

    suspend fun getUtxosByCovenantIds(covenantIds: List<String>, timeoutMs: Long = 15_000L): List<UtxoItem> {
        if (covenantIds.isEmpty()) return emptyList()
        val out = ArrayList<UtxoItem>()
        val seen = HashSet<String>()
        covenantIds.distinct().sorted().chunked(200).forEach { batch ->
            val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetUtxosByCovenantIdsRequest(id, batch) }
            val result = resp.payload as? KaspadProtoCodec.Payload.Utxos
                ?: throw IOException("Unexpected gRPC response for GetUtxosByCovenantIds")
            if (result.error != null) throw IOException(result.error)
            for (u in result.utxos) {
                if (seen.add(u.outpointKey)) out.add(u)
            }
        }
        return out.sortedByDescending { it.amountSompi }
    }

    suspend fun getFeeEstimate(timeoutMs: Long = 8_000L): FeeEstimate {
        val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeGetFeeEstimateRequest(id) }
        val result = resp.payload as? KaspadProtoCodec.Payload.FeeEstimateResult
            ?: throw IOException("Unexpected gRPC response for GetFeeEstimate")
        if (result.error != null) throw IOException(result.error)
        return result.feeEstimate
    }

    /**
     * Serializes transaction broadcasts cleanly over the gRPC stream. If the stream disconnects
     * mid-batch (e.g. 5G hiccup during Miner Donations), cleanly rebuilds the gRPC channel once
     * and retries before propagating any transport error.
     */
    suspend fun submitTransaction(submitJson: String, timeoutMs: Long = 15_000L): String =
        submitMutex.withLock {
            suspend fun attemptOnce(): String {
                val resp = callRaw(timeoutMs) { id -> KaspadProtoCodec.encodeSubmitTransactionRequest(id, submitJson) }
                val result = resp.payload as? KaspadProtoCodec.Payload.SubmitTx
                    ?: throw IOException("Unexpected gRPC response for SubmitTransaction")
                if (result.error != null) {
                    throw IllegalStateException("Broadcast rejected: ${result.error}")
                }
                return result.transactionId
            }

            try {
                attemptOnce()
            } catch (ce: CancellationException) {
                throw ce
            } catch (rejected: IllegalStateException) {
                // Never retry consensus/mempool rejections
                throw rejected
            } catch (_: Throwable) {
                // Stream hiccup mid-batch: rebuild channel, restore subscriptions, and retry cleanly once.
                // If the first attempt already landed in the mempool before the disconnect, the retry
                // will report "already in the mempool" — treat that exact txid as accepted!
                forceReconnect(timeoutMs = 8_000L)
                try {
                    attemptOnce()
                } catch (retryRejected: IllegalStateException) {
                    val alreadyTxId = KaspaMempoolIdempotency.extractAlreadyInMempoolTxId(retryRejected.message)
                    if (alreadyTxId != null) alreadyTxId else throw retryRejected
                }
            }
        }

    suspend fun subscribeVirtualDaaScoreChanged() {
        subscribeDaaActive = true
        val resp = callRaw(8_000L) { id -> KaspadProtoCodec.encodeNotifyVirtualDaaScoreChangedRequest(id, stop = false) }
        val ack = resp.payload as? KaspadProtoCodec.Payload.Ack
        if (ack?.error != null) throw IOException(ack.error)
    }

    suspend fun subscribeBlockAdded() {
        subscribeBlockAddedActive = true
        val resp = callRaw(8_000L) { id -> KaspadProtoCodec.encodeNotifyBlockAddedRequest(id, stop = false) }
        val ack = resp.payload as? KaspadProtoCodec.Payload.Ack
        if (ack?.error != null) throw IOException(ack.error)
    }

    suspend fun unsubscribeBlockAdded() {
        subscribeBlockAddedActive = false
        blockAddedListener = null
        if (isConnected) {
            runCatching {
                callRaw(4_000L) { id -> KaspadProtoCodec.encodeNotifyBlockAddedRequest(id, stop = true) }
            }
        }
    }

    private suspend fun sendSubscribeUtxosChanged(addresses: List<String>, stop: Boolean) {
        if (addresses.isEmpty()) return
        addresses.chunked(200).forEach { batch ->
            val resp = callRaw(10_000L) { id -> KaspadProtoCodec.encodeNotifyUtxosChangedRequest(id, batch, stop = stop) }
            val ack = resp.payload as? KaspadProtoCodec.Payload.Ack
            if (ack?.error != null) throw IOException(ack.error)
        }
    }

    suspend fun subscribeUtxosChanged(addresses: Collection<String>) {
        val distinct = addresses.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        watchedAddresses.addAll(distinct)
        sendSubscribeUtxosChanged(distinct, stop = false)
    }

    /**
     * Replaces the watched address set for the active wallet (mirroring Kaspium's
     * `utxosChangedProvider`): unsubscribes addresses no longer belonging to the active wallet
     * (`NOTIFY_STOP`) and ensures ALL current receive + change addresses are subscribed (`NOTIFY_START`).
     */
    suspend fun setWatchedAddresses(addresses: Collection<String>, forceResubscribe: Boolean = false) {
        val desired = addresses.filter { it.isNotBlank() }.toSet()
        val removed = watchedAddresses.filter { it !in desired }
        val added = desired.filter { it !in watchedAddresses }

        if (removed.isNotEmpty()) {
            watchedAddresses.removeAll(removed.toSet())
            if (isConnected) {
                runCatching { sendSubscribeUtxosChanged(removed, stop = true) }
            }
        }
        watchedAddresses.addAll(desired)
        val toSubscribe = if (forceResubscribe) desired.toList() else added
        if (toSubscribe.isNotEmpty() && isConnected) {
            runCatching { sendSubscribeUtxosChanged(toSubscribe, stop = false) }
        }
    }

    suspend fun updateWatchedAddresses(addresses: Collection<String>) {
        val clean = addresses.filter { it.isNotBlank() }.toSet()
        val newlyAdded = clean.filter { it !in watchedAddresses }
        if (newlyAdded.isEmpty()) return
        watchedAddresses.addAll(newlyAdded)
        if (isConnected) {
            runCatching { sendSubscribeUtxosChanged(newlyAdded, stop = false) }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        streamGeneration.incrementAndGet()
        subscribeDaaActive = false
        subscribeBlockAddedActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        watchedAddresses.clear()
        daaListener = null
        utxoListener = null
        blockAddedListener = null
        connectionListener = null
        val obs = requestObserver
        requestObserver = null
        obs?.completeOnce()
        connected.set(false)
        pending.values.forEach { it.completeExceptionally(IOException("gRPC client closed")) }
        pending.clear()
        runCatching { channel?.shutdownNow() }
        channel = null
    }

    companion object {
        private val RAW_BYTES_MARSHALLER = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

        private val MESSAGE_STREAM_METHOD: MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor.newBuilder<ByteArray, ByteArray>()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("protowire.RPC/MessageStream")
                .setRequestMarshaller(RAW_BYTES_MARSHALLER)
                .setResponseMarshaller(RAW_BYTES_MARSHALLER)
                .setSampledToLocalTracing(true)
                .build()
    }
}
