package com.deloreanhovercraft.capacitor.stepsensor

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StepTrackingLogicTest {

    // --- floorTo30Seconds ---

    @Test
    fun `floorTo30Seconds - exact 30s boundary is unchanged`() {
        val input = Instant.parse("2026-01-15T10:02:30Z")
        assertEquals(input, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - exact minute boundary is unchanged`() {
        val input = Instant.parse("2026-01-15T10:03:00Z")
        assertEquals(input, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - mid-bucket floors down`() {
        val input = Instant.parse("2026-01-15T10:02:47Z")
        val expected = Instant.parse("2026-01-15T10:02:30Z")
        assertEquals(expected, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - 1 second past boundary floors down`() {
        val input = Instant.parse("2026-01-15T10:02:31Z")
        val expected = Instant.parse("2026-01-15T10:02:30Z")
        assertEquals(expected, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - 29 seconds past minute floors to minute`() {
        val input = Instant.parse("2026-01-15T10:02:29Z")
        val expected = Instant.parse("2026-01-15T10:02:00Z")
        assertEquals(expected, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - midnight`() {
        val input = Instant.parse("2026-01-15T00:00:00Z")
        assertEquals(input, StepTrackingLogic.floorTo30Seconds(input))
    }

    @Test
    fun `floorTo30Seconds - epoch zero`() {
        val input = Instant.EPOCH
        assertEquals(input, StepTrackingLogic.floorTo30Seconds(input))
    }

    // --- computeSensorDelta ---

    @Test
    fun `computeSensorDelta - first reading establishes baseline with zero delta`() {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(
            currentCount = 5000, lastCount = -1
        )
        assertEquals(0L, delta)
        assertEquals(5000L, newBaseline)
    }

    @Test
    fun `computeSensorDelta - normal increment`() {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(
            currentCount = 5045, lastCount = 5000
        )
        assertEquals(45L, delta)
        assertEquals(5045L, newBaseline)
    }

    @Test
    fun `computeSensorDelta - no change`() {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(
            currentCount = 5000, lastCount = 5000
        )
        assertEquals(0L, delta)
        assertEquals(5000L, newBaseline)
    }

    @Test
    fun `computeSensorDelta - reboot detected (counter reset)`() {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(
            currentCount = 100, lastCount = 5000
        )
        // Reboot: delta is 0 (steps during reboot are lost), baseline resets
        assertEquals(0L, delta)
        assertEquals(100L, newBaseline)
    }

    @Test
    fun `computeSensorDelta - no sensor reading yet`() {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(
            currentCount = -1, lastCount = -1
        )
        assertEquals(0L, delta)
        assertEquals(-1L, newBaseline)
    }

    @Test
    fun `computeSensorDelta - large delta`() {
        val (delta, _) = StepTrackingLogic.computeSensorDelta(
            currentCount = 100_000, lastCount = 50_000
        )
        assertEquals(50_000L, delta)
    }

    // --- mergeStepSources ---

    @Test
    fun `mergeStepSources - phone only (HC hasn't synced)`() {
        assertEquals(45, StepTrackingLogic.mergeStepSources(45, 0))
    }

    @Test
    fun `mergeStepSources - both same (no double-count)`() {
        assertEquals(45, StepTrackingLogic.mergeStepSources(45, 45))
    }

    @Test
    fun `mergeStepSources - HC only (phone on desk)`() {
        assertEquals(45, StepTrackingLogic.mergeStepSources(0, 45))
    }

    @Test
    fun `mergeStepSources - HC slightly higher (watch counted more)`() {
        assertEquals(50, StepTrackingLogic.mergeStepSources(45, 50))
    }

    @Test
    fun `mergeStepSources - both zero`() {
        assertEquals(0, StepTrackingLogic.mergeStepSources(0, 0))
    }

    // --- computeBucketBoundaries ---

    @Test
    fun `computeBucketBoundaries - at exact boundary`() {
        val now = Instant.parse("2026-01-15T10:03:00Z")
        val (start, end) = StepTrackingLogic.computeBucketBoundaries(now)
        assertEquals(Instant.parse("2026-01-15T10:02:30Z"), start)
        assertEquals(Instant.parse("2026-01-15T10:03:00Z"), end)
    }

    @Test
    fun `computeBucketBoundaries - mid-bucket`() {
        val now = Instant.parse("2026-01-15T10:02:47Z")
        val (start, end) = StepTrackingLogic.computeBucketBoundaries(now)
        // Floor of 10:02:47 = 10:02:30, minus 30s = 10:02:00
        assertEquals(Instant.parse("2026-01-15T10:02:00Z"), start)
        assertEquals(Instant.parse("2026-01-15T10:02:30Z"), end)
    }

    // --- mergeOverlapping ---

    @Test
    fun `mergeOverlapping - empty list`() {
        assertEquals(emptyList<TrackingWindow>(), StepTrackingLogic.mergeOverlapping(emptyList()))
    }

    @Test
    fun `mergeOverlapping - single window`() {
        val window = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(window))
        assertEquals(1, result.size)
        assertEquals(window, result[0])
    }

    @Test
    fun `mergeOverlapping - non-overlapping windows stay separate`() {
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T14:00:00Z"),
            Instant.parse("2026-01-15T16:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(w1, w2))
        assertEquals(2, result.size)
        assertEquals(w1, result[0])
        assertEquals(w2, result[1])
    }

    @Test
    fun `mergeOverlapping - overlapping windows merge`() {
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:30:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T12:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(w1, w2))
        assertEquals(1, result.size)
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), result[0].startAt)
        assertEquals(Instant.parse("2026-01-15T12:00:00Z"), result[0].endAt)
    }

    @Test
    fun `mergeOverlapping - adjacent windows merge (endAt == startAt)`() {
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T12:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(w1, w2))
        assertEquals(1, result.size)
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), result[0].startAt)
        assertEquals(Instant.parse("2026-01-15T12:00:00Z"), result[0].endAt)
    }

    @Test
    fun `mergeOverlapping - window fully contained in another`() {
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T17:00:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T12:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(w1, w2))
        assertEquals(1, result.size)
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), result[0].startAt)
        assertEquals(Instant.parse("2026-01-15T17:00:00Z"), result[0].endAt)
    }

    @Test
    fun `mergeOverlapping - three windows, first two overlap, third separate`() {
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:30:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T11:00:00Z")
        )
        val w3 = TrackingWindow(
            Instant.parse("2026-01-15T14:00:00Z"),
            Instant.parse("2026-01-15T16:00:00Z")
        )
        val result = StepTrackingLogic.mergeOverlapping(listOf(w1, w2, w3))
        assertEquals(2, result.size)
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), result[0].startAt)
        assertEquals(Instant.parse("2026-01-15T11:00:00Z"), result[0].endAt)
        assertEquals(w3, result[1])
    }

    @Test
    fun `mergeOverlapping - chain of overlapping windows all merge into one`() {
        val windows = listOf(
            TrackingWindow(Instant.parse("2026-01-15T09:00:00Z"), Instant.parse("2026-01-15T10:00:00Z")),
            TrackingWindow(Instant.parse("2026-01-15T09:30:00Z"), Instant.parse("2026-01-15T11:00:00Z")),
            TrackingWindow(Instant.parse("2026-01-15T10:30:00Z"), Instant.parse("2026-01-15T12:00:00Z")),
            TrackingWindow(Instant.parse("2026-01-15T11:30:00Z"), Instant.parse("2026-01-15T13:00:00Z")),
        )
        val result = StepTrackingLogic.mergeOverlapping(windows)
        assertEquals(1, result.size)
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), result[0].startAt)
        assertEquals(Instant.parse("2026-01-15T13:00:00Z"), result[0].endAt)
    }

    // --- filterAndSortWindows ---

    @Test
    fun `filterAndSortWindows - skips fully past windows`() {
        val now = Instant.parse("2026-01-15T12:00:00Z")
        val past = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val future = TrackingWindow(
            Instant.parse("2026-01-15T14:00:00Z"),
            Instant.parse("2026-01-15T16:00:00Z")
        )
        val result = StepTrackingLogic.filterAndSortWindows(listOf(past, future), now)
        assertEquals(1, result.size)
        assertEquals(future, result[0])
    }

    @Test
    fun `filterAndSortWindows - keeps window where startAt is past but endAt is future`() {
        val now = Instant.parse("2026-01-15T10:30:00Z")
        val window = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T12:00:00Z")
        )
        val result = StepTrackingLogic.filterAndSortWindows(listOf(window), now)
        assertEquals(1, result.size)
        assertEquals(window, result[0])
    }

    @Test
    fun `filterAndSortWindows - sorts by startAt`() {
        val now = Instant.parse("2026-01-15T08:00:00Z")
        val w1 = TrackingWindow(
            Instant.parse("2026-01-15T14:00:00Z"),
            Instant.parse("2026-01-15T16:00:00Z")
        )
        val w2 = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val result = StepTrackingLogic.filterAndSortWindows(listOf(w1, w2), now)
        assertEquals(2, result.size)
        assertEquals(w2, result[0]) // 09:00 first
        assertEquals(w1, result[1]) // 14:00 second
    }

    // --- findActiveWindow ---

    @Test
    fun `findActiveWindow - now is inside window`() {
        val w = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T12:00:00Z")
        )
        val now = Instant.parse("2026-01-15T10:30:00Z")
        assertEquals(w, StepTrackingLogic.findActiveWindow(listOf(w), now))
    }

    @Test
    fun `findActiveWindow - no match returns null`() {
        val w = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val now = Instant.parse("2026-01-15T11:00:00Z")
        assertNull(StepTrackingLogic.findActiveWindow(listOf(w), now))
    }

    @Test
    fun `findActiveWindow - exactly at startAt is inclusive`() {
        val w = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val now = Instant.parse("2026-01-15T09:00:00Z")
        assertEquals(w, StepTrackingLogic.findActiveWindow(listOf(w), now))
    }

    @Test
    fun `findActiveWindow - exactly at endAt is exclusive`() {
        val w = TrackingWindow(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T10:00:00Z")
        )
        val now = Instant.parse("2026-01-15T10:00:00Z")
        assertNull(StepTrackingLogic.findActiveWindow(listOf(w), now))
    }

    // --- subtractAndFill ---

    // Helper to create commitment window covering a large range (no boundary clamping)
    private val wideCommitmentStart = Instant.parse("2026-01-15T00:00:00Z")
    private val wideCommitmentEnd = Instant.parse("2026-01-16T00:00:00Z")

    @Test
    fun `subtractAndFill - phone on desk (all zeros) distributes evenly`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 0,
            Instant.parse("2026-01-15T10:00:30Z") to 0,
            Instant.parse("2026-01-15T10:01:00Z") to 0,
            Instant.parse("2026-01-15T10:01:30Z") to 0
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:02:00Z"),
            120, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(4, result.size)
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:30Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:01:00Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:01:30Z")])
    }

    @Test
    fun `subtractAndFill - mixed zero and non-zero fills only zeros`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 10,
            Instant.parse("2026-01-15T10:00:30Z") to 10,
            Instant.parse("2026-01-15T10:01:00Z") to 0,
            Instant.parse("2026-01-15T10:01:30Z") to 0
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:02:00Z"),
            120, existing, wideCommitmentStart, wideCommitmentEnd
        )
        // surplus = 120 - 20 = 100, distributed into 2 zero buckets
        assertEquals(2, result.size)
        assertEquals(50, result[Instant.parse("2026-01-15T10:01:00Z")])
        assertEquals(50, result[Instant.parse("2026-01-15T10:01:30Z")])
    }

    @Test
    fun `subtractAndFill - phone equals HC (no surplus)`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 30,
            Instant.parse("2026-01-15T10:00:30Z") to 30,
            Instant.parse("2026-01-15T10:01:00Z") to 30,
            Instant.parse("2026-01-15T10:01:30Z") to 30
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:02:00Z"),
            120, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subtractAndFill - phone greater than HC (clamped to 0)`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 40,
            Instant.parse("2026-01-15T10:00:30Z") to 40,
            Instant.parse("2026-01-15T10:01:00Z") to 40,
            Instant.parse("2026-01-15T10:01:30Z") to 40
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:02:00Z"),
            120, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subtractAndFill - no zero buckets (surplus to last bucket)`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 10,
            Instant.parse("2026-01-15T10:00:30Z") to 10,
            Instant.parse("2026-01-15T10:01:00Z") to 10,
            Instant.parse("2026-01-15T10:01:30Z") to 1
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:02:00Z"),
            130, existing, wideCommitmentStart, wideCommitmentEnd
        )
        // surplus = 130 - 31 = 99, no zero buckets → last bucket gets existing + surplus
        assertEquals(1, result.size)
        assertEquals(1 + 99, result[Instant.parse("2026-01-15T10:01:30Z")])
    }

    @Test
    fun `subtractAndFill - hcCount 0 returns empty`() {
        val existing = mapOf(Instant.parse("2026-01-15T10:00:00Z") to 0)
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:30Z"),
            0, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subtractAndFill - start equals end returns empty`() {
        val t = Instant.parse("2026-01-15T10:00:00Z")
        val result = StepTrackingLogic.subtractAndFill(
            t, t, 100, emptyMap(), wideCommitmentStart, wideCommitmentEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subtractAndFill - single bucket`() {
        val existing = mapOf(Instant.parse("2026-01-15T10:00:00Z") to 5)
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:30Z"),
            10, existing, wideCommitmentStart, wideCommitmentEnd
        )
        // surplus = 10 - 5 = 5, no zero buckets → last (only) bucket gets 5 + 5 = 10
        assertEquals(1, result.size)
        assertEquals(10, result[Instant.parse("2026-01-15T10:00:00Z")])
    }

    @Test
    fun `subtractAndFill - cap enforcement limits each zero bucket to 90`() {
        // 2 zero buckets, surplus = 160 → each capped at 90 would be needed if over capacity
        // But 160 / 2 = 80 each, which is under cap
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 0,
            Instant.parse("2026-01-15T10:00:30Z") to 0
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:01:00Z"),
            160, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(80, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(80, result[Instant.parse("2026-01-15T10:00:30Z")])
    }

    @Test
    fun `subtractAndFill - cap overflow redistributes to last bucket`() {
        // 2 zero buckets, surplus = 200 → capacity = 2*90 = 180, leftover = 20
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 0,
            Instant.parse("2026-01-15T10:00:30Z") to 0
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:01:00Z"),
            200, existing, wideCommitmentStart, wideCommitmentEnd
        )
        // Each zero bucket gets 90, leftover 20 to last in-window bucket (which is 10:00:30)
        assertEquals(90, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(90 + 20, result[Instant.parse("2026-01-15T10:00:30Z")])
    }

    @Test
    fun `subtractAndFill - start boundary excludes pre-commitment buckets`() {
        // HC record spans 09:59:00–10:01:00, commitment starts at 10:00:00
        // Only buckets at 10:00:00 and 10:00:30 are in-window
        val existing = mapOf(
            Instant.parse("2026-01-15T09:59:00Z") to 0,
            Instant.parse("2026-01-15T09:59:30Z") to 0,
            Instant.parse("2026-01-15T10:00:00Z") to 0,
            Instant.parse("2026-01-15T10:00:30Z") to 0
        )
        val commitStart = Instant.parse("2026-01-15T10:00:00Z")
        val commitEnd = Instant.parse("2026-01-15T12:00:00Z")
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T09:59:00Z"),
            Instant.parse("2026-01-15T10:01:00Z"),
            120, existing, commitStart, commitEnd
        )
        // Full inclusion: all 120 steps credited, only 2 in-window buckets
        assertEquals(2, result.size)
        assertEquals(60, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(60, result[Instant.parse("2026-01-15T10:00:30Z")])
        assertNull(result[Instant.parse("2026-01-15T09:59:00Z")])
        assertNull(result[Instant.parse("2026-01-15T09:59:30Z")])
    }

    @Test
    fun `subtractAndFill - end boundary excludes post-commitment buckets`() {
        // HC record spans 11:59:00–12:01:00, commitment ends at 12:00:00
        // Only buckets at 11:59:00 and 11:59:30 are in-window
        val existing = mapOf(
            Instant.parse("2026-01-15T11:59:00Z") to 0,
            Instant.parse("2026-01-15T11:59:30Z") to 0,
            Instant.parse("2026-01-15T12:00:00Z") to 0,
            Instant.parse("2026-01-15T12:00:30Z") to 0
        )
        val commitStart = Instant.parse("2026-01-15T09:00:00Z")
        val commitEnd = Instant.parse("2026-01-15T12:00:00Z")
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T11:59:00Z"),
            Instant.parse("2026-01-15T12:01:00Z"),
            100, existing, commitStart, commitEnd
        )
        assertEquals(2, result.size)
        assertEquals(50, result[Instant.parse("2026-01-15T11:59:00Z")])
        assertEquals(50, result[Instant.parse("2026-01-15T11:59:30Z")])
        assertNull(result[Instant.parse("2026-01-15T12:00:00Z")])
    }

    @Test
    fun `subtractAndFill - HC entirely outside commitment window`() {
        val existing = mapOf(
            Instant.parse("2026-01-15T08:00:00Z") to 0,
            Instant.parse("2026-01-15T08:00:30Z") to 0
        )
        val commitStart = Instant.parse("2026-01-15T10:00:00Z")
        val commitEnd = Instant.parse("2026-01-15T12:00:00Z")
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T08:00:00Z"),
            Instant.parse("2026-01-15T08:01:00Z"),
            100, existing, commitStart, commitEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subtractAndFill - remainder distribution gives extra to first buckets`() {
        // 3 zero buckets, surplus = 10 → 10/3 = 3 each, remainder 1 to first bucket
        val existing = mapOf(
            Instant.parse("2026-01-15T10:00:00Z") to 0,
            Instant.parse("2026-01-15T10:00:30Z") to 0,
            Instant.parse("2026-01-15T10:01:00Z") to 0
        )
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:01:30Z"),
            10, existing, wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(3, result.size)
        // 10 / 3 = 3 remainder 1 → first bucket gets 4
        assertEquals(4, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(3, result[Instant.parse("2026-01-15T10:00:30Z")])
        assertEquals(3, result[Instant.parse("2026-01-15T10:01:00Z")])
    }

    @Test
    fun `subtractAndFill - existing buckets not in map treated as zero`() {
        // existingBuckets doesn't have entries for these buckets (service just started)
        val result = StepTrackingLogic.subtractAndFill(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:01:00Z"),
            60, emptyMap(), wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(2, result.size)
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:30Z")])
    }

    // --- processHcRecords ---

    @Test
    fun `processHcRecords - empty records returns empty`() {
        val result = StepTrackingLogic.processHcRecords(
            emptyList(), emptyMap(),
            wideCommitmentStart, wideCommitmentEnd
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `processHcRecords - single source distributes correctly`() {
        val records = listOf(
            HcStepRecord(
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T10:01:00Z"),
                60, "com.sec.android.app.shealth"
            )
        )
        val result = StepTrackingLogic.processHcRecords(
            records, emptyMap(), wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:30Z")])
    }

    @Test
    fun `processHcRecords - two sources takes MAX per bucket`() {
        val records = listOf(
            HcStepRecord(
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T10:00:30Z"),
                50, "com.sec.android.app.shealth"
            ),
            HcStepRecord(
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T10:00:30Z"),
                30, "com.google.android.apps.fitness"
            )
        )
        val result = StepTrackingLogic.processHcRecords(
            records, emptyMap(), wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(1, result.size)
        assertEquals(50, result[Instant.parse("2026-01-15T10:00:00Z")])
    }

    @Test
    fun `processHcRecords - adjacent records from same source`() {
        val records = listOf(
            HcStepRecord(
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T10:01:00Z"),
                60, "com.sec.android.app.shealth"
            ),
            HcStepRecord(
                Instant.parse("2026-01-15T10:01:00Z"),
                Instant.parse("2026-01-15T10:02:00Z"),
                55, "com.sec.android.app.shealth"
            )
        )
        val result = StepTrackingLogic.processHcRecords(
            records, emptyMap(), wideCommitmentStart, wideCommitmentEnd
        )
        assertEquals(4, result.size)
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:00Z")])
        assertEquals(30, result[Instant.parse("2026-01-15T10:00:30Z")])
        // Second record: 55/2 = 27 remainder 1 → first gets 28
        assertEquals(28, result[Instant.parse("2026-01-15T10:01:00Z")])
        assertEquals(27, result[Instant.parse("2026-01-15T10:01:30Z")])
    }
}
