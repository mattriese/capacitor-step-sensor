package com.deloreanhovercraft.capacitor.stepsensor

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HcDeltaTrackerTest {

    private val now = Instant.parse("2026-01-15T10:00:00Z")

    private fun record(
        id: String,
        count: Long,
        origin: String = "com.sec.android.app.shealth"
    ) = HcStepRecord(
        recordId = id,
        startTime = Instant.parse("2026-01-15T08:00:00Z"),
        endTime = Instant.parse("2026-01-16T07:59:59Z"),
        count = count,
        dataOrigin = origin
    )

    @Test
    fun `first call establishes baselines - all deltas are zero`() {
        val tracker = HcDeltaTracker()
        val records = listOf(
            record("rec-1", 1000),
            record("rec-2", 500)
        )
        val deltas = tracker.computeDeltas(records)
        assertEquals(0L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `first call does not advance lastProcessTime`() {
        val tracker = HcDeltaTracker()
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        assertNull(tracker.lastProcessTime)
    }

    @Test
    fun `markProcessed advances lastProcessTime`() {
        val tracker = HcDeltaTracker()
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)
        assertEquals(now, tracker.lastProcessTime)
    }

    @Test
    fun `second call computes correct deltas`() {
        val tracker = HcDeltaTracker()

        // Baseline
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // Update: count went from 1000 to 1200
        val deltas = tracker.computeDeltas(listOf(record("rec-1", 1200)))
        assertEquals(200L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `decrease is clamped to zero`() {
        val tracker = HcDeltaTracker()

        // Baseline
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // Count went down (shouldn't happen but be safe)
        val deltas = tracker.computeDeltas(listOf(record("rec-1", 800)))
        assertEquals(0L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `new record after baseline gets full count`() {
        val tracker = HcDeltaTracker()

        // Baseline with rec-1
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // New record rec-2 appears (never seen before)
        val deltas = tracker.computeDeltas(listOf(
            record("rec-1", 1200),
            record("rec-2", 300)
        ))
        // rec-1: delta = 200, rec-2: full count = 300, total = 500
        assertEquals(500L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `multiple origins are independent`() {
        val tracker = HcDeltaTracker()

        // Baseline for both origins
        tracker.computeDeltas(listOf(
            record("rec-1", 1000, "com.sec.android.app.shealth"),
            record("rec-2", 500, "com.google.android.apps.fitness")
        ))
        tracker.markProcessed(now)

        // Updates
        val deltas = tracker.computeDeltas(listOf(
            record("rec-1", 1300, "com.sec.android.app.shealth"),
            record("rec-2", 600, "com.google.android.apps.fitness")
        ))
        assertEquals(300L, deltas["com.sec.android.app.shealth"])
        assertEquals(100L, deltas["com.google.android.apps.fitness"])
    }

    @Test
    fun `multiple records same origin sum correctly`() {
        val tracker = HcDeltaTracker()

        // Baseline: two records from same origin
        tracker.computeDeltas(listOf(
            record("rec-1", 500),
            record("rec-2", 300)
        ))
        tracker.markProcessed(now)

        // Both update
        val deltas = tracker.computeDeltas(listOf(
            record("rec-1", 700),
            record("rec-2", 400)
        ))
        // rec-1: +200, rec-2: +100, total = 300
        assertEquals(300L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `no change in count gives zero delta`() {
        val tracker = HcDeltaTracker()

        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        val deltas = tracker.computeDeltas(listOf(record("rec-1", 1000)))
        assertEquals(0L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `lastProcessTime only advances via markProcessed`() {
        val tracker = HcDeltaTracker()

        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        val later = now.plusSeconds(30)
        // computeDeltas does NOT advance lastProcessTime
        tracker.computeDeltas(listOf(record("rec-1", 1200)))
        assertEquals(now, tracker.lastProcessTime)

        // Only markProcessed advances it
        tracker.markProcessed(later)
        assertEquals(later, tracker.lastProcessTime)
    }

    @Test
    fun `empty records returns empty deltas`() {
        val tracker = HcDeltaTracker()
        val deltas = tracker.computeDeltas(emptyList())
        assertTrue(deltas.isEmpty())
    }
}
