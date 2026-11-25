package com.kaspakinetic.wallet.core.covenants

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * The 7 curated consumer Kaspa Layer-1 SilverScript Covenant templates.
 */
@Serializable
enum class CovenantTemplateId(
    val id: String,
    val category: CovenantCategory,
    val isStatefulKip20: Boolean,
    val contractName: String,
) {
    CLAWBACK_VAULT("clawback_vault", CovenantCategory.SECURITY_VAULTS, true, "ClawbackVault"),
    DEAD_MANS_SWITCH("dead_mans_switch", CovenantCategory.SECURITY_VAULTS, true, "DeadMansSwitch"),
    DAILY_SPEND_LIMIT("daily_spend_limit", CovenantCategory.SECURITY_VAULTS, true, "DailySpendLimit"),
    COLD_WHITELIST("cold_whitelist", CovenantCategory.SECURITY_VAULTS, false, "ColdWhitelist"),
    HODL_TIMELOCK("hodl_timelock", CovenantCategory.SAVINGS_PAYMENTS, false, "HodlTimelock"),
    GOAL_PIGGY_BANK("goal_piggy_bank", CovenantCategory.SAVINGS_PAYMENTS, true, "GoalPiggyBank"),
    RECURRING_ALLOWANCE("recurring_allowance", CovenantCategory.SAVINGS_PAYMENTS, true, "RecurringAllowance"),
    ;

    companion object {
        fun fromId(raw: String): CovenantTemplateId? = entries.firstOrNull { it.id == raw }
    }
}

@Serializable
enum class CovenantCategory {
    SECURITY_VAULTS,
    SAVINGS_PAYMENTS,
}

@Serializable
enum class CovenantStatus {
    ACTIVE,
    UNVAULTING,
    READY,
    SETTLED,
    UNCONFIRMED,
}

/**
 * Immutable & stateful parameters for any of the 7 consumer Kaspa L1 SilverScript covenants.
 */
@Serializable
data class CovenantParams(
    val ownerPubKeyHex: String,
    val ownerAddress: String = "",
    // Exact BIP-32 derivation metadata for the signing key (persisted in both .kinetic and KKCV1)
    val ownerDerivationPath: String = "",
    val ownerDerivationChain: Int = -1,
    val ownerDerivationIndex: Int = -1,
    // Unique per-vault CSPRNG salt (32 hex chars = 16 bytes) baked into redeemScript via `<salt> OP_DROP`
    // so two vaults with identical user settings NEVER collide on the same P2SH address!
    val vaultSaltHex: String = "",
    // Clawback Vault
    val alarmPubKeyHex: String = "",
    val recoveryAddress: String = "",
    val unvaultDelayDaa: Long = 864_000L, // 24 hours at 10 BPS
    val vaultStage: Int = 0, // 0 = vaulted, 1 = unvaulting
    val pendingUnvaultDestAddress: String = "",
    // Dead Man's Switch
    val heirAddress: String = "",
    val heirPubKeyHex: String = "",
    val inactivityDaa: Long = 25_920_000L, // Immutable CSV relative DAA timeout (10 BPS, fits 32-bit unsigned)
    val inactivityDeadlineMs: Long = 0L, // Legacy v3.2-v3.7 tx.time Unix ms deadline (>= 500_000_000_000L)
    val inactivityDurationMs: Long = 30L * 86_400_000L, // Human-readable inactivity duration
    val keylessCsvHeir: Boolean = false, // v3.8 permanent fixed-address CSV keyless heirClaim() (0-KAS claimable)
    // Daily Spend Limit (immediate Day-1 spend + dynamic reset window + bypass full-withdraw unlock time)
    val dailyCapSompi: Long = 100L * SOMPI_PER_KAS,
    val windowDaa: Long = 864_000L, // 24 hours default at 10 BPS (CSV relative sequence lock)
    val spentInWindowSompi: Long = 0L,
    val windowStartDaa: Long = 0L,
    val fullWithdrawUnlockDaa: Long = 0L, // Legacy DAA fallback
    val fullWithdrawUnlockTimeMs: Long = 0L, // Native tx.time Unix ms bypass unlock
    // Cold Whitelist (1..3 cold addresses)
    val whitelistAddresses: List<String> = emptyList(),
    // Hodl Time-Lock
    val unlockDaa: Long = 0L, // Legacy DAA fallback
    val unlockTimeMs: Long = 0L, // Native tx.time Unix ms unlock (>= 500_000_000_000L)
    val unlockDateIso: String = "",
    // Goal-Locked Piggy Bank
    val targetGoalSompi: Long = 1_000L * SOMPI_PER_KAS,
    val fallbackUnlockDaa: Long = 0L, // Legacy DAA fallback
    val fallbackUnlockTimeMs: Long = 0L, // Native tx.time Unix ms safety fallback
    // Recurring Allowance / Subscription
    val recipientAddress: String = "",
    val recipientPubKeyHex: String = "",
    val allowanceSompi: Long = 10L * SOMPI_PER_KAS,
    val periodDaa: Long = 6_048_000L, // Legacy DAA fallback
    val nextDueTimeMs: Long = 0L, // Native tx.time Unix ms for next keyless payout
    val intervalMs: Long = 7L * 86_400_000L, // Cycle interval added on-chain via OP_NUM2BIN
)

/**
 * Canonical tracked covenant record stored per wallet and synced live against chain UTXOs.
 */
@Serializable
data class TrackedCovenant(
    val canonicalKey: String,
    val walletId: String,
    val network: KaspaNetwork,
    val templateId: CovenantTemplateId,
    val label: String,
    val note: String = "",
    val covenantIdHex: String? = null, // 64-char KIP-20 lineage ID for stateful covenants
    val currentP2shAddress: String,
    val currentRedeemScriptHex: String,
    val redeemScriptHashHex: String,
    val silSource: String,
    val abiArtifact: SilAbiArtifact,
    val params: CovenantParams,
    val knownRedeemScriptsHex: List<String> = emptyList(),
    val deployTxId: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastUpdatedAtMs: Long = System.currentTimeMillis(),
    // Live chain-resolved fields (NEVER downgraded by older .kinetic files!)
    val liveBalanceSompi: Long = 0L,
    val liveUtxoTxId: String? = null,
    val liveUtxoIndex: Int? = null,
    val liveUtxoBlockDaaScore: Long = 0L,
    val liveStatus: CovenantStatus = CovenantStatus.UNCONFIRMED,
    val lastChainVerifiedAtMs: Long = 0L,
    val isSharedPassImport: Boolean = false,
    // Non-zero ONLY when THIS device just broadcast a deploy or state-transition tx awaiting
    // block inclusion (< 60s). Restored vaults (KKCV1 / .kinetic / .kpass) keep this 0L so
    // already-settled vaults NEVER get stuck in Pending!
    val localBroadcastAtMs: Long = 0L,
    // Non-zero when plain unbound KAS was sent externally straight to the covenant's P2SH address
    val strayUnboundBalanceSompi: Long = 0L,
)

data class CompiledCovenantBundle(
    val templateId: CovenantTemplateId,
    val silSource: String,
    val redeemScript: ByteArray,
    val redeemScriptHashHex: String,
    val p2shScriptPubKey: ByteArray,
    val p2shAddress: String,
    val abiArtifact: SilAbiArtifact,
)

data class PreparedCovenantTx(
    val rawTx: KaspaMassAndTx.RawTx,
    val txId: String,
    val submitJson: String,
    val feeSompi: Long,
    val mass: Long,
    val covenantIdHex: String?,
    val vmResult: KaspaScriptVm.VmResult?,
)

/**
 * Live cryptographic & on-chain proof report displayed in the "Verify On-Chain" sheet.
 */
data class OnChainCovenantProof(
    val covenant: TrackedCovenant,
    val isLiveOnChain: Boolean,
    val scriptHashVerified: Boolean,
    val latestTxId: String,
    val confirmedBlockDaaScore: Long,
    val currentVirtualDaaScore: Long,
    val confirmedBalanceSompi: Long,
    val strayUnboundBalanceSompi: Long,
    val covenantIdHex: String?,
    val p2shAddress: String,
    val kaspaStreamUrl: String,
    val explorerKaspaOrgUrl: String,
    val verifiedAtMs: Long = System.currentTimeMillis(),
    val nodeTransport: String = "gRPC",
)

enum class TimeLockStage {
    /** Countdown still has >15s remaining; button disabled, displays smooth 1s countdown. */
    LOCKED,
    /** Local countdown is <=15s (or 0s) while waiting for real gRPC node `pastMedianTime > unlockTimeMs`. */
    FINALIZING,
    /** Strictly confirmed by real non-extrapolated gRPC server `pastMedianTime > unlockTimeMs`. */
    READY,
}

data class TimeLockEvaluation(
    val stage: TimeLockStage,
    val extrapolatedRemainingMs: Long,
    val serverConfirmedReady: Boolean,
)

/**
 * Monotonic Kaspa chain-time anchor driven by live gRPC `GetBlockDagInfo` (`pastMedianTime` + `virtualDaaScore`)
 * and device monotonic `SystemClock.elapsedRealtime()`.
 *
 * CRITICAL CONSENSUS INVARIANT:
 * - [pastMedianTimeMs] and [virtualDaaScore] store STRICTLY the real, non-extrapolated server-reported
 *   values from `GetBlockDagInfoResponseMessage` / `NotifyVirtualDaaScoreChanged` (NEVER polluted by
 *   local extrapolated deltas!).
 * - [estimatePastMedianTimeMs] and [estimateVirtualDaaScore] are used ONLY to display smooth 1-second
 *   countdowns while locked.
 * - [evaluateTimeLock] and [evaluateRelativeDaaLock] NEVER report [TimeLockStage.READY] until the real
 *   non-extrapolated gRPC server `pastMedianTimeMs > unlockTimeMs` or
 *   `virtualDaaScore - utxoBlockDaaScore >= requiredDaa`, eliminating premature `"not finalized"` /
 *   CSV rejections!
 */
