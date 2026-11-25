package com.kaspakinetic.wallet.core.explore

import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.WalletTx
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * 100% local, privacy-preserving personal wallet performance & milestone analytics engine.
 * Works identically for Hot HD wallets, Watch-Only kpub wallets, and single Watch-Only addresses.
 */
object WalletPerformanceEngine {

    fun computePerformance(
        transactions: List<WalletTx>,
        currentBalanceSompi: Long,
        historicalKasPricesInCurrency: List<CandlePoint>,
        currentSpotPriceInCurrency: Double?,
        timeframe: PerformanceTimeframe,
        privacyKasOnly: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): WalletPerformanceSnapshot {
        val currentKas = (currentBalanceSompi.coerceAtLeast(0L)).toDouble() / SOMPI_PER_KAS.toDouble()
        val currentTier = OceanTier.forKasBalance(currentKas)
        val nextTier = currentTier.nextTier()
        val progressToNext = if (nextTier != null) {
            val span = (nextTier.minKas - currentTier.minKas).coerceAtLeast(1.0)
            ((currentKas - currentTier.minKas) / span).toFloat().coerceIn(0f, 1f)
        } else {
            1f
        }
        val remainingToNext = if (nextTier != null) (nextTier.minKas - currentKas).coerceAtLeast(0.0) else 0.0

        val datedTxs = transactions
            .filter { it.blockTimeMs > 0L }
            .sortedBy { it.blockTimeMs }

        if (datedTxs.isEmpty() && currentBalanceSompi <= 0L) {
            return WalletPerformanceSnapshot(
                isEmpty = true,
                timeframe = timeframe,
                currentKas = 0.0,
                currentFiatValue = if (privacyKasOnly) null else 0.0,
                estimatedCostBasisFiat = null,
                averageEntryPriceFiat = null,
                currentPriceFiat = if (privacyKasOnly) null else currentSpotPriceInCurrency,
                timeframePnlFiat = null,
                timeframePnlPct = null,
                allTimeHighFiatValue = null,
                allTimeHighDateMs = null,
                valueTimeline = emptyList(),
                kasTimeline = emptyList(),
                currentTier = OceanTier.PLANKTON,
                nextTier = OceanTier.OYSTER,
                progressToNextTier = 0f,
                kasRemainingToNextTier = 1.0,
                avgMonthlyDcaKas = 0.0,
                avgMonthlyDcaFiat = null,
                stackingSinceMs = null,
                daysStacking = 0,
                diamondHandsRatioPct = 0.0,
                longestStackingStreakMonths = 0,
                incomingCount = 0,
                outgoingCount = 0,
                milestones = emptyList(),
            )
        }

        val sortedPrices = if (privacyKasOnly) {
            emptyList()
        } else {
            historicalKasPricesInCurrency
                .filter { it.timestampMs > 0L && it.close.isFinite() && it.close > 0.0 }
                .sortedBy { it.timestampMs }
        }
        val effectiveSpot = if (privacyKasOnly) null else (currentSpotPriceInCurrency?.takeIf { it > 0.0 } ?: sortedPrices.lastOrNull()?.close)

        // 1. Compute signed sompi deltas per transaction and reconcile running baseline to live currentBalanceSompi
        fun signedDeltaKas(tx: WalletTx): Double {
            val amt = abs(tx.netAmountSompi).toDouble() / SOMPI_PER_KAS.toDouble()
            return when (tx.direction) {
                TxDirection.RECEIVED -> amt
                TxDirection.SENT, TxDirection.BURN, TxDirection.MINER_DONATION, TxDirection.COVENANT -> -amt
                TxDirection.SELF_TRANSFER, TxDirection.COMPOUND -> -(tx.feeSompi.coerceAtLeast(0L).toDouble() / SOMPI_PER_KAS.toDouble())
            }
        }

        val totalDeltaKas = datedTxs.sumOf { signedDeltaKas(it) }
        // If wallet history was truncated to a recent page, anchor starting balance so final balance equals true live currentKas
        val startingBaselineKas = (currentKas - totalDeltaKas).coerceAtLeast(0.0)

        // 2. Forward walk over transactions to compute:
        //    - running balance timeline
        //    - Ocean Tier unlock dates (first crossing of each threshold!)
        //    - incoming/outgoing counts, total received, biggest single deposit
        //    - estimated cost basis (sum of incoming KAS * historical spot at receive date)
        //    - monthly net KAS map
        var runningKas = startingBaselineKas
        var totalReceivedKas = startingBaselineKas
        var incomingCount = 0
        var outgoingCount = 0
        var biggestDeposit: PerformanceMilestone.BiggestSingleDeposit? = null
        var costBasisFiat = 0.0
        var pricedReceivedKas = 0.0

        val tierUnlockDates = LinkedHashMap<OceanTier, Pair<Long, Double>>()
        val monthlyNetKas = LinkedHashMap<YearMonthKey, Double>()
        val eventPoints = ArrayList<Pair<Long, Double>>(datedTxs.size + 2)

        val firstTxMs = datedTxs.firstOrNull()?.blockTimeMs ?: nowMs
        if (datedTxs.isNotEmpty()) {
            eventPoints.add((firstTxMs - 60_000L).coerceAtLeast(0L) to startingBaselineKas)
        }

        // Record baseline tier unlocks if startingBaselineKas already spans tiers
        if (startingBaselineKas > 0.0) {
            OceanTier.entries.filter { it != OceanTier.PLANKTON && startingBaselineKas >= it.minKas }.forEach { tier ->
                tierUnlockDates[tier] = firstTxMs to startingBaselineKas
            }
        }

        for (tx in datedTxs) {
            val delta = signedDeltaKas(tx)
            runningKas = (runningKas + delta).coerceAtLeast(0.0)
            eventPoints.add(tx.blockTimeMs to runningKas)

            // Check newly crossed Ocean Tiers
            for (tier in OceanTier.entries) {
                if (tier == OceanTier.PLANKTON) continue
                if (runningKas >= tier.minKas && !tierUnlockDates.containsKey(tier)) {
                    tierUnlockDates[tier] = tx.blockTimeMs to runningKas
                }
            }

            if (tx.direction == TxDirection.RECEIVED) {
                incomingCount++
                val receivedKas = abs(tx.netAmountSompi).toDouble() / SOMPI_PER_KAS.toDouble()
                totalReceivedKas += receivedKas
                if (biggestDeposit == null || receivedKas > biggestDeposit.amountKas) {
                    biggestDeposit = PerformanceMilestone.BiggestSingleDeposit(
                        amountKas = receivedKas,
                        timestampMs = tx.blockTimeMs,
                        txId = tx.transactionId,
                    )
                }
                if (!privacyKasOnly) {
                    val histPrice = lookupClosestPrice(sortedPrices, tx.blockTimeMs) ?: effectiveSpot
                    if (histPrice != null && histPrice > 0.0) {
                        costBasisFiat += receivedKas * histPrice
                        pricedReceivedKas += receivedKas
                    }
                }
            } else if (tx.direction == TxDirection.SENT || tx.direction == TxDirection.BURN || tx.direction == TxDirection.MINER_DONATION) {
                outgoingCount++
            }

            val ym = yearMonthOf(tx.blockTimeMs)
            monthlyNetKas[ym] = (monthlyNetKas[ym] ?: 0.0) + delta
        }

        // Anchor final point at `nowMs` with exact live `currentKas`
        eventPoints.add(nowMs to currentKas)

        // 3. Build daily sampled KAS & Fiat Value timelines across the requested timeframe
        val windowStartMs = timeframe.lookbackDays?.let { days ->
            (nowMs - days.toLong() * 86_400_000L).coerceAtLeast(firstTxMs)
        } ?: firstTxMs

        val sampleCount = 72
        val spanMs = (nowMs - windowStartMs).coerceAtLeast(3_600_000L)
        val kasTimeline = ArrayList<Pair<Long, Double>>(sampleCount + 1)
        val valueTimeline = ArrayList<Pair<Long, Double>>(sampleCount + 1)

        var evIdx = 0
        for (i in 0..sampleCount) {
            val ts = if (i == sampleCount) nowMs else windowStartMs + (spanMs * i) / sampleCount
            while (evIdx + 1 < eventPoints.size && eventPoints[evIdx + 1].first <= ts) {
                evIdx++
            }
            val balAtTs = if (i == sampleCount) currentKas else if (eventPoints[evIdx].first <= ts) eventPoints[evIdx].second else startingBaselineKas
            kasTimeline.add(ts to balAtTs)
            if (!privacyKasOnly) {
                val p = if (i == sampleCount) effectiveSpot else (lookupClosestPrice(sortedPrices, ts) ?: effectiveSpot)
                if (p != null && p > 0.0) {
                    valueTimeline.add(ts to (balAtTs * p))
                }
            }
        }

        // 4. Compute ATH wallet value across entire all-time span (sampled cleanly)
        var athValue: Double? = null
        var athDateMs: Long? = null
        if (!privacyKasOnly && effectiveSpot != null) {
            val allSpan = (nowMs - firstTxMs).coerceAtLeast(3_600_000L)
            val athSamples = 96
            var aIdx = 0
            for (i in 0..athSamples) {
                val ts = if (i == athSamples) nowMs else firstTxMs + (allSpan * i) / athSamples
                while (aIdx + 1 < eventPoints.size && eventPoints[aIdx + 1].first <= ts) {
                    aIdx++
                }
                val bal = if (i == athSamples) currentKas else if (eventPoints[aIdx].first <= ts) eventPoints[aIdx].second else startingBaselineKas
                val p = if (i == athSamples) effectiveSpot else (lookupClosestPrice(sortedPrices, ts) ?: effectiveSpot)
                val v = bal * p
                if (athValue == null || v > athValue) {
                    athValue = v
                    athDateMs = ts
                }
            }
        }

        // 5. Timeframe Value P&L & Estimated Average Entry Price
        val currentFiatVal = if (!privacyKasOnly && effectiveSpot != null) currentKas * effectiveSpot else null
        val avgEntry = if (!privacyKasOnly && pricedReceivedKas > 0.0) costBasisFiat / pricedReceivedKas else null
        val estimatedCostBasis = if (!privacyKasOnly && pricedReceivedKas > 0.0) costBasisFiat else null

        val timeframePnlFiat: Double?
        val timeframePnlPct: Double?
        if (currentFiatVal != null && valueTimeline.size >= 2) {
            if (timeframe == PerformanceTimeframe.ALL && estimatedCostBasis != null && estimatedCostBasis > 0.0) {
                timeframePnlFiat = currentFiatVal - estimatedCostBasis
                timeframePnlPct = (timeframePnlFiat / estimatedCostBasis) * 100.0
            } else {
                val startVal = valueTimeline.first().second
                timeframePnlFiat = currentFiatVal - startVal
                timeframePnlPct = if (startVal > 0.01) (timeframePnlFiat / startVal) * 100.0 else null
            }
        } else {
            timeframePnlFiat = null
            timeframePnlPct = null
        }

        // 6. DCA, Diamond Hands Ratio, & Longest Positive Stacking Streak
        val daysStacking = ((nowMs - firstTxMs) / 86_400_000L).toInt().coerceAtLeast(1)
        val monthsStacking = (daysStacking.toDouble() / 30.4375).coerceAtLeast(1.0)
        val avgMonthlyDcaKas = currentKas / monthsStacking
        val avgMonthlyDcaFiat = if (effectiveSpot != null) avgMonthlyDcaKas * effectiveSpot else null

        val diamondHandsRatio = if (totalReceivedKas > 0.0) {
            ((currentKas / totalReceivedKas) * 100.0).coerceIn(0.0, 100.0)
        } else {
            100.0
        }

        val longestStreak = computeLongestPositiveStreak(monthlyNetKas, firstTxMs, nowMs)

        // 7. Build Personal Milestones & Cool Insights Feed
        val milestones = ArrayList<PerformanceMilestone>()
        // Ocean Tier unlocks (highest achieved first!)
        tierUnlockDates.entries.sortedByDescending { it.key.tierIndex }.forEach { (tier, pair) ->
            milestones.add(
                PerformanceMilestone.TierUnlock(
                    tier = tier,
                    achievedAtMs = pair.first,
                    balanceKasAtUnlock = pair.second,
                ),
            )
        }

        // Biggest & Slowest stacking months
        val positiveMonths = monthlyNetKas.entries.filter { it.value > 0.0 }
        positiveMonths.maxByOrNull { it.value }?.let { best ->
            milestones.add(
                PerformanceMilestone.BiggestStackingMonth(
                    monthLabel = best.key.formatLabel(),
                    netKas = best.value,
                ),
            )
        }
        if (monthlyNetKas.size >= 2) {
            monthlyNetKas.entries.minByOrNull { it.value }?.let { slowest ->
                milestones.add(
                    PerformanceMilestone.SlowestGrowthMonth(
                        monthLabel = slowest.key.formatLabel(),
                        netKas = slowest.value,
                    ),
                )
            }
        }

        biggestDeposit?.let { milestones.add(it) }
        if (datedTxs.isNotEmpty()) {
            milestones.add(
                PerformanceMilestone.StackingActivitySummary(
                    firstTxMs = firstTxMs,
                    incomingCount = incomingCount,
                    outgoingCount = outgoingCount,
                ),
            )
        }

        return WalletPerformanceSnapshot(
            isEmpty = false,
            timeframe = timeframe,
            currentKas = currentKas,
            currentFiatValue = currentFiatVal,
            estimatedCostBasisFiat = estimatedCostBasis,
            averageEntryPriceFiat = avgEntry,
            currentPriceFiat = effectiveSpot,
            timeframePnlFiat = timeframePnlFiat,
            timeframePnlPct = timeframePnlPct,
            allTimeHighFiatValue = athValue,
            allTimeHighDateMs = athDateMs,
            valueTimeline = valueTimeline,
            kasTimeline = kasTimeline,
            currentTier = currentTier,
            nextTier = nextTier,
            progressToNextTier = progressToNext,
            kasRemainingToNextTier = remainingToNext,
            avgMonthlyDcaKas = avgMonthlyDcaKas,
            avgMonthlyDcaFiat = avgMonthlyDcaFiat,
            stackingSinceMs = firstTxMs,
            daysStacking = daysStacking,
            diamondHandsRatioPct = diamondHandsRatio,
            longestStackingStreakMonths = longestStreak,
            incomingCount = incomingCount,
            outgoingCount = outgoingCount,
            milestones = milestones,
        )
    }

