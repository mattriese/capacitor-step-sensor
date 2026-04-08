package com.deloreanhovercraft.capacitor.stepsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StepIntervalNotificationLogicTest {

    @Test
    fun `computeReminder schedules from last qualifying step bout`() {
        val config = StepIntervalNotificationConfig(
            commitmentId = "commitment-1",
            taskName = "__fitness_steps__",
            maxOrMin = "max",
            intervalInMinutes = 120,
            completionMetric = 100,
            completionMetricType = "quantity",
            timePeriodStartAt = Instant.parse("2026-01-15T08:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-01-15T18:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )

        val reminder = StepIntervalNotificationLogic.computeReminder(
            config,
            buckets = listOf(
                StepBucket(
                    id = 1,
                    bucketStart = "2026-01-15T09:00:00Z",
                    bucketEnd = "2026-01-15T09:00:30Z",
                    steps = 60,
                    createdAt = "2026-01-15T09:00:30Z",
                    modifiedAt = "2026-01-15T09:00:30Z",
                    hcMetadata = null
                ),
                StepBucket(
                    id = 2,
                    bucketStart = "2026-01-15T09:00:30Z",
                    bucketEnd = "2026-01-15T09:01:00Z",
                    steps = 55,
                    createdAt = "2026-01-15T09:01:00Z",
                    modifiedAt = "2026-01-15T09:01:00Z",
                    hcMetadata = null
                )
            ),
            now = Instant.parse("2026-01-15T09:20:00Z")
        )

        assertNotNull(reminder)
        assertEquals("2026-01-15T10:51:00Z", reminder?.scheduleAt.toString())
        assertEquals("2026-01-15T11:01:00Z", reminder?.dueAt.toString())
        assertEquals(
            "10m warning: do 100 steps before the 120m interval ends.",
            reminder?.body
        )
    }

    @Test
    fun `computeReminder returns null for min interval commitments`() {
        val config = StepIntervalNotificationConfig(
            commitmentId = "commitment-2",
            taskName = "__fitness_steps__",
            maxOrMin = "min",
            intervalInMinutes = 120,
            completionMetric = 100,
            completionMetricType = "quantity",
            timePeriodStartAt = Instant.parse("2026-01-15T08:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-01-15T18:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )

        val reminder = StepIntervalNotificationLogic.computeReminder(
            config,
            buckets = emptyList(),
            now = Instant.parse("2026-01-15T09:20:00Z")
        )

        assertNull(reminder)
    }

    @Test
    fun `computeReminder returns null once interval is already due`() {
        val config = StepIntervalNotificationConfig(
            commitmentId = "commitment-3",
            taskName = "__fitness_steps__",
            maxOrMin = "max",
            intervalInMinutes = 120,
            completionMetric = 100,
            completionMetricType = "quantity",
            timePeriodStartAt = Instant.parse("2026-01-15T08:00:00Z"),
            timePeriodEndAt = Instant.parse("2026-01-15T18:00:00Z"),
            reminderLeadMinutes = 10,
            staleAfterMinutes = 15
        )

        val reminder = StepIntervalNotificationLogic.computeReminder(
            config,
            buckets = emptyList(),
            now = Instant.parse("2026-01-15T10:30:00Z")
        )

        assertNull(reminder)
    }
}
