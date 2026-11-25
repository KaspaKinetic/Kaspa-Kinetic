package com.kaspakinetic.wallet.core.model

import com.kaspakinetic.wallet.core.network.KaspadProtoCodec

/**
 * Atomic, thread-safe UTXO reconciler eliminating double-counted balances and duplicate
 * `Block DAA: 0` pending UTXOs during rapid broadcasts (e.g. Miner Donations), `NotifyUtxosChanged`
 * pushes, and authoritative `GetUtxosByAddresses` snapshots.
 *
 * Invariants guaranteed on every operation:
 * 1. Every outpoint (`txId:index`) appears at most once in the returned UTXO list.
 * 2. Locally spent input outpoints (`pendingSpentOutpoints`) are excluded immediately from the
 *    displayed UTXO list and Total Balance until the node confirms their removal (or TTL expires).
 * 3. Optimistic `Block DAA: 0` change UTXOs (`pendingChangeUtxos`) are automatically superseded
 *    the instant the node reports a confirmed UTXO for that outpoint — or removed if spent by a
 *    subsequent chained split.
 */
class UtxoReconciler(
    private val pendingTtlMs: Long = 5L * 60_000L,
) {
    private val lock = Any()
    private val confirmedUtxos = LinkedHashMap<String, UtxoItem>()
    private val pendingSpentOutpoints = LinkedHashMap<String, Long>()
    private val pendingChangeUtxos = LinkedHashMap<String, Pair<UtxoItem, Long>>()

    fun reset() = synchronized(lock) {
        confirmedUtxos.clear()
        pendingSpentOutpoints.clear()
        pendingChangeUtxos.clear()
    }

    /**
     * Records a locally broadcast transaction:
     * - Immediately hides all spent input outpoints so they cannot coexist with returning change.
     * - Removes any previous optimistic change UTXO that was just spent by this chained tx.
     * - Registers any new returning change UTXO(s) (with `blockDaaScore = 0L`) unless already spent.
     */
    fun recordLocalBroadcast(
        spentOutpoints: Collection<String>,
        optimisticChangeOutputs: Collection<UtxoItem>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<UtxoItem> = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        for (rawKey in spentOutpoints) {
            val key = rawKey.trim()
            if (key.isEmpty()) continue
            pendingSpentOutpoints[key] = nowMs
            confirmedUtxos.remove(key)
            pendingChangeUtxos.remove(key)
        }
        for (change in optimisticChangeOutputs) {
            val key = change.outpointKey
            if (key.isBlank() || key in pendingSpentOutpoints) continue
            // If node already has a confirmed entry for this exact outpoint, keep the confirmed one!
            val existingConfirmed = confirmedUtxos[key]
            if (existingConfirmed != null && existingConfirmed.blockDaaScore > 0L) continue
            pendingChangeUtxos[key] = change to nowMs
        }
        computeViewLocked()
    }

    /**
     * Applies a real-time `NotifyUtxosChanged` gRPC push notification atomically.
     */
    fun applyUtxosChangedPush(
        added: List<UtxoItem>,
        removed: List<KaspadProtoCodec.RemovedUtxoRef>,
        walletAddressSet: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<UtxoItem> = synchronized(lock) {
        pruneExpiredLocked(nowMs)

        val addedKeys = added.mapTo(HashSet()) { it.outpointKey }

        // 1. Apply removed outpoints first: node confirmed these inputs are spent!
        for (rem in removed) {
            val key = rem.outpointKey
            confirmedUtxos.remove(key)
            pendingChangeUtxos.remove(key)
            if (key !in addedKeys) {
                pendingSpentOutpoints.remove(key)
            }
        }

        // 2. Apply added outpoints (filtered to this wallet's addresses when known)
        for (item in added) {
            if (walletAddressSet.isNotEmpty() && item.address !in walletAddressSet) continue
            val key = item.outpointKey
            // Always clear any superseded Block DAA: 0 optimistic placeholder for this exact outpoint
            pendingChangeUtxos.remove(key)
            // If this outpoint was ALREADY spent locally by a subsequent chained split, keep it hidden!
            if (key in pendingSpentOutpoints) {
                confirmedUtxos.remove(key)
            } else {
                confirmedUtxos[key] = item
            }
        }

        computeViewLocked()
    }

    /**
     * Reconciles an authoritative full `GetUtxosByAddresses` snapshot from the node.
     */
    fun applyFullSnapshot(
        snapshot: List<UtxoItem>,
        walletAddressSet: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<UtxoItem> = synchronized(lock) {
        pruneExpiredLocked(nowMs)

        val deduped = LinkedHashMap<String, UtxoItem>(snapshot.size)
        for (item in snapshot) {
            if (walletAddressSet.isNotEmpty() && item.address !in walletAddressSet) continue
            deduped[item.outpointKey] = item
        }
        val snapshotKeys = deduped.keys

        // Any pending-spent outpoint that is no longer in the node's UTXO set has been confirmed spent!
        val spentIter = pendingSpentOutpoints.entries.iterator()
        while (spentIter.hasNext()) {
            val (key, _) = spentIter.next()
            if (key !in snapshotKeys) {
                spentIter.remove()
            }
        }

        // Any optimistic Block DAA: 0 change UTXO that now exists in the node's confirmed UTXO set
        // (or was spent locally) is superseded and cleared cleanly!
        val changeIter = pendingChangeUtxos.entries.iterator()
        while (changeIter.hasNext()) {
            val (key, _) = changeIter.next()
            if (key in snapshotKeys || key in pendingSpentOutpoints) {
                changeIter.remove()
            }
        }

        confirmedUtxos.clear()
        for ((key, item) in deduped) {
            if (key !in pendingSpentOutpoints) {
                confirmedUtxos[key] = item
            }
        }

        computeViewLocked()
    }

    /**
     * Authoritative partial refresh for a subset of addresses (mirroring Kaspium's
     * `UtxosNotifier.refresh(addresses)` after `utxosChangedDebouncedProvider`).
     */
    fun applyAddressSubsetSnapshot(
        refreshedAddresses: Set<String>,
        snapshotForAddresses: List<UtxoItem>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<UtxoItem> = synchronized(lock) {
        if (refreshedAddresses.isEmpty()) return computeViewLocked()
        pruneExpiredLocked(nowMs)

        val deduped = LinkedHashMap<String, UtxoItem>(snapshotForAddresses.size)
        for (item in snapshotForAddresses) {
            if (item.address in refreshedAddresses) {
                deduped[item.outpointKey] = item
            }
        }
        val freshKeys = deduped.keys

        // Remove old confirmed UTXOs belonging to refreshedAddresses
        val confIter = confirmedUtxos.entries.iterator()
        while (confIter.hasNext()) {
            val (_, item) = confIter.next()
            if (item.address in refreshedAddresses) {
                confIter.remove()
            }
        }

        // Clear any pendingChangeUtxos for refreshedAddresses that are now confirmed on-node
        val changeIter = pendingChangeUtxos.entries.iterator()
        while (changeIter.hasNext()) {
            val (key, pair) = changeIter.next()
            if (key in freshKeys || key in pendingSpentOutpoints) {
                changeIter.remove()
            } else if (pair.first.address in refreshedAddresses && pair.first.blockDaaScore > 0L) {
                changeIter.remove()
            }
        }

        // Add fresh confirmed entries unless locally pending-spent
        for ((key, item) in deduped) {
            if (key !in pendingSpentOutpoints) {
                confirmedUtxos[key] = item
            }
        }

        computeViewLocked()
    }

    fun currentUtxos(nowMs: Long = System.currentTimeMillis()): List<UtxoItem> = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        computeViewLocked()
    }

    fun pendingSpentCount(): Int = synchronized(lock) { pendingSpentOutpoints.size }
    fun pendingChangeCount(): Int = synchronized(lock) { pendingChangeUtxos.size }

    private fun pruneExpiredLocked(nowMs: Long) {
        val spentIter = pendingSpentOutpoints.entries.iterator()
        while (spentIter.hasNext()) {
            if (nowMs - spentIter.next().value > pendingTtlMs) {
                spentIter.remove()
            }
        }
        val changeIter = pendingChangeUtxos.entries.iterator()
        while (changeIter.hasNext()) {
            if (nowMs - changeIter.next().value.second > pendingTtlMs) {
                changeIter.remove()
            }
        }
    }

    private fun computeViewLocked(): List<UtxoItem> {
        val merged = LinkedHashMap<String, UtxoItem>(confirmedUtxos.size + pendingChangeUtxos.size)
        for ((key, item) in confirmedUtxos) {
            if (key !in pendingSpentOutpoints) {
                merged[key] = item
            }
        }
        for ((key, pair) in pendingChangeUtxos) {
            if (key !in pendingSpentOutpoints && key !in merged) {
                merged[key] = pair.first
            }
        }
        return merged.values.sortedByDescending { it.amountSompi }
    }
}
