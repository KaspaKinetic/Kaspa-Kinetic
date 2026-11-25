package com.kaspakinetic.wallet.core.explore

import com.kaspakinetic.wallet.core.model.FiatCurrency
import kotlinx.coroutines.CancellationException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

/**
 * Safe wrapper that NEVER swallows coroutine [CancellationException] (preventing
 * `"The coroutine scope left the composition"` errors from leaking into UI banners).
 */
inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

/**
 * Pure, deterministic, zero-Android-dependency analytics math for Stage 2:
 * - On-device KAS vs BTC normalized relative performance calculation
 * - Top 10,000 Rich List instant rank / address / entity-label search & pagination
 * - Exact Kaspa chromatic emission curve generation
 * - Human-friendly hashrate, multi-currency fiat, and supply formatters
 */
object ExploreAnalyticsMath {

    /**
     * Aligns raw KAS and BTC historical spot candles by overlapping timestamp window and computes
     * normalized `%` performance curves starting at `0.0%` at `t0`, plus the exact relative
     * outperformance/underperformance (`kasChangePct - btcChangePct`).
     */
    fun computeKasVsBtcComparison(
        timeframe: MarketTimeframe,
        sourceName: String,
        kasCandlesRaw: List<CandlePoint>,
        btcCandlesRaw: List<CandlePoint>,
    ): KasVsBtcComparison? {
        val kasSorted = kasCandlesRaw
            .filter { it.timestampMs > 0L && it.close.isFinite() && it.close > 0.0 }
            .sortedBy { it.timestampMs }
            .distinctBy { it.timestampMs }
        val btcSorted = btcCandlesRaw
            .filter { it.timestampMs > 0L && it.close.isFinite() && it.close > 0.0 }
            .sortedBy { it.timestampMs }
            .distinctBy { it.timestampMs }

        if (kasSorted.size < 2 || btcSorted.size < 2) return null

        // Align on overlapping time window so KAS and BTC start at the exact same baseline date
        // (crucial for 5Y / All where BTC predates Kaspa's 2022 exchange listing).
        val overlapStart = maxOf(kasSorted.first().timestampMs, btcSorted.first().timestampMs)
        val overlapEnd = minOf(kasSorted.last().timestampMs, btcSorted.last().timestampMs)
        val kasWindow = kasSorted.filter { it.timestampMs in overlapStart..overlapEnd }
        if (kasWindow.size < 2) return null

        // For each KAS candle timestamp, locate the closest BTC candle via two-pointer walk
        val alignedKas = ArrayList<CandlePoint>(kasWindow.size)
        val alignedBtc = ArrayList<CandlePoint>(kasWindow.size)
        var btcIdx = 0
        for (k in kasWindow) {
            while (btcIdx + 1 < btcSorted.size &&
                abs(btcSorted[btcIdx + 1].timestampMs - k.timestampMs) <= abs(btcSorted[btcIdx].timestampMs - k.timestampMs)
            ) {
                btcIdx++
            }
            alignedKas.add(k)
            alignedBtc.add(CandlePoint(timestampMs = k.timestampMs, close = btcSorted[btcIdx].close))
        }

        if (alignedKas.size < 2 || alignedBtc.size < 2) return null

        val kasBase = alignedKas.first().close
        val btcBase = alignedBtc.first().close
        if (kasBase <= 0.0 || btcBase <= 0.0) return null

        val kasPct = alignedKas.map { pt ->
            pt.timestampMs to (((pt.close / kasBase) - 1.0) * 100.0)
        }
        val btcPct = alignedBtc.map { pt ->
            pt.timestampMs to (((pt.close / btcBase) - 1.0) * 100.0)
        }

        val kasChange = kasPct.last().second
        val btcChange = btcPct.last().second
        val relativeDiff = kasChange - btcChange

        return KasVsBtcComparison(
            timeframe = timeframe,
            sourceName = sourceName,
            earliestTimestampMs = alignedKas.first().timestampMs,
            kasPctPoints = kasPct,
            btcPctPoints = btcPct,
            kasChangePct = kasChange,
            btcChangePct = btcChange,
            relativeOutperformancePct = relativeDiff,
            kasLatestPrice = alignedKas.last().close,
            btcLatestPrice = alignedBtc.last().close,
        )
    }

