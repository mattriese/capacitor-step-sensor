# Proposal: Temporally Accurate Health Connect Step Distribution

## Problem

When the phone is on a desk and the user walks with a Samsung Galaxy Watch, steps accumulate on the watch and sync to Health Connect in batches. The current code detects that sync as a single delta and dumps all the steps into whichever 30-second bucket happens to coincide with the poll:

```
T=0:00  Service starts. User starts walking with watch, phone on desk.
T=0:30  phoneDelta=0, HC has no changes         → bucket [0:00, 0:30] = 0
T=1:00  phoneDelta=0, HC has no changes         → bucket [0:30, 1:00] = 0
T=1:30  phoneDelta=0, HC has no changes         → bucket [1:00, 1:30] = 0
T=2:00  phoneDelta=0, HC has no changes         → bucket [1:30, 2:00] = 0
        ↕ Watch syncs 240 steps to Health Connect
T=2:30  phoneDelta=0, HC delta=240              → bucket [2:00, 2:30] = 240  ← ALL steps here
```

Result: the database says 240 steps happened in a single 30-second window. The total is correct, but the temporal distribution is wrong.

## Three Levels of Granularity

There are three layers involved, each with different time resolution:

```
┌─────────────────────────────────────────────────────────────────┐
│  Watch → Phone sync event                                       │
│  One batch every ~2-5 minutes (Samsung's undocumented policy)   │
│  This is what we have TODAY — zero temporal info                 │
├─────────────────────────────────────────────────────────────────┤
│  StepsRecord entries inside that sync                           │
│  Each has its own startTime/endTime from the watch              │
│  Granularity is NOT documented by Samsung — observed to be      │
│  somewhere in the range of 1-15 minute intervals                │
│  This is what we'd get with this proposal — BETTER, not perfect │
├─────────────────────────────────────────────────────────────────┤
│  Our 30-second buckets                                          │
│  Finer than StepsRecord entries, so we must distribute          │
│  proportionally when a record spans multiple buckets             │
└─────────────────────────────────────────────────────────────────┘
```

**The StepsRecord granularity is coarser than our 30-second buckets.** When a single StepsRecord spans, say, 10 minutes (twenty 30s buckets), we distribute steps proportionally — which assumes even pacing within the record. This is an approximation, but a much better one than "all 240 steps in one bucket."

The exact granularity Samsung Health uses when writing StepsRecord entries to Health Connect is an implementation detail Samsung does not publicly document. Their FAQ states that Galaxy Watch sync timing "follows its own policy" for battery reasons. Developer observations suggest records typically cover 1-15 minute intervals, but this could vary by device, Samsung Health version, or activity type.

We store the raw StepsRecord data in a metadata column (see "Schema Change" below) so the consuming app has full visibility into what Health Connect actually provided.

## Health Connect API Reference

### StepsRecord

