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
}