    /**
     * Smart search over the Top 10,000 Rich List:
     * - Typing `#5000` or `5000` matches that exact rank immediately (and returns it pinned cleanly).
     * - Typing a `kaspa:` / `kaspatest:` address or address substring finds matching addresses.
     * - Typing a known entity label (e.g., `Kraken`, `Gate.io`, `Bybit`) filters labeled addresses.
     */
    fun filterAndSearchRichList(
        entries: List<RichListEntry>,
        rawQuery: String,
    ): RichListSearchResult {
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            return RichListSearchResult(entries = entries, exactJumpRank = null, scrollToIndex = null)
        }

        // 1. Rank jump query: `#5000` or `5000`
        val rankCandidate = q.removePrefix("#").trim()
        if (rankCandidate.isNotEmpty() && rankCandidate.all { it.isDigit() }) {
            val targetRank = rankCandidate.toIntOrNull()
            if (targetRank != null && targetRank >= 1) {
                val idx = entries.indexOfFirst { it.rank == targetRank }
                if (idx >= 0) {
                    // Return the exact entry plus up to 24 surrounding neighbors for context, with exact match highlighted
                    val start = (idx - 2).coerceAtLeast(0)
                    val end = (idx + 25).coerceAtMost(entries.size)
                    val window = entries.subList(start, end)
                    val exactPos = idx - start
                    return RichListSearchResult(
                        entries = window,
                        exactJumpRank = targetRank,
                        scrollToIndex = exactPos,
                    )
                }
                return RichListSearchResult(entries = emptyList(), exactJumpRank = targetRank, scrollToIndex = null)
            }
        }

