package com.kaspakinetic.wallet.core.explore

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.model.FiatCurrency
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.SpotPrice
import com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pure official-API + keyless-market repository for Stage 2:
 * - Native Block Explorer (official `api.kaspa.org` / `api-tn10.kaspa.org` + KNS `.kas`/`.k`)
 * - Kaspa Network Stats (official `/info/...`, `/addresses/top`, `/addresses/names`, `/addresses/distribution`)
 * - On-device KAS vs BTC Relative Performance via keyless spot klines cascade:
 *   Primary: MEXC (`api.mexc.com`) -> Fallback 1: Gate.io (`api.gateio.ws`) ->
 *   Fallback 2: Kraken (`api.kraken.com`) / CoinGecko (`<= 1Y` only).
 * - Live BlockDAG REST seed & graceful fallback (`/info/virtual-chain-blue-score` + `/blocks-from-bluescore`).
 */
class KaspaExploreRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // In-memory caches so tab switching and drill-downs feel instantaneous
    private val addressLabelsByNetwork = ConcurrentHashMap<KaspaNetwork, Map<String, String>>()
    private val liveFeedBufferByNetwork = ConcurrentHashMap<KaspaNetwork, LinkedHashMap<String, LiveTxFeedItem>>()
    private val richListCache = ConcurrentHashMap<KaspaNetwork, Pair<Long, List<RichListEntry>>>()
    private val distributionCache = ConcurrentHashMap<KaspaNetwork, Pair<Long, List<DistributionTier>>>()
    private val hashrateHistoryCache = ConcurrentHashMap<Pair<KaspaNetwork, HashrateTimeframe>, Pair<Long, List<HashratePoint>>>()
    private val kasVsBtcCache = ConcurrentHashMap<MarketTimeframe, Pair<Long, KasVsBtcComparison>>()
    private val rawKasCandlesCache = ConcurrentHashMap<MarketTimeframe, Pair<Long, Pair<String, List<CandlePoint>>>>()
    private val dailyAllTimeKasUsdCache = ConcurrentHashMap<String, Pair<Long, List<CandlePoint>>>()
    private val usdFxRatesCache = ConcurrentHashMap<String, Pair<Long, Map<String, Double>>>()
    private val marketOverviewCache = ConcurrentHashMap<String, Pair<Long, MarketOverview>>()

    fun getCachedLiveFeed(network: KaspaNetwork): List<LiveTxFeedItem> {
        val buf = liveFeedBufferByNetwork[network] ?: return emptyList()
        synchronized(buf) {
            return buf.values
                .sortedWith(compareByDescending<LiveTxFeedItem> { it.timestampMs }.thenByDescending { it.totalOutputSompi })
        }
    }

    /**
     * Merges newly fetched transactions into the deduplicated rolling ring buffer (up to 160 rows).
     * Existing rows are NEVER wiped when a poll tick returns 0 new items or encounters a transient gap.
     */
    fun mergeIntoLiveFeedBuffer(
        network: KaspaNetwork,
        incoming: List<LiveTxFeedItem>,
        maxBufferSize: Int = 80,
    ): List<LiveTxFeedItem> {
        val buf = liveFeedBufferByNetwork.getOrPut(network) { LinkedHashMap() }
        synchronized(buf) {
            for (item in incoming) {
                val existing = buf[item.transactionId]
                if (existing == null || item.timestampMs >= existing.timestampMs) {
                    buf[item.transactionId] = item
                }
            }
            val sorted = buf.values
                .sortedWith(compareByDescending<LiveTxFeedItem> { it.timestampMs }.thenByDescending { it.totalOutputSompi })
                .take(maxBufferSize)
            if (buf.size > maxBufferSize) {
                buf.clear()
                sorted.forEach { buf[it.transactionId] = it }
            }
            return sorted
        }
    }

    private fun baseUrl(network: KaspaNetwork): String = network.defaultRestUrl.trimEnd('/')

    private fun fetchJson(url: String): JsonElement? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KaspaKinetic/2.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull()
                throw IOException(KaspaErrorSanitizer.sanitize(errBody, resp.code))
            }
            val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return json.parseToJsonElement(text)
        }
    }

    private fun fetchJsonWithHeaders(url: String): Pair<JsonElement?, Map<String, String>> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KaspaKinetic/2.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull()
                throw IOException(KaspaErrorSanitizer.sanitize(errBody, resp.code))
            }
            val headers = HashMap<String, String>()
            for (i in 0 until resp.headers.size) {
                headers[resp.headers.name(i).lowercase(Locale.ROOT)] = resp.headers.value(i)
            }
            val text = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null to headers
            return json.parseToJsonElement(text) to headers
        }
    }

    // =========================================================================
    // 1. Address Entity Labels (`/addresses/names`)
    // =========================================================================

    suspend fun getAddressLabels(network: KaspaNetwork, forceRefresh: Boolean = false): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                addressLabelsByNetwork[network]?.let { return@withContext it }
            }
            val parsed = runCatching {
                val arr = fetchJson("${baseUrl(network)}/addresses/names") as? JsonArray ?: return@runCatching emptyMap()
                val map = LinkedHashMap<String, String>(arr.size)
                for (el in arr) {
                    val obj = el as? JsonObject ?: continue
                    val addr = obj["address"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (addr.isNotEmpty() && name.isNotEmpty()) {
                        map[addr] = name
                    }
                }
                map
            }.getOrDefault(emptyMap())
            if (parsed.isNotEmpty()) {
                addressLabelsByNetwork[network] = parsed
            }
            addressLabelsByNetwork[network] ?: parsed
        }

    fun getCachedLabel(network: KaspaNetwork, address: String): String? =
        addressLabelsByNetwork[network]?.get(address)

    // =========================================================================
    // 2. Universal Explorer Search Resolver (TxID / Block Hash / Address / KNS)
    // =========================================================================

    suspend fun resolveSearchQuery(network: KaspaNetwork, rawQuery: String): ExplorerTarget =
        withContext(Dispatchers.IO) {
            val cleaned = rawQuery.trim()
            if (cleaned.isEmpty()) return@withContext ExplorerTarget.Feed

            // 1. Direct Kaspa address or kaspa: URI
            KaspaAddress.parseUriOrAddress(cleaned)?.let { parsed ->
                return@withContext ExplorerTarget.Address(parsed.address)
            }
            if (cleaned.startsWith("kaspa:", ignoreCase = true) || cleaned.startsWith("kaspatest:", ignoreCase = true)) {
                return@withContext ExplorerTarget.Address(cleaned.lowercase(Locale.ROOT))
            }

            // 2. KNS `.kas` or `.k` domain
            val lower = cleaned.lowercase(Locale.ROOT)
            if (lower.endsWith(".kas") || lower.endsWith(".k")) {
                val resolved = resolveKnsDomain(network, lower)
                    ?: throw IOException("Could not resolve domain \"$cleaned\"")
                return@withContext ExplorerTarget.Address(resolved)
            }

            // 3. 64-character hex string -> probe Transaction first, then Block
            val hex = lower.removePrefix("0x")
            if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                val base = baseUrl(network)
                val txExists = runCatching {
                    fetchJson("$base/transactions/$hex?inputs=false&outputs=false") != null
                }.getOrDefault(false)
                if (txExists) {
                    return@withContext ExplorerTarget.Transaction(hex)
                }
                val blockExists = runCatching {
                    fetchJson("$base/blocks/$hex?includeTransactions=false") != null
                }.getOrDefault(false)
                if (blockExists) {
                    return@withContext ExplorerTarget.Block(hex)
                }
                // Default to Transaction target so the detail view displays a clean not-found error
                return@withContext ExplorerTarget.Transaction(hex)
            }

            throw IOException("Enter a valid Kaspa address, 64-char TxID/Block hash, or .kas domain.")
        }

    suspend fun resolveKnsDomain(network: KaspaNetwork, domain: String): String? = withContext(Dispatchers.IO) {
        val clean = domain.trim().lowercase(Locale.ROOT)
        // Well-known Kaspa Kinetic funding alias fallback
        if (clean == "funding.k" && !network.isTestnet) {
            return@withContext "kaspa:qp2kf6gf8py4gy7xe77gkey54vxacc7xslxm5qcwfw268wwurfqacyznv5cuk"
        }
        val netSegment = if (network.isTestnet) "tn10" else "mainnet"
        val encoded = URLEncoder.encode(clean, "UTF-8")
        // Try KNS official public API
        runCatching {
            val root = fetchJson("https://api.knsdomains.org/$netSegment/api/v1/$encoded/owner")?.jsonObject
            val owner = root?.get("data")?.jsonObject?.get("owner")?.jsonPrimitive?.content
                ?: root?.get("owner")?.jsonPrimitive?.content
            owner?.takeIf { KaspaAddress.decode(it) != null }
        }.getOrNull()
    }

    // =========================================================================
    // 3. Block Explorer Detail Queries & Live Transactions Feed
    // =========================================================================

    suspend fun fetchLiveTransactionsFeed(network: KaspaNetwork, maxItems: Int = 40): List<LiveTxFeedItem> =
        withContext(Dispatchers.IO) {
            val base = baseUrl(network)
            val vbObj = fetchJson("$base/info/virtual-chain-blue-score")?.jsonObject
                ?: throw IOException("Unable to fetch virtual blue score")
            val tipBlue = vbObj["blueScore"]?.jsonPrimitive?.longOrNull
                ?: vbObj["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw IOException("Invalid virtual blue score")

            // Fetch a small recent window of blocks around tip (polite 2-3 calls max)
            val queryScores = listOf((tipBlue - 3L).coerceAtLeast(0L), (tipBlue - 12L).coerceAtLeast(0L))
            val blocks = ArrayList<JsonObject>()
            for (bs in queryScores) {
                val arr = runCatching {
                    fetchJson("$base/blocks-from-bluescore?blueScore=$bs&includeTransactions=true") as? JsonArray
                }.getOrNull()
                arr?.forEach { el -> (el as? JsonObject)?.let { blocks.add(it) } }
            }

            val seenTx = HashSet<String>()
            val feed = ArrayList<LiveTxFeedItem>()
            for (blk in blocks) {
                val header = blk["header"] as? JsonObject
                val verbose = blk["verboseData"] as? JsonObject
                val blockHash = verbose?.get("hash")?.jsonPrimitive?.content
                    ?: header?.get("hash")?.jsonPrimitive?.content.orEmpty()
                val blueScore = header?.get("blueScore")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: verbose?.get("blueScore")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: tipBlue
                val blockTime = header?.get("timestamp")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: header?.get("timestamp")?.jsonPrimitive?.longOrNull
                    ?: System.currentTimeMillis()
                val isChainBlock = verbose?.get("isChainBlock")?.jsonPrimitive?.booleanOrNull ?: true
                val txs = blk["transactions"] as? JsonArray ?: continue

                for (txEl in txs) {
                    val txObj = txEl as? JsonObject ?: continue
                    val txVerbose = txObj["verboseData"] as? JsonObject
                    val txId = txVerbose?.get("transactionId")?.jsonPrimitive?.content
                        ?: txObj["transaction_id"]?.jsonPrimitive?.content
                        ?: continue
                    if (!seenTx.add(txId)) continue
                    val inputs = txObj["inputs"] as? JsonArray
                    val outputs = txObj["outputs"] as? JsonArray
                    val inputCount = inputs?.size ?: 0
                    val outputCount = outputs?.size ?: 0
                    var totalOut = 0L
                    var firstAddr = ""
                    outputs?.forEach { outEl ->
                        val outObj = outEl as? JsonObject ?: return@forEach
                        val amt = outObj["amount"]?.jsonPrimitive?.longOrNull
                            ?: outObj["amount"]?.jsonPrimitive?.content?.toLongOrNull()
                            ?: 0L
                        totalOut += amt.coerceAtLeast(0L)
                        if (firstAddr.isEmpty()) {
                            firstAddr = outObj["verboseData"]?.jsonObject
                                ?.get("scriptPublicKeyAddress")?.jsonPrimitive?.content.orEmpty()
                        }
                    }
                    feed.add(
                        LiveTxFeedItem(
                            transactionId = txId,
                            blockHash = blockHash,
                            blockBlueScore = blueScore,
                            timestampMs = blockTime,
                            isCoinbase = inputCount == 0,
                            isAccepted = isChainBlock,
                            totalOutputSompi = totalOut,
                            inputCount = inputCount,
                            outputCount = outputCount,
                            primaryRecipient = firstAddr,
                        ),
                    )
                }
            }
            val fresh = feed.sortedWith(compareByDescending<LiveTxFeedItem> { it.timestampMs }.thenByDescending { it.totalOutputSompi })
                .take(maxItems)
            mergeIntoLiveFeedBuffer(network, fresh)
        }

    suspend fun fetchTransactionDetail(network: KaspaNetwork, txId: String): ExplorerTxDetail =
        withContext(Dispatchers.IO) {
            val cleanId = txId.trim().lowercase(Locale.ROOT)
            val base = baseUrl(network)
            val obj = fetchJson("$base/transactions/$cleanId?inputs=true&outputs=true&resolve_previous_outpoints=light")
                ?.jsonObject
                ?: throw IOException("Transaction not found")
            parseExplorerTxDetail(obj)
        }

    suspend fun fetchBlockDetail(network: KaspaNetwork, blockHash: String): ExplorerBlockDetail =
        withContext(Dispatchers.IO) {
            val cleanHash = blockHash.trim().lowercase(Locale.ROOT)
            val base = baseUrl(network)
            val obj = fetchJson("$base/blocks/$cleanHash?includeTransactions=true&includeColor=true")
                ?.jsonObject
                ?: throw IOException("Block not found")
            val header = obj["header"] as? JsonObject ?: JsonObject(emptyMap())
            val verbose = obj["verboseData"] as? JsonObject ?: JsonObject(emptyMap())
            val extra = obj["extra"] as? JsonObject

            val hash = verbose["hash"]?.jsonPrimitive?.content
                ?: header["hash"]?.jsonPrimitive?.content
                ?: cleanHash
            val version = header["version"]?.jsonPrimitive?.intOrNull ?: 0
            val timestamp = header["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: header["timestamp"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val daaScore = header["daaScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: header["daaScore"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val blueScore = header["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: verbose["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 0L
            val difficulty = verbose["difficulty"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val nonce = header["nonce"]?.jsonPrimitive?.content.orEmpty()
            val isChainBlock = verbose["isChainBlock"]?.jsonPrimitive?.booleanOrNull ?: false
            val color = extra?.get("color")?.jsonPrimitive?.content?.lowercase(Locale.ROOT)
            val minerAddress = extra?.get("minerAddress")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val minerInfo = extra?.get("minerInfo")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val selectedParent = verbose["selectedParentHash"]?.jsonPrimitive?.content.orEmpty()

            val parents = ArrayList<String>()
            (header["parents"] as? JsonArray)?.firstOrNull()?.let { level0 ->
                ((level0 as? JsonObject)?.get("parentHashes") as? JsonArray)?.forEach { p ->
                    p.jsonPrimitive.content.takeIf { it.isNotBlank() }?.let { parents.add(it) }
                }
            }
            val mergeBlues = (verbose["mergeSetBluesHashes"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() }
            }.orEmpty()
            val mergeReds = (verbose["mergeSetRedsHashes"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() }
            }.orEmpty()

            val txSummaries = ArrayList<ExplorerBlockTxSummary>()
            (obj["transactions"] as? JsonArray)?.forEach { txEl ->
                val txObj = txEl as? JsonObject ?: return@forEach
                val tid = txObj["verboseData"]?.jsonObject?.get("transactionId")?.jsonPrimitive?.content ?: return@forEach
                val inArr = txObj["inputs"] as? JsonArray
                val outArr = txObj["outputs"] as? JsonArray
                var sumOut = 0L
                var firstAddr = ""
                outArr?.forEach { outEl ->
                    val o = outEl as? JsonObject ?: return@forEach
                    sumOut += (o["amount"]?.jsonPrimitive?.longOrNull
                        ?: o["amount"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: 0L).coerceAtLeast(0L)
                    if (firstAddr.isEmpty()) {
                        firstAddr = o["verboseData"]?.jsonObject
                            ?.get("scriptPublicKeyAddress")?.jsonPrimitive?.content.orEmpty()
                    }
                }
                txSummaries.add(
                    ExplorerBlockTxSummary(
                        transactionId = tid,
                        isCoinbase = (inArr?.size ?: 0) == 0,
                        inputCount = inArr?.size ?: 0,
                        outputCount = outArr?.size ?: 0,
                        totalOutputSompi = sumOut,
                        primaryAddress = firstAddr,
                    ),
                )
            }
            val txCount = maxOf(
                txSummaries.size,
                (verbose["transactionIds"] as? JsonArray)?.size ?: 0,
            )

            ExplorerBlockDetail(
                hash = hash,
                version = version,
                timestampMs = timestamp,
                daaScore = daaScore,
                blueScore = blueScore,
                difficulty = difficulty,
                nonce = nonce,
                isChainBlock = isChainBlock,
                color = color,
                minerAddress = minerAddress,
                minerInfo = minerInfo,
                selectedParentHash = selectedParent,
                parentHashes = parents.distinct(),
                mergeSetBlueHashes = mergeBlues,
                mergeSetRedHashes = mergeReds,
                txCount = txCount,
                transactions = txSummaries,
            )
        }

    suspend fun fetchAddressDetail(
        network: KaspaNetwork,
        address: String,
        beforeTimestampMs: Long? = null,
        pageSize: Int = 20,
    ): ExplorerAddressDetail = withContext(Dispatchers.IO) {
        val cleanAddr = address.trim()
        val encoded = URLEncoder.encode(cleanAddr, "UTF-8")
        val base = baseUrl(network)

        coroutineScope {
            val labelsDef = async { getAddressLabels(network) }
            val balDef = async {
                runCatching {
                    fetchJson("$base/addresses/$encoded/balance")?.jsonObject
                        ?.get("balance")?.jsonPrimitive?.longOrNull
                        ?: 0L
                }.getOrDefault(0L)
            }
            val utxoCountDef = async {
                runCatching {
                    fetchJson("$base/addresses/$encoded/utxos/count")?.jsonObject
                        ?.get("count")?.jsonPrimitive?.intOrNull
                        ?: 0
                }.getOrDefault(0)
            }
            val txCountDef = async {
                runCatching {
                    fetchJson("$base/addresses/$encoded/transactions-count")?.jsonObject
                        ?.get("total")?.jsonPrimitive?.longOrNull
                        ?: 0L
                }.getOrDefault(0L)
            }
            val txPageDef = async {
                val beforeParam = if (beforeTimestampMs != null && beforeTimestampMs > 0L) "&before=$beforeTimestampMs" else ""
                val url = "$base/addresses/$encoded/full-transactions-page?limit=$pageSize$beforeParam&resolve_previous_outpoints=light"
                val (el, headers) = fetchJsonWithHeaders(url)
                val list = (el as? JsonArray)?.mapNotNull { item ->
                    (item as? JsonObject)?.let { runCatching { parseExplorerTxDetail(it) }.getOrNull() }
                }.orEmpty()
                val nextBefore = headers["x-next-page-before"]?.toLongOrNull()
                    ?: if (list.size >= pageSize) list.minOfOrNull { it.blockTimeMs }?.takeIf { it > 0L } else null
                list to nextBefore
            }

            val labels = labelsDef.await()
            val (txs, nextBefore) = txPageDef.await()
            ExplorerAddressDetail(
                address = cleanAddr,
                label = labels[cleanAddr],
                balanceSompi = balDef.await(),
                utxoCount = utxoCountDef.await(),
                txCount = txCountDef.await(),
                transactions = txs,
                nextPageBefore = nextBefore,
            )
        }
    }

    private fun parseExplorerTxDetail(obj: JsonObject): ExplorerTxDetail {
        val txId = obj["transaction_id"]?.jsonPrimitive?.content.orEmpty()
        val blockHashes = (obj["block_hash"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() }
        }.orEmpty()
        val acceptingHash = obj["accepting_block_hash"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val acceptingBlue = obj["accepting_block_blue_score"]?.jsonPrimitive?.longOrNull
        val blockTime = obj["block_time"]?.jsonPrimitive?.longOrNull
            ?: obj["accepting_block_time"]?.jsonPrimitive?.longOrNull
            ?: 0L
        val isAccepted = obj["is_accepted"]?.jsonPrimitive?.booleanOrNull ?: (acceptingHash != null)
        val mass = obj["mass"]?.jsonPrimitive?.longOrNull ?: 0L

        val inputs = (obj["inputs"] as? JsonArray)?.mapNotNull { el ->
            val inp = el as? JsonObject ?: return@mapNotNull null
            ExplorerTxInput(
                previousTxId = inp["previous_outpoint_hash"]?.jsonPrimitive?.content.orEmpty(),
                previousOutpointIndex = inp["previous_outpoint_index"]?.jsonPrimitive?.intOrNull
                    ?: inp["previous_outpoint_index"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: 0,
                address = inp["previous_outpoint_address"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                amountSompi = inp["previous_outpoint_amount"]?.jsonPrimitive?.longOrNull,
            )
        }.orEmpty()

        val outputs = (obj["outputs"] as? JsonArray)?.mapIndexedNotNull { idx, el ->
            val out = el as? JsonObject ?: return@mapIndexedNotNull null
            val addr = out["script_public_key_address"]?.jsonPrimitive?.content.orEmpty()
            val amt = out["amount"]?.jsonPrimitive?.longOrNull ?: 0L
            val spkType = out["script_public_key_type"]?.jsonPrimitive?.content.orEmpty()
            ExplorerTxOutput(
                index = out["index"]?.jsonPrimitive?.intOrNull ?: idx,
                address = addr,
                amountSompi = amt.coerceAtLeast(0L),
                scriptPublicKeyType = spkType,
            )
        }.orEmpty()

        val totalIn = inputs.sumOf { (it.amountSompi ?: 0L).coerceAtLeast(0L) }
        val totalOut = outputs.sumOf { it.amountSompi }
        val isCoinbase = inputs.isEmpty()
        val fee = if (!isCoinbase && totalIn >= totalOut && totalIn > 0L) (totalIn - totalOut) else 0L

        return ExplorerTxDetail(
            transactionId = txId,
            blockHashes = blockHashes,
            acceptingBlockHash = acceptingHash,
            acceptingBlockBlueScore = acceptingBlue,
            blockTimeMs = blockTime,
            isAccepted = isAccepted,
            isCoinbase = isCoinbase,
            mass = mass,
            inputs = inputs,
            outputs = outputs,
            totalInputSompi = totalIn,
            totalOutputSompi = totalOut,
            feeSompi = fee,
        )
    }

    // =========================================================================
    // 4. Multi-Currency FX Rates, Standalone Kaspa Performance, & KAS vs BTC Cascade
    // =========================================================================

    /**
     * Resolves the multiplier converting USD -> [currency] (cached 1h).
     * Uses wallet's live [walletSpotPrice] when available or keyless `open.er-api.com` / BTCUSDT spot.
     */
    suspend fun getUsdToCurrencyRate(
        currency: FiatCurrency,
        walletSpotPrice: SpotPrice? = null,
        privacyKasOnly: Boolean = false,
    ): Double = withContext(Dispatchers.IO) {
        if (currency == FiatCurrency.USD || privacyKasOnly) return@withContext 1.0

        val now = System.currentTimeMillis()
        if (currency == FiatCurrency.BTC) {
            val btcUsd = runCatchingNonCancellation {
                fetchJson("https://api.mexc.com/api/v3/ticker/price?symbol=BTCUSDT")?.jsonObject
                    ?.get("price")?.jsonPrimitive?.content?.toDoubleOrNull()
            }.getOrNull()
                ?: runCatchingNonCancellation {
                    fetchJson("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=BTC_USDT")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("last")?.jsonPrimitive?.content?.toDoubleOrNull()
                }.getOrNull()
            if (btcUsd != null && btcUsd > 0.0) return@withContext 1.0 / btcUsd
            if (walletSpotPrice != null && walletSpotPrice.currency == FiatCurrency.BTC && walletSpotPrice.price > 0.0) {
                val kasUsd = fetchMarketOverview(false)?.spotPriceUsd
                if (kasUsd != null && kasUsd > 0.0) return@withContext walletSpotPrice.price / kasUsd
            }
            return@withContext 1.0
        }

        // Check cached fiat FX table first (1h TTL)
        usdFxRatesCache["usd"]?.takeIf { now - it.first < 3_600_000L }?.second?.get(currency.code)?.let { rate ->
            if (rate > 0.0) return@withContext rate
        }

        // Keyless public FX rates (no API key, covers AUD, EUR, GBP, JPY, KRW, CAD, CHF, CNY, INR, BRL, TRY)
        val ratesMap = runCatchingNonCancellation {
            val root = fetchJson("https://open.er-api.com/v6/latest/USD")?.jsonObject
            val ratesObj = root?.get("rates")?.jsonObject ?: return@runCatchingNonCancellation emptyMap<String, Double>()
            val out = HashMap<String, Double>()
            ratesObj.forEach { (k, v) ->
                v.jsonPrimitive.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() }?.let { out[k] = it }
            }
            out
        }.getOrDefault(emptyMap())

        if (ratesMap.isNotEmpty()) {
            usdFxRatesCache["usd"] = now to ratesMap
            ratesMap[currency.code]?.let { return@withContext it }
        }

        // Fallback: derive FX ratio directly from walletSpotPrice / kasUsd
        if (walletSpotPrice != null && walletSpotPrice.currency == currency && walletSpotPrice.price > 0.0) {
            val kasUsd = runCatchingNonCancellation { fetchMarketOverview(false)?.spotPriceUsd }.getOrNull()
            if (kasUsd != null && kasUsd > 0.0) {
                return@withContext walletSpotPrice.price / kasUsd
            }
        }
        1.0
    }

    /**
     * Fetches standalone Kaspa historical spot price series converted into the user's selected currency.
     * Reuses the same keyless MEXC -> Gate.io -> Kraken -> CoinGecko cascade and memory cache.
     */
    suspend fun fetchKasPriceSeries(
        timeframe: MarketTimeframe,
        currency: FiatCurrency,
        walletSpotPrice: SpotPrice?,
        privacyKasOnly: Boolean,
        forceRefresh: Boolean = false,
    ): KasPriceSeries = withContext(Dispatchers.IO) {
        if (privacyKasOnly) {
            throw IOException("External market data is disabled in KAS Only Privacy Mode.")
        }
        val (sourceName, rawUsdCandles) = fetchRawKasUsdCandles(timeframe, forceRefresh = forceRefresh)
        val fx = getUsdToCurrencyRate(currency, walletSpotPrice, privacyKasOnly = false)
        val pts = rawUsdCandles
            .filter { it.timestampMs > 0L && it.close > 0.0 && it.close.isFinite() }
            .sortedBy { it.timestampMs }
            .map { it.timestampMs to (it.close * fx) }
        if (pts.size < 2) throw IOException("Insufficient historical KAS candle data")
        val first = pts.first().second
        val last = pts.last().second
        val pct = if (first > 0.0) ((last / first) - 1.0) * 100.0 else 0.0
        KasPriceSeries(
            timeframe = timeframe,
            sourceName = sourceName,
            earliestTimestampMs = pts.first().first,
            pointsInCurrency = pts,
            latestPriceInCurrency = last,
            changePct = pct,
        )
    }

    /**
     * Fetches daily all-time KAS historical spot candles converted to [currency] for `"Your Performance"`.
     * Uses MEXC daily klines (up to 1000d + weekly backfill back to Sept 2022) -> Gate.io -> Kraken.
     */
    suspend fun fetchAllTimeDailyKasPricesInCurrency(
        currency: FiatCurrency,
        walletSpotPrice: SpotPrice?,
        privacyKasOnly: Boolean,
    ): List<CandlePoint> = withContext(Dispatchers.IO) {
        if (privacyKasOnly) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val rawUsd = dailyAllTimeKasUsdCache["all"]?.takeIf { now - it.first < 600_000L }?.second ?: run {
            val fetched = runCatchingNonCancellation {
                coroutineScope {
                    val dailyDef = async {
                        parseMexcKlines(fetchJson("https://api.mexc.com/api/v3/klines?symbol=KASUSDT&interval=1d&limit=1000"))
                    }
                    val weeklyDef = async {
                        parseMexcKlines(fetchJson("https://api.mexc.com/api/v3/klines?symbol=KASUSDT&interval=1W&limit=300"))
                    }
                    val daily = dailyDef.await()
                    val earliestDaily = daily.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE
                    val olderWeekly = weeklyDef.await().filter { it.timestampMs < earliestDaily }
                    (olderWeekly + daily).sortedBy { it.timestampMs }.distinctBy { it.timestampMs }
                }
            }.getOrNull()?.takeIf { it.size >= 2 }
                ?: runCatchingNonCancellation {
                    parseGateCandles(fetchJson("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=KAS_USDT&interval=1d&limit=1000"))
                }.getOrNull()?.takeIf { it.size >= 2 }
                ?: runCatchingNonCancellation {
                    val since = (System.currentTimeMillis() / 1000L) - 5L * 365L * 86_400L
                    parseKrakenOhlc(fetchJson("https://api.kraken.com/0/public/OHLC?pair=KASUSD&interval=1440&since=$since"))
                }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) dailyAllTimeKasUsdCache["all"] = now to fetched
            fetched
        }
        val fx = getUsdToCurrencyRate(currency, walletSpotPrice, privacyKasOnly = false)
        rawUsd.map { CandlePoint(timestampMs = it.timestampMs, close = it.close * fx) }
    }

    private suspend fun fetchRawKasUsdCandles(
        timeframe: MarketTimeframe,
        forceRefresh: Boolean,
    ): Pair<String, List<CandlePoint>> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            rawKasCandlesCache[timeframe]?.let { (ts, cached) ->
                val ttlMs = if (timeframe == MarketTimeframe.H1) 45_000L else 180_000L
                if (now - ts < ttlMs) return cached
            }
        }
        // 1. MEXC
        runCatchingNonCancellation {
            val (interval, limit) = when (timeframe) {
                MarketTimeframe.H1 -> "1m" to 60
                MarketTimeframe.D1 -> "15m" to 96
                MarketTimeframe.W1 -> "60m" to 168
                MarketTimeframe.M1 -> "60m" to 720
                MarketTimeframe.Y1 -> "1d" to 365
                MarketTimeframe.ALL_5Y -> "1W" to 300
            }
            val list = parseMexcKlines(fetchJson("https://api.mexc.com/api/v3/klines?symbol=KASUSDT&interval=$interval&limit=$limit"))
            if (list.size >= 2) "MEXC" to list else null
        }.getOrNull()?.let {
            rawKasCandlesCache[timeframe] = now to it
            return it
        }
        // 2. Gate.io
        runCatchingNonCancellation {
            val (interval, limit) = when (timeframe) {
                MarketTimeframe.H1 -> "1m" to 60
                MarketTimeframe.D1 -> "15m" to 96
                MarketTimeframe.W1 -> "1h" to 168
                MarketTimeframe.M1 -> "4h" to 180
                MarketTimeframe.Y1 -> "1d" to 365
                MarketTimeframe.ALL_5Y -> "7d" to 260
            }
            val list = parseGateCandles(fetchJson("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=KAS_USDT&interval=$interval&limit=$limit"))
            if (list.size >= 2) "Gate.io" to list else null
        }.getOrNull()?.let {
            rawKasCandlesCache[timeframe] = now to it
            return it
        }
        // 3. Kraken
        runCatchingNonCancellation {
            val nowSec = System.currentTimeMillis() / 1000L
            val (ivMin, since) = when (timeframe) {
                MarketTimeframe.H1 -> 1 to (nowSec - 3_600L)
                MarketTimeframe.D1 -> 15 to (nowSec - 86_400L)
                MarketTimeframe.W1 -> 60 to (nowSec - 7L * 86_400L)
                MarketTimeframe.M1 -> 240 to (nowSec - 30L * 86_400L)
                MarketTimeframe.Y1 -> 1440 to (nowSec - 365L * 86_400L)
                MarketTimeframe.ALL_5Y -> 10080 to (nowSec - 5L * 365L * 86_400L)
            }
            val list = parseKrakenOhlc(fetchJson("https://api.kraken.com/0/public/OHLC?pair=KASUSD&interval=$ivMin&since=$since"))
            if (list.size >= 2) "Kraken" to list else null
        }.getOrNull()?.let {
            rawKasCandlesCache[timeframe] = now to it
            return it
        }
        // 4. CoinGecko (<= 1Y only)
        if (timeframe != MarketTimeframe.ALL_5Y) {
            runCatchingNonCancellation {
                val days = when (timeframe) {
                    MarketTimeframe.H1, MarketTimeframe.D1 -> "1"
                    MarketTimeframe.W1 -> "7"
                    MarketTimeframe.M1 -> "30"
                    MarketTimeframe.Y1 -> "365"
                    MarketTimeframe.ALL_5Y -> "365"
                }
                val cutoff = if (timeframe == MarketTimeframe.H1) now - 3_600_000L else 0L
                val list = parseCoinGeckoPrices(fetchJson("https://api.coingecko.com/api/v3/coins/kaspa/market_chart?vs_currency=usd&days=$days"), cutoff)
                if (list.size >= 2) "CoinGecko" to list else null
            }.getOrNull()?.let {
                rawKasCandlesCache[timeframe] = now to it
                return it
            }
        }
        throw IOException("Unable to fetch historical KAS spot prices.")
    }

    suspend fun fetchMarketOverview(privacyKasOnly: Boolean): MarketOverview? = withContext(Dispatchers.IO) {
        if (privacyKasOnly) return@withContext null
        val now = System.currentTimeMillis()
        marketOverviewCache["mainnet"]?.let { (ts, cached) ->
            if (now - ts < 30_000L) return@withContext cached
        }
        val base = KaspaNetwork.MAINNET.defaultRestUrl
        coroutineScope {
            val priceDef = async {
                runCatching {
                    fetchJson("$base/info/price")?.jsonObject?.get("price")?.jsonPrimitive?.doubleOrNull
                }.getOrNull()
            }
            val mcapDef = async {
                runCatching {
                    fetchJson("$base/info/marketcap")?.jsonObject?.get("marketcap")?.jsonPrimitive?.doubleOrNull
                }.getOrNull()
            }
            val supplyDef = async {
                runCatching { fetchJson("$base/info/coinsupply")?.jsonObject }.getOrNull()
            }
            val change24Def = async {
                // Fast keyless 24h change from MEXC ticker -> Gate.io ticker fallback
                runCatching {
                    fetchJson("https://api.mexc.com/api/v3/ticker/24hr?symbol=KASUSDT")?.jsonObject
                        ?.get("priceChangePercent")?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?.times(100.0)
                }.getOrNull() ?: runCatching {
                    fetchJson("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=KAS_USDT")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("change_percentage")?.jsonPrimitive?.content?.toDoubleOrNull()
                }.getOrNull()
            }

            val supplyObj = supplyDef.await()
            val circSompi = supplyObj?.get("circulatingSupply")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val maxSompi = supplyObj?.get("maxSupply")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 28_704_035_605.0 * SOMPI_PER_KAS
            val circKas = circSompi / SOMPI_PER_KAS.toDouble()
            val maxKas = maxSompi / SOMPI_PER_KAS.toDouble()

            val spot = priceDef.await() ?: 0.0
            val mcap = mcapDef.await() ?: (spot * circKas)
            if (spot <= 0.0 && circKas <= 0.0) return@coroutineScope null

            MarketOverview(
                spotPriceUsd = spot,
                change24hPct = change24Def.await(),
                marketCapUsd = mcap,
                circulatingSupplyKas = circKas,
                maxSupplyKas = maxKas,
            ).also { marketOverviewCache["mainnet"] = now to it }
        }
    }

    /**
     * Keyless Market Data Cascade for KAS vs BTC relative performance:
     * 1. Primary: MEXC (`api.mexc.com/api/v3/klines`, history back to Sept 2022)
     * 2. Fallback 1: Gate.io (`api.gateio.ws/api/v4/spot/candlesticks`, history back to Mar 2023)
     * 3. Fallback 2: Kraken (`api.kraken.com/0/public/OHLC`) + CoinGecko (`<= 1Y` only)
     */
    suspend fun fetchKasVsBtcComparison(
        timeframe: MarketTimeframe,
        privacyKasOnly: Boolean,
        forceRefresh: Boolean = false,
    ): KasVsBtcComparison = withContext(Dispatchers.IO) {
        if (privacyKasOnly) {
            throw IOException("External market data is disabled in KAS Only Privacy Mode.")
        }
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            kasVsBtcCache[timeframe]?.let { (ts, cached) ->
                val ttlMs = if (timeframe == MarketTimeframe.H1) 45_000L else 180_000L
                if (now - ts < ttlMs) return@withContext cached
            }
        }

        // 1. Primary: MEXC (generous limits, history back to Sept 2022)
        runCatching { fetchPairFromMexc(timeframe) }.getOrNull()?.let { result ->
            kasVsBtcCache[timeframe] = now to result
            return@withContext result
        }

        // 2. Fallback 1: Gate.io (history back to March 2023)
        runCatching { fetchPairFromGateIo(timeframe) }.getOrNull()?.let { result ->
            kasVsBtcCache[timeframe] = now to result
            return@withContext result
        }

        // 3. Fallback 2: Kraken (geo-restricted fallback)
        runCatching { fetchPairFromKraken(timeframe) }.getOrNull()?.let { result ->
            kasVsBtcCache[timeframe] = now to result
            return@withContext result
        }

        // 4. Fallback 3: CoinGecko keyless (<= 1Y only)
        if (timeframe != MarketTimeframe.ALL_5Y) {
            runCatching { fetchPairFromCoinGecko(timeframe) }.getOrNull()?.let { result ->
                kasVsBtcCache[timeframe] = now to result
                return@withContext result
            }
        }

        throw IOException("Unable to reach market data exchanges (MEXC, Gate.io, Kraken). Check internet connection.")
    }

    private suspend fun fetchPairFromMexc(timeframe: MarketTimeframe): KasVsBtcComparison? = coroutineScope {
        val (interval, limit) = when (timeframe) {
            MarketTimeframe.H1 -> "1m" to 60
            MarketTimeframe.D1 -> "15m" to 96
            MarketTimeframe.W1 -> "60m" to 168
            MarketTimeframe.M1 -> "60m" to 720
            MarketTimeframe.Y1 -> "1d" to 365
            MarketTimeframe.ALL_5Y -> "1W" to 300
        }
        val kasDef = async {
            parseMexcKlines(fetchJson("https://api.mexc.com/api/v3/klines?symbol=KASUSDT&interval=$interval&limit=$limit"))
        }
        val btcDef = async {
            parseMexcKlines(fetchJson("https://api.mexc.com/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit"))
        }
        ExploreAnalyticsMath.computeKasVsBtcComparison(
            timeframe = timeframe,
            sourceName = "MEXC",
            kasCandlesRaw = kasDef.await(),
            btcCandlesRaw = btcDef.await(),
        )
    }

    private fun parseMexcKlines(el: JsonElement?): List<CandlePoint> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            if (a.size < 5) return@mapNotNull null
            val ts = a[0].jsonPrimitive.longOrNull ?: return@mapNotNull null
            val close = a[4].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            CandlePoint(timestampMs = ts, close = close)
        }
    }

    private suspend fun fetchPairFromGateIo(timeframe: MarketTimeframe): KasVsBtcComparison? = coroutineScope {
        val (interval, limit) = when (timeframe) {
            MarketTimeframe.H1 -> "1m" to 60
            MarketTimeframe.D1 -> "15m" to 96
            MarketTimeframe.W1 -> "1h" to 168
            MarketTimeframe.M1 -> "4h" to 180
            MarketTimeframe.Y1 -> "1d" to 365
            MarketTimeframe.ALL_5Y -> "7d" to 260
        }
        val kasDef = async {
            parseGateCandles(fetchJson("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=KAS_USDT&interval=$interval&limit=$limit"))
        }
        val btcDef = async {
            parseGateCandles(fetchJson("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=BTC_USDT&interval=$interval&limit=$limit"))
        }
        ExploreAnalyticsMath.computeKasVsBtcComparison(
            timeframe = timeframe,
            sourceName = "Gate.io",
            kasCandlesRaw = kasDef.await(),
            btcCandlesRaw = btcDef.await(),
        )
    }

    /** Gate.io candlesticks format: `[timestampSecStr, quoteVol, close, high, low, open, ...]` */
    private fun parseGateCandles(el: JsonElement?): List<CandlePoint> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            if (a.size < 6) return@mapNotNull null
            val tsSec = a[0].jsonPrimitive.content.toLongOrNull() ?: return@mapNotNull null
            val close = a[2].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            CandlePoint(timestampMs = tsSec * 1000L, close = close)
        }
    }

    private suspend fun fetchPairFromKraken(timeframe: MarketTimeframe): KasVsBtcComparison? = coroutineScope {
        val nowSec = System.currentTimeMillis() / 1000L
        val (intervalMin, sinceSec) = when (timeframe) {
            MarketTimeframe.H1 -> 1 to (nowSec - 3_600L)
            MarketTimeframe.D1 -> 15 to (nowSec - 86_400L)
            MarketTimeframe.W1 -> 60 to (nowSec - 7L * 86_400L)
            MarketTimeframe.M1 -> 240 to (nowSec - 30L * 86_400L)
            MarketTimeframe.Y1 -> 1440 to (nowSec - 365L * 86_400L)
            MarketTimeframe.ALL_5Y -> 10080 to (nowSec - 5L * 365L * 86_400L)
        }
        val kasDef = async {
            parseKrakenOhlc(fetchJson("https://api.kraken.com/0/public/OHLC?pair=KASUSD&interval=$intervalMin&since=$sinceSec"))
        }
        val btcDef = async {
            parseKrakenOhlc(fetchJson("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=$intervalMin&since=$sinceSec"))
        }
        ExploreAnalyticsMath.computeKasVsBtcComparison(
            timeframe = timeframe,
            sourceName = "Kraken",
            kasCandlesRaw = kasDef.await(),
            btcCandlesRaw = btcDef.await(),
        )
    }

    private fun parseKrakenOhlc(el: JsonElement?): List<CandlePoint> {
        val resultObj = (el as? JsonObject)?.get("result") as? JsonObject ?: return emptyList()
        val seriesKey = resultObj.keys.firstOrNull { it != "last" } ?: return emptyList()
        val arr = resultObj[seriesKey] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            if (a.size < 5) return@mapNotNull null
            val tsSec = a[0].jsonPrimitive.longOrNull ?: return@mapNotNull null
            val close = a[4].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            CandlePoint(timestampMs = tsSec * 1000L, close = close)
        }
    }

    private suspend fun fetchPairFromCoinGecko(timeframe: MarketTimeframe): KasVsBtcComparison? = coroutineScope {
        val daysParam = when (timeframe) {
            MarketTimeframe.H1, MarketTimeframe.D1 -> "1"
            MarketTimeframe.W1 -> "7"
            MarketTimeframe.M1 -> "30"
            MarketTimeframe.Y1 -> "365"
            MarketTimeframe.ALL_5Y -> return@coroutineScope null
        }
        val cutoffMs = if (timeframe == MarketTimeframe.H1) System.currentTimeMillis() - 3_600_000L else 0L
        val kasDef = async {
            parseCoinGeckoPrices(fetchJson("https://api.coingecko.com/api/v3/coins/kaspa/market_chart?vs_currency=usd&days=$daysParam"), cutoffMs)
        }
        val btcDef = async {
            parseCoinGeckoPrices(fetchJson("https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=$daysParam"), cutoffMs)
        }
        ExploreAnalyticsMath.computeKasVsBtcComparison(
            timeframe = timeframe,
            sourceName = "CoinGecko",
            kasCandlesRaw = kasDef.await(),
            btcCandlesRaw = btcDef.await(),
        )
    }

    private fun parseCoinGeckoPrices(el: JsonElement?, minTsMs: Long): List<CandlePoint> {
        val prices = (el as? JsonObject)?.get("prices") as? JsonArray ?: return emptyList()
        return prices.mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            if (a.size < 2) return@mapNotNull null
            val ts = a[0].jsonPrimitive.longOrNull ?: return@mapNotNull null
            if (ts < minTsMs) return@mapNotNull null
            val close = a[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            CandlePoint(timestampMs = ts, close = close)
        }
    }

    // =========================================================================
    // 5. Network & Mining Tab (`/info/hashrate`, `/info/hashrate/max`, `/info/hashrate/history`, `/info/blockreward`, `/info/halving`)
    // =========================================================================

    suspend fun fetchNetworkMiningStats(network: KaspaNetwork): NetworkMiningStats = withContext(Dispatchers.IO) {
        val base = baseUrl(network)
        coroutineScope {
            val hrDef = async { runCatching { fetchJson("$base/info/hashrate")?.jsonObject }.getOrNull() }
            val maxHrDef = async { runCatching { fetchJson("$base/info/hashrate/max")?.jsonObject }.getOrNull() }
            val rewardDef = async { runCatching { fetchJson("$base/info/blockreward")?.jsonObject }.getOrNull() }
            val halvingDef = async { runCatching { fetchJson("$base/info/halving")?.jsonObject }.getOrNull() }
            val dagDef = async { runCatching { fetchJson("$base/info/blockdag")?.jsonObject }.getOrNull() }
            val vbDef = async { runCatching { fetchJson("$base/info/virtual-chain-blue-score")?.jsonObject }.getOrNull() }

            val hr = hrDef.await()?.get("hashrate")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val maxHrObj = maxHrDef.await()
            val maxHr = maxHrObj?.get("hashrate")?.jsonPrimitive?.doubleOrNull ?: hr
            val maxDate = maxHrObj?.get("blockheader")?.jsonObject?.get("timestamp")?.jsonPrimitive?.content
                ?.substringBefore("T").orEmpty()
            val blockReward = rewardDef.await()?.get("blockreward")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val halvingObj = halvingDef.await()
            val nextAmount = halvingObj?.get("nextHalvingAmount")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val nextTsSec = halvingObj?.get("nextHalvingTimestamp")?.jsonPrimitive?.longOrNull ?: 0L
            val nextDate = halvingObj?.get("nextHalvingDate")?.jsonPrimitive?.content.orEmpty()
            val dagObj = dagDef.await()
            val daaScore = dagObj?.get("virtualDaaScore")?.jsonPrimitive?.content?.toLongOrNull()
                ?: dagObj?.get("virtualDaaScore")?.jsonPrimitive?.longOrNull
                ?: 0L
            val difficulty = dagObj?.get("difficulty")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val blueScore = vbDef.await()?.get("blueScore")?.jsonPrimitive?.longOrNull ?: 0L

            NetworkMiningStats(
                currentHashrateThs = hr,
                maxHashrateThs = maxHr,
                maxHashrateDate = maxDate,
                blockRewardKas = blockReward,
                nextReductionRewardKas = nextAmount,
                nextReductionTimestampSec = nextTsSec,
                nextReductionDate = nextDate,
                virtualDaaScore = daaScore,
                blueScore = blueScore,
                difficulty = difficulty,
            )
        }
    }

    suspend fun fetchHashrateHistory(
        network: KaspaNetwork,
        timeframe: HashrateTimeframe,
        forceRefresh: Boolean = false,
    ): List<HashratePoint> = withContext(Dispatchers.IO) {
        val key = network to timeframe
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            hashrateHistoryCache[key]?.let { (ts, cached) ->
                if (now - ts < 180_000L) return@withContext cached
            }
        }
        val base = baseUrl(network)
        val (resolution, windowMs) = when (timeframe) {
            HashrateTimeframe.D1 -> "15m" to 86_400_000L
            HashrateTimeframe.D7 -> "3h" to 7L * 86_400_000L
            HashrateTimeframe.D30 -> "1d" to 30L * 86_400_000L
            HashrateTimeframe.Y1 -> "1d" to 365L * 86_400_000L
            HashrateTimeframe.MAX -> "7d" to Long.MAX_VALUE
        }

        val arr = fetchJson("$base/info/hashrate/history?resolution=$resolution") as? JsonArray
            ?: return@withContext emptyList()
        val parsed = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ts = o["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val kh = o["hashrate_kh"]?.jsonPrimitive?.doubleOrNull
                ?: o["hashrate_kh"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return@mapNotNull null
            if (!kh.isFinite() || kh <= 0.0) return@mapNotNull null
            // Convert kH/s -> TH/s (1 TH/s = 10^9 kH/s)
            HashratePoint(timestampMs = ts, hashrateThs = kh / 1_000_000_000.0)
        }.sortedBy { it.timestampMs }

        val filtered = if (windowMs == Long.MAX_VALUE || parsed.isEmpty()) {
            parsed
        } else {
            val cutoff = parsed.last().timestampMs - windowMs
            parsed.filter { it.timestampMs >= cutoff }.ifEmpty { parsed.takeLast(60) }
        }
        // Downsample smoothly to <= 120 points for crisp 120fps chart animation
        val decimated = downsampleList(filtered, maxPoints = 120)
        hashrateHistoryCache[key] = now to decimated
        decimated
    }

    // =========================================================================
    // 6. Rich List Tab (`/addresses/top`, `/addresses/names`, `/addresses/distribution`)
    // =========================================================================

    suspend fun fetchRichListAndDistribution(
        network: KaspaNetwork,
        forceRefresh: Boolean = false,
    ): Pair<List<RichListEntry>, List<DistributionTier>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cachedList = if (!forceRefresh) richListCache[network]?.takeIf { now - it.first < 300_000L }?.second else null
        val cachedDist = if (!forceRefresh) distributionCache[network]?.takeIf { now - it.first < 300_000L }?.second else null
        if (cachedList != null && cachedDist != null) {
            return@withContext cachedList to cachedDist
        }

        val base = baseUrl(network)
        coroutineScope {
            val labelsDef = async { getAddressLabels(network, forceRefresh = forceRefresh) }
            val topDef = async {
                if (cachedList != null) return@async cachedList
                val arr = fetchJson("$base/addresses/top") as? JsonArray ?: return@async emptyList()
                val ranking = (arr.firstOrNull() as? JsonObject)?.get("ranking") as? JsonArray ?: return@async emptyList()
                val labels = labelsDef.await()
                ranking.mapIndexedNotNull { idx, el ->
                    val o = el as? JsonObject ?: return@mapIndexedNotNull null
                    val addr = o["address"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                    val rawRank = o["rank"]?.jsonPrimitive?.intOrNull ?: idx
                    val amountKas = o["amount"]?.jsonPrimitive?.longOrNull
                        ?: o["amount"]?.jsonPrimitive?.doubleOrNull?.toLong()
                        ?: 0L
                    RichListEntry(
                        rank = rawRank + 1, // API rank is 0-based -> display 1..10,000
                        address = addr,
                        amountKas = amountKas,
                        label = labels[addr],
                    )
                }
            }
            val distDef = async {
                if (cachedDist != null) return@async cachedDist
                val arr = runCatching { fetchJson("$base/addresses/distribution") as? JsonArray }.getOrNull()
                    ?: return@async emptyList()
                val tiers = (arr.firstOrNull() as? JsonObject)?.get("tiers") as? JsonArray ?: return@async emptyList()
                tiers.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val tier = o["tier"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val count = o["count"]?.jsonPrimitive?.longOrNull ?: 0L
                    val amt = o["amount"]?.jsonPrimitive?.longOrNull ?: 0L
                    DistributionTier(
                        tier = tier,
                        rangeLabel = ExploreAnalyticsMath.distributionTierLabel(tier),
                        addressCount = count,
                        amountKas = amt,
                    )
                }.sortedBy { it.tier }
            }

            val finalList = topDef.await()
            val finalDist = distDef.await()
            if (finalList.isNotEmpty()) richListCache[network] = now to finalList
            if (finalDist.isNotEmpty()) distributionCache[network] = now to finalDist
            finalList to finalDist
        }
    }

    // =========================================================================
    // 7. BlockDAG Visualizer REST Seed & Polite Fallback (Parallel Multi-Slice Backfill)
    // =========================================================================

    suspend fun fetchRecentDagBlocks(network: KaspaNetwork, lookbackBlueScores: Int = 24): List<DagBlockNode> =
        withContext(Dispatchers.IO) {
            val base = baseUrl(network)
            val vbObj = fetchJson("$base/info/virtual-chain-blue-score")?.jsonObject ?: return@withContext emptyList()
            val tipBlue = vbObj["blueScore"]?.jsonPrimitive?.longOrNull
                ?: vbObj["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: return@withContext emptyList()

            val now = System.currentTimeMillis()
            // Sample dense consecutive blue-score slices in parallel so the BlockDAG canvas opens
            // immediately full of interconnected columns (~50-120 blocks) instead of looking sparse!
            val step = (lookbackBlueScores / 7).coerceAtLeast(1)
            val scores = buildList {
                var offset = lookbackBlueScores
                while (offset >= 1) {
                    add((tipBlue - offset.toLong()).coerceAtLeast(0L))
                    offset -= step
                }
                add((tipBlue - 1L).coerceAtLeast(0L))
            }.distinct()

            val fetchedArrays = coroutineScope {
                scores.map { bs ->
                    async {
                        bs to runCatchingNonCancellation {
                            fetchJson("$base/blocks-from-bluescore?blueScore=$bs&includeTransactions=false") as? JsonArray
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            val out = LinkedHashMap<String, DagBlockNode>()
            for ((bs, arr) in fetchedArrays) {
                if (arr == null) continue
                for (el in arr) {
                    val obj = el as? JsonObject ?: continue
                    val header = obj["header"] as? JsonObject ?: continue
                    val verbose = obj["verboseData"] as? JsonObject ?: continue
                    val hash = verbose["hash"]?.jsonPrimitive?.content
                        ?: header["hash"]?.jsonPrimitive?.content
                        ?: continue
                    val daa = header["daaScore"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: header["daaScore"]?.jsonPrimitive?.longOrNull
                        ?: 0L
                    val blue = header["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: verbose["blueScore"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: bs
                    val ts = header["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: header["timestamp"]?.jsonPrimitive?.longOrNull
                        ?: now
                    val parents = ArrayList<String>()
                    (header["parents"] as? JsonArray)?.firstOrNull()?.let { l0 ->
                        ((l0 as? JsonObject)?.get("parentHashes") as? JsonArray)?.forEach { p ->
                            p.jsonPrimitive.content.takeIf { it.isNotBlank() }?.let { parents.add(it) }
                        }
                    }
                    val selParent = verbose["selectedParentHash"]?.jsonPrimitive?.content
                        ?: parents.firstOrNull().orEmpty()
                    val isChain = verbose["isChainBlock"]?.jsonPrimitive?.booleanOrNull ?: false
                    val txCount = (verbose["transactionIds"] as? JsonArray)?.size ?: 0
                    val reds = (verbose["mergeSetRedsHashes"] as? JsonArray)?.mapNotNull {
                        it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() }
                    }.orEmpty().toSet()

                    reds.forEach { rHash ->
                        out[rHash]?.let { existing ->
                            out[rHash] = existing.copy(isRed = true)
                        }
                    }
                    out[hash] = DagBlockNode(
                        hash = hash,
                        daaScore = daa,
                        blueScore = blue,
                        timestampMs = ts,
                        parentHashes = parents.distinct(),
                        selectedParentHash = selParent,
                        isChainBlock = isChain,
                        isRed = out[hash]?.isRed == true,
                        txCount = txCount,
                        arrivalTimeMs = now,
                    )
                }
            }
            out.values.sortedWith(compareBy<DagBlockNode> { it.daaScore }.thenBy { it.blueScore }.thenBy { it.hash })
        }

    private fun <T> downsampleList(list: List<T>, maxPoints: Int): List<T> {
        if (list.size <= maxPoints) return list
        val step = (list.size - 1).toDouble() / (maxPoints - 1).toDouble()
        val out = ArrayList<T>(maxPoints)
        for (i in 0 until maxPoints) {
            val idx = (i * step).toInt().coerceIn(0, list.lastIndex)
            out.add(list[idx])
        }
        return out
    }
}
