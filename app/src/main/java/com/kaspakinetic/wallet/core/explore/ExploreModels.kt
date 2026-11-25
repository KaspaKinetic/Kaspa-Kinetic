package com.kaspakinetic.wallet.core.explore

/**
 * Domain models for Stage 2 (v2) Native Block Explorer, Kaspa Network Stats (Market + KAS vs BTC +
 * Network & Mining + Top 10,000 Rich List), and Live 10 BPS BlockDAG Visualizer.
 */

// ============================================================================
// A. Native Block Explorer Models
// ============================================================================

data class ExplorerTxInput(
    val previousTxId: String,
    val previousOutpointIndex: Int,
    val address: String?,
    val amountSompi: Long?,
)

data class ExplorerTxOutput(
    val index: Int,
    val address: String,
    val amountSompi: Long,
    val scriptPublicKeyType: String = "",
)

data class ExplorerTxDetail(
    val transactionId: String,
    val blockHashes: List<String>,
    val acceptingBlockHash: String?,
    val acceptingBlockBlueScore: Long?,
    val blockTimeMs: Long,
    val isAccepted: Boolean,
    val isCoinbase: Boolean,
    val mass: Long,
    val inputs: List<ExplorerTxInput>,
    val outputs: List<ExplorerTxOutput>,
    val totalInputSompi: Long,
    val totalOutputSompi: Long,
    val feeSompi: Long,
)

data class ExplorerBlockTxSummary(
    val transactionId: String,
    val isCoinbase: Boolean,
    val inputCount: Int,
    val outputCount: Int,
    val totalOutputSompi: Long,
    val primaryAddress: String,
)

data class ExplorerBlockDetail(
    val hash: String,
    val version: Int,
    val timestampMs: Long,
    val daaScore: Long,
    val blueScore: Long,
    val difficulty: Double,
    val nonce: String,
    val isChainBlock: Boolean,
    val color: String?, // "blue", "red", or null
    val minerAddress: String?,
    val minerInfo: String?,
    val selectedParentHash: String,
    val parentHashes: List<String>,
    val mergeSetBlueHashes: List<String>,
    val mergeSetRedHashes: List<String>,
    val txCount: Int,
    val transactions: List<ExplorerBlockTxSummary>,
)

data class ExplorerAddressDetail(
    val address: String,
    val label: String?,
    val balanceSompi: Long,
    val utxoCount: Int,
    val txCount: Long,
    val transactions: List<ExplorerTxDetail>,
    val nextPageBefore: Long?,
)

enum class LiveFeedFilter {
    ALL,
    TRANSFERS,
    COINBASE,
    LARGE,
}

data class LiveTxFeedItem(
    val transactionId: String,
    val blockHash: String,
    val blockBlueScore: Long,
    val timestampMs: Long,
    val isCoinbase: Boolean,
    val isAccepted: Boolean,
    val totalOutputSompi: Long,
    val inputCount: Int,
    val outputCount: Int,
    val primaryRecipient: String,
) {
    /** Consider >= 10,000 KAS as a "Large Tx" for quick explorer filtering. */
    val isLarge: Boolean get() = totalOutputSompi >= 10_000L * 100_000_000L
}

sealed class ExplorerTarget {
    data object Feed : ExplorerTarget()
    data class Transaction(val txId: String) : ExplorerTarget()
    data class Block(val blockHash: String) : ExplorerTarget()
    data class Address(val address: String) : ExplorerTarget()
}

// ============================================================================
// B. Kaspa Network Stats Models (Market, KAS vs BTC, Mining, Rich List)
// ============================================================================

enum class MarketTimeframe(val label: String) {
    H1("1H"),
    D1("1D"),
    W1("1W"),
    M1("1M"),
    Y1("1Y"),
    ALL_5Y("5Y / All"),
}

enum class HashrateTimeframe(val label: String) {
    D1("1D"),
    D7("7D"),
    D30("30D"),
    Y1("1Y"),
    MAX("Max"),
}

data class CandlePoint(
    val timestampMs: Long,
    val close: Double,
)

data class MarketOverview(
    val spotPriceUsd: Double,
    val change24hPct: Double?,
    val marketCapUsd: Double,
    val circulatingSupplyKas: Double,
    val maxSupplyKas: Double,
)

data class KasVsBtcComparison(
    val timeframe: MarketTimeframe,
    val sourceName: String,
    val earliestTimestampMs: Long,
    val kasPctPoints: List<Pair<Long, Double>>,
    val btcPctPoints: List<Pair<Long, Double>>,
    val kasChangePct: Double,
    val btcChangePct: Double,
    val relativeOutperformancePct: Double,
    val kasLatestPrice: Double,
    val btcLatestPrice: Double,
)

data class KasPriceSeries(
    val timeframe: MarketTimeframe,
    val sourceName: String,
    val earliestTimestampMs: Long,
    val pointsInCurrency: List<Pair<Long, Double>>,
    val latestPriceInCurrency: Double,
    val changePct: Double,
)

/**
 * Official Kaspa sea-creature distribution tiers matching kaspa.stream/coinDistribution
 * and Kaspa's official `/addresses/distribution` tier indices (0..10).
 */