        // 2. Address or known entity label substring filter (case-insensitive)
        val needle = q.lowercase(Locale.ROOT)
        val filtered = entries.filter { entry ->
            entry.address.lowercase(Locale.ROOT).contains(needle) ||
                (entry.label?.lowercase(Locale.ROOT)?.contains(needle) == true)
        }
        return RichListSearchResult(
            entries = filtered,
            exactJumpRank = null,
            scrollToIndex = if (filtered.isNotEmpty()) 0 else null,
        )
    }

    /**
     * Generates Kaspa's exact chromatic-phase monthly block reward emission schedule:
     * Each month reduces block reward smoothly by `(1/2)^(1/12)` (~5.61% monthly -> 50% yearly).
     */
    fun buildEmissionSchedulePoints(
        currentRewardKas: Double,
        nextReductionTimestampSec: Long,
        monthsAhead: Int = 48,
    ): List<EmissionSchedulePoint> {
        if (currentRewardKas <= 0.0 || !currentRewardKas.isFinite()) return emptyList()
        val monthlyFactor = 0.5.pow(1.0 / 12.0)
        val monthMs = 30L * 24L * 3600L * 1000L
        val baseMs = if (nextReductionTimestampSec > 0L) {
            nextReductionTimestampSec * 1000L - monthMs
        } else {
            System.currentTimeMillis()
        }
        val fmt = SimpleDateFormat("MMM yy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val out = ArrayList<EmissionSchedulePoint>(monthsAhead + 1)
        var reward = currentRewardKas
        for (m in 0..monthsAhead) {
            val ts = baseMs + m * monthMs
            out.add(
                EmissionSchedulePoint(
                    monthOffset = m,
                    timestampMs = ts,
                    label = fmt.format(Date(ts)),
                    blockRewardKas = reward,
                ),
            )
            reward *= monthlyFactor
        }
        return out
    }

    fun distributionTierLabel(tier: Int): String = when (tier) {
        0 -> "< 1 KAS"
        1 -> "1 – 10"
        2 -> "10 – 100"
        3 -> "100 – 1K"
        4 -> "1K – 10K"
        5 -> "10K – 100K"
        6 -> "100K – 1M"
        7 -> "1M – 10M"
        8 -> "10M – 100M"
        9 -> "100M – 1B"
        else -> "1B+ KAS"
    }

    fun formatHashrateFromThs(ths: Double): String {
        if (!ths.isFinite() || ths <= 0.0) return "—"
        return when {
            ths >= 1_000_000.0 -> String.format(Locale.US, "%.2f EH/s", ths / 1_000_000.0)
            ths >= 1_000.0 -> String.format(Locale.US, "%.2f PH/s", ths / 1_000.0)
            ths >= 1.0 -> String.format(Locale.US, "%.2f TH/s", ths)
            else -> String.format(Locale.US, "%.1f GH/s", ths * 1_000.0)
        }
    }

    /**
     * Paginates the Top 10,000 Rich List while preserving instant `#rank` jump, `kaspa:` address,
     * and entity-label filtering.
     */
    fun paginateRichList(
        entries: List<RichListEntry>,
        rawQuery: String,
        requestedPage: Int,
        pageSize: Int,
    ): RichListPageSlice {
        val safePageSize = pageSize.coerceIn(1, 500)
        val q = rawQuery.trim()

        // 1. Rank jump query: `#5000` or `5000` -> jump directly to the page containing that rank!
        val rankCandidate = q.removePrefix("#").trim()
        if (q.isNotEmpty() && rankCandidate.isNotEmpty() && rankCandidate.all { it.isDigit() }) {
            val targetRank = rankCandidate.toIntOrNull()
            if (targetRank != null && targetRank >= 1) {
                val idx = entries.indexOfFirst { it.rank == targetRank }
                if (idx >= 0) {
                    val totalPages = ((entries.size + safePageSize - 1) / safePageSize).coerceAtLeast(1)
                    val targetPage = (idx / safePageSize).coerceIn(0, totalPages - 1)
                    val from = targetPage * safePageSize
                    val to = (from + safePageSize).coerceAtMost(entries.size)
                    return RichListPageSlice(
                        pageItems = entries.subList(from, to),
                        currentPage = targetPage,
                        totalPages = totalPages,
                        totalItems = entries.size,
                        highlightedRank = targetRank,
                    )
                }
                return RichListPageSlice(
                    pageItems = emptyList(),
                    currentPage = 0,
                    totalPages = 1,
                    totalItems = 0,
                    highlightedRank = targetRank,
                )
            }
        }

        // 2. Address or entity label filter (or unfiltered full list)
        val source = if (q.isEmpty()) {
            entries
        } else {
            val needle = q.lowercase(Locale.ROOT)
            entries.filter { entry ->
                entry.address.lowercase(Locale.ROOT).contains(needle) ||
                    (entry.label?.lowercase(Locale.ROOT)?.contains(needle) == true)
            }
        }
        val totalPages = ((source.size + safePageSize - 1) / safePageSize).coerceAtLeast(1)
        val safePage = requestedPage.coerceIn(0, totalPages - 1)
        val from = (safePage * safePageSize).coerceAtMost(source.size)
        val to = (from + safePageSize).coerceAtMost(source.size)
        return RichListPageSlice(
            pageItems = if (from < to) source.subList(from, to) else emptyList(),
            currentPage = safePage,
            totalPages = totalPages,
            totalItems = source.size,
            highlightedRank = null,
        )
    }

    fun formatCompactCurrency(amount: Double, currency: FiatCurrency): String {
        if (!amount.isFinite()) return "—"
        val neg = amount < 0.0
        val v = abs(amount)
        val sign = if (neg) "-" else ""
        val sym = currency.symbol
        if (currency == FiatCurrency.BTC) {
            return when {
                v >= 1_000_000.0 -> String.format(Locale.US, "%s%s%.2fM", sign, sym, v / 1_000_000.0)
                v >= 1_000.0 -> String.format(Locale.US, "%s%s%.2fK", sign, sym, v / 1_000.0)
                v >= 1.0 -> String.format(Locale.US, "%s%s%.4f", sign, sym, v)
                else -> String.format(Locale.US, "%s%s%.6f", sign, sym, v)
            }
        }
        return when {
            v >= 1_000_000_000_000.0 -> String.format(Locale.US, "%s%s%.2fT", sign, sym, v / 1_000_000_000_000.0)
            v >= 1_000_000_000.0 -> String.format(Locale.US, "%s%s%.2fB", sign, sym, v / 1_000_000_000.0)
            v >= 1_000_000.0 -> String.format(Locale.US, "%s%s%.2fM", sign, sym, v / 1_000_000.0)
            v >= 1_000.0 -> String.format(Locale.US, "%s%s%.1fK", sign, sym, v / 1_000.0)
            currency == FiatCurrency.JPY || currency == FiatCurrency.KRW -> String.format(Locale.US, "%s%s%,.0f", sign, sym, v)
            else -> String.format(Locale.US, "%s%s%,.2f", sign, sym, v)
        }
    }

    fun formatFullCurrency(amount: Double, currency: FiatCurrency, showPlus: Boolean = false): String {
        if (!amount.isFinite()) return "—"
        val sign = when {
            amount < 0.0 -> "-"
            showPlus && amount > 0.0 -> "+"
            else -> ""
        }
        val v = abs(amount)
        val sym = currency.symbol
        return when (currency) {
            FiatCurrency.BTC -> String.format(Locale.US, "%s%s%.6f", sign, sym, v)
            FiatCurrency.JPY, FiatCurrency.KRW -> String.format(Locale.US, "%s%s%,.0f", sign, sym, v)
            else -> String.format(Locale.US, "%s%s%,.2f", sign, sym, v)
        }
    }

    fun formatSpotPriceCurrency(price: Double, currency: FiatCurrency): String {
        if (!price.isFinite() || price <= 0.0) return "—"
        val sym = currency.symbol
        return when (currency) {
            FiatCurrency.BTC -> String.format(Locale.US, "%s%.8f", sym, price)
            FiatCurrency.JPY, FiatCurrency.KRW -> String.format(Locale.US, "%s%.2f", sym, price)
            else -> String.format(Locale.US, "%s%.5f", sym, price)
        }
    }

    fun formatCompactUsd(amountUsd: Double): String = formatCompactCurrency(amountUsd, FiatCurrency.USD)

    fun formatCompactKas(kas: Double): String {
        if (!kas.isFinite() || kas < 0.0) return "—"
        return when {
            kas >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB KAS", kas / 1_000_000_000.0)
            kas >= 1_000_000.0 -> String.format(Locale.US, "%.2fM KAS", kas / 1_000_000.0)
            kas >= 1_000.0 -> String.format(Locale.US, "%.1fK KAS", kas / 1_000.0)
            else -> DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).format(kas) + " KAS"
        }
    }

    fun formatSignedPct(pct: Double): String {
        if (!pct.isFinite()) return "—"
        val prefix = if (pct >= 0.0) "+" else ""
        return String.format(Locale.US, "%s%.2f%%", prefix, pct)
    }

    fun formatUtcDate(timestampMs: Long): String {
        if (timestampMs <= 0L) return "—"
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(timestampMs))
    }

    fun formatCountdown(targetTimestampSec: Long, nowMs: Long = System.currentTimeMillis()): String {
        val remSec = (targetTimestampSec - nowMs / 1000L).coerceAtLeast(0L)
        val days = remSec / 86_400L
        val hours = (remSec % 86_400L) / 3_600L
        val mins = (remSec % 3_600L) / 60L
        val secs = remSec % 60L
        return if (days > 0L) {
            "${days}d ${hours}h ${mins}m"
        } else {
            "${hours}h ${mins}m ${secs}s"
        }
    }
}
