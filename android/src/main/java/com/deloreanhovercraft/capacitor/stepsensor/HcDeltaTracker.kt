package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Instant
import kotlin.math.max

/**
 * Stateful per-origin, per-record delta tracking for Health Connect step records.
 *
 * On service start (or first HC data), baselines are established (delta=0).
 * Subsequent updates compute delta = newCount - lastCount per record ID.
 * New records appearing after baseline get their full count as delta.
 *
 * In-memory only — resets on service restart, which is correct because
 * a fresh service has no prior HC state to delta against.
 */
class HcDeltaTracker {

    // origin -> (recordId -> lastSeenCount)
    private val knownRecordCounts = mutableMapOf<String, MutableMap<String, Long>>()

    // When we last processed HC data with a non-zero delta
    var lastProcessTime: Instant? = null
        private set

    /**
     * Compute deltas from HC records, grouped by origin.
     *
     * First call (lastProcessTime == null): establishes baselines, all deltas = 0.
     * Subsequent calls:
     *   - Known record ID: delta = max(0, newCount - oldCount)
     *   - New record ID (after baseline): delta = record.count (full count)
     *   - Always updates knownRecordCounts
     *
     * @return Map of dataOrigin -> total delta for that origin
     */
    fun computeDeltas(records: List<HcStepRecord>): Map<String, Long> {
        val isBaseline = lastProcessTime == null
        val deltas = mutableMapOf<String, Long>()

        for (record in records) {
            val originMap = knownRecordCounts.getOrPut(record.dataOrigin) { mutableMapOf() }
            val previousCount = originMap[record.recordId]

            val delta = if (isBaseline) {
                // First call: baseline establishment, delta = 0
                0L
            } else if (previousCount != null) {
                // Known record: compute delta, clamp to 0
                max(0L, record.count - previousCount)
            } else {
                // New record appearing after baseline: full count is new
                record.count
            }

            originMap[record.recordId] = record.count
            deltas[record.dataOrigin] = (deltas[record.dataOrigin] ?: 0L) + delta
        }

        return deltas
    }

    /**
     * Advance lastProcessTime. Call ONLY when deltas were actually processed
     * (not on empty ticks or baseline establishment).
     */
    fun markProcessed(now: Instant) {
        lastProcessTime = now
    }
}
