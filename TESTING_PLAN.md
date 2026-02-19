# Test Plan: capacitor-step-sensor Plugin

**Repo**: `~/webdev/capacitor-step-sensor`
**Status**: Plugin is feature-complete (913 lines of Kotlin, iOS stubs, TS defs). Tests are placeholder-only.

## What's worth testing

The plugin has 6 Kotlin files. Three contain real logic worth testing. The others are thin delegation code (alarm receiver, boot receiver, plugin bridge).

| Class                       | Lines | Core Logic                                              | Test Approach                           |
| --------------------------- | ----- | ------------------------------------------------------- | --------------------------------------- |
| `StepSensorDatabase`        | 109   | MAX upsert, time-filtered queries, delete               | Robolectric (needs Android SQLite)      |
| `StepTrackingScheduler`     | 196   | Window merging, past-window skipping                    | Pure JUnit (extract `mergeOverlapping`) |
| `StepCounterService`        | 362   | Sensor delta, reboot detection, floor-to-30s, MAX merge | Pure JUnit (extract pure functions)     |
| `StepSensorPlugin`          | 189   | Permission checks, call delegation                      | Skip — thin wrapper                     |
| `StepTrackingAlarmReceiver` | 36    | Start/stop intent dispatch                              | Skip — trivial                          |
| `BootCompletedReceiver`     | 21    | Calls reregisterFromPersisted                           | Skip — trivial                          |

## Phase 1: Refactor for testability

The core logic is trapped in private methods on classes with heavy Android dependencies. Extract pure functions so they can be tested with plain JUnit (no Robolectric needed for most tests).

### 1a. Extract `StepTrackingLogic.kt` (new file)

Pull the pure functions out of `StepCounterService` and `StepTrackingScheduler`:

```kotlin
// android/src/main/java/com/deloreanhovercraft/capacitor/stepsensor/StepTrackingLogic.kt
package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Instant
import kotlin.math.max

object StepTrackingLogic {

    /**
     * Floor an Instant to the nearest 30-second boundary.
     * e.g., 10:02:47 → 10:02:30, 10:03:00 → 10:03:00
     */
    fun floorTo30Seconds(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        val floored = epochSecond - (epochSecond % 30)
        return Instant.ofEpochSecond(floored)
    }

    /**
     * Compute phone sensor step delta, handling reboot detection.
     * Returns (delta, newBaseline).
     *
     * @param currentCount Latest cumulative sensor value (-1 if no reading yet)
     * @param lastCount Previous cumulative sensor value (-1 if first interval)
     * @return Pair(stepDelta, newLastCount)
     */
    fun computeSensorDelta(currentCount: Long, lastCount: Long): Pair<Long, Long> {
        if (currentCount < 0) return Pair(0L, lastCount)

        val delta = if (lastCount >= 0) {
            if (currentCount >= lastCount) {
                currentCount - lastCount
            } else {
                // Reboot detected — counter reset
                0L
            }
        } else {
            // First reading — establish baseline
            0L
        }

        return Pair(delta, currentCount)
    }

    /**
     * Merge phone sensor delta and Health Connect delta using MAX strategy.
     * Avoids double-counting when both sources record the same steps.
     */
    fun mergeStepSources(phoneDelta: Long, hcDelta: Long): Int {
        return max(phoneDelta, hcDelta).toInt()
    }

    /**
     * Compute the bucket boundaries for a timer tick.
     * The bucket END is the floor of `now`, and START is 30s before that.
     * This means the bucket covers the 30 seconds that just elapsed.
     *
     * @return Pair(bucketStart, bucketEnd)
     */
    fun computeBucketBoundaries(now: Instant): Pair<Instant, Instant> {
        val bucketEnd = floorTo30Seconds(now)
        val bucketStart = bucketEnd.minusSeconds(30)
        return Pair(bucketStart, bucketEnd)
    }

    /**
     * Merge overlapping or adjacent tracking windows.
     * Input must be sorted by startAt.
     * Adjacent windows (one's endAt == another's startAt) are merged.
     */
    fun mergeOverlapping(windows: List<TrackingWindow>): List<TrackingWindow> {
        if (windows.isEmpty()) return emptyList()

        val result = mutableListOf<TrackingWindow>()
        var current = windows[0]

        for (i in 1 until windows.size) {
            val next = windows[i]
            if (!next.startAt.isAfter(current.endAt)) {
                // Overlapping or adjacent — merge
                current = TrackingWindow(
                    startAt = current.startAt,
                    endAt = if (next.endAt.isAfter(current.endAt)) next.endAt else current.endAt
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    /**
     * Filter windows: skip past windows, keep windows where endAt > now.
     * Sort by startAt.
     */
    fun filterAndSortWindows(windows: List<TrackingWindow>, now: Instant): List<TrackingWindow> {
        return windows
            .filter { it.endAt.isAfter(now) }
            .sortedBy { it.startAt }
    }
}
```