> Docs: [Track steps](https://developer.android.com/health-and-fitness/health-connect/features/steps) · [API reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/StepsRecord) · [Data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)

`StepsRecord` implements the `IntervalRecord` interface (a `Record` measured over a time interval, as opposed to an `InstantaneousRecord` at a single point).

```
StepsRecord
├── count: Long                    // number of steps in this interval
├── startTime: Instant             // when the interval began (UTC)
├── endTime: Instant               // when the interval ended (UTC)
├── startZoneOffset: ZoneOffset    // local timezone offset at start
├── endZoneOffset: ZoneOffset      // local timezone offset at end
└── metadata: Metadata             // see below
```

Constructor:
```kotlin
StepsRecord(
    count: Long,
    startTime: Instant,
    endTime: Instant,
    startZoneOffset: ZoneOffset?,
    endZoneOffset: ZoneOffset?,
    metadata: Metadata = Metadata.unknownRecordingMethod()
)
```

Companion object:
```kotlin
StepsRecord.COUNT_TOTAL  // AggregateMetric<Long> for use with aggregate()
```

Permissions: `android.permission.health.READ_STEPS`, `android.permission.health.WRITE_STEPS`

### Metadata

> Docs: [Metadata requirements](https://developer.android.com/health-and-fitness/guides/health-connect/develop/metadata) · [API reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/metadata/Metadata)

```
Metadata
├── id: String                         // Health Connect's unique record ID
├── dataOrigin: DataOrigin             // which app wrote this record
│   └── packageName: String            // e.g. "com.sec.android.app.shealth"
├── lastModifiedTime: Instant          // when the record was last written/updated in HC
├── clientRecordId: String?            // app-assigned ID for dedup
├── clientRecordVersion: Long          // app-assigned version number
├── recordingMethod: Int               // how the data was captured (see below)
└── device: Device?                    // device that recorded the data
    ├── manufacturer: String?          // e.g. "Samsung"
    ├── model: String?                 // e.g. "Galaxy Watch 6"
    └── type: Int                      // Device.TYPE_WATCH, TYPE_PHONE, etc.
```

Recording method constants:
```
Metadata.RECORDING_METHOD_UNKNOWN                 = 0
Metadata.RECORDING_METHOD_ACTIVELY_RECORDED       = 1
Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED  = 2
Metadata.RECORDING_METHOD_MANUAL_ENTRY            = 3
```

Device type constants:
```
Device.TYPE_UNKNOWN        Device.TYPE_PHONE          Device.TYPE_WATCH
Device.TYPE_SCALE          Device.TYPE_RING           Device.TYPE_FITNESS_BAND
Device.TYPE_CHEST_STRAP    Device.TYPE_HEAD_MOUNTED   Device.TYPE_SMART_DISPLAY
```

Factory methods (since Health Connect 1.1.0-alpha12, metadata is mandatory):
```kotlin
Metadata.autoRecorded(device: Device): Metadata
Metadata.activelyRecorded(device: Device): Metadata
Metadata.manualEntry(device: Device? = null): Metadata
Metadata.unknownRecordingMethod(device: Device? = null): Metadata
```

### Reading Individual Records

> Docs: [Read raw data](https://developer.android.com/health-and-fitness/health-connect/read-data)

```kotlin
val response = healthConnectClient.readRecords(
    ReadRecordsRequest(
        recordType = StepsRecord::class,
        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
    )
)
for (record in response.records) {
    // record.count     → Long
    // record.startTime → Instant (original measurement time, NOT sync time)
    // record.endTime   → Instant
    // record.metadata.dataOrigin.packageName → "com.sec.android.app.shealth"
    // record.metadata.lastModifiedTime → when it was synced to HC
    // record.metadata.device?.type → Device.TYPE_WATCH, etc.
}
```

Default page size is 1000 records. Use `response.pageToken` for pagination.

`TimeRangeFilter` factory methods:
```kotlin
TimeRangeFilter.between(startTime: Instant, endTime: Instant)
TimeRangeFilter.after(startTime: Instant)
TimeRangeFilter.before(endTime: Instant)
// Also LocalDateTime overloads for each
```

### Synchronizing via Changes

> Docs: [Synchronize data](https://developer.android.com/health-and-fitness/health-connect/sync-data)

```kotlin
// Get a token (do this once, store it)
val token = healthConnectClient.getChangesToken(
    ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
)

// Poll for changes (on each tick)
var nextToken = token
do {
    val response = healthConnectClient.getChanges(nextToken)
    for (change in response.changes) {
        when (change) {
            is UpsertionChange -> {
                // change.record is typed as Record — cast to StepsRecord
                val record = change.record
                if (record is StepsRecord) {
                    // record.count, record.startTime, record.endTime all available
                    // record.metadata.dataOrigin.packageName → filter out own app
                }
            }
            is DeletionChange -> {
                // change.deletedRecordId — only the ID, no record data (privacy)
            }
        }
    }
    nextToken = response.nextChangesToken
} while (response.hasMore)
```

Tokens expire after **30 days** of non-use. Best practice: one token per data type.

### Samsung Health Sync Behavior

> Docs: [Samsung Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html) · [Accessing Samsung Health Data](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)

- Samsung Health writes to Health Connect "as soon as data is created or changed" on the phone.
- Galaxy Watch → Samsung Health phone app sync timing "follows its own policy" for battery reasons. No specific interval is documented.
- Step data from Samsung Health has `dataOrigin.packageName = "com.sec.android.app.shealth"`.
- StepsRecord entries preserve the **original measurement timestamps** from the watch (`startTime`/`endTime`), not the sync time. The sync time is available via `metadata.lastModifiedTime`.
- The exact interval/granularity of individual StepsRecord entries is **not publicly documented** by Samsung.

---

## Current Approach (What Changes)

### Current: `recordHcInterval()` in `StepCounterService.kt`

```
getChanges(token) → any UpsertionChange? → aggregate(COUNT_TOTAL) → delta from lastHcTotal
```

This produces a single number (e.g. 240) with no time information.

### Proposed: `collectHcRecords()` replaces `recordHcInterval()`

```
getChanges(token) → cast UpsertionChange.record to StepsRecord → distribute into 30s buckets
```

This produces a map of `bucketStart → steps` with temporal attribution, plus raw records stored as metadata for diagnostics.

## Detailed Design

### 1. Extract StepsRecord entries from changes

`UpsertionChange.record` is typed as `Record`. Cast it to `StepsRecord` to access `startTime`, `endTime`, and `count`:

```kotlin
private suspend fun collectHcRecords(): List<StepsRecord> {
    val client = healthConnectClient ?: return emptyList()

    return hcMutex.withLock {
        val token = changesToken ?: return@withLock emptyList()
        val records = mutableListOf<StepsRecord>()

        try {
            var nextToken = token
            do {
                val response = client.getChanges(nextToken)

                if (response.changesTokenExpired) {
                    changesToken = client.getChangesToken(
                        ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
                    )
                    return@withLock emptyList()
                }

                for (change in response.changes) {
                    if (change is UpsertionChange && change.record is StepsRecord) {
                        records.add(change.record as StepsRecord)
                    }
                }
                nextToken = response.nextChangesToken
            } while (response.hasMore)

            changesToken = nextToken
            records
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting Health Connect records", e)
            emptyList()
        }
    }
}
```

### 2. Distribute records into 30-second buckets

A single `StepsRecord` will typically span multiple 30-second buckets (e.g. a record covering 10:00:00 to 10:10:00 spans twenty 30s buckets). Since we don't know the step distribution within the record, we distribute proportionally — assuming even pacing within the record's time window:

```kotlin
// New function in StepTrackingLogic.kt
fun distributeRecordIntoBuckets(
    recordStart: Instant,
    recordEnd: Instant,
    count: Long
): Map<Instant, Int> {
    if (count <= 0 || !recordEnd.isAfter(recordStart)) return emptyMap()

    val totalSeconds = recordEnd.epochSecond - recordStart.epochSecond
    if (totalSeconds <= 0) return emptyMap()

    val buckets = mutableMapOf<Instant, Int>()

    // Walk through each 30s bucket that this record overlaps
    var cursor = floorTo30Seconds(recordStart)
    while (cursor.isBefore(recordEnd)) {
        val bucketEnd = cursor.plusSeconds(30)

        // Calculate overlap between [cursor, bucketEnd) and [recordStart, recordEnd)
        val overlapStart = if (recordStart.isAfter(cursor)) recordStart else cursor
        val overlapEnd = if (recordEnd.isBefore(bucketEnd)) recordEnd else bucketEnd
        val overlapSeconds = overlapEnd.epochSecond - overlapStart.epochSecond

        if (overlapSeconds > 0) {
            val steps = (count * overlapSeconds / totalSeconds).toInt()
            if (steps > 0) {
                buckets[cursor] = (buckets[cursor] ?: 0) + steps
            }
        }

        cursor = bucketEnd
    }

    // Assign any rounding remainder to the last bucket with steps
    val distributed = buckets.values.sum()
    val remainder = count.toInt() - distributed
    if (remainder > 0 && buckets.isNotEmpty()) {
        val lastKey = buckets.keys.maxBy { it.epochSecond }
        buckets[lastKey] = buckets[lastKey]!! + remainder
    }

    return buckets
}
```

### 3. Revised `onTimerTick()`

Instead of merging a single phone delta with a single HC delta, the tick now:
1. Records the phone sensor delta for the current bucket (same as before)
2. Collects any new HC StepsRecord entries and distributes them across their correct buckets
3. For the current bucket: MAX(phoneDelta, hcBucketDelta) as before
4. For past buckets that only HC contributed to: write directly
5. Stores raw StepsRecord data as JSON metadata on the affected buckets

```kotlin
private fun onTimerTick() {
    val phoneDelta = recordPhoneSensorInterval()

    scope.launch {
        val hcRecords = collectHcRecords()

        // Distribute all HC records into their correct 30s buckets
        val hcBuckets = mutableMapOf<Instant, Int>()
        for (record in hcRecords) {
            val distributed = StepTrackingLogic.distributeRecordIntoBuckets(
                record.startTime, record.endTime, record.count
            )
            for ((bucketStart, steps) in distributed) {
                hcBuckets[bucketStart] = (hcBuckets[bucketStart] ?: 0) + steps
            }
        }

        // Serialize raw HC records for diagnostics
        val hcRecordsJson = if (hcRecords.isNotEmpty()) {
            StepTrackingLogic.serializeHcRecords(hcRecords)
        } else null

        val now = Instant.now()
        val (currentBucketStart, currentBucketEnd) = StepTrackingLogic.computeBucketBoundaries(now)

        // Write HC steps to their correct past buckets
        for ((bucketStart, hcSteps) in hcBuckets) {
            if (bucketStart == currentBucketStart) continue  // handle below with phone merge
            if (hcSteps > 0) {
                database.insertOrUpdate(
                    bucketStart, bucketStart.plusSeconds(30), hcSteps, hcRecordsJson
                )
            }
        }

        // Current bucket: MAX(phone, hc) — same merge strategy as before
        val hcCurrentBucket = hcBuckets[currentBucketStart] ?: 0
        val steps = StepTrackingLogic.mergeStepSources(phoneDelta, hcCurrentBucket.toLong())

        if (steps > 0) {
            database.insertOrUpdate(currentBucketStart, currentBucketEnd, steps, hcRecordsJson)
        }
    }
}
```

### 4. What gets removed

- `lastHcTotal: Long` field — no longer needed (we don't track running totals)
- `getHcTotalSteps()` method — no longer needed (we don't aggregate)
- `trackingStartTime` — no longer needed for HC (still used elsewhere if applicable)
- The `AggregateRequest` import and usage

### 5. Schema change: add `hc_metadata` column

Add a nullable TEXT column to store the raw Health Connect StepsRecord data as JSON. This gives the consuming app full visibility into what HC actually provided — the original time ranges, step counts, data origins, and device info — so it can implement its own analysis or diagnostics without relying on our proportional distribution.

#### Migration (DATABASE_VERSION 1 → 2)

```kotlin
override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
        db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN hc_metadata TEXT")
    }
}
```

#### Updated `insertOrUpdate`

```kotlin
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
```

The `COALESCE(excluded.hc_metadata, hc_metadata)` means: use the new metadata if provided, otherwise keep the existing. On the first write (phone-only), `hc_metadata` is NULL. When HC data arrives later for the same bucket, it gets populated.

#### Updated `StepBucket` data class

```kotlin
data class StepBucket(
    val id: Long,
    val bucketStart: String,
    val bucketEnd: String,
    val steps: Int,
    val createdAt: String,
    val hcMetadata: String?   // raw HC StepsRecord JSON, null if phone-only
)
```

#### Metadata JSON format

Each bucket's `hc_metadata` contains the raw StepsRecord entries that contributed to that tick's HC data. This is the full set of records from the sync event, not per-bucket — so the consuming app can see exactly what Health Connect returned:

```json
[
  {
    "startTime": "2026-01-15T10:00:00Z",
    "endTime": "2026-01-15T10:10:00Z",
    "count": 120,
    "dataOrigin": "com.sec.android.app.shealth",
    "deviceType": 2,
    "deviceManufacturer": "Samsung",
    "deviceModel": "Galaxy Watch 6",
    "recordingMethod": 2,
    "lastModifiedTime": "2026-01-15T10:12:34Z"
  },
  {
    "startTime": "2026-01-15T10:10:00Z",
    "endTime": "2026-01-15T10:20:00Z",
    "count": 135,
    "dataOrigin": "com.sec.android.app.shealth",
    "deviceType": 2,
    "deviceManufacturer": "Samsung",
    "deviceModel": "Galaxy Watch 6",
    "recordingMethod": 2,
    "lastModifiedTime": "2026-01-15T10:12:34Z"
  }
]
```

#### Serialization helper in `StepTrackingLogic.kt`

```kotlin
fun serializeHcRecords(records: List<StepsRecord>): String {
    val jsonArray = JSONArray()
    for (record in records) {
        val obj = JSONObject().apply {
            put("startTime", record.startTime.toString())
            put("endTime", record.endTime.toString())
            put("count", record.count)
            put("dataOrigin", record.metadata.dataOrigin.packageName)
            put("lastModifiedTime", record.metadata.lastModifiedTime.toString())
            put("recordingMethod", record.metadata.recordingMethod)
            record.metadata.device?.let { device ->
                put("deviceType", device.type)
                device.manufacturer?.let { put("deviceManufacturer", it) }
                device.model?.let { put("deviceModel", it) }
            }
        }
        jsonArray.put(obj)
    }
    return jsonArray.toString()
}
```

#### Updated `getStepsSince` query and TypeScript return value

The `getTrackedSteps` plugin method should include `hcMetadata` in its response so the consuming app can access it:

```typescript
// In the plugin's TypeScript interface
interface StepBucket {
  bucketStart: string;
  bucketEnd: string;
  steps: number;
  createdAt: string;
  hcMetadata?: HcRecord[];  // raw Health Connect records, if available
}

interface HcRecord {
  startTime: string;
  endTime: string;
  count: number;
  dataOrigin: string;
  lastModifiedTime: string;
  recordingMethod: number;
  deviceType?: number;
  deviceManufacturer?: string;
  deviceModel?: string;
}
```

---

## Handling Edge Cases

### HC record spans a single 30s bucket exactly
No splitting needed. The record's count goes directly to that bucket.

### HC record spans many buckets (e.g. 10-minute Samsung Health record)
Steps distributed proportionally assuming even pacing. A 10-minute record with 600 steps spanning twenty 30s buckets gives ~30 steps per bucket. Remainder from integer rounding assigned to the last bucket. The raw record is preserved in `hc_metadata` so the consuming app knows this was an approximation.

### Multiple HC records for the same time range (e.g. phone + watch both write to HC)
Both records get distributed, their per-bucket values sum in `hcBuckets`, and `insertOrUpdate` takes `MAX(existing, new)`. The MAX upsert prevents double-counting across ticks.

### HC record arrives for a bucket already consumed by `getTrackedSteps(deleteAfterRead=true)`
The bucket was already deleted. `insertOrUpdate` creates a new row. The consumer app would see this as a late-arriving correction on the next read. This is a pre-existing limitation unrelated to this change.

### Token expiration (30-day timeout)
Same as current behavior: re-initialize token and skip the interval. No records are lost in Health Connect — they'll be picked up via subsequent changes or a `readRecords` backfill if needed.

### DeletionChange from Health Connect
Currently ignored, same as before. If a record is deleted from HC (e.g. user manually removes data in Samsung Health), the steps remain in our SQLite. This is acceptable since deletions are rare and the plugin has no way to know which bucket to decrement.

## What This Looks Like After the Fix

Same scenario — 2 minutes of walking with watch, phone on desk. Assume Samsung Health wrote records in ~1-minute intervals:

```
T=0:00  Service starts.
T=0:30  phoneDelta=0, no HC changes
T=1:00  phoneDelta=0, no HC changes
T=1:30  phoneDelta=0, no HC changes
T=2:00  phoneDelta=0, no HC changes
        ↕ Watch syncs. HC now has StepsRecord entries with original timestamps.
T=2:30  phoneDelta=0, collectHcRecords() finds:
          StepsRecord(0:00–1:00, count=60)
            → distributed: bucket[0:00]=30, bucket[0:30]=30
          StepsRecord(1:00–2:00, count=55)
            → distributed: bucket[1:00]=27, bucket[1:30]=28
        All 4 past buckets get their proportional share.
        hc_metadata stored on each affected bucket row.
```

**Before (current):** 115 steps crammed into bucket [2:00, 2:30].
**After (proposed):** 115 steps distributed across buckets [0:00]–[2:00] proportionally, with raw HC records stored for diagnostics.

The proportional distribution is an approximation (we assume even pacing within each StepsRecord), but it's a dramatically better approximation than "all steps in one bucket."

## Files Changed

| File | Change |
|------|--------|
| `StepCounterService.kt` | Replace `recordHcInterval()` with `collectHcRecords()`. Remove `lastHcTotal`, `getHcTotalSteps()`, `trackingStartTime`. Rewrite `onTimerTick()` to distribute HC records and store metadata. |
| `StepTrackingLogic.kt` | Add `distributeRecordIntoBuckets()` and `serializeHcRecords()`. |
| `StepSensorDatabase.kt` | Bump `DATABASE_VERSION` to 2. Add `hc_metadata` column in `onUpgrade`. Update `insertOrUpdate` signature. Update `StepBucket` data class. Update `getStepsSince` to read the new column. |
| `StepSensorPlugin.kt` | Include `hcMetadata` in `getTrackedSteps` response. |
| `StepTrackingLogicTest.kt` | Add tests for `distributeRecordIntoBuckets()`. |
| `StepSensorDatabaseTest.kt` | Add tests for `insertOrUpdate` with `hcMetadata` parameter. |
| `src/definitions.ts` | Add `hcMetadata` field to TypeScript interface. |

## Testing the Distribution Function

`distributeRecordIntoBuckets` is a pure function (Instant in, Map out) — fully testable with plain JUnit, no Robolectric or mocks needed. Example test cases:

- Record exactly aligned to one 30s bucket → all steps in that bucket
- Record spanning 4 buckets evenly → ~25% per bucket
- Record with 1 step spanning 3 buckets → 1 step in the last bucket (rounding)
- Record with count=0 → empty map
- Record where startTime == endTime → empty map
- Record starting mid-bucket → first bucket gets proportional share
- 10-minute record → 20 buckets, verify sum equals original count

The HC-specific code (`collectHcRecords`) requires Health Connect mocks and is harder to unit test, but the distribution logic — which is where bugs would hide — is fully covered.
