package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class StepIntervalNotificationConfig(
    val commitmentId: String,
    val taskName: String,
    val maxOrMin: String,
    val intervalInMinutes: Int,
    val completionMetric: Int,
    val completionMetricType: String,
    val timePeriodStartAt: Instant,
    val timePeriodEndAt: Instant,
    val reminderLeadMinutes: Int,
    val staleAfterMinutes: Int
)

data class ScheduledStepIntervalReminder(
    val commitmentId: String,
    val title: String,
    val body: String,
    val scheduleAt: Instant,
    val dueAt: Instant
)

private data class StepBoutRange(
    val boutStart: Instant,
    val boutEnd: Instant,
    val totalSteps: Int
)

object StepIntervalNotificationLogic {
    private const val STEP_BOUT_BREAK_SECONDS = 90L

    fun computeReminder(
        config: StepIntervalNotificationConfig,
        buckets: List<StepBucket>,
        now: Instant
    ): ScheduledStepIntervalReminder? {
        if (config.maxOrMin != "max") {
            return null
        }
        if (now.isBefore(config.timePeriodStartAt) || !now.isBefore(config.timePeriodEndAt)) {
            return null
        }

        val relevantBuckets = buckets
            .asSequence()
            .filter {
                val bucketStart = Instant.parse(it.bucketStart)
                val bucketEnd = Instant.parse(it.bucketEnd)
                !bucketStart.isBefore(config.timePeriodStartAt) && !bucketEnd.isAfter(now)
            }
            .sortedBy { Instant.parse(it.bucketStart) }
            .toList()

        val lastQualifyingBoutEnd = computeQualifyingBouts(
            relevantBuckets,
            config.completionMetric
        ).lastOrNull()?.boutEnd

        val referenceTime = lastQualifyingBoutEnd ?: config.timePeriodStartAt
        val dueAt = referenceTime.plus(Duration.ofMinutes(config.intervalInMinutes.toLong()))
        if (!dueAt.isAfter(now)) {
            return null
        }

        val rawScheduleAt = dueAt.minus(Duration.ofMinutes(config.reminderLeadMinutes.toLong()))
        val scheduleAt = if (!rawScheduleAt.isAfter(now)) {
            floorToMinute(now)
        } else {
            floorToMinute(rawScheduleAt)
        }

        return ScheduledStepIntervalReminder(
            commitmentId = config.commitmentId,
            title = "Interval Reminder",
            body = buildBody(config),
            scheduleAt = scheduleAt,
            dueAt = dueAt
        )
    }

    private fun computeQualifyingBouts(
        buckets: List<StepBucket>,
        requiredSteps: Int
    ): List<StepBoutRange> {
        val bouts = mutableListOf<StepBoutRange>()
        var currentBoutStart: Instant? = null
        var currentBoutEnd: Instant? = null
        var currentBoutSteps = 0

        for (bucket in buckets) {
            if (bucket.steps <= 0) {
                continue
            }

            val bucketStart = Instant.parse(bucket.bucketStart)
            val bucketEnd = Instant.parse(bucket.bucketEnd)

            if (currentBoutStart == null || currentBoutEnd == null) {
                currentBoutStart = bucketStart
                currentBoutEnd = bucketEnd
                currentBoutSteps = bucket.steps
                continue
            }

            val inactivitySeconds = Duration.between(currentBoutEnd, bucketStart).seconds
            if (inactivitySeconds >= STEP_BOUT_BREAK_SECONDS) {
                bouts.add(
                    StepBoutRange(
                        boutStart = currentBoutStart,
                        boutEnd = currentBoutEnd,
                        totalSteps = currentBoutSteps
                    )
                )
                currentBoutStart = bucketStart
                currentBoutEnd = bucketEnd
                currentBoutSteps = bucket.steps
                continue
            }

            currentBoutEnd = bucketEnd
            currentBoutSteps += bucket.steps
        }

        if (currentBoutStart != null && currentBoutEnd != null) {
            bouts.add(
                StepBoutRange(
                    boutStart = currentBoutStart,
                    boutEnd = currentBoutEnd,
                    totalSteps = currentBoutSteps
                )
            )
        }

        return bouts.filter { it.totalSteps >= requiredSteps }
    }

    private fun floorToMinute(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        return Instant.ofEpochSecond(epochSecond - (epochSecond % 60))
    }

    private fun buildBody(config: StepIntervalNotificationConfig): String {
        val lead = config.reminderLeadMinutes
        val interval = config.intervalInMinutes
        val actionText = when (config.completionMetricType) {
            "quantity" -> "do ${config.completionMetric} steps"
            "seconds" -> {
                val minutes = config.completionMetric / 60.0
                val roundedMinutes = minutes.roundToInt()
                if (roundedMinutes > 0 && roundedMinutes.toDouble() == minutes) {
                    "do step activity for ${roundedMinutes} min"
                } else {
                    "do step activity"
                }
            }

            else -> "do step activity"
        }

        return "${lead}m warning: ${actionText} before the ${interval}m interval ends."
    }
}