data class ChainTimeAnchor(
    val pastMedianTimeMs: Long = 0L,
    val virtualDaaScore: Long = 0L,
    val capturedElapsedRealtimeMs: Long = 0L,
    val daaCapturedElapsedRealtimeMs: Long = capturedElapsedRealtimeMs,
    val displayFloorDaa: Long = 0L,
) {
    val hasLiveMedianTime: Boolean
        get() = pastMedianTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD && capturedElapsedRealtimeMs > 0L

    val hasLiveVirtualDaa: Boolean
        get() = virtualDaaScore > 0L && daaCapturedElapsedRealtimeMs > 0L

    /** Live monotonic estimate of Kaspa's consensus `pastMedianTime` in ms — for countdown display ONLY. */
    fun estimatePastMedianTimeMs(
        nowElapsedRealtimeMs: Long,
        fallbackWallClockMs: Long = System.currentTimeMillis(),
    ): Long {
        if (!hasLiveMedianTime) {
            // Conservative ~2.5 min lag when offline so UI never falsely rushes past node pastMedianTime
            return (fallbackWallClockMs - OFFLINE_MEDIAN_LAG_MS).coerceAtLeast(CovenantCompiler.LOCK_TIME_THRESHOLD)
        }
        val deltaMs = (nowElapsedRealtimeMs - capturedElapsedRealtimeMs).coerceAtLeast(0L)
        return pastMedianTimeMs + deltaMs
    }

    /** True ONLY when the real, non-extrapolated gRPC server `pastMedianTime` strictly exceeds [unlockTimeMs]. */
    fun isServerTimeLockReady(unlockTimeMs: Long): Boolean =
        hasLiveMedianTime && CovenantCompiler.isTimeLockReady(unlockTimeMs, pastMedianTimeMs)

    /** True ONLY when the real, non-extrapolated gRPC server `virtualDaaScore - utxoBlockDaaScore >= requiredDaa`. */
    fun isServerRelativeDaaLockReady(utxoBlockDaaScore: Long, requiredDaa: Long): Boolean =
        hasLiveVirtualDaa && CovenantCompiler.isRelativeDaaLockReady(utxoBlockDaaScore, requiredDaa, virtualDaaScore)

    /**
     * Evaluates a native `tx.time` lock against BOTH the real server `pastMedianTime` (for readiness)
     * and the smooth local 1-second extrapolated ticker (for countdown display & `Finalizing` state).
     */
    fun evaluateTimeLock(
        unlockTimeMs: Long,
        nowElapsedRealtimeMs: Long,
        finalizingThresholdMs: Long = FINALIZING_POLL_WINDOW_MS,
    ): TimeLockEvaluation {
        if (unlockTimeMs < CovenantCompiler.LOCK_TIME_THRESHOLD) {
            return TimeLockEvaluation(TimeLockStage.LOCKED, Long.MAX_VALUE, serverConfirmedReady = false)
        }
        if (isServerTimeLockReady(unlockTimeMs)) {
            return TimeLockEvaluation(TimeLockStage.READY, 0L, serverConfirmedReady = true)
        }
        val estRem = CovenantCompiler.remainingTimeLockMs(
            unlockTimeMs = unlockTimeMs,
            livePastMedianTimeMs = estimatePastMedianTimeMs(nowElapsedRealtimeMs),
        )
        return if (estRem <= finalizingThresholdMs) {
            TimeLockEvaluation(TimeLockStage.FINALIZING, estRem, serverConfirmedReady = false)
        } else {
            TimeLockEvaluation(TimeLockStage.LOCKED, estRem, serverConfirmedReady = false)
        }
    }

    /**
     * Evaluates an `OP_CHECKSEQUENCEVERIFY` relative DAA lock against BOTH the real server
     * `virtualDaaScore` (strict Ready gate) and the smooth local 1-second extrapolated ticker
     * (human-readable countdown & `Finalizing on Kaspa L1...` within <= 10s).
     */
    fun evaluateRelativeDaaLock(
        utxoBlockDaaScore: Long,
        requiredDaa: Long,
        nowElapsedRealtimeMs: Long,
        finalizingThresholdMs: Long = FINALIZING_DAA_POLL_WINDOW_MS,
    ): TimeLockEvaluation {
        if (requiredDaa <= 0L || utxoBlockDaaScore <= 0L) {
            return TimeLockEvaluation(TimeLockStage.LOCKED, Long.MAX_VALUE, serverConfirmedReady = false)
        }
        if (isServerRelativeDaaLockReady(utxoBlockDaaScore, requiredDaa)) {
            return TimeLockEvaluation(TimeLockStage.READY, 0L, serverConfirmedReady = true)
        }
        val estDaa = estimateVirtualDaaScore(nowElapsedRealtimeMs)
        val estAgeDaa = if (estDaa > 0L) (estDaa - utxoBlockDaaScore).coerceAtLeast(0L) else 0L
        val remDaa = (requiredDaa - estAgeDaa).coerceAtLeast(0L)
        val estRemMs = (remDaa * 1_000L) / CovenantCompiler.DAA_BLOCKS_PER_SECOND
        return if (estRemMs <= finalizingThresholdMs) {
            TimeLockEvaluation(TimeLockStage.FINALIZING, estRemMs, serverConfirmedReady = false)
        } else {
            TimeLockEvaluation(TimeLockStage.LOCKED, estRemMs, serverConfirmedReady = false)
        }
    }

    /** Returns true if any active `tx.time` OR relative CSV DAA lock on [covenant] is currently in [TimeLockStage.FINALIZING]. */
    fun isCovenantFinalizing(covenant: TrackedCovenant, nowElapsedRealtimeMs: Long): Boolean {
        if (covenant.liveStatus == CovenantStatus.SETTLED) return false
        val unlockTimes = when (covenant.templateId) {
            CovenantTemplateId.HODL_TIMELOCK -> listOf(covenant.params.unlockTimeMs)
            CovenantTemplateId.DEAD_MANS_SWITCH -> listOf(covenant.params.inactivityDeadlineMs)
            CovenantTemplateId.GOAL_PIGGY_BANK -> listOf(covenant.params.fallbackUnlockTimeMs)
            CovenantTemplateId.DAILY_SPEND_LIMIT -> listOf(covenant.params.fullWithdrawUnlockTimeMs)
            CovenantTemplateId.RECURRING_ALLOWANCE -> listOf(covenant.params.nextDueTimeMs)
            else -> emptyList()
        }
        val timeFinalizing = unlockTimes.any { ts ->
            ts >= CovenantCompiler.LOCK_TIME_THRESHOLD &&
                evaluateTimeLock(ts, nowElapsedRealtimeMs).stage == TimeLockStage.FINALIZING
        }
        if (timeFinalizing) return true

        val utxoDaa = covenant.liveUtxoBlockDaaScore
        if (utxoDaa <= 0L) return false
        return when (covenant.templateId) {
            CovenantTemplateId.DEAD_MANS_SWITCH ->
                !CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(covenant.params) &&
                    evaluateRelativeDaaLock(utxoDaa, covenant.params.inactivityDaa, nowElapsedRealtimeMs).stage == TimeLockStage.FINALIZING
            CovenantTemplateId.CLAWBACK_VAULT ->
                covenant.params.vaultStage == 1 &&
                    evaluateRelativeDaaLock(utxoDaa, covenant.params.unvaultDelayDaa, nowElapsedRealtimeMs).stage == TimeLockStage.FINALIZING
            CovenantTemplateId.DAILY_SPEND_LIMIT ->
                covenant.params.spentInWindowSompi > 0L &&
                    evaluateRelativeDaaLock(utxoDaa, covenant.params.windowDaa, nowElapsedRealtimeMs).stage == TimeLockStage.FINALIZING
            else -> false
        }
    }

    /** Live monotonic estimate of Kaspa's `virtualDaaScore` (10 BPS = 100ms per block) — for countdown display ONLY. */
    fun estimateVirtualDaaScore(nowElapsedRealtimeMs: Long): Long {
        if (virtualDaaScore <= 0L || daaCapturedElapsedRealtimeMs <= 0L) {
            return maxOf(virtualDaaScore, displayFloorDaa)
        }
        val deltaMs = (nowElapsedRealtimeMs - daaCapturedElapsedRealtimeMs).coerceAtLeast(0L)
        return maxOf(virtualDaaScore + (deltaMs / 100L), displayFloorDaa)
    }

    /**
     * Merges a fresh gRPC `GetBlockDagInfo` / `NotifyVirtualDaaScoreChanged` update:
     * - [pastMedianTimeMs] and [virtualDaaScore] ONLY ever adopt real server-reported values
     *   (`maxOf(new, current)`), NEVER polluted by local extrapolated deltas!
     * - [displayFloorDaa] keeps countdown display monotonic even if a stale snapshot arrives.
     * - DAA-only ticks within <900ms do not churn StateFlow collectors at 10 BPS.
     */
    fun mergeUpdate(
        newPastMedianTimeMs: Long = 0L,
        newVirtualDaaScore: Long = 0L,
        nowElapsedRealtimeMs: Long,
    ): ChainTimeAnchor {
        val prevEstDaa = if (virtualDaaScore > 0L && daaCapturedElapsedRealtimeMs > 0L) {
            estimateVirtualDaaScore(nowElapsedRealtimeMs)
        } else {
            maxOf(virtualDaaScore, displayFloorDaa)
        }
        val hasFreshServerPmt = newPastMedianTimeMs >= CovenantCompiler.LOCK_TIME_THRESHOLD
        val hasFreshServerDaa = newVirtualDaaScore > 0L
        if (!hasFreshServerPmt &&
            virtualDaaScore > 0L &&
            daaCapturedElapsedRealtimeMs > 0L &&
            (newVirtualDaaScore <= virtualDaaScore || (nowElapsedRealtimeMs - daaCapturedElapsedRealtimeMs) < 900L)
        ) {
            return this
        }
        val mergedPmt = if (hasFreshServerPmt) maxOf(newPastMedianTimeMs, pastMedianTimeMs) else pastMedianTimeMs
        val nextPmtCaptured = if (hasFreshServerPmt) nowElapsedRealtimeMs else capturedElapsedRealtimeMs
        val mergedDaa = if (hasFreshServerDaa) maxOf(newVirtualDaaScore, virtualDaaScore) else virtualDaaScore
        val nextDaaCaptured = if (hasFreshServerDaa) nowElapsedRealtimeMs else daaCapturedElapsedRealtimeMs
        val nextDisplayFloor = maxOf(displayFloorDaa, prevEstDaa, mergedDaa)
        return ChainTimeAnchor(
            pastMedianTimeMs = mergedPmt,
            virtualDaaScore = mergedDaa,
            capturedElapsedRealtimeMs = nextPmtCaptured,
            daaCapturedElapsedRealtimeMs = nextDaaCaptured,
            displayFloorDaa = nextDisplayFloor,
        )
    }

    companion object {
        const val OFFLINE_MEDIAN_LAG_MS: Long = 150_000L
        const val FINALIZING_POLL_WINDOW_MS: Long = 15_000L
        const val FINALIZING_DAA_POLL_WINDOW_MS: Long = 10_000L
    }
}

/**
 * Compiles all 7 curated consumer Kaspa L1 SilverScript covenants into real executable Kaspa Script
 * P2SH redeem scripts + portable `SilAbiArtifact` v1 metadata, and builds verified Transaction v1
 * deploy & spend transactions checked locally by [KaspaScriptVm].
 */
object CovenantCompiler {
    const val DAA_BLOCKS_PER_SECOND: Long = 10L
    const val DAA_PER_MINUTE: Long = 600L
    const val DAA_PER_HOUR: Long = 36_000L
    const val DAA_PER_DAY: Long = 864_000L
    const val DAA_PER_WEEK: Long = 6_048_000L
    const val DAA_PER_MONTH: Long = 25_920_000L
    // Max 10-year safety guardrail on Hodl Time-Lock so users cannot fat-finger a 100-year lock
    const val MAX_HODL_LOCK_DAYS: Int = 3_650
    const val MAX_HODL_LOCK_DAA: Long = MAX_HODL_LOCK_DAYS.toLong() * DAA_PER_DAY
    const val MAX_HODL_LOCK_MS: Long = MAX_HODL_LOCK_DAYS.toLong() * 86_400_000L
    // Kaspa consensus CLTV threshold: values >= 500_000_000_000 are interpreted as Unix epoch milliseconds (tx.time)
    const val LOCK_TIME_THRESHOLD: Long = 500_000_000_000L
    // Toccata v1 default mempool min relay fee is 100 sompi/gram (~250,000-450,000 sompi per covenant tx)
    const val COVENANT_TX_FEE_BUFFER_SOMPI: Long = 350_000L
    // Max miner fee permitted inside keyless anyone-can-trigger paths (prevents fee-burn griefing: 0.01 KAS)
    const val MAX_KEYLESS_FEE_SOMPI: Long = 1_000_000L
    // Versioned P2SH prefix bytes (`00 00 aa 20`) and suffix (`87`) for on-chain OP_CAT self-introspection
    private val VERSIONED_P2SH_PREFIX = byteArrayOf(0x00, 0x00, KaspaAddress.OP_BLAKE2B, 0x20)
    private val VERSIONED_P2SH_SUFFIX = byteArrayOf(KaspaAddress.OP_EQUAL)

    /** Extracts the immutable script suffix (`bytes[9..end]`) of a v3.2 keyless `tx.time` Recurring Allowance script. */
    fun extractSubscriptionScriptSuffix(redeemScript: ByteArray): ByteArray {
        require(redeemScript.size > 9 && redeemScript[0] == 0x08.toByte()) {
            "Not a v3.2 keyless tx.time Recurring Allowance redeemScript"
        }
        return redeemScript.copyOfRange(9, redeemScript.size)
    }

    /** True if this Recurring Allowance uses the v3.2 keyless `tx.time` self-advancing script. */
    fun isKeylessTimeBasedSubscription(params: CovenantParams): Boolean =
        params.nextDueTimeMs >= LOCK_TIME_THRESHOLD && params.intervalMs >= 60_000L

    /** True if this Dead Man's Switch uses the legacy v3.2–v3.7 keyless `tx.time` heirClaim script. */
    fun isKeylessTimeBasedDeadMansSwitch(params: CovenantParams): Boolean =
        params.inactivityDeadlineMs >= LOCK_TIME_THRESHOLD

    /**
     * True if this Dead Man's Switch uses the v3.8 permanent fixed-address `OP_CHECKSEQUENCEVERIFY`
     * keyless `heirClaim()` script (0-KAS claimable; owner check-ins NEVER invalidate heir `.kpass`!).
     */
    fun isKeylessCsvDeadMansSwitch(params: CovenantParams): Boolean =
        params.keylessCsvHeir && params.inactivityDeadlineMs < LOCK_TIME_THRESHOLD && params.inactivityDaa > 0L

    /** True if `heirClaim()` on this Dead Man's Switch is keyless (0-KAS claimable, no heir signature). */
    fun isKeylessDeadMansSwitch(params: CovenantParams): Boolean =
        isKeylessTimeBasedDeadMansSwitch(params) || isKeylessCsvDeadMansSwitch(params)

    /**
     * Exact Kaspa consensus relative CSV finality rule:
     * a UTXO relative lock is finalized ONLY when real server `virtualDaaScore - utxoBlockDaaScore >= requiredDaa`!
     */
    fun isRelativeDaaLockReady(
        utxoBlockDaaScore: Long,
        requiredDaa: Long,
        serverVirtualDaaScore: Long,
    ): Boolean = serverVirtualDaaScore > 0L &&
        utxoBlockDaaScore > 0L &&
        requiredDaa > 0L &&
        (serverVirtualDaaScore - utxoBlockDaaScore) >= requiredDaa

    /**
     * Exact Kaspa consensus finality rule (`rusty-kaspa` `tx.lock_time < block_time`):
     * a Unix-ms `tx.time` lock is finalized ONLY when `livePastMedianTimeMs > unlockTimeMs`!
     */
    fun isTimeLockReady(unlockTimeMs: Long, livePastMedianTimeMs: Long): Boolean =
        unlockTimeMs >= LOCK_TIME_THRESHOLD && livePastMedianTimeMs > unlockTimeMs

    /** Remaining milliseconds until Kaspa consensus `pastMedianTime` finalizes [unlockTimeMs]. */
    fun remainingTimeLockMs(unlockTimeMs: Long, livePastMedianTimeMs: Long): Long =
        if (unlockTimeMs < LOCK_TIME_THRESHOLD) 0L
        else ((unlockTimeMs + 1_000L) - livePastMedianTimeMs).coerceAtLeast(0L)

    /**
     * Single source of truth for owned covenants shown identically in BOTH Home `Covenants` tab
     * and `Covenants Hub -> Yours` (strictly scoped by immutable [walletId] and active [network]).
     */
    fun ownedCovenantsForNetwork(
        covenants: List<TrackedCovenant>,
        network: KaspaNetwork,
        walletId: String? = null,
    ): List<TrackedCovenant> = covenants.filter { cov ->
        cov.network == network &&
            !cov.isSharedPassImport &&
            (walletId.isNullOrBlank() || cov.walletId == walletId)
    }

    /**
     * Single source of truth for imported `.kpass` passes shown in `Covenants Hub -> Shared`
     * (Dead Man's Switch & Recurring Allowance only, scoped by immutable [walletId] and [network]).
     */
    fun sharedCovenantsForNetwork(
        covenants: List<TrackedCovenant>,
        network: KaspaNetwork,
        walletId: String? = null,
    ): List<TrackedCovenant> = covenants.filter { cov ->
        cov.network == network &&
            cov.isSharedPassImport &&
            cov.templateId != CovenantTemplateId.CLAWBACK_VAULT &&
            (walletId.isNullOrBlank() || cov.walletId == walletId)
    }