enum class OceanTier(
    val tierIndex: Int,
    val emoji: String,
    val displayName: String,
    val rangeLabel: String,
    val minKas: Double,
) {
    PLANKTON(0, "🦠", "Plankton", "< 1 KAS", 0.0),
    OYSTER(1, "🦪", "Oyster", "1 – 10 KAS", 1.0),
    SHRIMP(2, "🦐", "Shrimp", "10 – 100 KAS", 10.0),
    CRAB(3, "🦀", "Crab", "100 – 1K KAS", 100.0),
    OCTOPUS(4, "🐙", "Octopus", "1K – 10K KAS", 1_000.0),
    FISH(5, "🐟", "Fish", "10K – 100K KAS", 10_000.0),
    DOLPHIN(6, "🐬", "Dolphin", "100K – 1M KAS", 100_000.0),
    SHARK(7, "🦈", "Shark", "1M – 10M KAS", 1_000_000.0),
    WHALE(8, "🐳", "Whale", "10M – 100M KAS", 10_000_000.0),
    HUMPBACK(9, "🐋", "Humpback", "100M – 1B KAS", 100_000_000.0),
    AQUAMAN(10, "🧜‍♂️", "Aquaman", "1B+ KAS", 1_000_000_000.0);

    fun nextTier(): OceanTier? = entries.firstOrNull { it.tierIndex == tierIndex + 1 }

    companion object {
        fun fromTierIndex(index: Int): OceanTier =
            entries.firstOrNull { it.tierIndex == index } ?: if (index >= 10) AQUAMAN else PLANKTON

        fun forKasBalance(kas: Double): OceanTier {
            val safe = kas.coerceAtLeast(0.0)
            return entries.lastOrNull { safe >= it.minKas } ?: PLANKTON
        }
    }
}

data class RichListPageSlice(
    val pageItems: List<RichListEntry>,
    val currentPage: Int, // 0-based
    val totalPages: Int,
    val totalItems: Int,
    val highlightedRank: Int? = null,
)

enum class PerformanceTimeframe(val label: String, val lookbackDays: Int?) {
    W1("1W", 7),
    M1("1M", 30),
    M3("3M", 90),
    Y1("1Y", 365),
    ALL("All", null),
}

sealed class PerformanceMilestone {
    data class TierUnlock(
        val tier: OceanTier,
        val achievedAtMs: Long,
        val balanceKasAtUnlock: Double,
    ) : PerformanceMilestone()

    data class BiggestStackingMonth(
        val monthLabel: String,
        val netKas: Double,
    ) : PerformanceMilestone()

    data class SlowestGrowthMonth(
        val monthLabel: String,
        val netKas: Double,
    ) : PerformanceMilestone()

    data class BiggestSingleDeposit(
        val amountKas: Double,
        val timestampMs: Long,
        val txId: String,
    ) : PerformanceMilestone()

    data class StackingActivitySummary(
        val firstTxMs: Long,
        val incomingCount: Int,
        val outgoingCount: Int,
    ) : PerformanceMilestone()
}

data class WalletPerformanceSnapshot(
    val isEmpty: Boolean,
    val timeframe: PerformanceTimeframe,
    val currentKas: Double,
    val currentFiatValue: Double?,
    val estimatedCostBasisFiat: Double?,
    val averageEntryPriceFiat: Double?,
    val currentPriceFiat: Double?,
    val timeframePnlFiat: Double?,
    val timeframePnlPct: Double?,
    val allTimeHighFiatValue: Double?,
    val allTimeHighDateMs: Long?,
    val valueTimeline: List<Pair<Long, Double>>,
    val kasTimeline: List<Pair<Long, Double>>,
    val currentTier: OceanTier,
    val nextTier: OceanTier?,
    val progressToNextTier: Float,
    val kasRemainingToNextTier: Double,
    val avgMonthlyDcaKas: Double,
    val avgMonthlyDcaFiat: Double?,
    val stackingSinceMs: Long?,
    val daysStacking: Int,
    val diamondHandsRatioPct: Double,
    val longestStackingStreakMonths: Int,
    val incomingCount: Int,
    val outgoingCount: Int,
    val milestones: List<PerformanceMilestone>,
)

data class WalletGoalEstimate(
    val targetKas: Double,
    val currentKas: Double,
    val remainingKas: Double,
    val progressFraction: Float,
    val progressPct: Double,
    val isReached: Boolean,
    val baselineMonths: Double?,
    val fasterMonthsMinus30Pct: Double?,
    val slowerMonthsPlus30Pct: Double?,
    val effectiveMonthlyDcaFiat: Double?,
    val effectiveMonthlyDcaKas: Double?,
    val isPrivacyKasOnlyMode: Boolean,
    val usedCustomDcaOverride: Boolean,
)

data class NetworkMiningStats(
    val currentHashrateThs: Double,
    val maxHashrateThs: Double,
    val maxHashrateDate: String,
    val blockRewardKas: Double,
    val nextReductionRewardKas: Double,
    val nextReductionTimestampSec: Long,
    val nextReductionDate: String,
    val virtualDaaScore: Long,
    val blueScore: Long,
    val difficulty: Double,
)

data class HashratePoint(
    val timestampMs: Long,
    val hashrateThs: Double,
)

data class EmissionSchedulePoint(
    val monthOffset: Int,
    val timestampMs: Long,
    val label: String,
    val blockRewardKas: Double,
)

data class RichListEntry(
    val rank: Int, // 1-based rank (1..10000)
    val address: String,
    val amountKas: Long,
    val label: String? = null,
)

data class RichListSearchResult(
    val entries: List<RichListEntry>,
    val exactJumpRank: Int? = null,
    val scrollToIndex: Int? = null,
)

data class DistributionTier(
    val tier: Int,
    val rangeLabel: String,
    val addressCount: Long,
    val amountKas: Long,
)

// ============================================================================
// C. Live 10 BPS BlockDAG Visualizer Models
// ============================================================================

data class DagBlockNode(
    val hash: String,
    val daaScore: Long,
    val blueScore: Long,
    val timestampMs: Long,
    val parentHashes: List<String>,
    val selectedParentHash: String,
    val isChainBlock: Boolean,
    val isRed: Boolean,
    val txCount: Int,
    val arrivalTimeMs: Long,
)
