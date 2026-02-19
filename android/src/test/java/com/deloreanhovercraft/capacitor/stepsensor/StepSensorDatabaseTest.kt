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