    /**
     * Pre-VM validation for `Cold-Only Address Whitelist`: returns `true` iff [destinationAddress]
     * decodes cleanly and matches one of the vault's 1–3 pre-approved cold addresses (by SPK bytes).
     */
    fun isDestinationWhitelisted(covenant: TrackedCovenant, destinationAddress: String): Boolean {
        if (covenant.templateId != CovenantTemplateId.COLD_WHITELIST) return true
        val cleanDest = destinationAddress.trim()
        if (cleanDest.isEmpty()) return false
        val destSpk = runCatching { decodeVersionedSpk(cleanDest, covenant.network) }.getOrNull() ?: return false
        val approved = covenant.params.whitelistAddresses
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(covenant.params.ownerAddress) }
            .take(3)
        return approved.any { addr ->
            val spk = runCatching { decodeVersionedSpk(addr, covenant.network) }.getOrNull()
            spk != null && spk.contentEquals(destSpk)
        }
    }

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    /** Generates a fresh 16-byte (32 lowercase hex char) unique per-vault salt. */
    fun generateVaultSaltHex(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return KaspaCrypto.bytesToHex(bytes)
    }

    /** Re-hashes [redeemScriptHex] with unkeyed BLAKE2b-256 and verifies exact Bech32 P2SH match. */
    fun verifyRedeemScriptProof(
        redeemScriptHex: String,
        expectedP2shAddress: String,
        network: KaspaNetwork,
    ): Boolean {
        val bytes = runCatching { KaspaCrypto.hexToBytes(redeemScriptHex.trim()) }.getOrNull() ?: return false
        if (bytes.isEmpty()) return false
        val hash = KaspaCrypto.blake2b256(bytes, domainKey = null)
        val derivedAddr = KaspaAddress.encodeScriptHash(hash, network)
        return derivedAddr.equals(expectedP2shAddress.trim(), ignoreCase = true)
    }

    fun kaspaStreamUrl(txId: String?, p2shAddress: String, network: KaspaNetwork): String {
        val base = if (network.isTestnet) "https://tn10.kaspa.stream" else "https://kaspa.stream"
        val cleanTx = txId?.trim().orEmpty()
        return if (cleanTx.length == 64) "$base/transactions/$cleanTx" else "$base/addresses/${p2shAddress.trim()}"
    }

    fun explorerKaspaOrgUrl(txId: String?, p2shAddress: String, network: KaspaNetwork): String {
        val base = if (network.isTestnet) "https://explorer-tn10.kaspa.org" else "https://explorer.kaspa.org"
        val cleanTx = txId?.trim().orEmpty()
        return if (cleanTx.length == 64) "$base/txs/$cleanTx" else "$base/addresses/${p2shAddress.trim()}"
    }

    fun canonicalKeyFor(
        network: KaspaNetwork,
        templateId: CovenantTemplateId,
        covenantIdHex: String?,
        redeemScriptHashHex: String,
    ): String {
        val cleanCov = covenantIdHex?.trim()?.lowercase().orEmpty()
        return if (templateId.isStatefulKip20 && cleanCov.length == 64) {
            "kip20:${network.id}:$cleanCov"
        } else {
            "p2sh:${network.id}:${templateId.id}:${redeemScriptHashHex.trim().lowercase()}"
        }
    }

    /**
     * Compiles any of the 7 curated consumer covenants into executable Kaspa Script bytecode +
     * portable `SilAbiArtifact` + unique salted P2SH address.
     */
    fun compile(
        templateId: CovenantTemplateId,
        params: CovenantParams,
        network: KaspaNetwork,
    ): CompiledCovenantBundle {
        val ownerPk = decodePubKey32(params.ownerPubKeyHex, "ownerPubKey")
        val entries = LinkedHashMap<String, SilEntryArtifact>()
        val runtimeFields = ArrayList<SilFieldArtifact>()
        val scriptBuilder = KaspaScriptBuilder()
        val silSource: String

        // Unique per-vault salt prefix prevents identical parameters from ever colliding on the same P2SH address!
        // (For v3.2 keyless tx.time Recurring Allowance, the salt lives right after the 9-byte `nextDueTimeMs` header
        // inside the immutable script suffix so byte[0..8] is ALWAYS `0x08 <8-byte LE nextDueTimeMs>`!)
        val isKeylessTimeSub = templateId == CovenantTemplateId.RECURRING_ALLOWANCE && isKeylessTimeBasedSubscription(params)
        val cleanSaltHex = params.vaultSaltHex.trim()
        val saltBytes: ByteArray? = if (cleanSaltHex.isNotEmpty() && cleanSaltHex.length % 2 == 0) {
            runCatching { KaspaCrypto.hexToBytes(cleanSaltHex) }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (!isKeylessTimeSub && saltBytes != null) {
            scriptBuilder.pushData(saltBytes)
            scriptBuilder.op(KaspaOpcodes.OP_DROP)
        }

        // Helper: emits KCC-01 dispatch branch matching `entrySig`:
        // Stack on entry: [<args...>, <4-byte dispatchTag>]
        fun addEntryDef(name: String, sig: String, paramSpecs: List<Pair<String, String>>): ByteArray {
            val tag = SilAbiCodec.computeKcc01DispatchTag(sig)
            entries[name] = SilEntryArtifact(
                dispatchTag = KaspaCrypto.bytesToHex(tag),
                signature = sig,
                params = paramSpecs.map { SilFieldArtifact(it.first, it.second) },
            )
            return tag
        }

        fun emitDispatchChain(branches: List<Pair<ByteArray, KaspaScriptBuilder.() -> Unit>>) {
            branches.forEachIndexed { idx, (tag, body) ->
                if (idx < branches.lastIndex) {
                    scriptBuilder.op(KaspaOpcodes.OP_DUP)
                    scriptBuilder.pushData(tag)
                    scriptBuilder.op(KaspaOpcodes.OP_EQUAL)
                    scriptBuilder.op(KaspaOpcodes.OP_IF)
                    scriptBuilder.op(KaspaOpcodes.OP_DROP)
                    scriptBuilder.body()
                    scriptBuilder.op(KaspaOpcodes.OP_ELSE)
                } else {
                    scriptBuilder.pushData(tag)
                    scriptBuilder.op(KaspaOpcodes.OP_EQUALVERIFY)
                    scriptBuilder.body()
                }
            }
            repeat((branches.size - 1).coerceAtLeast(0)) {
                scriptBuilder.op(KaspaOpcodes.OP_ENDIF)
            }
        }

        // Shared KIP-20 stateful Top-Up branch: output 0 keeps identical SPK & covenant_id and grows balance
        fun KaspaScriptBuilder.emitStatefulTopUp(pk: ByteArray) {
            pushInt(0L)
            op(KaspaOpcodes.OP_TXOUTPUTSPK)
            op(KaspaOpcodes.OP_TXINPUTINDEX)
            op(KaspaOpcodes.OP_TXINPUTSPK)
            op(KaspaOpcodes.OP_EQUALVERIFY)
            op(KaspaOpcodes.OP_TXINPUTINDEX)
            op(KaspaOpcodes.OP_INPUTCOVENANTID)
            pushInt(0L)
            op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
            op(KaspaOpcodes.OP_EQUALVERIFY)
            pushInt(0L)
            op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
            op(KaspaOpcodes.OP_TXINPUTINDEX)
            op(KaspaOpcodes.OP_TXINPUTAMOUNT)
            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
            op(KaspaOpcodes.OP_VERIFY)
            pushData(pk)
            op(KaspaOpcodes.OP_CHECKSIG)
        }

        // Shared stateless P2SH Top-Up branch: output 0 keeps exact same SPK and strictly preserves/grows balance
        fun KaspaScriptBuilder.emitStatelessTopUp(pk: ByteArray) {
            pushInt(0L)
            op(KaspaOpcodes.OP_TXOUTPUTSPK)
            op(KaspaOpcodes.OP_TXINPUTINDEX)
            op(KaspaOpcodes.OP_TXINPUTSPK)
            op(KaspaOpcodes.OP_EQUALVERIFY)
            pushInt(0L)
            op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
            op(KaspaOpcodes.OP_TXINPUTINDEX)
            op(KaspaOpcodes.OP_TXINPUTAMOUNT)
            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
            op(KaspaOpcodes.OP_VERIFY)
            pushData(pk)
            op(KaspaOpcodes.OP_CHECKSIG)
        }

        when (templateId) {
            CovenantTemplateId.CLAWBACK_VAULT -> {
                val alarmPk = decodePubKey32(params.alarmPubKeyHex.ifBlank { params.ownerPubKeyHex }, "alarmPubKey")
                val recoverySpk = decodeVersionedSpk(params.recoveryAddress.ifBlank { params.ownerAddress }, network)
                val delayDaa = params.unvaultDelayDaa.coerceIn(10L, MAX_HODL_LOCK_DAA)
                runtimeFields += SilFieldArtifact("stage", "int")
                val tagUnvault = addEntryDef("initiateUnvault", "initiateUnvault(sig)", listOf("hotSig" to "sig"))
                val tagComplete = addEntryDef("completeUnvault", "completeUnvault(sig)", listOf("hotSig" to "sig"))
                val tagClawback = addEntryDef("alarmClawback", "alarmClawback(sig)", listOf("alarmSig" to "sig"))
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagUnvault to {
                            // Require stage == 0, check hotSig, preserve KIP-20 covenant_id on output 0
                            pushInt(params.vaultStage.toLong())
                            pushInt(0L)
                            op(KaspaOpcodes.OP_NUMEQUALVERIFY)
                            op(KaspaOpcodes.OP_TXINPUTINDEX)
                            op(KaspaOpcodes.OP_INPUTCOVENANTID)
                            pushInt(0L)
                            op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                            op(KaspaOpcodes.OP_EQUALVERIFY)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagComplete to {
                            // Require stage == 1, CSV delay (Kaspa CSV pops arg — NO OP_DROP!), check hotSig
                            pushInt(params.vaultStage.toLong())
                            pushInt(1L)
                            op(KaspaOpcodes.OP_NUMEQUALVERIFY)
                            pushInt(delayDaa)
                            op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                            if (params.pendingUnvaultDestAddress.isNotBlank()) {
                                val destSpk = decodeVersionedSpk(params.pendingUnvaultDestAddress, network)
                                pushInt(0L)
                                op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                pushData(destSpk)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                            }
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagClawback to {
                            // Instant Alarm clawback exclusively to recoveryAddress
                            pushInt(0L)
                            op(KaspaOpcodes.OP_TXOUTPUTSPK)
                            pushData(recoverySpk)
                            op(KaspaOpcodes.OP_EQUALVERIFY)
                            pushData(alarmPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagTopUp to { emitStatefulTopUp(ownerPk) },
                    ),
                )
                silSource = """
                    pragma silverscript ^0.1.0;

                    contract ClawbackVault(
                        pubkey hotKey,
                        pubkey alarmKey,
                        byte[] recoverySpk,
                        int unvaultDelayDaa,
                        int stage
                    ) {
                        entry initiateUnvault(sig hotSig) {
                            require(stage == 0);
                            require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                            require(checkSig(hotSig, hotKey));
                        }
                        entry completeUnvault(sig hotSig) {
                            require(stage == 1);
                            require(this.ageDaa >= unvaultDelayDaa);
                            require(checkSig(hotSig, hotKey));
                        }
                        entry alarmClawback(sig alarmSig) {
                            require(tx.outputs[0].scriptPubKey == recoverySpk);
                            require(checkSig(alarmSig, alarmKey));
                        }
                        entry topUp(sig ownerSig) {
                            require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                            require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                            require(checkSig(ownerSig, hotKey));
                        }
                    }
                """.trimIndent()
            }

            CovenantTemplateId.DEAD_MANS_SWITCH -> {
                val heirSpk = decodeVersionedSpk(params.heirAddress.ifBlank { params.ownerAddress }, network)
                val heirPk = resolveAddressPubKeyOrFallback(params.heirAddress, params.heirPubKeyHex, ownerPk)
                val useTimeMode = isKeylessTimeBasedDeadMansSwitch(params)
                val useKeylessCsv = isKeylessCsvDeadMansSwitch(params)
                val deadlineMs = params.inactivityDeadlineMs
                val timeoutDaa = params.inactivityDaa.coerceIn(10L, MAX_HODL_LOCK_DAA)
                val tagCheckIn = addEntryDef("checkIn", "checkIn(sig)", listOf("ownerSig" to "sig"))
                val tagReclaim = addEntryDef("ownerReclaim", "ownerReclaim(sig)", listOf("ownerSig" to "sig"))
                val tagClaim = if (useTimeMode || useKeylessCsv) {
                    // KEYLESS anyone-can-trigger heirClaim once lock expires (0-KAS claimable)!
                    addEntryDef("heirClaim", "heirClaim()", emptyList())
                } else {
                    addEntryDef("heirClaim", "heirClaim(sig)", listOf("heirSig" to "sig"))
                }
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagCheckIn to {
                            if (useTimeMode) {
                                // Legacy v3.2-v3.7: owner advances inactivityDeadlineMs into successor P2SH preserving covenant_id
                                op(KaspaOpcodes.OP_TXINPUTINDEX)
                                op(KaspaOpcodes.OP_INPUTCOVENANTID)
                                pushInt(0L)
                                op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                pushData(ownerPk)
                                op(KaspaOpcodes.OP_CHECKSIG)
                            } else {
                                // v3.8 Permanent Fixed-Address CSV checkIn:
                                // Self-spend preserving EXACT SAME P2SH scriptPubKey + KIP-20 covenant_id!
                                // Resets UTXO blockDaaScore without ever changing the P2SH address or invalidating heir .kpass!
                                // Allowed anytime (even after inactivity timer reaches 0, as long as heir hasn't claimed yet).
                                pushInt(0L)
                                op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                op(KaspaOpcodes.OP_TXINPUTINDEX)
                                op(KaspaOpcodes.OP_TXINPUTSPK)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                op(KaspaOpcodes.OP_TXINPUTINDEX)
                                op(KaspaOpcodes.OP_INPUTCOVENANTID)
                                pushInt(0L)
                                op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                pushData(ownerPk)
                                op(KaspaOpcodes.OP_CHECKSIG)
                            }
                        },
                        tagReclaim to {
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagClaim to {
                            when {
                                useTimeMode -> {
                                    // Legacy v3.2-v3.7 KEYLESS tx.time CLTV heir claim (0-KAS claimable)
                                    pushInt(deadlineMs)
                                    op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                                    pushInt(0L)
                                    op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                    pushData(heirSpk)
                                    op(KaspaOpcodes.OP_EQUALVERIFY)
                                    // Anti-griefing bound: output 0 >= inputAmount - MAX_KEYLESS_FEE_SOMPI
                                    pushInt(0L)
                                    op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                                    op(KaspaOpcodes.OP_TXINPUTINDEX)
                                    op(KaspaOpcodes.OP_TXINPUTAMOUNT)
                                    pushInt(MAX_KEYLESS_FEE_SOMPI)
                                    op(KaspaOpcodes.OP_SUB)
                                    op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                                    op(KaspaOpcodes.OP_VERIFY)
                                    op(KaspaOpcodes.OP_TRUE)
                                }
                                useKeylessCsv -> {
                                    // v3.8 Permanent Fixed-Address KEYLESS OP_CHECKSEQUENCEVERIFY heir claim!
                                    // Kaspa CSV pops its argument (NEVER emit OP_DROP!), verifies this.age >= timeoutDaa,
                                    // locks output[0] strictly to heirSpk with bounded miner fee (0-KAS claimable!).
                                    pushInt(timeoutDaa)
                                    op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                                    pushInt(0L)
                                    op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                    pushData(heirSpk)
                                    op(KaspaOpcodes.OP_EQUALVERIFY)
                                    pushInt(0L)
                                    op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                                    op(KaspaOpcodes.OP_TXINPUTINDEX)
                                    op(KaspaOpcodes.OP_TXINPUTAMOUNT)
                                    pushInt(MAX_KEYLESS_FEE_SOMPI)
                                    op(KaspaOpcodes.OP_SUB)
                                    op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                                    op(KaspaOpcodes.OP_VERIFY)
                                    op(KaspaOpcodes.OP_TRUE)
                                }
                                else -> {
                                    // Legacy v3.1 signed CSV heirClaim (Kaspa CSV pops argument — NO OP_DROP!)
                                    pushInt(timeoutDaa)
                                    op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                                    pushInt(0L)
                                    op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                    pushData(heirSpk)
                                    op(KaspaOpcodes.OP_EQUALVERIFY)
                                    pushData(heirPk)
                                    op(KaspaOpcodes.OP_CHECKSIG)
                                }
                            }
                        },
                        tagTopUp to { emitStatefulTopUp(ownerPk) },
                    ),
                )
                silSource = when {
                    useTimeMode -> """
                        pragma silverscript ^0.1.0;

                        contract DeadMansSwitch(pubkey ownerKey, byte[] heirSpk, int inactivityDeadlineMs) {
                            entry checkIn(sig ownerSig) {
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry ownerReclaim(sig ownerSig) {
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry heirClaim() {
                                require(tx.time >= inactivityDeadlineMs);
                                require(tx.outputs[0].scriptPubKey == heirSpk);
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value - $MAX_KEYLESS_FEE_SOMPI);
                            }
                            entry topUp(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                                require(checkSig(ownerSig, ownerKey));
                            }
                        }
                    """.trimIndent()
                    useKeylessCsv -> """
                        pragma silverscript ^0.1.0;

                        contract DeadMansSwitch(pubkey ownerKey, byte[] heirSpk, int inactivityDaa) {
                            entry checkIn(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry ownerReclaim(sig ownerSig) {
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry heirClaim() {
                                require(this.age >= inactivityDaa);
                                require(tx.outputs[0].scriptPubKey == heirSpk);
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value - $MAX_KEYLESS_FEE_SOMPI);
                            }
                            entry topUp(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                                require(checkSig(ownerSig, ownerKey));
                            }
                        }
                    """.trimIndent()
                    else -> """
                        pragma silverscript ^0.1.0;

                        contract DeadMansSwitch(pubkey ownerKey, pubkey heirKey, byte[] heirSpk, int inactivityDaa) {
                            entry checkIn(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry ownerReclaim(sig ownerSig) {
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry heirClaim(sig heirSig) {
                                require(this.ageDaa >= inactivityDaa);
                                require(tx.outputs[0].scriptPubKey == heirSpk);
                                require(checkSig(heirSig, heirKey));
                            }
                            entry topUp(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                                require(checkSig(ownerSig, ownerKey));
                            }
                        }
                    """.trimIndent()
                }
            }

            CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                val cap = params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI)
                val remaining = (cap - params.spentInWindowSompi).coerceAtLeast(0L)
                val windowDaa = params.windowDaa.coerceAtLeast(10L)
                val bypassUnlockTimeMs = params.fullWithdrawUnlockTimeMs
                val bypassUnlockDaa = params.fullWithdrawUnlockDaa.coerceAtLeast(0L)
                runtimeFields += SilFieldArtifact("spent_in_window", "int")
                runtimeFields += SilFieldArtifact("window_start_daa", "int")
                val tagSpend = addEntryDef("spendWithinLimit", "spendWithinLimit(sig)", listOf("ownerSig" to "sig"))
                val tagResetSpend = addEntryDef("spendAfterReset", "spendAfterReset(sig)", listOf("ownerSig" to "sig"))
                val tagReclaim = addEntryDef("ownerReclaim", "ownerReclaim(sig)", listOf("ownerSig" to "sig"))
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagSpend to {
                            // Immediate Day-1 spend within remaining cap; rolls remainder back into covenant_id
                            pushInt(0L)
                            op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                            pushInt(remaining.coerceAtLeast(1L))
                            op(KaspaOpcodes.OP_LESSTHANOREQUAL)
                            op(KaspaOpcodes.OP_VERIFY)
                            op(KaspaOpcodes.OP_TXOUTPUTCOUNT)
                            pushInt(2L)
                            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                            op(KaspaOpcodes.OP_IF)
                            op(KaspaOpcodes.OP_TXINPUTINDEX)
                            op(KaspaOpcodes.OP_INPUTCOVENANTID)
                            pushInt(1L)
                            op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                            op(KaspaOpcodes.OP_EQUALVERIFY)
                            op(KaspaOpcodes.OP_ENDIF)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagResetSpend to {
                            // Once windowDaa has elapsed since the previous spend, full dailyCap refreshes cleanly
                            pushInt(windowDaa)
                            op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                            pushInt(0L)
                            op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                            pushInt(cap)
                            op(KaspaOpcodes.OP_LESSTHANOREQUAL)
                            op(KaspaOpcodes.OP_VERIFY)
                            op(KaspaOpcodes.OP_TXOUTPUTCOUNT)
                            pushInt(2L)
                            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                            op(KaspaOpcodes.OP_IF)
                            op(KaspaOpcodes.OP_TXINPUTINDEX)
                            op(KaspaOpcodes.OP_INPUTCOVENANTID)
                            pushInt(1L)
                            op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                            op(KaspaOpcodes.OP_EQUALVERIFY)
                            op(KaspaOpcodes.OP_ENDIF)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagReclaim to {
                            // Settle & Withdraw All (bypass spending limit once fullWithdrawUnlockTimeMs / DAA is reached!)
                            when {
                                bypassUnlockTimeMs >= LOCK_TIME_THRESHOLD -> {
                                    pushInt(bypassUnlockTimeMs)
                                    op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                                }
                                bypassUnlockDaa > 0L -> {
                                    pushInt(bypassUnlockDaa)
                                    op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                                }
                                else -> {
                                    pushInt(windowDaa)
                                    op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                                }
                            }
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagTopUp to { emitStatefulTopUp(ownerPk) },
                    ),
                )
                val reclaimLine = when {
                    bypassUnlockTimeMs >= LOCK_TIME_THRESHOLD -> "require(tx.time >= fullWithdrawUnlockTimeMs);"
                    bypassUnlockDaa > 0L -> "require(tx.daa >= fullWithdrawUnlockDaa);"
                    else -> "require(this.ageDaa >= windowDaa);"
                }
                silSource = """
                    pragma silverscript ^0.1.0;

                    contract DailySpendLimit(
                        pubkey ownerKey,
                        int dailyCap,
                        int windowDaa,
                        int spentInWindow,
                        int fullWithdrawUnlockDaa
                    ) {
                        entry spendWithinLimit(sig ownerSig) {
                            require(tx.outputs[0].value <= dailyCap - spentInWindow);
                            if (tx.outputs.length >= 2) {
                                require(OpOutputCovenantId(1) == OpInputCovenantId(this.activeInputIndex));
                            }
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry spendAfterReset(sig ownerSig) {
                            require(this.ageDaa >= windowDaa);
                            require(tx.outputs[0].value <= dailyCap);
                            if (tx.outputs.length >= 2) {
                                require(OpOutputCovenantId(1) == OpInputCovenantId(this.activeInputIndex));
                            }
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry ownerReclaim(sig ownerSig) {
                            $reclaimLine
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry topUp(sig ownerSig) {
                            require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                            require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                            require(checkSig(ownerSig, ownerKey));
                        }
                    }
                """.trimIndent()
            }

            CovenantTemplateId.COLD_WHITELIST -> {
                val addrs = params.whitelistAddresses
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(params.ownerAddress) }
                    .take(3)
                val spks = addrs.map { decodeVersionedSpk(it, network) }
                val tagSpend = addEntryDef("spend", "spend(sig)", listOf("ownerSig" to "sig"))
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagSpend to {
                            // tx.outputs[0].scriptPubKey must match one of the 1..3 hardcoded cold SPKs;
                            // if partial spend leaves change in output 1, output 1 MUST stay in the same whitelist P2SH!
                            pushInt(0L)
                            op(KaspaOpcodes.OP_TXOUTPUTSPK)
                            spks.forEachIndexed { idx, spk ->
                                if (idx == 0) {
                                    if (spks.size > 1) op(KaspaOpcodes.OP_DUP)
                                    pushData(spk)
                                    op(KaspaOpcodes.OP_EQUAL)
                                } else {
                                    op(KaspaOpcodes.OP_SWAP)
                                    if (idx < spks.lastIndex) op(KaspaOpcodes.OP_DUP)
                                    pushData(spk)
                                    op(KaspaOpcodes.OP_EQUAL)
                                    if (idx < spks.lastIndex) {
                                        op(KaspaOpcodes.OP_ROT)
                                        op(KaspaOpcodes.OP_BOOLOR)
                                    } else {
                                        op(KaspaOpcodes.OP_BOOLOR)
                                    }
                                }
                            }
                            op(KaspaOpcodes.OP_VERIFY)
                            op(KaspaOpcodes.OP_TXOUTPUTCOUNT)
                            pushInt(2L)
                            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                            op(KaspaOpcodes.OP_IF)
                            pushInt(1L)
                            op(KaspaOpcodes.OP_TXOUTPUTSPK)
                            op(KaspaOpcodes.OP_TXINPUTINDEX)
                            op(KaspaOpcodes.OP_TXINPUTSPK)
                            op(KaspaOpcodes.OP_EQUALVERIFY)
                            op(KaspaOpcodes.OP_ENDIF)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagTopUp to { emitStatelessTopUp(ownerPk) },
                    ),
                )
                silSource = """
                    pragma silverscript ^0.1.0;

                    contract ColdWhitelist(pubkey ownerKey, byte[][] whitelistedColdSpks) {
                        entry spend(sig ownerSig) {
                            byte[] outSpk = tx.outputs[0].scriptPubKey;
                            require(matchesAnyWhitelist(outSpk, whitelistedColdSpks));
                            if (tx.outputs.length >= 2) {
                                require(tx.outputs[1].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            }
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry topUp(sig ownerSig) {
                            require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                            require(checkSig(ownerSig, ownerKey));
                        }
                    }
                """.trimIndent()
            }

            CovenantTemplateId.HODL_TIMELOCK -> {
                val useTimeLock = params.unlockTimeMs >= LOCK_TIME_THRESHOLD
                val lockVal = if (useTimeLock) params.unlockTimeMs else params.unlockDaa.coerceAtLeast(1L)
                val tagUnlock = addEntryDef("unlock", "unlock(sig)", listOf("ownerSig" to "sig"))
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagUnlock to {
                            // Kaspa CLTV pops argument (NO OP_DROP!)
                            pushInt(lockVal)
                            op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagTopUp to {
                            // Allows adding more KAS into the exact same time-locked P2SH without ever breaking the lock
                            emitStatelessTopUp(ownerPk)
                        },
                    ),
                )
                val lockCond = if (useTimeLock) "require(tx.time >= unlockTimeMs);" else "require(tx.daa >= unlockDaa);"
                val lockParam = if (useTimeLock) "int unlockTimeMs" else "int unlockDaa"
                silSource = """
                    pragma silverscript ^0.1.0;

                    contract HodlTimelock(pubkey ownerKey, $lockParam) {
                        entry unlock(sig ownerSig) {
                            $lockCond
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry topUp(sig ownerSig) {
                            require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                            require(checkSig(ownerSig, ownerKey));
                        }
                    }
                """.trimIndent()
            }

            CovenantTemplateId.GOAL_PIGGY_BANK -> {
                val goal = params.targetGoalSompi.coerceAtLeast(DUST_LIMIT_SOMPI)
                val useTimeFallback = params.fallbackUnlockTimeMs >= LOCK_TIME_THRESHOLD
                val fallbackVal = if (useTimeFallback) params.fallbackUnlockTimeMs else params.fallbackUnlockDaa.coerceAtLeast(1L)
                val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))
                val tagGoal = addEntryDef("unlockGoal", "unlockGoal(sig)", listOf("ownerSig" to "sig"))
                val tagFallback = addEntryDef("unlockFallback", "unlockFallback(sig)", listOf("ownerSig" to "sig"))

                emitDispatchChain(
                    listOf(
                        tagTopUp to { emitStatefulTopUp(ownerPk) },
                        tagGoal to {
                            // Unlock once accumulated vault UTXO balance >= targetGoalSompi
                            op(KaspaOpcodes.OP_TXINPUTINDEX)
                            op(KaspaOpcodes.OP_TXINPUTAMOUNT)
                            pushInt(goal)
                            op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                            op(KaspaOpcodes.OP_VERIFY)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                        tagFallback to {
                            // Explicit fallback unlock so funds can never be permanently stuck
                            pushInt(fallbackVal)
                            op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                            pushData(ownerPk)
                            op(KaspaOpcodes.OP_CHECKSIG)
                        },
                    ),
                )
                val fbCond = if (useTimeFallback) "require(tx.time >= fallbackUnlockTimeMs);" else "require(tx.daa >= fallbackUnlockDaa);"
                val fbParam = if (useTimeFallback) "int fallbackUnlockTimeMs" else "int fallbackUnlockDaa"
                silSource = """
                    pragma silverscript ^0.1.0;

                    contract GoalPiggyBank(pubkey ownerKey, int targetGoalSompi, $fbParam) {
                        entry topUp(sig ownerSig) {
                            require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                            require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                            require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry unlockGoal(sig ownerSig) {
                            require(tx.inputs[this.activeInputIndex].value >= targetGoalSompi);
                            require(checkSig(ownerSig, ownerKey));
                        }
                        entry unlockFallback(sig ownerSig) {
                            $fbCond
                            require(checkSig(ownerSig, ownerKey));
                        }
                    }
                """.trimIndent()
            }

            CovenantTemplateId.RECURRING_ALLOWANCE -> {
                val recSpk = decodeVersionedSpk(params.recipientAddress.ifBlank { params.ownerAddress }, network)
                val recPk = resolveAddressPubKeyOrFallback(params.recipientAddress, params.recipientPubKeyHex, ownerPk)
                val allowance = params.allowanceSompi.coerceAtLeast(DUST_LIMIT_SOMPI)

                if (isKeylessTimeSub) {
                    // KEYLESS tx.time Self-Advancing Recurring Allowance / Subscription!
                    // Layout: [0x08][8-byte LE nextDueTimeMs][immutableSuffix...]
                    val nextDue8 = KaspaScriptBuilder.serializeScriptInt(params.nextDueTimeMs, fixedLen = 8)
                    val intervalMs = params.intervalMs.coerceAtLeast(60_000L)
                    runtimeFields += SilFieldArtifact("next_due_time_ms", "int")

                    val tagWithdraw = addEntryDef("withdraw", "withdraw(byte[])", listOf("scriptSuffix" to "byte[]"))
                    val tagCancel = addEntryDef("cancelReclaim", "cancelReclaim(sig)", listOf("ownerSig" to "sig"))
                    val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                    // Header: bytes 0..8 = OP_DATA_8 <8-byte LE nextDueTimeMs>
                    scriptBuilder.pushRawBytes(nextDue8)
                    // Start of immutableSuffix (bytes 9..end): move dispatchTag back above due8
                    scriptBuilder.op(KaspaOpcodes.OP_SWAP)
                    if (saltBytes != null) {
                        scriptBuilder.pushData(saltBytes)
                        scriptBuilder.op(KaspaOpcodes.OP_DROP)
                    }

                    val branches = ArrayList<Pair<ByteArray, KaspaScriptBuilder.() -> Unit>>()
                    branches += tagWithdraw to {
                        // Stack here: [scriptSuffix, due8]
                        // 1. Enforce tx.time >= nextDueTimeMs (Kaspa CLTV pops top copy)
                        op(KaspaOpcodes.OP_DUP)
                        op(KaspaOpcodes.OP_CHECKLOCKTIMEVERIFY)
                        // 2. Enforce output 0 strictly pays recipientSpk & <= allowance
                        pushInt(0L)
                        op(KaspaOpcodes.OP_TXOUTPUTSPK)
                        pushData(recSpk)
                        op(KaspaOpcodes.OP_EQUALVERIFY)
                        pushInt(0L)
                        op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                        pushInt(allowance)
                        op(KaspaOpcodes.OP_LESSTHANOREQUAL)
                        op(KaspaOpcodes.OP_VERIFY)

                        // 3. Branch on whether pool loops into next cycle (outputs.length >= 2) or final drains
                        op(KaspaOpcodes.OP_TXOUTPUTCOUNT)
                        pushInt(2L)
                        op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                        op(KaspaOpcodes.OP_IF)
                        // 3a. Anti-griefing bounds on payout & looped pool remainder
                        val minLoopPayout = (allowance - MAX_KEYLESS_FEE_SOMPI).coerceAtLeast(DUST_LIMIT_SOMPI)
                        pushInt(0L)
                        op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                        pushInt(minLoopPayout)
                        op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                        op(KaspaOpcodes.OP_VERIFY)

                        pushInt(1L)
                        op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                        op(KaspaOpcodes.OP_TXINPUTINDEX)
                        op(KaspaOpcodes.OP_TXINPUTAMOUNT)
                        pushInt(allowance + MAX_KEYLESS_FEE_SOMPI)
                        op(KaspaOpcodes.OP_SUB)
                        op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                        op(KaspaOpcodes.OP_VERIFY)

                        // 3b. Prove scriptSuffix matches THIS input's exact P2SH scriptPubKey
                        // Stack: [S, D] -> [S, D, S, D]
                        op(KaspaOpcodes.OP_2DUP)
                        pushInt(8L) // minimal push for 1-byte [0x08]
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT) // 0x08 || D
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT) // 0x08 || D || S
                        op(KaspaOpcodes.OP_BLAKE2B)
                        pushData(VERSIONED_P2SH_PREFIX)
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT)
                        pushData(VERSIONED_P2SH_SUFFIX)
                        op(KaspaOpcodes.OP_CAT)
                        op(KaspaOpcodes.OP_TXINPUTINDEX)
                        op(KaspaOpcodes.OP_TXINPUTSPK)
                        op(KaspaOpcodes.OP_EQUALVERIFY)

                        // 3c. Advance nextDueTimeMs += intervalMs via OP_NUM2BIN(8) and bind output 1 SPK + covenant_id
                        // Stack: [S, D]
                        op(KaspaOpcodes.OP_BIN2NUM)
                        pushInt(intervalMs)
                        op(KaspaOpcodes.OP_ADD)
                        pushInt(8L)
                        op(KaspaOpcodes.OP_NUM2BIN) // [S, nextDue8]
                        pushInt(8L) // [S, nextDue8, 0x08]
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT) // [S, 0x08 || nextDue8]
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT) // [0x08 || nextDue8 || S]
                        op(KaspaOpcodes.OP_BLAKE2B)
                        pushData(VERSIONED_P2SH_PREFIX)
                        op(KaspaOpcodes.OP_SWAP)
                        op(KaspaOpcodes.OP_CAT)
                        pushData(VERSIONED_P2SH_SUFFIX)
                        op(KaspaOpcodes.OP_CAT)
                        pushInt(1L)
                        op(KaspaOpcodes.OP_TXOUTPUTSPK)
                        op(KaspaOpcodes.OP_EQUALVERIFY)

                        op(KaspaOpcodes.OP_TXINPUTINDEX)
                        op(KaspaOpcodes.OP_INPUTCOVENANTID)
                        pushInt(1L)
                        op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                        op(KaspaOpcodes.OP_EQUALVERIFY)
                        op(KaspaOpcodes.OP_TRUE)
                        op(KaspaOpcodes.OP_ELSE)
                        // Final drain (outputs.length == 1): drop [S, D], ensure full remaining balance minus fee goes to recipient
                        op(KaspaOpcodes.OP_2DROP)
                        pushInt(0L)
                        op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                        op(KaspaOpcodes.OP_TXINPUTINDEX)
                        op(KaspaOpcodes.OP_TXINPUTAMOUNT)
                        pushInt(MAX_KEYLESS_FEE_SOMPI)
                        op(KaspaOpcodes.OP_SUB)
                        op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                        op(KaspaOpcodes.OP_VERIFY)
                        op(KaspaOpcodes.OP_TRUE)
                        op(KaspaOpcodes.OP_ENDIF)
                    }
                    branches += tagCancel to {
                        op(KaspaOpcodes.OP_DROP) // drop due8
                        pushData(ownerPk)
                        op(KaspaOpcodes.OP_CHECKSIG)
                    }
                    branches += tagTopUp to {
                        op(KaspaOpcodes.OP_DROP) // drop due8
                        emitStatefulTopUp(ownerPk)
                    }
                    emitDispatchChain(branches)

                    silSource = """
                        pragma silverscript ^0.1.0;

                        contract RecurringAllowance(
                            pubkey ownerKey,
                            byte[] recipientSpk,
                            int allowance,
                            int nextDueTimeMs,
                            int intervalMs
                        ) {
                            entry withdraw(byte[] scriptSuffix) {
                                require(tx.time >= nextDueTimeMs);
                                require(tx.outputs[0].scriptPubKey == recipientSpk);
                                require(tx.outputs[0].value <= allowance);
                                if (tx.outputs.length >= 2) {
                                    byte[] nextScript = 0x08 + num2bin(nextDueTimeMs + intervalMs, 8) + scriptSuffix;
                                    require(tx.outputs[1].scriptPubKey == p2sh(blake2b(nextScript)));
                                    require(OpOutputCovenantId(1) == OpInputCovenantId(this.activeInputIndex));
                                }
                            }
                            entry cancelReclaim(sig ownerSig) {
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry topUp(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                                require(checkSig(ownerSig, ownerKey));
                            }
                        }
                    """.trimIndent()
                } else {
                    // Legacy v3.1 CSV Recurring Allowance (preserved for existing deployed vaults)
                    val period = params.periodDaa.coerceIn(10L, MAX_HODL_LOCK_DAA)
                    val tagWithdraw = addEntryDef("withdraw", "withdraw(sig)", listOf("recipientSig" to "sig"))
                    val tagCancel = addEntryDef("cancelReclaim", "cancelReclaim(sig)", listOf("ownerSig" to "sig"))
                    val tagTopUp = addEntryDef("topUp", "topUp(sig)", listOf("ownerSig" to "sig"))

                    emitDispatchChain(
                        listOf(
                            tagWithdraw to {
                                pushInt(period)
                                op(KaspaOpcodes.OP_CHECKSEQUENCEVERIFY)
                                pushInt(0L)
                                op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                pushData(recSpk)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                pushInt(0L)
                                op(KaspaOpcodes.OP_TXOUTPUTAMOUNT)
                                pushInt(allowance)
                                op(KaspaOpcodes.OP_LESSTHANOREQUAL)
                                op(KaspaOpcodes.OP_VERIFY)
                                op(KaspaOpcodes.OP_TXOUTPUTCOUNT)
                                pushInt(2L)
                                op(KaspaOpcodes.OP_GREATERTHANOREQUAL)
                                op(KaspaOpcodes.OP_IF)
                                pushInt(1L)
                                op(KaspaOpcodes.OP_TXOUTPUTSPK)
                                op(KaspaOpcodes.OP_TXINPUTINDEX)
                                op(KaspaOpcodes.OP_TXINPUTSPK)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                op(KaspaOpcodes.OP_TXINPUTINDEX)
                                op(KaspaOpcodes.OP_INPUTCOVENANTID)
                                pushInt(1L)
                                op(KaspaOpcodes.OP_OUTPUTCOVENANTID)
                                op(KaspaOpcodes.OP_EQUALVERIFY)
                                op(KaspaOpcodes.OP_ENDIF)
                                pushData(recPk)
                                op(KaspaOpcodes.OP_CHECKSIG)
                            },
                            tagCancel to {
                                pushData(ownerPk)
                                op(KaspaOpcodes.OP_CHECKSIG)
                            },
                            tagTopUp to { emitStatefulTopUp(ownerPk) },
                        ),
                    )
                    silSource = """
                        pragma silverscript ^0.1.0;

                        contract RecurringAllowance(pubkey ownerKey, pubkey recipientKey, byte[] recipientSpk, int allowance, int periodDaa) {
                            entry withdraw(sig recipientSig) {
                                require(this.ageDaa >= periodDaa);
                                require(tx.outputs[0].scriptPubKey == recipientSpk);
                                require(tx.outputs[0].value <= allowance);
                                if (tx.outputs.length >= 2) {
                                    require(tx.outputs[1].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                    require(OpOutputCovenantId(1) == OpInputCovenantId(this.activeInputIndex));
                                }
                                require(checkSig(recipientSig, recipientKey));
                            }
                            entry cancelReclaim(sig ownerSig) {
                                require(checkSig(ownerSig, ownerKey));
                            }
                            entry topUp(sig ownerSig) {
                                require(tx.outputs[0].scriptPubKey == tx.inputs[this.activeInputIndex].scriptPubKey);
                                require(OpOutputCovenantId(0) == OpInputCovenantId(this.activeInputIndex));
                                require(tx.outputs[0].value >= tx.inputs[this.activeInputIndex].value);
                                require(checkSig(ownerSig, ownerKey));
                            }
                        }
                    """.trimIndent()
                }
            }
        }

        val redeemScript = scriptBuilder.toByteArray()
        val redeemHash = KaspaCrypto.blake2b256(redeemScript, domainKey = null)
        val redeemHashHex = KaspaCrypto.bytesToHex(redeemHash)
        val p2shSpk = KaspaAddress.p2shScriptPublicKey(redeemScript)
        val p2shAddr = KaspaAddress.encodeScriptHash(redeemHash, network)
        val templateHash = SilAbiCodec.computeTemplateHash(redeemScript, ByteArray(0))

        val abi = SilAbiArtifact(
            schemaVersion = SIL_ABI_SCHEMA_VERSION,
            compilerVersion = SIL_COMPILER_VERSION,
            contracts = mapOf(
                templateId.contractName to SilContractArtifact(
                    sourcePath = "${templateId.id}.sil",
                    runtimeState = SilRuntimeStateArtifact(
                        source = templateId.contractName,
                        fields = runtimeFields,
                    ),
                    entries = entries,
                    compiled = SilCompiledContractArtifact(
                        bytecodeHex = KaspaCrypto.bytesToHex(redeemScript),
                        templateHashHex = KaspaCrypto.bytesToHex(templateHash),
                        stateSpan = SilStateSpanArtifact(offset = 0, len = 0),
                    ),
                ),
            ),
        )

        return CompiledCovenantBundle(
            templateId = templateId,
            silSource = silSource,
            redeemScript = redeemScript,
            redeemScriptHashHex = redeemHashHex,
            p2shScriptPubKey = p2shSpk,
            p2shAddress = p2shAddr,
            abiArtifact = abi,
        )
    }

    /**
     * Builds & signs a Transaction v1 funding/deploying a covenant P2SH output with:
     * - Full exact [amountSompi] preserved inside the covenant vault (miner fee paid strictly from funding UTXOs!)
     * - Optional KIP-20 `covenant_id` binding on output 0 (for stateful covenants)
     * - Optional seed-encrypted `KKCV1` on-chain recovery payload
     * - Toccata 100 sompi/gram minimum fee & KIP-9 storage mass check (no dust change)
     */
    fun buildAndSignDeployTxV1(
        bundle: CompiledCovenantBundle,
        amountSompi: Long,
        fundingUtxos: List<UtxoItem>,
        changeAddress: String,
        network: KaspaNetwork,
        feeRateSompiPerGram: Long = KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM,
        kkcv1Payload: ByteArray = ByteArray(0),
        keyLookup: (String) -> ByteArray?,
    ): PreparedCovenantTx {
        require(amountSompi >= DUST_LIMIT_SOMPI) { "Covenant deposit must be above dust limit" }
        val spendable = fundingUtxos.filter { !it.isCoinbase }.sortedByDescending { it.amountSompi }
        require(spendable.isNotEmpty()) { "No spendable UTXOs available to fund covenant" }

        val decodedChange = KaspaAddress.decode(changeAddress)
            ?: throw IllegalArgumentException("Invalid change address")
        val changeSpk = decodedChange.toScriptPublicKey()

        val minRate = feeRateSompiPerGram.coerceAtLeast(KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM)
        val selected = ArrayList<UtxoItem>()
        var totalIn = 0L
        var cursor = 0
        var fee = COVENANT_TX_FEE_BUFFER_SOMPI

        fun pullUtxosUntil(target: Long) {
            while (totalIn < target && cursor < spendable.size) {
                val u = spendable[cursor++]
                selected.add(u)
                totalIn += u.amountSompi
            }
        }
        pullUtxosUntil(amountSompi + fee)
        require(selected.isNotEmpty() && totalIn >= amountSompi + 1_000L) {
            "Insufficient KAS balance to fund covenant"
        }

        // Genesis outpoint is ALWAYS selected.first() (highest-value spendable UTXO, matching WalletViewModel)
        val genesisUtxo = selected.first()
        val covOutputNoBinding = KaspaMassAndTx.TxOutput(
            valueSompi = amountSompi,
            scriptPublicKeyVersion = 0,
            scriptPublicKey = bundle.p2shScriptPubKey,
            address = bundle.p2shAddress,
            covenant = null,
        )
        val covenantIdHex = if (bundle.templateId.isStatefulKip20) {
            KaspaMassAndTx.computeCovenantId(
                genesisTxIdHex = genesisUtxo.transactionId,
                genesisOutputIndex = genesisUtxo.index,
                authOutputs = listOf(0 to covOutputNoBinding),
            )
        } else {
            null
        }
        val covOutput = if (covenantIdHex != null) {
            covOutputNoBinding.copy(
                covenant = KaspaMassAndTx.CovenantBinding(authorizingInput = 0, covenantIdHex = covenantIdHex),
            )
        } else {
            covOutputNoBinding
        }

        fun makeRaw(change: Long): KaspaMassAndTx.RawTx {
            val outs = ArrayList<KaspaMassAndTx.TxOutput>(2).apply {
                add(covOutput)
                if (change >= DUST_LIMIT_SOMPI) {
                    add(KaspaMassAndTx.TxOutput(change, 0, changeSpk, changeAddress))
                }
            }
            val ins = selected.map { u ->
                KaspaMassAndTx.TxInput(
                    outpointTxId = u.transactionId,
                    outpointIndex = u.index,
                    signatureScript = ByteArray(KaspaMassAndTx.SCHNORR_SIGNATURE_SCRIPT_SIZE),
                    sequence = 0L,
                    sigOpCount = 0,
                    utxo = u,
                    computeBudget = KaspaMassAndTx.DEFAULT_V1_P2PK_COMPUTE_BUDGET,
                )
            }
            return KaspaMassAndTx.RawTx(
                version = 1,
                inputs = ins,
                outputs = outs,
                lockTime = 0L,
                subnetworkId = KaspaMassAndTx.SUBNETWORK_ID_NATIVE,
                gas = 0L,
                payload = kkcv1Payload,
            )
        }

        repeat(6) {
            pullUtxosUntil(amountSompi + fee)
            val tentativeChange = (totalIn - amountSompi - fee).coerceAtLeast(0L)
            val mass = KaspaMassAndTx.calculateMass(makeRaw(tentativeChange))
            val requiredFee = (mass.normalizedMass * minRate).coerceAtLeast(COVENANT_TX_FEE_BUFFER_SOMPI)
            if (requiredFee <= fee) {
                fee = requiredFee
                return@repeat
            }
            fee = requiredFee
        }
        pullUtxosUntil(amountSompi + fee)
        val finalChange = totalIn - amountSompi - fee
        require(finalChange >= 0L) {
            "Insufficient KAS for covenant deposit + Toccata v1 network fee (${fee / SOMPI_PER_KAS.toDouble()} KAS)"
        }
        val actualChange = if (finalChange >= DUST_LIMIT_SOMPI) finalChange else 0L
        val finalFee = totalIn - amountSompi - actualChange

        val unsigned = makeRaw(actualChange)
        val signedInputs = unsigned.inputs.mapIndexed { i, inp ->
            val privKey = keyLookup(inp.utxo.address)
                ?: throw IllegalStateException("Signing key not found for ${inp.utxo.address}")
            try {
                val sigHash = KaspaMassAndTx.computeSigHashAll(unsigned, i)
                val sig64 = KaspaCrypto.signSchnorr(privKey, sigHash)
                val sigScript = ByteArray(66).apply {
                    this[0] = 65.toByte()
                    sig64.copyInto(this, 1)
                    this[65] = KaspaMassAndTx.SIG_HASH_ALL
                }
                inp.copy(signatureScript = sigScript)
            } finally {
                privKey.fill(0)
            }
        }
        val signedTx = unsigned.copy(inputs = signedInputs)

        // Verify every P2PK funding input locally against real Toccata 100,000-unit OP_CHECKSIG & compute_budget
        var lastVmResult: KaspaScriptVm.VmResult? = null
        for (i in signedTx.inputs.indices) {
            val check = KaspaScriptVm.verifyP2pkInput(signedTx, inputIndex = i, enforceCommittedBudget = true)
            if (!check.success) {
                throw IllegalStateException("Local Kaspa VM rejected deploy funding input #$i: ${check.error}")
            }
            lastVmResult = check
        }

        val mass = KaspaMassAndTx.calculateMass(signedTx)
        val txId = KaspaMassAndTx.computeTransactionId(signedTx)
        val submitJson = KaspaMassAndTx.toRestSubmitJson(signedTx)
        return PreparedCovenantTx(
            rawTx = signedTx,
            txId = txId,
            submitJson = submitJson,
            feeSompi = finalFee,
            mass = mass.normalizedMass,
            covenantIdHex = covenantIdHex,
            vmResult = lastVmResult,
        )
    }

    data class SpendAndFeePlan(
        val spendSompi: Long,
        val changeSompi: Long,
        val feeSompi: Long,
        val isFullSweep: Boolean,
    )

    /**
     * Computes the exact spend amount, covenant change output, and Toccata v1 miner fee without
     * KIP-9 dust-output fee explosion when the user spends the full vault balance (e.g. `3.00 KAS`
     * on a `3.00 KAS` Cold Whitelist or Daily Spend Limit vault).
     */
    fun computeSpendAndFeePlan(
        covenant: TrackedCovenant,
        covenantUtxo: UtxoItem,
        entryName: String,
        requestedSpendSompi: Long,
        maxAllowedSpendSompi: Long = Long.MAX_VALUE,
        allowCovenantChangeOutput: Boolean = true,
        payloadSizeBytes: Int = 0,
        feeRateSompiPerGram: Long = KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM,
    ): SpendAndFeePlan {
        val covBalance = covenantUtxo.amountSompi
        require(covBalance > DUST_LIMIT_SOMPI) {
            "Balance too small to cover network fee"
        }

        // 1) Estimate 1-output full sweep fee (NO change output -> NO KIP-9 dust-output penalty!)
        val sweepFee = estimateSpendFeeSompi(
            covenant = covenant,
            covenantUtxo = covenantUtxo,
            entryName = entryName,
            outputAmountsSompi = listOf(covBalance),
            hasCovenantOutput = false,
            feeRateSompiPerGram = feeRateSompiPerGram,
            payloadSizeBytes = 0,
        )
        val maxSweepable = (covBalance - sweepFee).coerceAtLeast(0L)

        // Full sweep if caller doesn't want change OR requested amount leaves less than dust after 1-output fee
        val wantsFullSweep = !allowCovenantChangeOutput ||
            requestedSpendSompi >= covBalance - sweepFee - DUST_LIMIT_SOMPI
        if (wantsFullSweep && maxSweepable <= maxAllowedSpendSompi) {
            require(maxSweepable >= DUST_LIMIT_SOMPI) {
                "Balance too small after fee (${sweepFee / SOMPI_PER_KAS.toDouble()} KAS)"
            }
            return SpendAndFeePlan(
                spendSompi = maxSweepable,
                changeSompi = 0L,
                feeSompi = sweepFee,
                isFullSweep = true,
            )
        }

        // 2) Partial spend: 2 outputs (recipient + remaining balance back to covenant)
        val clampedRequested = requestedSpendSompi.coerceAtMost(maxAllowedSpendSompi)
        require(clampedRequested >= DUST_LIMIT_SOMPI) {
            "Enter a valid amount (>= 0.0001 KAS)"
        }
        val tentativeChange = (covBalance - clampedRequested).coerceAtLeast(DUST_LIMIT_SOMPI)
        val splitFee = estimateSpendFeeSompi(
            covenant = covenant,
            covenantUtxo = covenantUtxo,
            entryName = entryName,
            outputAmountsSompi = listOf(clampedRequested, tentativeChange),
            hasCovenantOutput = true,
            feeRateSompiPerGram = feeRateSompiPerGram,
            payloadSizeBytes = payloadSizeBytes,
        )
        val actualChange = covBalance - clampedRequested - splitFee
        if (actualChange < DUST_LIMIT_SOMPI) {
            // Remaining change would be dust — convert to 1-output full sweep within cap!
            val cappedSweepOut = minOf(maxSweepable, maxAllowedSpendSompi)
            if (cappedSweepOut >= DUST_LIMIT_SOMPI) {
                return SpendAndFeePlan(
                    spendSompi = cappedSweepOut,
                    changeSompi = 0L,
                    feeSompi = (covBalance - cappedSweepOut).coerceAtLeast(sweepFee),
                    isFullSweep = true,
                )
            }
            throw IllegalArgumentException(
                "Balance too small after fee (${splitFee / SOMPI_PER_KAS.toDouble()} KAS)",
            )
        }
        return SpendAndFeePlan(
            spendSompi = clampedRequested,
            changeSompi = actualChange,
            feeSompi = splitFee,
            isFullSweep = false,
        )
    }

    /**
     * Live spending-allowance & window-reset readout for `Daily Spend Limit` vaults.
     *
     * CRITICAL: [isWindowResetReady] is strictly gated on real server-reported [virtualDaaScore],
     * while smooth countdown display & [isFinalizing] use [extrapolatedVirtualDaaScore]!
     */
    data class DailySpendWindowStatus(
        val capSompi: Long,
        val availableNowSompi: Long,
        val isInCooldown: Boolean,
        val isWindowResetReady: Boolean,
        val isFinalizing: Boolean = false,
        val resetRemainingDaa: Long,
        val resetRemainingMs: Long,
    )

    fun evaluateDailySpendWindow(
        params: CovenantParams,
        liveBalanceSompi: Long,
        utxoBlockDaaScore: Long,
        virtualDaaScore: Long,
        extrapolatedVirtualDaaScore: Long = virtualDaaScore,
    ): DailySpendWindowStatus {
        val cap = params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI)
        val spent = params.spentInWindowSompi.coerceAtLeast(0L)
        val windowDaa = params.windowDaa.coerceAtLeast(10L)
        val serverAgeDaa = if (virtualDaaScore > 0L && utxoBlockDaaScore > 0L) {
            (virtualDaaScore - utxoBlockDaaScore).coerceAtLeast(0L)
        } else {
            0L
        }
        val windowResetReady = spent > 0L && virtualDaaScore > 0L && utxoBlockDaaScore > 0L && serverAgeDaa >= windowDaa
        val rawRemaining = if (spent <= 0L || windowResetReady) {
            cap
        } else {
            (cap - spent).coerceAtLeast(0L)
        }
        val availableNow = minOf(rawRemaining, liveBalanceSompi).coerceAtLeast(0L)
        val inCooldown = spent > 0L && !windowResetReady && rawRemaining < DUST_LIMIT_SOMPI
        val displayDaa = extrapolatedVirtualDaaScore.takeIf { it > 0L } ?: virtualDaaScore
        val displayAgeDaa = if (displayDaa > 0L && utxoBlockDaaScore > 0L) {
            (displayDaa - utxoBlockDaaScore).coerceAtLeast(0L)
        } else {
            0L
        }
        val resetRemDaa = if (spent > 0L && !windowResetReady) {
            (windowDaa - displayAgeDaa).coerceAtLeast(0L)
        } else {
            0L
        }
        val resetRemMs = (resetRemDaa * 1_000L) / DAA_BLOCKS_PER_SECOND
        val isFinalizing = spent > 0L &&
            !windowResetReady &&
            utxoBlockDaaScore > 0L &&
            resetRemMs <= ChainTimeAnchor.FINALIZING_DAA_POLL_WINDOW_MS
        return DailySpendWindowStatus(
            capSompi = cap,
            availableNowSompi = availableNow,
            isInCooldown = inCooldown,
            isWindowResetReady = windowResetReady,
            isFinalizing = isFinalizing,
            resetRemainingDaa = resetRemDaa,
            resetRemainingMs = resetRemMs,
        )
    }

    /**
     * Deterministically generates all candidate evolved P2SH addresses for [covenant] so a single
     * batched gRPC `GetUtxosByAddresses` call works universally on 100% of `--utxoindex` nodes
     * (even when the node does NOT have `--covenant-index` enabled!).
     */
    fun buildMultiStateLookaheadAddresses(
        covenant: TrackedCovenant,
        candidateDestAddresses: List<String> = emptyList(),
        subscriptionStartCycleOffset: Int = 0,
        subscriptionCycleCount: Int = 25,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        if (covenant.currentP2shAddress.isNotBlank()) {
            out.add(covenant.currentP2shAddress)
        }
        for (knownHex in covenant.knownRedeemScriptsHex) {
            runCatching {
                val hash = KaspaCrypto.blake2b256(KaspaCrypto.hexToBytes(knownHex), domainKey = null)
                KaspaAddress.encodeScriptHash(hash, covenant.network)
            }.getOrNull()?.let { out.add(it) }
        }
        when (covenant.templateId) {
            CovenantTemplateId.RECURRING_ALLOWANCE -> {
                if (isKeylessTimeBasedSubscription(covenant.params)) {
                    val baseTime = covenant.params.nextDueTimeMs
                    val interval = covenant.params.intervalMs.coerceAtLeast(60_000L)
                    val endExclusive = subscriptionStartCycleOffset + subscriptionCycleCount.coerceAtLeast(1)
                    for (k in subscriptionStartCycleOffset until endExclusive) {
                        val probeParams = covenant.params.copy(nextDueTimeMs = baseTime + k.toLong() * interval)
                        runCatching { compile(covenant.templateId, probeParams, covenant.network) }
                            .getOrNull()?.p2shAddress?.let { out.add(it) }
                    }
                }
            }

            CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                val cap = covenant.params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI)
                // Always include BOTH Stage 0 (spent=0 / ready) AND Stage 1 (spent=cap / cooldown)
                val probeSpent = LinkedHashSet<Long>().apply {
                    add(0L)
                    add(cap)
                    add(covenant.params.spentInWindowSompi.coerceIn(0L, cap))
                }
                // Include common round-KAS partial spends for small caps (<= 10 KAS)
                val capKas = (cap / SOMPI_PER_KAS).coerceAtMost(10L)
                for (k in 1L..capKas) {
                    probeSpent.add(k * SOMPI_PER_KAS)
                }
                for (s in probeSpent) {
                    val p = covenant.params.copy(spentInWindowSompi = s)
                    runCatching { compile(covenant.templateId, p, covenant.network) }
                        .getOrNull()?.p2shAddress?.let { out.add(it) }
                }
            }

            CovenantTemplateId.CLAWBACK_VAULT -> {
                // Stage 0 (Vaulted)
                runCatching {
                    compile(
                        covenant.templateId,
                        covenant.params.copy(vaultStage = 0, pendingUnvaultDestAddress = ""),
                        covenant.network,
                    )
                }.getOrNull()?.p2shAddress?.let { out.add(it) }
                // Stage 1 (Unvaulting) across known & wallet candidate destinations
                val dests = LinkedHashSet<String>().apply {
                    add("")
                    if (covenant.params.pendingUnvaultDestAddress.isNotBlank()) add(covenant.params.pendingUnvaultDestAddress)
                    if (covenant.params.ownerAddress.isNotBlank()) add(covenant.params.ownerAddress)
                    if (covenant.params.recoveryAddress.isNotBlank()) add(covenant.params.recoveryAddress)
                    for (d in candidateDestAddresses.take(6)) {
                        if (d.isNotBlank()) add(d)
                    }
                }
                for (dest in dests) {
                    val p = covenant.params.copy(vaultStage = 1, pendingUnvaultDestAddress = dest)
                    runCatching { compile(covenant.templateId, p, covenant.network) }
                        .getOrNull()?.p2shAddress?.let { out.add(it) }
                }
            }

            CovenantTemplateId.HODL_TIMELOCK,
            CovenantTemplateId.COLD_WHITELIST,
            CovenantTemplateId.GOAL_PIGGY_BANK,
            CovenantTemplateId.DEAD_MANS_SWITCH -> Unit
        }
        return out
    }

    /**
     * Returns true iff [redeemScriptHex] hashes via BLAKE2b-256 to the exact P2SH scriptPubKey
     * committed in [liveUtxo] (or matches [liveUtxo.address] if scriptPublicKeyHex is absent).
     */
    fun matchesLiveUtxoScriptHash(
        redeemScriptHex: String,
        network: KaspaNetwork,
        liveUtxo: UtxoItem,
    ): Boolean {
        if (redeemScriptHex.isBlank()) return false
        val redeemBytes = runCatching { KaspaCrypto.hexToBytes(redeemScriptHex) }.getOrNull() ?: return false
        val expectedHash = KaspaCrypto.blake2b256(redeemBytes, domainKey = null)
        val spkHex = liveUtxo.scriptPublicKeyHex.trim()
        if (spkHex.length == 70 && spkHex.startsWith("aa20", ignoreCase = true) && spkHex.endsWith("87", ignoreCase = true)) {
            val spkHashHex = spkHex.substring(4, 68)
            return spkHashHex.equals(KaspaCrypto.bytesToHex(expectedHash), ignoreCase = true)
        }
        if (liveUtxo.address.isNotBlank()) {
            val expectedAddr = KaspaAddress.encodeScriptHash(expectedHash, network)
            return expectedAddr.equals(liveUtxo.address, ignoreCase = true)
        }
        return false
    }

    /**
     * Self-healing stateful P2SH reconciler!
     *
     * Whenever a live on-chain UTXO's P2SH `scriptPublicKey` hash differs from the locally stored
     * `currentRedeemScriptHex` (for example after a cold restore from an older State-0 `.kinetic`
     * or `KKCV1` deploy payload while the vault has already progressed to State-1+), probes valid
     * state transitions until BLAKE2b(redeemScript) matches `liveUtxo.scriptPublicKey` bit-for-bit!
     */
    fun reconcileCovenantStateToLiveUtxo(
        covenant: TrackedCovenant,
        liveUtxo: UtxoItem,
        candidateSpentAmountsSompi: List<Long> = emptyList(),
        candidateDestAddresses: List<String> = emptyList(),
        candidateHistoricalRecipes: List<TrackedCovenant> = emptyList(),
    ): TrackedCovenant {
        // Fast path: already matches the live UTXO's exact BLAKE2b script hash!
        if (matchesLiveUtxoScriptHash(covenant.currentRedeemScriptHex, covenant.network, liveUtxo)) {
            val expectedHashHex = KaspaCrypto.bytesToHex(
                KaspaCrypto.blake2b256(KaspaCrypto.hexToBytes(covenant.currentRedeemScriptHex), domainKey = null),
            )
            return covenant.copy(
                currentP2shAddress = liveUtxo.address.ifBlank { covenant.currentP2shAddress },
                redeemScriptHashHex = expectedHashHex,
            )
        }

        fun applyCompiledMatch(
            base: TrackedCovenant,
            matchedParams: CovenantParams,
            bundle: CompiledCovenantBundle,
        ): TrackedCovenant {
            val matchedHex = KaspaCrypto.bytesToHex(bundle.redeemScript)
            return base.copy(
                currentP2shAddress = bundle.p2shAddress,
                currentRedeemScriptHex = matchedHex,
                redeemScriptHashHex = bundle.redeemScriptHashHex,
                silSource = bundle.silSource,
                abiArtifact = bundle.abiArtifact,
                params = matchedParams,
                knownRedeemScriptsHex = (base.knownRedeemScriptsHex + matchedHex).distinct(),
            )
        }

        // Step 1: Try newest-to-oldest historical KKCV1 / backup recipes first (covers Dead Man's Switch
        // Check-Ins, Daily Spend Limit transitions, Clawback Unvault transitions, Subscription cycles, etc.)
        for (recipe in candidateHistoricalRecipes) {
            if (matchesLiveUtxoScriptHash(recipe.currentRedeemScriptHex, covenant.network, liveUtxo)) {
                val mergedParams = preserveKeyMetadata(preferred = recipe.params, fallback = covenant.params)
                val compiled = runCatching { compile(covenant.templateId, mergedParams, covenant.network) }.getOrNull()
                if (compiled != null && matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(compiled.redeemScript), covenant.network, liveUtxo)) {
                    return applyCompiledMatch(covenant, mergedParams, compiled)
                }
                val hashHex = KaspaCrypto.bytesToHex(
                    KaspaCrypto.blake2b256(KaspaCrypto.hexToBytes(recipe.currentRedeemScriptHex), domainKey = null),
                )
                return covenant.copy(
                    currentP2shAddress = liveUtxo.address.ifBlank { recipe.currentP2shAddress },
                    currentRedeemScriptHex = recipe.currentRedeemScriptHex,
                    redeemScriptHashHex = hashHex,
                    silSource = recipe.silSource.ifBlank { covenant.silSource },
                    abiArtifact = recipe.abiArtifact,
                    params = mergedParams,
                    knownRedeemScriptsHex = (covenant.knownRedeemScriptsHex + recipe.currentRedeemScriptHex).distinct(),
                )
            }
            // Also try compiling recipe.params directly in case recipe script was stale
            val mergedParams = preserveKeyMetadata(preferred = recipe.params, fallback = covenant.params)
            val compiled = runCatching { compile(covenant.templateId, mergedParams, covenant.network) }.getOrNull()
            if (compiled != null && matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(compiled.redeemScript), covenant.network, liveUtxo)) {
                return applyCompiledMatch(covenant, mergedParams, compiled)
            }
        }

        // Step 2: Check current params freshly compiled (catches stale currentRedeemScriptHex)
        runCatching { compile(covenant.templateId, covenant.params, covenant.network) }.getOrNull()?.let { fresh ->
            if (matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(fresh.redeemScript), covenant.network, liveUtxo)) {
                return applyCompiledMatch(covenant, covenant.params, fresh)
            }
        }

        // Step 3: Template-specific deterministic state-space probing!
        when (covenant.templateId) {
            CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                val cap = covenant.params.dailyCapSompi.coerceAtLeast(DUST_LIMIT_SOMPI)
                val tried = HashSet<Long>()
                val candidates = LinkedHashSet<Long>()
                // Priority 1: Exact on-chain outputs[0] amounts & cumulative sums from liveUtxo.transactionId
                for (amt in candidateSpentAmountsSompi) {
                    if (amt >= 0L) {
                        candidates.add(amt.coerceAtMost(cap))
                        if (amt in 1L..cap) candidates.add((cap - amt).coerceAtLeast(0L))
                    }
                }
                // Priority 2: Reset (spent = 0) and exhausted (spent = cap)
                candidates.add(0L)
                candidates.add(cap)
                candidates.add(covenant.params.spentInWindowSompi.coerceIn(0L, cap))
                // Priority 3: Common round-KAS offline heuristic steps (rescues vaults even without REST tx fetch!)
                val kasSteps = longArrayOf(
                    10_000_000L, 25_000_000L, 50_000_000L, // 0.1, 0.25, 0.5 KAS
                    100_000_000L, 200_000_000L, 300_000_000L, 400_000_000L, 500_000_000L, // 1..5 KAS
                    1_000_000_000L, 1_500_000_000L, 2_000_000_000L, 2_500_000_000L, 5_000_000_000L,
                    10_000_000_000L, 20_000_000_000L, 25_000_000_000L, 50_000_000_000L, 100_000_000_000L,
                )
                for (s in kasSteps) {
                    if (s <= cap) {
                        candidates.add(s)
                        candidates.add((cap - s).coerceAtLeast(0L))
                    }
                }
                // Also probe integer KAS increments up to 500 if cap <= 500 KAS
                val capKas = (cap / SOMPI_PER_KAS).coerceAtMost(500L)
                for (k in 1L..capKas) {
                    candidates.add(k * SOMPI_PER_KAS)
                }
                for (spent in candidates) {
                    if (!tried.add(spent)) continue
                    val probeParams = covenant.params.copy(spentInWindowSompi = spent)
                    val bundle = runCatching { compile(covenant.templateId, probeParams, covenant.network) }.getOrNull()
                        ?: continue
                    if (matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(bundle.redeemScript), covenant.network, liveUtxo)) {
                        return applyCompiledMatch(covenant, probeParams, bundle)
                    }
                }
            }

            CovenantTemplateId.CLAWBACK_VAULT -> {
                val destCandidates = LinkedHashSet<String>().apply {
                    add("")
                    if (covenant.params.pendingUnvaultDestAddress.isNotBlank()) add(covenant.params.pendingUnvaultDestAddress)
                    if (covenant.params.ownerAddress.isNotBlank()) add(covenant.params.ownerAddress)
                    if (covenant.params.recoveryAddress.isNotBlank()) add(covenant.params.recoveryAddress)
                    for (d in candidateDestAddresses) {
                        if (d.isNotBlank()) add(d)
                    }
                }
                for (stage in intArrayOf(0, 1)) {
                    val dests = if (stage == 0) listOf("") else destCandidates.toList()
                    for (dest in dests) {
                        val probeParams = covenant.params.copy(
                            vaultStage = stage,
                            pendingUnvaultDestAddress = if (stage == 1) dest else "",
                        )
                        val bundle = runCatching { compile(covenant.templateId, probeParams, covenant.network) }.getOrNull()
                            ?: continue
                        if (matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(bundle.redeemScript), covenant.network, liveUtxo)) {
                            return applyCompiledMatch(covenant, probeParams, bundle)
                        }
                    }
                }
            }

            CovenantTemplateId.RECURRING_ALLOWANCE -> {
                if (isKeylessTimeBasedSubscription(covenant.params)) {
                    val baseTime = covenant.params.nextDueTimeMs
                    val interval = covenant.params.intervalMs.coerceAtLeast(60_000L)
                    // Forward probe k = 1..1000 (handles up to 1,000 automated/shared claims!)
                    for (k in 1..1000) {
                        val probeParams = covenant.params.copy(nextDueTimeMs = baseTime + k.toLong() * interval)
                        val bundle = runCatching { compile(covenant.templateId, probeParams, covenant.network) }.getOrNull()
                            ?: break
                        if (matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(bundle.redeemScript), covenant.network, liveUtxo)) {
                            return applyCompiledMatch(covenant, probeParams, bundle)
                        }
                    }
                    // Backward probe k = -1..-24 in case a slightly newer metadata record met an older UTXO
                    for (k in 1..24) {
                        val nextT = baseTime - k.toLong() * interval
                        if (nextT < LOCK_TIME_THRESHOLD) break
                        val probeParams = covenant.params.copy(nextDueTimeMs = nextT)
                        val bundle = runCatching { compile(covenant.templateId, probeParams, covenant.network) }.getOrNull()
                            ?: break
                        if (matchesLiveUtxoScriptHash(KaspaCrypto.bytesToHex(bundle.redeemScript), covenant.network, liveUtxo)) {
                            return applyCompiledMatch(covenant, probeParams, bundle)
                        }
                    }
                }
            }

            CovenantTemplateId.DEAD_MANS_SWITCH,
            CovenantTemplateId.GOAL_PIGGY_BANK,
            CovenantTemplateId.COLD_WHITELIST,
            CovenantTemplateId.HODL_TIMELOCK -> Unit
        }

        // Step 4: Fallback check over knownRedeemScriptsHex (matches previously archived preimages)
        for (knownHex in covenant.knownRedeemScriptsHex) {
            if (matchesLiveUtxoScriptHash(knownHex, covenant.network, liveUtxo)) {
                val hashHex = KaspaCrypto.bytesToHex(
                    KaspaCrypto.blake2b256(KaspaCrypto.hexToBytes(knownHex), domainKey = null),
                )
                return covenant.copy(
                    currentP2shAddress = liveUtxo.address.ifBlank { covenant.currentP2shAddress },
                    currentRedeemScriptHex = knownHex,
                    redeemScriptHashHex = hashHex,
                )
            }
        }

        return covenant
    }

    private fun preserveKeyMetadata(preferred: CovenantParams, fallback: CovenantParams): CovenantParams =
        preferred.copy(
            ownerDerivationPath = preferred.ownerDerivationPath.ifBlank { fallback.ownerDerivationPath },
            ownerDerivationChain = if (preferred.ownerDerivationChain >= 0) preferred.ownerDerivationChain else fallback.ownerDerivationChain,
            ownerDerivationIndex = if (preferred.ownerDerivationIndex >= 0) preferred.ownerDerivationIndex else fallback.ownerDerivationIndex,
            ownerAddress = preferred.ownerAddress.ifBlank { fallback.ownerAddress },
            vaultSaltHex = preferred.vaultSaltHex.ifBlank { fallback.vaultSaltHex },
        )

    /**
     * Estimates the exact Toccata v1 network fee (at 100 sompi/gram floor + 15% safety margin) for
     * spending or topping up a covenant P2SH UTXO, accounting for P2SH script size, compute_budget,
     * optional `KKCV1` state-transition payload, and KIP-9 storage mass.
     */
    fun estimateSpendFeeSompi(
        covenant: TrackedCovenant,
        covenantUtxo: UtxoItem,
        entryName: String,
        outputAmountsSompi: List<Long>,
        hasCovenantOutput: Boolean,
        extraFundingUtxos: List<UtxoItem> = emptyList(),
        feeRateSompiPerGram: Long = KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM,
        isKeyless: Boolean = false,
        extraSigScriptBytes: Int = 0,
        payloadSizeBytes: Int = 0,
    ): Long {
        val redeemLen = (covenant.currentRedeemScriptHex.length / 2).coerceAtLeast(32)
        val approxSigScriptLen = redeemLen + 10 + (if (isKeyless) 0 else 66) + extraSigScriptBytes
        val covBudget = if (isKeyless) 6 else 18

        val inputs = ArrayList<KaspaMassAndTx.TxInput>().apply {
            add(
                KaspaMassAndTx.TxInput(
                    outpointTxId = covenantUtxo.transactionId,
                    outpointIndex = covenantUtxo.index,
                    signatureScript = ByteArray(approxSigScriptLen),
                    sequence = 0L,
                    sigOpCount = 0,
                    utxo = covenantUtxo.copy(covenantId = covenant.covenantIdHex ?: covenantUtxo.covenantId),
                    computeBudget = covBudget,
                ),
            )
            for (u in extraFundingUtxos) {
                add(
                    KaspaMassAndTx.TxInput(
                        outpointTxId = u.transactionId,
                        outpointIndex = u.index,
                        signatureScript = ByteArray(KaspaMassAndTx.SCHNORR_SIGNATURE_SCRIPT_SIZE),
                        sequence = 0L,
                        sigOpCount = 0,
                        utxo = u,
                        computeBudget = KaspaMassAndTx.DEFAULT_V1_P2PK_COMPUTE_BUDGET,
                    ),
                )
            }
        }
        val dummySpk = ByteArray(35)
        val outputs = outputAmountsSompi.mapIndexed { idx, amt ->
            KaspaMassAndTx.TxOutput(
                valueSompi = amt.coerceAtLeast(DUST_LIMIT_SOMPI),
                scriptPublicKeyVersion = 0,
                scriptPublicKey = dummySpk,
                address = covenant.currentP2shAddress,
                covenant = if (hasCovenantOutput && idx == outputAmountsSompi.lastIndex && covenant.covenantIdHex != null) {
                    KaspaMassAndTx.CovenantBinding(0, covenant.covenantIdHex)
                } else {
                    null
                },
            )
        }
        val raw = KaspaMassAndTx.RawTx(
            version = 1,
            inputs = inputs,
            outputs = outputs,
            payload = if (payloadSizeBytes > 0) ByteArray(payloadSizeBytes) else ByteArray(0),
        )
        val mass = KaspaMassAndTx.calculateMass(raw).normalizedMass
        val minRate = feeRateSompiPerGram.coerceAtLeast(KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM)
        val floor = if (isKeyless) 250_000L else COVENANT_TX_FEE_BUFFER_SOMPI
        val est = ((mass * minRate * 115L) / 100L).coerceAtLeast(floor)
        return if (isKeyless) est.coerceAtMost(MAX_KEYLESS_FEE_SOMPI - 50_000L) else est
    }

    /**
     * Builds, signs (or constructs keyless 0-signer args), and runs **on-device [KaspaScriptVm] dry-run
     * verification** on a Transaction v1 spending or topping up a covenant P2SH UTXO!
     */
    fun buildSignAndVerifySpendTxV1(
        covenant: TrackedCovenant,
        covenantUtxo: UtxoItem,
        entryName: String,
        outputs: List<KaspaMassAndTx.TxOutput>,
        lockTime: Long = 0L,
        sequence: Long = 0L,
        virtualDaaScore: Long = 0L,
        currentTimeMs: Long = 0L,
        signerPrivateKeys: List<ByteArray> = emptyList(),
        extraScriptArgs: List<SilArgValue> = emptyList(),
        extraFundingUtxos: List<UtxoItem> = emptyList(),
        extraKeyLookup: (String) -> ByteArray? = { null },
        payload: ByteArray = ByteArray(0),
    ): PreparedCovenantTx {
        val redeemScript = KaspaCrypto.hexToBytes(covenant.currentRedeemScriptHex)
        val allInputs = ArrayList<KaspaMassAndTx.TxInput>().apply {
            add(
                KaspaMassAndTx.TxInput(
                    outpointTxId = covenantUtxo.transactionId,
                    outpointIndex = covenantUtxo.index,
                    signatureScript = ByteArray(0),
                    sequence = sequence,
                    sigOpCount = 0,
                    utxo = covenantUtxo.copy(covenantId = covenant.covenantIdHex ?: covenantUtxo.covenantId),
                    computeBudget = if (signerPrivateKeys.isEmpty()) 6 else 16,
                ),
            )
            for (u in extraFundingUtxos) {
                add(
                    KaspaMassAndTx.TxInput(
                        outpointTxId = u.transactionId,
                        outpointIndex = u.index,
                        signatureScript = ByteArray(KaspaMassAndTx.SCHNORR_SIGNATURE_SCRIPT_SIZE),
                        sequence = 0L,
                        sigOpCount = 0,
                        utxo = u,
                        computeBudget = KaspaMassAndTx.DEFAULT_V1_P2PK_COMPUTE_BUDGET,
                    ),
                )
            }
        }
        val unsigned = KaspaMassAndTx.RawTx(
            version = 1,
            inputs = allInputs,
            outputs = outputs,
            lockTime = lockTime,
            subnetworkId = KaspaMassAndTx.SUBNETWORK_ID_NATIVE,
            gas = 0L,
            payload = payload,
        )

        val sigArgs = if (signerPrivateKeys.isNotEmpty()) {
            val covSigHash = KaspaMassAndTx.computeSigHashAll(unsigned, 0)
            signerPrivateKeys.map { priv ->
                val sig64 = KaspaCrypto.signSchnorr(priv, covSigHash)
                val sig65 = ByteArray(65)
                sig64.copyInto(sig65, 0)
                sig65[64] = KaspaMassAndTx.SIG_HASH_ALL
                SilArgValue.BytesVal(sig65)
            }
        } else {
            emptyList()
        }
        val combinedArgs = extraScriptArgs + sigArgs
        val covSigScript = SilAbiCodec.encodeContractEntrySigScript(
            abi = covenant.abiArtifact,
            contractName = covenant.templateId.contractName,
            entryName = entryName,
            args = combinedArgs,
            redeemScriptOverride = redeemScript,
        )

        val signedInputs = ArrayList<KaspaMassAndTx.TxInput>(unsigned.inputs.size)
        signedInputs.add(unsigned.inputs[0].copy(signatureScript = covSigScript))
        for (i in 1 until unsigned.inputs.size) {
            val inp = unsigned.inputs[i]
            val priv = extraKeyLookup(inp.utxo.address)
                ?: throw IllegalStateException("Signing key not found for extra input ${inp.utxo.address}")
            try {
                val h = KaspaMassAndTx.computeSigHashAll(unsigned, i)
                val sig64 = KaspaCrypto.signSchnorr(priv, h)
                val sigScript = ByteArray(66).apply {
                    this[0] = 65.toByte()
                    sig64.copyInto(this, 1)
                    this[65] = KaspaMassAndTx.SIG_HASH_ALL
                }
                signedInputs.add(inp.copy(signatureScript = sigScript))
            } finally {
                priv.fill(0)
            }
        }

        val signedTx = unsigned.copy(inputs = signedInputs)

        // Pass 1: Measure real Toccata script units on P2SH input 0 & verify script logic
        val measured = KaspaScriptVm.verifyP2shInput(
            tx = signedTx,
            inputIndex = 0,
            virtualDaaScore = virtualDaaScore,
            currentTimeMs = currentTimeMs,
            enforceCommittedBudget = false,
        )
        if (!measured.success) {
            throw IllegalStateException("Local Kaspa VM dry-run rejected covenant spend: ${measured.error}")
        }

        // Update input 0 compute_budget from VM measurement (compute_budget does NOT change v1 sighash or txid!)
        val finalizedInputs = signedTx.inputs.mapIndexed { idx, inp ->
            if (idx == 0) inp.copy(computeBudget = measured.recommendedComputeBudget) else inp
        }
        val finalTx = signedTx.copy(inputs = finalizedInputs)

        // Pass 2: Enforce committed compute_budget on P2SH input 0 AND every extra P2PK funding input!
        val vmResult = KaspaScriptVm.verifyP2shInput(
            tx = finalTx,
            inputIndex = 0,
            virtualDaaScore = virtualDaaScore,
            currentTimeMs = currentTimeMs,
            enforceCommittedBudget = true,
        )
        if (!vmResult.success) {
            throw IllegalStateException("Local Kaspa VM rejected committed P2SH budget: ${vmResult.error}")
        }
        for (i in 1 until finalTx.inputs.size) {
            val extraCheck = KaspaScriptVm.verifyP2pkInput(finalTx, inputIndex = i, enforceCommittedBudget = true)
            if (!extraCheck.success) {
                throw IllegalStateException("Local Kaspa VM rejected extra funding input #$i: ${extraCheck.error}")
            }
        }

        val totalIn = finalTx.inputs.sumOf { it.utxo.amountSompi }
        val totalOut = finalTx.outputs.sumOf { it.valueSompi }
        val fee = (totalIn - totalOut).coerceAtLeast(0L)
        val mass = KaspaMassAndTx.calculateMass(finalTx)
        val txId = KaspaMassAndTx.computeTransactionId(finalTx)
        val submitJson = KaspaMassAndTx.toRestSubmitJson(finalTx)

        return PreparedCovenantTx(
            rawTx = finalTx,
            txId = txId,
            submitJson = submitJson,
            feeSompi = fee,
            mass = mass.normalizedMass,
            covenantIdHex = covenant.covenantIdHex,
            vmResult = vmResult,
        )
    }

    fun formatAbiPrettyJson(abi: SilAbiArtifact): String = prettyJson.encodeToString(abi)

    private fun decodePubKey32(hex: String, label: String): ByteArray {
        val clean = hex.trim()
        val bytes = runCatching { KaspaCrypto.hexToBytes(clean) }.getOrNull()
        require(bytes != null && bytes.size == 32) { "$label must be a 32-byte (64 hex char) x-only Schnorr public key" }
        return bytes
    }

    private fun resolveAddressPubKeyOrFallback(address: String, pubKeyHex: String, fallback: ByteArray): ByteArray {
        val cleanHex = pubKeyHex.trim()
        if (cleanHex.length == 64) {
            runCatching { KaspaCrypto.hexToBytes(cleanHex) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        }
        val decoded = KaspaAddress.decode(address)
        if (decoded != null && decoded.version == KaspaAddress.VERSION_PUBKEY && decoded.payload.size == 32) {
            return decoded.payload
        }
        return fallback
    }

    private fun decodeVersionedSpk(address: String, network: KaspaNetwork): ByteArray {
        val decoded = KaspaAddress.decode(address)
            ?: throw IllegalArgumentException("Invalid Kaspa address: $address")
        val converted = if (decoded.prefix != network.hrp) {
            KaspaAddress.decode(KaspaAddress.convertNetworkPrefix(address, network)) ?: decoded
        } else {
            decoded
        }
        return KaspaScriptVm.encodeVersionedSpk(0, converted.toScriptPublicKey())
    }
}
