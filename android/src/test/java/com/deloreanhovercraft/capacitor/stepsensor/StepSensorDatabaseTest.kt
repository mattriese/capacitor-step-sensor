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

    // --- deleteBefore ---

    @Test
    fun `deleteBefore removes rows with bucket_start before threshold`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:30Z"), 20
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 30
        )

        db.deleteBefore(Instant.parse("2026-01-15T10:00:00Z"))

        val remaining = db.getStepsSince(null)
        assertEquals(2, remaining.size)
        assertEquals("2026-01-15T10:00:00Z", remaining[0].bucketStart)
        assertEquals("2026-01-15T12:00:00Z", remaining[1].bucketStart)
    }

    @Test
    fun `deleteBefore keeps rows at or after threshold`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T10:00:00Z"),
            Instant.parse("2026-01-15T10:00:30Z"), 20
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 30
        )

        db.deleteBefore(Instant.parse("2026-01-15T10:00:00Z"))

        val remaining = db.getStepsSince(null)
        assertEquals(2, remaining.size) // Both rows at/after threshold survive
    }

    @Test
    fun `deleteBefore with no matching rows is a no-op`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 30
        )

        db.deleteBefore(Instant.parse("2026-01-15T10:00:00Z"))

        val remaining = db.getStepsSince(null)
        assertEquals(1, remaining.size)
        assertEquals(30, remaining[0].steps)
    }

    // --- hcMetadata ---

    @Test
    fun `insert with hcMetadata`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        val metadata = """[{"startTime":"2026-01-15T10:00:00Z","count":120}]"""
        db.insertOrUpdate(start, end, 45, metadata)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(metadata, results[0].hcMetadata)
    }

    @Test
    fun `insert without hcMetadata has null`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        db.insertOrUpdate(start, end, 45)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertNull(results[0].hcMetadata)
    }

    @Test
    fun `MAX upsert preserves existing metadata when new is null`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        val metadata = """[{"count":120}]"""

        db.insertOrUpdate(start, end, 30, metadata)
        db.insertOrUpdate(start, end, 45) // higher steps, null metadata

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(45, results[0].steps)
        assertEquals(metadata, results[0].hcMetadata) // preserved
    }

    @Test
    fun `MAX upsert replaces metadata when new is non-null`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        val oldMeta = """[{"count":60}]"""
        val newMeta = """[{"count":120}]"""

        db.insertOrUpdate(start, end, 30, oldMeta)
        db.insertOrUpdate(start, end, 45, newMeta) // higher steps, new metadata

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertEquals(45, results[0].steps)
        assertEquals(newMeta, results[0].hcMetadata)
    }

    @Test
    fun `getStepsSince returns hcMetadata field`() {
        val meta = """[{"startTime":"2026-01-15T09:00:00Z","count":100}]"""
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20, meta
        )

        val results = db.getStepsSince(null)
        assertEquals(2, results.size)
        assertNull(results[0].hcMetadata)     // phone-only bucket
        assertEquals(meta, results[1].hcMetadata) // HC-enriched bucket
    }

    // --- modified_at ---

    @Test
    fun `insert sets modifiedAt`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")
        db.insertOrUpdate(start, end, 45)

        val results = db.getStepsSince(null)
        assertEquals(1, results.size)
        assertNotNull(results[0].modifiedAt)
        // modifiedAt should equal createdAt on first insert
        assertEquals(results[0].createdAt, results[0].modifiedAt)
    }

    @Test
    fun `MAX upsert updates modifiedAt when steps increase`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")

        db.insertOrUpdate(start, end, 30)
        val originalModifiedAt = db.getStepsSince(null)[0].modifiedAt

        // Small delay to ensure timestamp changes
        Thread.sleep(10)

        db.insertOrUpdate(start, end, 45) // higher — should update modifiedAt
        val updatedModifiedAt = db.getStepsSince(null)[0].modifiedAt

        assertTrue(
            "modifiedAt should advance when steps increase",
            updatedModifiedAt > originalModifiedAt
        )
    }

    @Test
    fun `MAX upsert does NOT update modifiedAt when steps do not increase`() {
        val start = Instant.parse("2026-01-15T10:02:00Z")
        val end = Instant.parse("2026-01-15T10:02:30Z")

        db.insertOrUpdate(start, end, 45)
        val originalModifiedAt = db.getStepsSince(null)[0].modifiedAt

        Thread.sleep(10)

        db.insertOrUpdate(start, end, 10) // lower — no-op, modifiedAt unchanged
        val afterLower = db.getStepsSince(null)[0].modifiedAt
        assertEquals(originalModifiedAt, afterLower)

        Thread.sleep(10)

        db.insertOrUpdate(start, end, 45) // equal — no-op, modifiedAt unchanged
        val afterEqual = db.getStepsSince(null)[0].modifiedAt
        assertEquals(originalModifiedAt, afterEqual)
    }

    // --- modifiedSince ---

    @Test
    fun `modifiedSince returns only recently modified rows`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )

        // Capture a syncToken after first insert
        val syncToken = Instant.now()

        Thread.sleep(10)

        // Insert a second row after the token
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )

        val results = db.getStepsSince(null, modifiedSince = syncToken)
        assertEquals(1, results.size)
        assertEquals(20, results[0].steps)
    }

    @Test
    fun `modifiedSince with no changes returns empty`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )

        Thread.sleep(10)
        val syncToken = Instant.now()

        // No new inserts or updates after token
        val results = db.getStepsSince(null, modifiedSince = syncToken)
        assertEquals(0, results.size)
    }

    @Test
    fun `modifiedSince detects backfill updates to old buckets`() {
        // Simulate initial phone data
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:30Z"),
            Instant.parse("2026-01-15T09:01:00Z"), 5
        )

        val syncToken = Instant.now()
        Thread.sleep(10)

        // Simulate backfill updating the first bucket (HC had more steps)
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 25
        )

        val results = db.getStepsSince(null, modifiedSince = syncToken)
        // Only the updated bucket should appear
        assertEquals(1, results.size)
        assertEquals("2026-01-15T09:00:00Z", results[0].bucketStart)
        assertEquals(25, results[0].steps)
    }

    @Test
    fun `modifiedSince combined with since filters both dimensions`() {
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 10
        )
        db.insertOrUpdate(
            Instant.parse("2026-01-15T12:00:00Z"),
            Instant.parse("2026-01-15T12:00:30Z"), 20
        )

        val syncToken = Instant.now()
        Thread.sleep(10)

        // Update the early bucket
        db.insertOrUpdate(
            Instant.parse("2026-01-15T09:00:00Z"),
            Instant.parse("2026-01-15T09:00:30Z"), 30
        )
        // Insert a new late bucket
        db.insertOrUpdate(
            Instant.parse("2026-01-15T15:00:00Z"),
            Instant.parse("2026-01-15T15:00:30Z"), 40
        )

        // since=10:00 filters out 09:00 bucket, modifiedSince filters out 12:00 bucket
        val results = db.getStepsSince(
            Instant.parse("2026-01-15T10:00:00Z"),
            modifiedSince = syncToken
        )
        assertEquals(1, results.size)
        assertEquals("2026-01-15T15:00:00Z", results[0].bucketStart)
        assertEquals(40, results[0].steps)
    }
}
