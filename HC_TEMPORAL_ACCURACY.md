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

## Double-Counting Bug in the Current Code

The temporal inaccuracy above also causes a **double-counting bug** when the user walks with **both** the phone and the watch.

### Scenario: phone in pocket + watch on wrist, 2 minutes of walking

Both devices independently count the same ~120 physical steps. Our plugin reads the phone sensor directly (not via Health Connect). The watch records independently and syncs to HC later.

```
T=0:30  phoneDelta=30, hcDelta=0 (watch hasn't synced)
        MAX(30, 0) = 30 → bucket[0:00] = 30

T=1:00  phoneDelta=32, hcDelta=0 → bucket[0:30] = 32
T=1:30  phoneDelta=28, hcDelta=0 → bucket[1:00] = 28
T=2:00  phoneDelta=30, hcDelta=0 → bucket[1:30] = 30

        ↕ Watch syncs ~120 steps to HC covering 0:00–2:00

T=2:30  phoneDelta=31
        hcDelta = aggregate(COUNT_TOTAL) - lastHcTotal = 120 - 0 = 120
        MAX(31, 120) = 120 → bucket[2:00] = 120

DB total: 30 + 32 + 28 + 30 + 120 = 240
Actual physical steps: ~120
```

**The MAX only operates within a single tick.** At T=2:30 it correctly picks MAX(31, 120) = 120 for that one bucket. But the 120 HC steps cover the same physical activity that the phone already recorded into buckets [0:00]–[1:30]. Those past buckets are never revisited. Result: ~120 phone steps in past buckets + 120 HC steps in the current bucket = 240 in the database. Double the real amount.

The root cause: `recordHcInterval()` returns the total HC delta since last sync as a single number, and `onTimerTick()` writes it to whichever bucket is current. There's no way to MAX it against the phone data that was already written to past buckets, because the HC data lost its time information when it was aggregated.

### How the proposed code fixes this

The key insight: **phone sensor data is real-time and accurate per-bucket**. HC data from the watch is more complete (captures steps when the phone is on a desk) but arrives late with coarser time resolution. So we trust the phone data where it exists and use HC data only to fill in gaps.

**Algorithm: Subtract-and-Fill**

When HC records arrive for a time range:
1. Sum the phone steps already recorded in the buckets covered by this HC record
2. Compute the surplus: `hcCount - phoneTotalForRange` (clamp to 0 if negative)
3. Distribute the surplus into only the buckets that have **0 phone-sensor steps** (the gaps)
4. **Edge case:** if ALL buckets already have phone steps (no zero-step buckets), SUM the entire surplus into the **last** bucket

#### Example 1: Phone on desk for part of the walk

Phone records `(10, 10, 0, 0)`, watch syncs 120 steps for the same 2-minute range.

```
Phone total for range: 10 + 10 + 0 + 0 = 20
Surplus: 120 - 20 = 100
Zero-step buckets: bucket[1:00], bucket[1:30] → 2 buckets
Distribute 100 evenly: 50 each

Result: (10, 10, 50, 50) = 130 total
```

Phone data preserved. Gaps filled. Total = max(phoneTotal, hcTotal) = 120... wait, 130? That's because the phone counted 20 steps that the watch didn't attribute to those specific buckets. The phone's 20 are real, the watch's 120 are real, and the surplus (100) goes into the gaps. The 130 total is correct — it means the phone picked up 10 extra steps in the first two buckets that the watch missed (e.g. the user fidgeted with the phone before putting it down).

Actually, if both devices are counting the same physical steps, the phone total should be ≤ HC total. If phone > HC for the range, surplus is 0 and nothing changes.

#### Example 2: Phone active the whole time, watch counted more

Phone records `(10, 10, 10, 1)`, watch syncs 130 steps.

```
Phone total for range: 10 + 10 + 10 + 1 = 31
Surplus: 130 - 31 = 99
Zero-step buckets: NONE (all buckets have phone data)
Edge case: SUM surplus into last bucket: 1 + 99 = 100

Result: (10, 10, 10, 100) = 130 total
```

