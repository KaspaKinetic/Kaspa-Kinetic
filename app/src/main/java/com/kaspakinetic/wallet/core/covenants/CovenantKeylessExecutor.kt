package com.kaspakinetic.wallet.core.covenants

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx

/**
 * Pure, testable builder for user-initiated **keyless** (0-KAS claimable) Kaspa L1 covenant claims:
 * - Recurring Allowance / Subscription `withdraw(scriptSuffix)` (once server `pastMedianTime > nextDueTimeMs`)
 * - Dead Man's Switch `heirClaim()` (v3.8 permanent fixed-address `OP_CHECKSEQUENCEVERIFY` once server
 *   `virtualDaaScore - utxo.blockDaaScore >= inactivityDaa`, OR legacy `tx.time` CLTV once
 *   server `pastMedianTime > inactivityDeadlineMs`)
 *
 * Requires ZERO wallet unlock, ZERO seed/private keys, and ZERO extra wallet UTXOs because the
 * Toccata miner fee is deducted directly from the covenant UTXO itself. Executed strictly when
 * the user taps Claim (zero background auto-send workers).
 */
object CovenantKeylessExecutor {

    /**
     * Builds & locally VM-verifies a keyless claim tx if [covenant] is currently finalized against
     * Kaspa's real server-reported [pastMedianTimeMs] (`tx.time`) or [virtualDaaScore] (relative CSV),
     * or returns `null` if not yet finalized.
     */
    fun buildDueKeylessTx(
        covenant: TrackedCovenant,
        covenantUtxo: UtxoItem,
        pastMedianTimeMs: Long,
        preferredEntry: String? = null,
        virtualDaaScore: Long = 0L,
    ): Pair<PreparedCovenantTx, TrackedCovenant>? {
        val network = covenant.network
        val totalSompi = covenantUtxo.amountSompi
        if (totalSompi <= DUST_LIMIT_SOMPI) return null
        val wallNowMs = System.currentTimeMillis()

        return when (covenant.templateId) {
            CovenantTemplateId.RECURRING_ALLOWANCE -> {
                if (!CovenantCompiler.isKeylessTimeBasedSubscription(covenant.params)) return null
                if (preferredEntry != null && preferredEntry != "withdraw") return null
                if (!CovenantCompiler.isTimeLockReady(covenant.params.nextDueTimeMs, pastMedianTimeMs)) return null
                val recAddr = covenant.params.recipientAddress.ifBlank { covenant.params.ownerAddress }
                val recSpk = decodeForNetworkSpk(recAddr, network) ?: return null
                val redeemBytes = KaspaCrypto.hexToBytes(covenant.currentRedeemScriptHex)
                val scriptSuffix = CovenantCompiler.extractSubscriptionScriptSuffix(redeemBytes)
                val allowance = covenant.params.allowanceSompi.coerceAtLeast(DUST_LIMIT_SOMPI)

                val estFeeLoop = CovenantCompiler.estimateSpendFeeSompi(
                    covenant = covenant,
                    covenantUtxo = covenantUtxo,
                    entryName = "withdraw",
                    outputAmountsSompi = listOf(allowance, (totalSompi - allowance).coerceAtLeast(DUST_LIMIT_SOMPI)),
                    hasCovenantOutput = true,
                    isKeyless = true,
                    extraSigScriptBytes = scriptSuffix.size + 4,
                )
                val estFeeDrain = CovenantCompiler.estimateSpendFeeSompi(
                    covenant = covenant,
                    covenantUtxo = covenantUtxo,
                    entryName = "withdraw",
                    outputAmountsSompi = listOf(totalSompi),
                    hasCovenantOutput = false,
                    isKeyless = true,
                    extraSigScriptBytes = scriptSuffix.size + 4,
                )

                val canLoop = totalSompi >= allowance + estFeeLoop + DUST_LIMIT_SOMPI
                val outs: List<KaspaMassAndTx.TxOutput>
                val updatedCov: TrackedCovenant

                if (canLoop) {
                    val nextParams = covenant.params.copy(
                        nextDueTimeMs = covenant.params.nextDueTimeMs + covenant.params.intervalMs,
                    )
                    val nextBundle = CovenantCompiler.compile(
                        templateId = CovenantTemplateId.RECURRING_ALLOWANCE,
                        params = nextParams,
                        network = network,
                    )
                    val nextRedeemHex = KaspaCrypto.bytesToHex(nextBundle.redeemScript)
                    val remainder = totalSompi - allowance - estFeeLoop
                    outs = listOf(
                        KaspaMassAndTx.TxOutput(
                            valueSompi = allowance,
                            scriptPublicKeyVersion = 0,
                            scriptPublicKey = recSpk,
                            address = recAddr,
                        ),
                        KaspaMassAndTx.TxOutput(
                            valueSompi = remainder,
                            scriptPublicKeyVersion = 0,
                            scriptPublicKey = nextBundle.p2shScriptPubKey,
                            address = nextBundle.p2shAddress,
                            covenant = covenant.covenantIdHex?.let {
                                KaspaMassAndTx.CovenantBinding(authorizingInput = 0, covenantIdHex = it)
                            },
                        ),
                    )
                    updatedCov = covenant.copy(
                        currentP2shAddress = nextBundle.p2shAddress,
                        currentRedeemScriptHex = nextRedeemHex,
                        redeemScriptHashHex = nextBundle.redeemScriptHashHex,
                        silSource = nextBundle.silSource,
                        abiArtifact = nextBundle.abiArtifact,
                        params = nextParams,
                        knownRedeemScriptsHex = (covenant.knownRedeemScriptsHex + nextRedeemHex).distinct(),
                        liveBalanceSompi = remainder,
                        liveStatus = CovenantStatus.ACTIVE,
                        lastUpdatedAtMs = wallNowMs,
                    )
                } else {
                    val payout = (totalSompi - estFeeDrain).coerceAtMost(allowance)
                    if (payout < DUST_LIMIT_SOMPI) return null
                    outs = listOf(
                        KaspaMassAndTx.TxOutput(
                            valueSompi = payout,
                            scriptPublicKeyVersion = 0,
                            scriptPublicKey = recSpk,
                            address = recAddr,
                        ),
                    )
                    updatedCov = covenant.copy(
                        liveBalanceSompi = 0L,
                        liveStatus = CovenantStatus.SETTLED,
                        lastUpdatedAtMs = wallNowMs,
                    )
                }

                val prepared = CovenantCompiler.buildSignAndVerifySpendTxV1(
                    covenant = covenant,
                    covenantUtxo = covenantUtxo,
                    entryName = "withdraw",
                    outputs = outs,
                    lockTime = covenant.params.nextDueTimeMs,
                    sequence = 0L,
                    currentTimeMs = pastMedianTimeMs,
                    signerPrivateKeys = emptyList(),
                    extraScriptArgs = listOf(SilArgValue.BytesVal(scriptSuffix)),
                )
                prepared to updatedCov.copy(
                    liveUtxoTxId = if (updatedCov.liveStatus == CovenantStatus.ACTIVE) prepared.txId else null,
                    liveUtxoIndex = if (updatedCov.liveStatus == CovenantStatus.ACTIVE) 1 else null,
                )
            }

            CovenantTemplateId.DEAD_MANS_SWITCH -> {
                val isTimeMode = CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(covenant.params)
                val isCsvMode = CovenantCompiler.isKeylessCsvDeadMansSwitch(covenant.params)
                if (!isTimeMode && !isCsvMode) return null
                if (preferredEntry != null && preferredEntry != "heirClaim") return null
                val timeoutDaa = covenant.params.inactivityDaa.coerceIn(10L, CovenantCompiler.MAX_HODL_LOCK_DAA)
                if (isTimeMode) {
                    if (!CovenantCompiler.isTimeLockReady(covenant.params.inactivityDeadlineMs, pastMedianTimeMs)) return null
                } else {
                    if (!CovenantCompiler.isRelativeDaaLockReady(covenantUtxo.blockDaaScore, timeoutDaa, virtualDaaScore)) return null
                }
                val heirAddr = covenant.params.heirAddress.ifBlank { covenant.params.ownerAddress }
                val heirSpk = decodeForNetworkSpk(heirAddr, network) ?: return null
                val estFee = CovenantCompiler.estimateSpendFeeSompi(
                    covenant = covenant,
                    covenantUtxo = covenantUtxo,
                    entryName = "heirClaim",
                    outputAmountsSompi = listOf(totalSompi),
                    hasCovenantOutput = false,
                    isKeyless = true,
                )
                val payout = totalSompi - estFee
                if (payout < DUST_LIMIT_SOMPI) return null
                val outs = listOf(
                    KaspaMassAndTx.TxOutput(
                        valueSompi = payout,
                        scriptPublicKeyVersion = 0,
                        scriptPublicKey = heirSpk,
                        address = heirAddr,
                    ),
                )
                val prepared = CovenantCompiler.buildSignAndVerifySpendTxV1(
                    covenant = covenant,
                    covenantUtxo = covenantUtxo,
                    entryName = "heirClaim",
                    outputs = outs,
                    lockTime = if (isTimeMode) covenant.params.inactivityDeadlineMs else 0L,
                    sequence = if (isCsvMode) timeoutDaa else 0L,
                    virtualDaaScore = if (isCsvMode) virtualDaaScore else 0L,
                    currentTimeMs = if (isTimeMode) pastMedianTimeMs else 0L,
                    signerPrivateKeys = emptyList(),
                )
                val updated = covenant.copy(
                    liveBalanceSompi = 0L,
                    liveUtxoTxId = null,
                    liveUtxoIndex = null,
                    liveStatus = CovenantStatus.SETTLED,
                    lastUpdatedAtMs = wallNowMs,
                )
                prepared to updated
            }

            else -> null
        }
    }

    private fun decodeForNetworkSpk(address: String, network: KaspaNetwork): ByteArray? {
        val decoded = KaspaAddress.decode(address.trim()) ?: return null
        val target = if (decoded.prefix != network.hrp) {
            KaspaAddress.decode(KaspaAddress.convertNetworkPrefix(address.trim(), network)) ?: decoded
        } else {
            decoded
        }
        return target.toScriptPublicKey()
    }
}
