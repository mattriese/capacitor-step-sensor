package com.deloreanhovercraft.capacitor.stepsensor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.format.DateTimeFormatter

data class StepBucket(
    val id: Long,
    val bucketStart: String,
    val bucketEnd: String,
    val steps: Int,
    val createdAt: String,
    val hcMetadata: String?
)

class StepSensorDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "step_sensor.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_NAME = "step_sensor_log"

        @Volatile
        private var instance: StepSensorDatabase? = null

        fun getInstance(context: Context): StepSensorDatabase =
            instance ?: synchronized(this) {
                instance ?: StepSensorDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bucket_start TEXT NOT NULL,
                bucket_end TEXT NOT NULL,
                steps INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                hc_metadata TEXT,
                UNIQUE(bucket_start)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN hc_metadata TEXT")
        }
    }

    fun insertOrUpdate(bucketStart: Instant, bucketEnd: Instant, steps: Int, hcMetadata: String? = null) {
        val startStr = DateTimeFormatter.ISO_INSTANT.format(bucketStart)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(bucketEnd)
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val db = writableDatabase
        db.execSQL(
            """INSERT INTO $TABLE_NAME (bucket_start, bucket_end, steps, created_at, hc_metadata)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(bucket_start) DO UPDATE SET
                 steps = MAX(steps, excluded.steps),
                 hc_metadata = COALESCE(excluded.hc_metadata, hc_metadata)""",
            arrayOf(startStr, endStr, steps, now, hcMetadata)
        )
    }

    fun getStepsSince(since: Instant?): List<StepBucket> {
        val db = readableDatabase
        val cursor = if (since != null) {
            val sinceStr = DateTimeFormatter.ISO_INSTANT.format(since)
            db.rawQuery(
                "SELECT id, bucket_start, bucket_end, steps, created_at, hc_metadata FROM $TABLE_NAME WHERE bucket_start >= ? ORDER BY bucket_start ASC",
                arrayOf(sinceStr)
            )
        } else {
            db.rawQuery(
                "SELECT id, bucket_start, bucket_end, steps, created_at, hc_metadata FROM $TABLE_NAME ORDER BY bucket_start ASC",
                null
            )
        }

        val results = mutableListOf<StepBucket>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    StepBucket(
                        id = it.getLong(0),
                        bucketStart = it.getString(1),
                        bucketEnd = it.getString(2),
                        steps = it.getInt(3),
                        createdAt = it.getString(4),
                        hcMetadata = if (it.isNull(5)) null else it.getString(5)
                    )
                )
            }
        }
        return results
    }

    fun deleteStepsSince(since: Instant?) {
        val db = writableDatabase
        if (since != null) {
            val sinceStr = DateTimeFormatter.ISO_INSTANT.format(since)
            db.delete(TABLE_NAME, "bucket_start >= ?", arrayOf(sinceStr))
        } else {
            db.delete(TABLE_NAME, null, null)
        }
    }
}
