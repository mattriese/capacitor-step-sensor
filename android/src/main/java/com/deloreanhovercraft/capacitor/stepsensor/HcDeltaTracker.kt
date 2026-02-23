package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Instant
import kotlin.math.max

/**
 * Stateful per-origin, per-record delta tracking for Health Connect step records.
 *
 * On service start (or first HC data), baselines are established (delta=0).
 * Subsequent updates compute delta = newCount - lastCount per record ID.
 * New records appearing after baseline: if their startTime predates
 * lastProcessTime, they are pre-existing cumulative records (e.g., Samsung's
 * daily aggregate appearing in HC mid-session) and are baselined at delta=0.
 * Only records whose startTime is at or after lastProcessTime get their full
 * count credited as new activity.
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
     *   - Known record ID whose endTime > lastProcessTime: delta = max(0, newCount - oldCount)
     *   - Known record ID whose endTime <= lastProcessTime: delta = 0 (pre-tracking update)
     *   - New record ID whose startTime >= lastProcessTime: delta = record.count
     *   - New record ID whose startTime < lastProcessTime: delta = 0 (baseline)
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
                // Known record: only credit delta if the record's time range
                // extends into our tracking window. If endTime <= lastProcessTime,
                // the entire record covers pre-tracking time — any count increase
                // represents steps walked before our window (e.g., Garmin updating
                // a 9:40-9:50 record after commitment started at 10:00).
                if (!record.endTime.isAfter(lastProcessTime)) {
                    0L
                } else {
                    max(0L, record.count - previousCount)
                }
            } else {
                // New record after baseline: if its span started before our
                // tracking window, it's a pre-existing cumulative record
                // (e.g., Samsung daily aggregate appearing in HC mid-session).
                // Baseline it at 0 to avoid a massive spike.
                if (record.startTime.isBefore(lastProcessTime)) {
                    0L
                } else {
                    record.count
                }
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