The last bucket absorbs the surplus. This is imperfect temporally (those 99 steps probably didn't all happen in the last 30 seconds), but the total is correct and the phone data for the first three buckets is preserved exactly.

#### Example 3: Phone counted everything, watch agrees

Phone records `(30, 30, 30, 30)`, watch syncs 120 steps.

```
Phone total for range: 120
Surplus: 120 - 120 = 0
Nothing changes.

Result: (30, 30, 30, 30) = 120 total
```

#### Example 4: Phone counted more than watch

Phone records `(40, 40, 40, 40)`, watch syncs 120 steps.

```
Phone total for range: 160
Surplus: max(0, 120 - 160) = 0
Nothing changes.

Result: (40, 40, 40, 40) = 160 total
```

Phone is trusted. The watch may have missed some wrist motion. The phone's per-bucket data is never reduced.

#### Example 5: Phone was off the whole time

Phone records `(0, 0, 0, 0)`, watch syncs 120 steps.

```
Phone total for range: 0
Surplus: 120
Zero-step buckets: all 4
Distribute 120 evenly: 30 each

Result: (30, 30, 30, 30) = 120 total
```

Falls back to proportional distribution (same as the simpler approach) when there's no phone data at all.

### Remaining multi-source HC risk and fix

`getChanges()` returns records from **all** apps that write to Health Connect. If Samsung Health writes 120 steps from the watch, and Google Fit also writes 120 steps from the phone's own sensors, the proposed code would **sum** both in `hcBuckets` before writing — resulting in 240, defeating the MAX.

The fix: group HC records by `dataOrigin` and distribute each source independently, then take the **MAX across sources** per bucket instead of summing:

```kotlin
// Group records by data origin
val bySource = hcRecords.groupBy { it.metadata.dataOrigin.packageName }

// Distribute each source independently, then MAX across sources per bucket
val hcBuckets = mutableMapOf<Instant, Int>()
for ((_, sourceRecords) in bySource) {
    val sourceBuckets = mutableMapOf<Instant, Int>()
    for (record in sourceRecords) {
        val distributed = StepTrackingLogic.distributeRecordIntoBuckets(
            record.startTime, record.endTime, record.count
        )
        for ((bucketStart, steps) in distributed) {
            sourceBuckets[bucketStart] = (sourceBuckets[bucketStart] ?: 0) + steps
        }
    }
    // MAX across sources, not sum
    for ((bucketStart, steps) in sourceBuckets) {
        hcBuckets[bucketStart] = max(hcBuckets[bucketStart] ?: 0, steps)
    }
}
```

This mirrors the same MAX strategy used between the phone sensor and HC — always take the higher of two independent measurements of the same physical activity, never add them.

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

**The StepsRecord granularity is coarser than our 30-second buckets.** When a single StepsRecord spans, say, 10 minutes (twenty 30s buckets), we use the subtract-and-fill algorithm: subtract the phone steps already counted, then distribute the surplus evenly into the zero-step buckets (where the phone had no readings). This preserves accurate phone data and only fills gaps with watch data.

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
getChanges(token) → cast UpsertionChange.record to StepsRecord → subtract phone steps → fill zero-step buckets
```

This produces a map of `bucketStart → steps` representing only the surplus the watch counted beyond the phone's readings. Phone sensor data is never overwritten. Raw records stored as metadata for diagnostics.

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

### 2. Subtract-and-fill: distribute HC surplus into empty buckets

The core algorithm. For each HC record, we subtract the phone steps already counted in the overlapping buckets, then distribute the surplus into only the zero-step buckets:

```kotlin
// In StepTrackingLogic.kt
fun subtractAndFill(
    recordStart: Instant,
    recordEnd: Instant,
    hcCount: Long,
    existingBuckets: Map<Instant, Int>,  // current phone-sensor data in DB
    commitmentStart: Instant,            // commitment window start (inclusive)
    commitmentEnd: Instant,              // commitment window end (exclusive)
    perBucketCap: Int = MAX_STEPS_PER_BUCKET  // 90 steps/30s
): Map<Instant, Int> {
    if (hcCount <= 0 || !recordEnd.isAfter(recordStart)) return emptyMap()

    // Enumerate all 30s buckets this record covers
    val allBuckets = mutableListOf<Instant>()
    var cursor = floorTo30Seconds(recordStart)
    while (cursor.isBefore(recordEnd)) {
        allBuckets.add(cursor)
        cursor = cursor.plusSeconds(30)
    }
    if (allBuckets.isEmpty()) return emptyMap()

    // Clamp to commitment window — only keep in-window buckets
    val inWindowBuckets = allBuckets.filter { bucket ->
        !bucket.isBefore(commitmentStart) && bucket.isBefore(commitmentEnd)
    }
    if (inWindowBuckets.isEmpty()) return emptyMap()

    // Sum phone steps in in-window buckets only
    val phoneTotal = inWindowBuckets.sumOf { existingBuckets[it] ?: 0 }

    // Full inclusion: entire hcCount, not proportional
    val surplus = max(0, hcCount - phoneTotal)
    if (surplus == 0L) return emptyMap()

    val zeroBuckets = inWindowBuckets.filter { (existingBuckets[it] ?: 0) == 0 }
    val result = mutableMapOf<Instant, Int>()

    if (zeroBuckets.isNotEmpty()) {
        val totalCapacity = zeroBuckets.size.toLong() * perBucketCap
        if (surplus <= totalCapacity) {
            // Fits within caps — distribute evenly
            val perBucket = (surplus / zeroBuckets.size).toInt()
            val remainder = (surplus % zeroBuckets.size).toInt()
            for ((i, bucket) in zeroBuckets.withIndex()) {
                result[bucket] = perBucket + if (i < remainder) 1 else 0
            }
        } else {
            // Overflow: fill each zero bucket to cap, leftover to last bucket (uncapped)
            for (bucket in zeroBuckets) { result[bucket] = perBucketCap }
            val leftover = (surplus - totalCapacity).toInt()
            val lastBucket = inWindowBuckets.last()
            val existing = existingBuckets[lastBucket] ?: 0
            result[lastBucket] = (result[lastBucket] ?: existing) + leftover
        }
    } else {
        // No zero buckets: all surplus to last in-window bucket (uncapped)
        val lastBucket = inWindowBuckets.last()
        val existing = existingBuckets[lastBucket] ?: 0
        result[lastBucket] = existing + surplus.toInt()
    }

    return result
}
```

### 3. Handle multiple HC data origins

Multiple apps can write step data to Health Connect (Samsung Health from a watch, Google Fit from the phone, etc.). We group by `dataOrigin`, run subtract-and-fill for each source independently, then take MAX across sources per bucket:

```kotlin
// In StepTrackingLogic.kt
fun processHcRecords(
    records: List<HcStepRecord>,
    existingBuckets: Map<Instant, Int>,
    commitmentStart: Instant,
    commitmentEnd: Instant,
    perBucketCap: Int = MAX_STEPS_PER_BUCKET
): Map<Instant, Int> {
    if (records.isEmpty()) return emptyMap()

    // Group by data origin — each source measured the same physical steps independently
    val bySource = records.groupBy { it.dataOrigin }

    val result = mutableMapOf<Instant, Int>()
    for ((_, sourceRecords) in bySource) {
        val sourceBuckets = mutableMapOf<Instant, Int>()
        for (record in sourceRecords) {
            val filled = subtractAndFill(
                record.startTime, record.endTime, record.count,
                existingBuckets, commitmentStart, commitmentEnd, perBucketCap
            )
            for ((bucketStart, steps) in filled) {
                sourceBuckets[bucketStart] = max(sourceBuckets[bucketStart] ?: 0, steps)
            }
        }
        for ((bucketStart, steps) in sourceBuckets) {
            result[bucketStart] = max(result[bucketStart] ?: 0, steps)
        }
    }
    return result
}
```

The logic: different data origins (Samsung Health, Google Fit) are independent measurements of the same physical steps. We run subtract-and-fill for each source independently, then MAX across sources per bucket.

### 4. Revised `onTimerTick()`

Instead of merging a single phone delta with a single HC delta, the tick now:
1. Records the phone sensor delta for the current bucket (same as before)
2. Writes the phone delta to the current bucket immediately (phone data is always trusted)
3. Collects any new HC StepsRecord entries
4. Reads existing bucket data from the DB for the time range covered by the HC records
5. Runs subtract-and-fill to compute what the watch counted beyond the phone's total
6. Writes the filled values to the appropriate buckets
7. Stores raw StepsRecord data as JSON metadata on the affected buckets

```kotlin
private fun onTimerTick() {
    val phoneDelta = recordPhoneSensorInterval()

    scope.launch {
        val now = Instant.now()
        val (currentBucketStart, currentBucketEnd) = StepTrackingLogic.computeBucketBoundaries(now)

        // Always write phone data first — it's real-time and per-bucket accurate
        if (phoneDelta > 0) {
            database.insertOrUpdate(currentBucketStart, currentBucketEnd, phoneDelta.toInt())
        }

        val hcRecords = collectHcRecords()
        if (hcRecords.isEmpty()) return@launch

        // Serialize raw HC records for diagnostics
        val hcRecordsJson = StepTrackingLogic.serializeHcRecords(hcRecords)

        // Determine the time range covered by HC records
        val hcStart = hcRecords.minOf { it.startTime }
        val hcEnd = hcRecords.maxOf { it.endTime }

        // Read existing bucket data from DB for the covered range
        val existingRows = database.getStepsSince(hcStart.toString())
        val existingBuckets = existingRows
            .filter { Instant.parse(it.bucketStart).isBefore(hcEnd) }
            .associate { Instant.parse(it.bucketStart) to it.steps }

        // Subtract-and-fill: compute surplus from watch beyond phone's total,
        // distribute into zero-step buckets only
        val filledBuckets = StepTrackingLogic.processHcRecords(hcRecords, existingBuckets)

        // Write filled values to DB
        for ((bucketStart, steps) in filledBuckets) {
            if (steps > 0) {
                val bucketEnd = bucketStart.plusSeconds(30)
                // Use direct insert/update (not MAX upsert) since subtractAndFill
                // already computed the correct final value for each bucket.
                // For zero-step buckets: this is a fresh INSERT.
                // For the edge-case last bucket: this is the SUM value.
                database.insertOrUpdate(bucketStart, bucketEnd, steps, hcRecordsJson)
            }
        }
    }
}
```

**Note on the SQLite upsert:** For the subtract-and-fill algorithm, the edge-case path (no zero-step buckets, SUM into last bucket) returns the **final value** (existing + surplus), not just the surplus. So the `MAX(steps, excluded.steps)` upsert still works correctly — the new value is always ≥ the existing value.

### 5. What gets removed

- `lastHcTotal: Long` field — no longer needed (we don't track running totals)
- `getHcTotalSteps()` method — no longer needed (we don't aggregate)
- `trackingStartTime` — no longer needed for HC (still used elsewhere if applicable)
- The `AggregateRequest` import and usage

### 6. Schema change: add `hc_metadata` column

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
Subtract-and-fill computes the surplus (hcCount - phoneTotal for the range), then distributes evenly across zero-step buckets only. If the phone was active for some of those buckets, only the remaining gap buckets receive steps. Remainder from integer division goes to the last zero-step bucket. The raw record is preserved in `hc_metadata` so the consuming app has full visibility.

### Multiple HC data origins for the same time range (e.g. Samsung Health + Google Fit)
`processHcRecords` groups by `dataOrigin`, runs subtract-and-fill for each source independently, then takes MAX across sources per bucket. Different data origins are independent measurements of the same physical steps — MAX prevents double-counting.

### HC record arrives for a bucket already consumed by `getTrackedSteps(deleteAfterRead=true)`
The bucket was already deleted. `insertOrUpdate` creates a new row. The consumer app would see this as a late-arriving correction on the next read. This is a pre-existing limitation unrelated to this change.

### Token expiration (30-day timeout)
Same as current behavior: re-initialize token and skip the interval. No records are lost in Health Connect — they'll be picked up via subsequent changes or a `readRecords` backfill if needed.

### DeletionChange from Health Connect
Currently ignored, same as before. If a record is deleted from HC (e.g. user manually removes data in Samsung Health), the steps remain in our SQLite. This is acceptable since deletions are rare and the plugin has no way to know which bucket to decrement.

## What This Looks Like After the Fix

### Scenario A: Phone on desk, watch only

2 minutes of walking with watch, phone on desk. Samsung Health wrote records in ~1-minute intervals:

```
T=0:00  Service starts.
T=0:30  phoneDelta=0 → bucket[0:00] = 0
T=1:00  phoneDelta=0 → bucket[0:30] = 0
T=1:30  phoneDelta=0 → bucket[1:00] = 0
T=2:00  phoneDelta=0 → bucket[1:30] = 0
        ↕ Watch syncs. HC now has StepsRecord entries with original timestamps.
T=2:30  phoneDelta=0, collectHcRecords() finds:
          StepsRecord(0:00–1:00, count=60)
            phone total for [0:00–1:00] = 0, surplus = 60
            zero-step buckets: [0:00], [0:30] → 30 each
          StepsRecord(1:00–2:00, count=55)
            phone total for [1:00–2:00] = 0, surplus = 55
            zero-step buckets: [1:00], [1:30] → 27, 28
        hc_metadata stored on each affected bucket row.

Result: (30, 30, 27, 28) = 115 total
```

**Before (current):** 115 steps crammed into bucket [2:00, 2:30].
**After (proposed):** 115 steps distributed across buckets [0:00]–[2:00], with raw HC records stored for diagnostics.

### Scenario B: Phone in pocket for first minute, then on desk

```
T=0:30  phoneDelta=10 → bucket[0:00] = 10
T=1:00  phoneDelta=10 → bucket[0:30] = 10
T=1:30  phoneDelta=0  → bucket[1:00] = 0   ← phone put on desk
T=2:00  phoneDelta=0  → bucket[1:30] = 0
        ↕ Watch syncs: StepsRecord(0:00–2:00, count=120)
T=2:30  phone total for [0:00–2:00] = 20, surplus = 100
        zero-step buckets: [1:00], [1:30] → 50 each

Result: (10, 10, 50, 50) = 120 total
```

Phone data preserved exactly. Watch surplus fills the gaps where the phone had no readings.

### Scenario C: Phone active the whole time, watch counted more

```
T=0:30  phoneDelta=10 → bucket[0:00] = 10
T=1:00  phoneDelta=10 → bucket[0:30] = 10
T=1:30  phoneDelta=10 → bucket[1:00] = 10
T=2:00  phoneDelta=1  → bucket[1:30] = 1    ← user set phone down, started jogging
        ↕ Watch syncs: StepsRecord(0:00–2:00, count=130)
T=2:30  phone total for [0:00–2:00] = 31, surplus = 99
        zero-step buckets: NONE
        Edge case: SUM surplus into last bucket: 1 + 99 = 100

Result: (10, 10, 10, 100) = 130 total
```

The last bucket absorbs the surplus. Temporally imperfect for that bucket, but the phone data for the first three buckets is preserved exactly, and the total is correct.

## Files Changed

| File | Change |
|------|--------|
| `StepCounterService.kt` | Replace `recordHcInterval()` with `collectHcRecords()`. Remove `lastHcTotal`, `getHcTotalSteps()`, `trackingStartTime`. Rewrite `onTimerTick()` to distribute HC records and store metadata. |
| `StepTrackingLogic.kt` | Add `subtractAndFill()`, `processHcRecords()`, and `serializeHcRecords()`. |
| `StepSensorDatabase.kt` | Bump `DATABASE_VERSION` to 2. Add `hc_metadata` column in `onUpgrade`. Update `insertOrUpdate` signature. Update `StepBucket` data class. Update `getStepsSince` to read the new column. |
| `StepSensorPlugin.kt` | Include `hcMetadata` in `getTrackedSteps` response. |
| `StepTrackingLogicTest.kt` | Add tests for `subtractAndFill()` and `processHcRecords()`. |
| `StepSensorDatabaseTest.kt` | Add tests for `insertOrUpdate` with `hcMetadata` parameter. |
| `src/definitions.ts` | Add `hcMetadata` field to TypeScript interface. |

## Testing the Subtract-and-Fill Function

`subtractAndFill` is a pure function (Instants + counts + existing bucket map in, Map out) — fully testable with plain JUnit, no Robolectric or mocks needed. Example test cases:

- Phone on desk (all zero buckets): surplus distributed evenly across all buckets
- Phone active then desk (mixed zero/non-zero): surplus fills only zero buckets, phone data untouched
- Phone counted everything (no zeros, phone == HC): surplus is 0, empty result
- Phone counted more than watch (phone > HC): surplus clamped to 0, empty result
- All buckets have phone data but watch > phone (edge case): surplus goes to last bucket as SUM
- HC count=0 → empty map
- HC record where startTime == endTime → empty map
- Single 30s bucket, phone had 5, watch had 10 → no zeros, surplus=5 → last bucket becomes 5+5=10
- 10-minute record with 20 buckets, phone had data in first 5: surplus fills remaining 15

`processHcRecords` can also be tested with plain JUnit by constructing StepsRecord objects with different `dataOrigin` values:

- Two sources, same time range → MAX per bucket across sources
- Single source, two adjacent records → each record processed independently
- Mixed: one source for 0:00–1:00, another for 0:00–2:00 → MAX for overlapping range

The HC-specific code (`collectHcRecords`) requires Health Connect mocks and is harder to unit test, but the subtract-and-fill logic — which is where bugs would hide — is fully covered.

## Commitment Boundary Handling

HC StepsRecords can overlap commitment window boundaries. A 5-minute watch sync arriving at the start of a commitment could credit pre-commitment steps, and one at the end could span past the deadline.

### Policy: Full Inclusion

Any overlapping HC record is fully credited (the entire `hcCount` is used, not proportionally reduced), but steps are only written to in-window buckets. This prioritizes never falsely denying steps that may have occurred during the commitment.

### How It Works

`subtractAndFill` accepts `commitmentStart` and `commitmentEnd` parameters:

1. Enumerate all 30s buckets the HC record covers
2. **Clamp to commitment window** — only keep buckets in `[commitmentStart, commitmentEnd)`
3. Sum phone steps in those in-window buckets only
4. Surplus = `max(0, hcCount - phoneTotal)` — using the **full** hcCount
5. Distribute surplus into zero-step in-window buckets

Pre-commitment and post-commitment buckets are never written to. The commitment window is loaded from persisted scheduler windows via `StepTrackingScheduler.loadPersistedWindows()` + `findActiveWindow()` at service startup.

### Example: HC Record Spanning Commitment Start

```
Commitment: 10:00:00–12:00:00
HC record:  09:58:00–10:02:00, count=120

All covered buckets: [09:58:00, 09:58:30, 09:59:00, 09:59:30, 10:00:00, 10:00:30, 10:01:00, 10:01:30]
In-window buckets:   [10:00:00, 10:00:30, 10:01:00, 10:01:30]  (4 buckets)

Phone total for in-window buckets: 0
Surplus: max(0, 120 - 0) = 120 (full inclusion)
Distribute 120 into 4 zero buckets: 30 each
```

All 120 steps are credited even though the record started before the commitment.

## Per-Bucket Cap

To prevent physically impossible step values, each zero-step bucket receiving HC surplus is capped at `MAX_STEPS_PER_BUCKET = 90` steps per 30-second interval (equivalent to 180 steps/min, which covers fast running).

### How It Works

During surplus distribution in `subtractAndFill`:

1. Calculate total capacity: `zeroBuckets.size * MAX_STEPS_PER_BUCKET`
2. If surplus fits within capacity: distribute evenly (no bucket exceeds cap)
3. If surplus exceeds capacity: fill each zero bucket to cap, overflow goes to the **last in-window bucket uncapped**

The last-bucket overflow is intentional — correctness (not losing steps) takes priority over plausibility. The consuming app can inspect `hcMetadata` to determine if a bucket's value seems unreasonable.

### Example: Cap Overflow

```
2 zero-step buckets, surplus = 200, cap = 90
Total capacity: 2 × 90 = 180
Each zero bucket gets: 90
Leftover: 200 - 180 = 20
Last in-window bucket gets: 90 + 20 = 110 (uncapped)
```

## Summary: Double-Counting Prevention

There are three layers where the same physical steps could be counted twice:

| Layer | Source A | Source B | Prevention |
|-------|----------|----------|------------|
| Phone sensor vs HC | Phone TYPE_STEP_COUNTER | Watch via Health Connect | **Subtract-and-fill**: HC surplus = hcCount - phoneTotal. Only the surplus (steps the phone didn't see) gets written. Phone data is never overwritten. |
| HC source vs HC source | Samsung Health | Google Fit (or other) | `processHcRecords` groups by `dataOrigin`, MAX across sources per bucket |
| Within a single tick | phoneDelta | hcCurrentBucket | Phone writes first; HC only adds surplus via subtract-and-fill |

**Current code** only handles within-tick merging via `MAX(phoneDelta, hcDelta)`. Late-arriving HC data double-counts against phone data already in past buckets.

**Proposed code** handles all three layers. The subtract-and-fill algorithm ensures HC data only adds steps the phone didn't already count. Phone sensor data is always preserved as-is since it has the best per-bucket temporal accuracy.