### 1b. Update `StepCounterService.kt`

Replace the private methods with calls to `StepTrackingLogic`:

```kotlin
// In onTimerTick():
val (bucketStart, bucketEnd) = StepTrackingLogic.computeBucketBoundaries(Instant.now())
val steps = StepTrackingLogic.mergeStepSources(phoneDelta, hcDelta)

// In recordPhoneSensorInterval():
val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(latestSensorValue, lastSensorCount)
lastSensorCount = newBaseline
return delta

// Remove private floorTo30Seconds — now on StepTrackingLogic
```

### 1c. Update `StepTrackingScheduler.kt`

Replace the private `mergeOverlapping` with `StepTrackingLogic.mergeOverlapping`:

```kotlin
// In scheduleWindows():
val validWindows = StepTrackingLogic.filterAndSortWindows(windows, now)
val merged = StepTrackingLogic.mergeOverlapping(validWindows)
```

Remove the private `mergeOverlapping` method from the scheduler.

---

## Phase 2: Pure JVM unit tests (no Robolectric)

These tests have zero Android dependencies. They run on the JVM with plain JUnit + Kotlin. Fast, reliable, no emulator needed.

### File: `android/src/test/java/com/deloreanhovercraft/capacitor/stepsensor/StepTrackingLogicTest.kt`

```kotlin
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
```

**Test count: 25 tests**, zero Android dependencies, runs in <1 second.

---

## Phase 3: Robolectric tests for StepSensorDatabase

The database uses `SQLiteOpenHelper` which requires an Android `Context`. Robolectric provides a fake context with real SQLite on JVM — no emulator needed.

### 3a. Add Robolectric dependency to `build.gradle`

```gradle
dependencies {
    // ... existing deps ...
    testImplementation "junit:junit:$junitVersion"
    testImplementation "org.robolectric:robolectric:4.14.1"
    testImplementation "androidx.test:core:1.6.1"
}
```

Also add to `android` block:

```gradle
android {
    // ... existing config ...
    testOptions {
        unitTests {
            includeAndroidResources = true
        }
    }
}
```

### 3b. File: `android/src/test/java/com/deloreanhovercraft/capacitor/stepsensor/StepSensorDatabaseTest.kt`

```kotlin
package com.deloreanhovercraft.capacitor.stepsensor

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepSensorDatabaseTest {

    private lateinit var db: StepSensorDatabase

    @Before
    fun setUp() {
        // Robolectric provides a real Context backed by in-memory SQLite
        db = StepSensorDatabase.getInstance(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // Clear all data between tests
        db.deleteStepsSince(null)
    }

    // --- insertOrUpdate ---

    @Test
    fun `insert new bucket`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        db.insertOrUpdate(start, end, 45)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals("2026-01-15T10:02:00Z", results[0].bucketStart)
        assertEquals("2026-01-15T10:02:30Z", results[0].bucketEnd)
        assertEquals(45, results[0].steps)
    }

    @Test
    fun `MAX upsert - higher value wins`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")

        db.insertOrUpdate(start, end, 30)
        db.insertOrUpdate(start, end, 45)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(45, results[0].steps)
    }

    @Test
    fun `MAX upsert - lower value does NOT overwrite`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")

        db.insertOrUpdate(start, end, 45)
        db.insertOrUpdate(start, end, 10) // Lower — should be ignored

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(45, results[0].steps) // Still 45
    }

    @Test
    fun `MAX upsert - equal value is a no-op`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")

        db.insertOrUpdate(start, end, 45)
        db.insertOrUpdate(start, end, 45)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(45, results[0].steps)
    }

    @Test
    fun `multiple distinct buckets`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:02:00Z"),
            Instant.parse("2026-01-15T10:02:30Z"), 45
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:02:30Z"),
            Instant.parse("2026-01-15T10:03:00Z"), 30
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:03:00Z"),
            Instant.parse("2026-01-15T10:03:30Z"), 12
        )

        val results = db.getStepsSince(null)
        assertEquals(3, results.size)
        assertEquals(45, results[0].steps)
        assertEquals(30, results[1].steps)
        assertEquals(12, results[2].steps)
    }

    // --- getStepsSince ---

    @Test
    fun `getStepsSince null returns all rows`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )
        assertEquals(2, db.getStepsSince(null).size)
    }

    @Test
    fun `getStepsSince filters by bucket_start`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )

        val since = Instant.parse("2026-01-15T10:00:00Z")
        val results = db.getStepsSince(since)
        assertEquals(1, results.size)
        assertEquals(20, results[0].steps)
    }

    @Test
    fun `getStepsSince returns results ordered by bucket_start ASC`() {
        // Insert in reverse order
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 30
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )

        val results = db.getStepsSince(null)
        assertEquals("2026-01-15T09:00:00Z", results[0].bucketStart)
        assertEquals("2026-01-15T12:00:00Z", results[1].bucketStart)
    }

    @Test
    fun `getStepsSince with no data returns empty list`() {
        assertEquals(0, db.getStepsSince(null).size)
    }

    // --- deleteStepsSince ---

    @Test
    fun `deleteStepsSince null deletes all rows`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )

        db.deleteStepsSince(null)
        assertEquals(0, db.getStepsSince(null).size)
    }

    @Test
    fun `deleteStepsSince filters by bucket_start`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )

        db.deleteStepsSince(Instant.parse("2026-01-15T10:00:00Z"))

        val remaining = db.getStepsSince(null)
        assertEquals(1, remaining.size)
        assertEquals(10, remaining[0].steps) // 09:00 bucket survived
    }

    @Test
    fun `delete then read pattern (consume)`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:30Z"), 45
        )

        val data = db.getStepsSince(null)
        assertEquals(1, data.size)

        db.deleteStepsSince(null)
        assertEquals(0, db.getStepsSince(null).size)
    }
}
```