    private data class YearMonthKey(val year: Int, val month0: Int) : Comparable<YearMonthKey> {
        override fun compareTo(other: YearMonthKey): Int =
            if (year != other.year) year - other.year else month0 - other.month0

        fun next(): YearMonthKey = if (month0 == 11) YearMonthKey(year + 1, 0) else YearMonthKey(year, month0 + 1)

        fun formatLabel(): String {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month0)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val fmt = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.format(Date(cal.timeInMillis))
        }
    }

    private fun yearMonthOf(timestampMs: Long): YearMonthKey {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestampMs
        }
        return YearMonthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    private fun computeLongestPositiveStreak(
        monthlyNetKas: Map<YearMonthKey, Double>,
        firstTxMs: Long,
        nowMs: Long,
    ): Int {
        if (monthlyNetKas.isEmpty()) return 0
        var cur = yearMonthOf(firstTxMs)
        val end = yearMonthOf(nowMs)
        var best = 0
        var streak = 0
        var guard = 0
        while (cur <= end && guard < 240) {
            val net = monthlyNetKas[cur] ?: 0.0
            if (net > 0.0) {
                streak++
                if (streak > best) best = streak
            } else {
                streak = 0
            }
            cur = cur.next()
            guard++
        }
        return best
    }

    private fun lookupClosestPrice(sortedPrices: List<CandlePoint>, targetMs: Long): Double? {
        if (sortedPrices.isEmpty()) return null
        if (targetMs <= sortedPrices.first().timestampMs) return sortedPrices.first().close
        if (targetMs >= sortedPrices.last().timestampMs) return sortedPrices.last().close

        var lo = 0
        var hi = sortedPrices.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val ts = sortedPrices[mid].timestampMs
            when {
                ts == targetMs -> return sortedPrices[mid].close
                ts < targetMs -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        val rightIdx = lo.coerceIn(0, sortedPrices.lastIndex)
        val leftIdx = hi.coerceIn(0, sortedPrices.lastIndex)
        return if (abs(sortedPrices[rightIdx].timestampMs - targetMs) < abs(sortedPrices[leftIdx].timestampMs - targetMs)) {
            sortedPrices[rightIdx].close
        } else {
            sortedPrices[leftIdx].close
        }
    }

    /**
     * Computes progress & DCA time-to-goal ETA with a -30% to +30% KAS price sensitivity band.
     *
     * Math (Fiat DCA mode):
     * - At current price `P`: monthly KAS = `monthlyFiat / P`, `baselineMonths = remainingKas / (monthlyFiat / P)`
     * - If KAS price drops 30% (`0.70 * P`): same fiat buys `1 / 0.70`x more KAS/mo -> `fasterMonths = baselineMonths * 0.70`
     * - If KAS price rises 30% (`1.30 * P`): same fiat buys `1 / 1.30`x fewer KAS/mo -> `slowerMonths = baselineMonths * 1.30`
     *
     * In KAS Only Privacy Mode (or when spot price is unavailable), falls back cleanly to pure
     * KAS/month pace without external fiat queries.
     */
    fun computeGoalEstimate(
        currentKas: Double,
        targetKas: Double,
        historicalMonthlyDcaKas: Double,
        historicalMonthlyDcaFiat: Double?,
        currentPriceFiat: Double?,
        privacyKasOnly: Boolean,
        customMonthlyDcaOverride: Double? = null,
    ): WalletGoalEstimate {
        val safeCurrent = currentKas.coerceAtLeast(0.0)
        val safeTarget = targetKas.coerceAtLeast(0.0)
        val remaining = (safeTarget - safeCurrent).coerceAtLeast(0.0)
        val progressPct = if (safeTarget > 0.0) ((safeCurrent / safeTarget) * 100.0).coerceIn(0.0, 100.0) else 0.0
        val progressFraction = (progressPct / 100.0).toFloat().coerceIn(0f, 1f)
        val isReached = safeTarget > 0.0 && safeCurrent >= safeTarget

        if (isReached) {
            return WalletGoalEstimate(
                targetKas = safeTarget,
                currentKas = safeCurrent,
                remainingKas = 0.0,
                progressFraction = 1f,
                progressPct = 100.0,
                isReached = true,
                baselineMonths = 0.0,
                fasterMonthsMinus30Pct = 0.0,
                slowerMonthsPlus30Pct = 0.0,
                effectiveMonthlyDcaFiat = if (!privacyKasOnly) (customMonthlyDcaOverride?.takeIf { it > 0.0 } ?: historicalMonthlyDcaFiat?.takeIf { it > 0.0 }) else null,
                effectiveMonthlyDcaKas = historicalMonthlyDcaKas.takeIf { it > 0.0 },
                isPrivacyKasOnlyMode = privacyKasOnly,
                usedCustomDcaOverride = customMonthlyDcaOverride != null && customMonthlyDcaOverride > 0.0,
            )
        }

        val validCustom = customMonthlyDcaOverride?.takeIf { it.isFinite() && it > 0.0 }
        val validSpot = if (privacyKasOnly) null else currentPriceFiat?.takeIf { it.isFinite() && it > 0.0 }

        if (!privacyKasOnly && validSpot != null) {
            val monthlyFiat = validCustom ?: historicalMonthlyDcaFiat?.takeIf { it.isFinite() && it > 0.0 }
            if (monthlyFiat == null || monthlyFiat <= 0.0) {
                return WalletGoalEstimate(
                    targetKas = safeTarget,
                    currentKas = safeCurrent,
                    remainingKas = remaining,
                    progressFraction = progressFraction,
                    progressPct = progressPct,
                    isReached = false,
                    baselineMonths = null,
                    fasterMonthsMinus30Pct = null,
                    slowerMonthsPlus30Pct = null,
                    effectiveMonthlyDcaFiat = null,
                    effectiveMonthlyDcaKas = null,
                    isPrivacyKasOnlyMode = false,
                    usedCustomDcaOverride = false,
                )
            }
            val kasPerMonthCurrent = monthlyFiat / validSpot
            val kasPerMonthFast = monthlyFiat / (validSpot * 0.70)
            val kasPerMonthSlow = monthlyFiat / (validSpot * 1.30)

            val baselineMonths = remaining / kasPerMonthCurrent
            val fasterMonths = remaining / kasPerMonthFast
            val slowerMonths = remaining / kasPerMonthSlow

            return WalletGoalEstimate(
                targetKas = safeTarget,
                currentKas = safeCurrent,
                remainingKas = remaining,
                progressFraction = progressFraction,
                progressPct = progressPct,
                isReached = false,
                baselineMonths = baselineMonths,
                fasterMonthsMinus30Pct = fasterMonths,
                slowerMonthsPlus30Pct = slowerMonths,
                effectiveMonthlyDcaFiat = monthlyFiat,
                effectiveMonthlyDcaKas = kasPerMonthCurrent,
                isPrivacyKasOnlyMode = false,
                usedCustomDcaOverride = validCustom != null,
            )
        } else {
            // KAS Only Privacy Mode (or spot price unavailable): pure KAS/month pace
            val monthlyKas = validCustom ?: historicalMonthlyDcaKas.takeIf { it.isFinite() && it > 0.0 }
            val baselineMonths = if (monthlyKas != null && monthlyKas > 0.0) remaining / monthlyKas else null
            return WalletGoalEstimate(
                targetKas = safeTarget,
                currentKas = safeCurrent,
                remainingKas = remaining,
                progressFraction = progressFraction,
                progressPct = progressPct,
                isReached = false,
                baselineMonths = baselineMonths,
                fasterMonthsMinus30Pct = null,
                slowerMonthsPlus30Pct = null,
                effectiveMonthlyDcaFiat = null,
                effectiveMonthlyDcaKas = monthlyKas,
                isPrivacyKasOnlyMode = true,
                usedCustomDcaOverride = validCustom != null,
            )
        }
    }

    /**
     * Formats an estimated duration in months cleanly as `< 1 mo`, `3.5 mo`, `1 yr 4 mo`, or `3 yrs`.
     */
    fun formatDurationMonths(
        months: Double,
        monthsUnit: String = "mo",
        yearsUnit: String = "yr",
    ): String {
        if (!months.isFinite() || months <= 0.0) return "< 1 $monthsUnit"
        if (months < 1.0) return "< 1 $monthsUnit"
        if (months < 12.0) {
            val rounded = kotlin.math.round(months * 10.0) / 10.0
            val text = if (rounded >= 10.0 || rounded % 1.0 == 0.0) {
                kotlin.math.round(rounded).toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", rounded)
            }
            return "$text $monthsUnit"
        }
        val totalRoundedMonths = kotlin.math.round(months).toInt()
        val years = totalRoundedMonths / 12
        val remMonths = totalRoundedMonths % 12
        return if (remMonths == 0) {
            "$years $yearsUnit"
        } else {
            "$years $yearsUnit $remMonths $monthsUnit"
        }
    }
}
