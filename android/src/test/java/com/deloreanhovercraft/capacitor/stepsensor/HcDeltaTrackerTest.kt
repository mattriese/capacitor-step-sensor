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
    fun `new record with old startTime after baseline is baselined at zero`() {
        val tracker = HcDeltaTracker()

        // Baseline with rec-1
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // New record rec-2 appears with startTime (08:00) before lastProcessTime (10:00).
        // This is a pre-existing cumulative record (e.g., Samsung daily aggregate
        // appearing in HC mid-session). Should be baselined, NOT credited.
        val deltas = tracker.computeDeltas(listOf(
            record("rec-1", 1200),
            record("rec-2", 300)
        ))
        // rec-1: delta = 200, rec-2: baselined at 0 (startTime < lastProcessTime)
        assertEquals(200L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `new record with recent startTime after baseline gets full count`() {
        val tracker = HcDeltaTracker()

        // Baseline with rec-1
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // New record with startTime AT lastProcessTime — genuinely new activity
        val recentRecord = HcStepRecord(
            recordId = "rec-new",
            startTime = now,  // startTime == lastProcessTime → not before → full count
            endTime = now.plusSeconds(60),
            count = 150,
            dataOrigin = "com.sec.android.app.shealth"
        )
        val deltas = tracker.computeDeltas(listOf(
            record("rec-1", 1200),
            recentRecord
        ))
        // rec-1: delta = 200, rec-new: full count = 150, total = 350
        assertEquals(350L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `new record with future startTime after baseline gets full count`() {
        val tracker = HcDeltaTracker()

        // Baseline with rec-1
        tracker.computeDeltas(listOf(record("rec-1", 1000)))
        tracker.markProcessed(now)

        // New record with startTime after lastProcessTime — genuinely new
        val futureRecord = HcStepRecord(
            recordId = "rec-future",
            startTime = now.plusSeconds(30),
            endTime = now.plusSeconds(90),
            count = 75,
            dataOrigin = "android"
        )
        val deltas = tracker.computeDeltas(listOf(futureRecord))
        assertEquals(75L, deltas["android"])
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
    fun `known record whose endTime is before lastProcessTime gives zero delta`() {
        val tracker = HcDeltaTracker()

        // A granular record that ended before tracking started (e.g., Garmin 9:40-9:50)
        val preTrackingRecord = HcStepRecord(
            recordId = "garmin-1",
            startTime = Instant.parse("2026-01-15T09:40:00Z"),
            endTime = Instant.parse("2026-01-15T09:50:00Z"),
            count = 500,
            dataOrigin = "com.garmin.connect"
        )

        // Baseline
        tracker.computeDeltas(listOf(preTrackingRecord))
        tracker.markProcessed(now) // lastProcessTime = 10:00

        // Garmin updates the pre-tracking record with more steps
        val updated = preTrackingRecord.copy(count = 520)
        val deltas = tracker.computeDeltas(listOf(updated))

        // endTime (9:50) <= lastProcessTime (10:00) → delta is 0
        assertEquals(0L, deltas["com.garmin.connect"])
    }

    @Test
    fun `known record whose endTime is after lastProcessTime gives normal delta`() {
        val tracker = HcDeltaTracker()

        // A record that spans across the tracking start (e.g., 9:55-10:05)
        val spanningRecord = HcStepRecord(
            recordId = "garmin-2",
            startTime = Instant.parse("2026-01-15T09:55:00Z"),
            endTime = Instant.parse("2026-01-15T10:05:00Z"),
            count = 300,
            dataOrigin = "com.garmin.connect"
        )

        // Baseline
        tracker.computeDeltas(listOf(spanningRecord))
        tracker.markProcessed(now) // lastProcessTime = 10:00

        // Record updated
        val updated = spanningRecord.copy(count = 350)
        val deltas = tracker.computeDeltas(listOf(updated))

        // endTime (10:05) > lastProcessTime (10:00) → normal delta
        assertEquals(50L, deltas["com.garmin.connect"])
    }

    @Test
    fun `samsung daily record always passes endTime check`() {
        val tracker = HcDeltaTracker()

        // Samsung's daily record: 08:00 today to 07:59:59 tomorrow
        // endTime is always far in the future relative to lastProcessTime
        val samsungRecord = record("samsung-daily", 3000)

        // Baseline
        tracker.computeDeltas(listOf(samsungRecord))
        tracker.markProcessed(now) // lastProcessTime = 10:00

        // Samsung updates cumulative count
        val updated = record("samsung-daily", 3050)
        val deltas = tracker.computeDeltas(listOf(updated))

        // endTime (tomorrow 07:59:59) > lastProcessTime (10:00) → normal delta
        assertEquals(50L, deltas["com.sec.android.app.shealth"])
    }

    @Test
    fun `empty records returns empty deltas`() {
        val tracker = HcDeltaTracker()
        val deltas = tracker.computeDeltas(emptyList())
        assertTrue(deltas.isEmpty())
    }
}