**Test count: 12 tests**, requires Robolectric (JVM, no emulator).

---

## Phase 4: Fix iOS test

The existing iOS test calls `implementation.echo(value)` which doesn't exist on `StepSensor`. Replace with a basic sanity test.

### File: `ios/Tests/StepSensorPluginTests/StepSensorTests.swift`

```swift
import XCTest
@testable import StepSensorPlugin

class StepSensorTests: XCTestCase {
    func testPluginInstantiates() {
        // StepSensor is a no-op on iOS — just verify it can be created
        let implementation = StepSensor()
        XCTAssertNotNil(implementation)
    }
}
```

---

## Phase 5: Delete placeholder test

Remove the template `ExampleUnitTest.java` that tests `2 + 2 == 4`.

### Delete: `android/src/test/java/com/getcapacitor/ExampleUnitTest.java`

### Delete: `android/src/androidTest/java/com/getcapacitor/android/ExampleInstrumentedTest.java`

---

## Summary

| Phase     | What                                              | Test Count        | Dependencies | Run Time |
| --------- | ------------------------------------------------- | ----------------- | ------------ | -------- |
| 1         | Refactor: extract `StepTrackingLogic.kt`          | 0 (refactor only) | None         | —        |
| 2         | Pure JVM tests: logic, merge, delta, floor        | 25                | JUnit only   | <1s      |
| 3         | Robolectric tests: database upsert, query, delete | 12                | Robolectric  | ~5s      |
| 4         | Fix iOS test                                      | 1                 | XCTest       | <1s      |
| 5         | Delete placeholders                               | 0 (cleanup)       | —            | —        |
| **Total** |                                                   | **38 tests**      |              |          |

### What these tests catch

- Window merging bugs → service starts/stops at wrong times
- MAX upsert failures → step data lost or double-counted
- Sensor delta miscalculation → inaccurate step counts
- Reboot detection failure → phantom steps after reboot
- Bucket boundary misalignment → steps attributed to wrong 30s window
- Database query/delete filtering bugs → consume pattern breaks

### What these tests DON'T cover (and shouldn't)

- Android service lifecycle (start/stop/bind) — framework behavior, not our logic
- Alarm delivery timing — OS-level, not testable in unit tests
- Health Connect API responses — requires real device or HC test harness
- Notification display — visual, not logic
- Permission grant/deny flow — Capacitor bridge behavior

### Running the tests

```bash
# From ~/webdev/capacitor-step-sensor

# Phase 2: Pure JVM tests (fast)
cd android && ./gradlew test --tests "com.deloreanhovercraft.capacitor.stepsensor.StepTrackingLogicTest"

# Phase 3: Robolectric database tests
cd android && ./gradlew test --tests "com.deloreanhovercraft.capacitor.stepsensor.StepSensorDatabaseTest"

# All Android tests
cd android && ./gradlew test

# iOS test
cd ios && xcodebuild test -scheme CapacitorStepSensor -destination 'platform=iOS Simulator,name=iPhone 16'

# Full verify (npm script)
npm run verify
```
