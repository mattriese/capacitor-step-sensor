## Android Foreground Step Sensor Service: Implementation Design

_This section specifies how to build the `@deloreanhovercraft/capacitor-step-sensor` Capacitor plugin — a standalone plugin (package ID `com.deloreanhovercraft.capacitor.stepsensor`) that provides a dual-source foreground step tracking service for Android. It covers the technical architecture, data flow, permissions, AlarmManager scheduling, and iOS stubs._

### Why this is needed

Health Connect is a passive data store — it only contains what other apps write to it. Samsung Health writes step data in 10-minute or daily buckets. Garmin writes in 15-minute blocks. No Health Connect query can produce per-30-second step counts from these coarse records.

The foreground service solves this with a **dual-source approach**:

1. **Phone sensor (`TYPE_STEP_COUNTER`)**: reads the phone's hardware accelerometer directly, producing genuine 30-second step buckets. This is the primary data source when the phone is on the user's body.
2. **Health Connect polling**: polls Health Connect via `getChanges()` every 30 seconds to detect watch step data as it arrives from the companion app (Samsung Health, Garmin Connect, etc.). This captures watch-only activity (phone on desk while user walks with watch) with ~2-5 minute temporal resolution.

Neither source alone is sufficient. The phone sensor misses watch-only activity. Health Connect alone has coarse granularity from the companion app's write cadence. Together, they cover all scenarios.

### Architecture overview

```
┌─────────────────────────────────────────────────────────────┐
│  Flow Ionic App (Capacitor WebView)                         │
│                                                             │
│  FitnessSyncService.ts                                      │
│    ├── scheduleStepTracking() ──→  Plugin JS bridge         │
│    ├── startStepTracking()    ──→  Plugin JS bridge         │
│    ├── stopStepTracking()     ──→  Plugin JS bridge         │
│    └── getTrackedSteps()      ──→  Plugin JS bridge         │
│              │                         │                    │
└──────────────┼─────────────────────────┼────────────────────┘
               │     Capacitor bridge    │
┌──────────────┼─────────────────────────┼────────────────────┐
│  StepSensorPlugin.kt                  │                    │
│    @PluginMethod scheduleStepTracking()                     │
│    @PluginMethod startStepTracking()   │                    │
│    @PluginMethod stopStepTracking()    │                    │
│    @PluginMethod getTrackedSteps()     │                    │
│              │                                              │
│  StepTrackingScheduler.kt                                   │
│    ├── AlarmManager.setExactAndAllowWhileIdle()              │
│    └── Persists windows to SharedPreferences                │
│              │                                              │
│  StepTrackingAlarmReceiver.kt (BroadcastReceiver)           │
│    ├── ACTION_START_TRACKING → startForegroundService()     │
│    └── ACTION_STOP_TRACKING  → stopService()                │
│              │                                              │
│  BootCompletedReceiver.kt (BroadcastReceiver)               │
│    └── Reads SharedPreferences → re-registers alarms        │
│              │                                              │
│  StepCounterService.kt (foreground service)                 │
│    ├── Source 1: TYPE_STEP_COUNTER sensor listener           │
│    │     └── Phone accelerometer → 30-sec delta buckets     │
│    ├── Source 2: Health Connect poller (getChanges)          │
│    │     └── Watch steps via companion app → delta buckets  │
│    ├── 30-second interval timer (drives both sources)       │
│    ├── Merge logic: MAX(phone_steps, hc_delta) per bucket   │
│    └── Writes merged result to SQLite → step_sensor_log     │
│                                                             │
│  Persistent notification: "Tracking steps for Flow"         │
└─────────────────────────────────────────────────────────────┘
```

### How the dual sources work

#### Source 1: Phone sensor (`TYPE_STEP_COUNTER`)

Android's `TYPE_STEP_COUNTER` is a hardware sensor backed by a low-power motion coprocessor (same chip that counts steps for Google Fit). It returns a **cumulative step count since the last device reboot**. The sensor fires `onSensorChanged` events whenever the count changes (batching varies by device).

The service does NOT poll the sensor — it registers a `SensorEventListener` and receives callbacks. To produce 30-second buckets, the service uses a **30-second interval timer** that reads the latest cumulative count and computes the delta since the last interval:

```kotlin
// Pseudocode — phone sensor
var lastSensorCount: Long = -1

// Called by 30-second timer
fun recordPhoneSensorInterval(): Long {
    val currentCount = latestSensorValue  // from onSensorChanged
    val delta = if (lastSensorCount >= 0) currentCount - lastSensorCount else 0
    lastSensorCount = currentCount
    return delta
}
```

**Reboot handling**: `TYPE_STEP_COUNTER` resets to 0 on device reboot. The service detects this when the new value is less than `lastSensorCount` and resets the baseline. Steps during the reboot gap are lost (unavoidable — the coprocessor doesn't persist across reboots).

**Limitation**: This sensor reads ONLY the phone's own accelerometer. If the phone is on a desk while the user walks with a watch, the sensor reads 0 steps. Source 2 covers this gap.

#### Source 2: Health Connect poller

The service also polls Health Connect via `getChanges()` every 30 seconds using a changes token. When the watch companion app (Samsung Health, Garmin Connect, Fitbit) syncs new step data from the watch to Health Connect, the poller detects the new or updated `StepsRecord` entries and computes the delta in total steps since the last poll.

```kotlin
// Pseudocode — Health Connect poller
var changesToken: String = healthConnectClient.getChangesToken(StepsRecord::class)
var lastHcTotal: Long = -1

// Called by 30-second timer (same timer as phone sensor)
suspend fun recordHcInterval(): Long {
    val changes = healthConnectClient.getChanges(changesToken)
    changesToken = changes.nextChangesToken

    // Sum all step records currently in HC for the tracking window
    val currentTotal = healthConnectClient.aggregate(
        AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), timeRange)
    ).get(StepsRecord.COUNT_TOTAL) ?: 0

    val delta = if (lastHcTotal >= 0) max(0, currentTotal - lastHcTotal) else 0
    lastHcTotal = currentTotal
    return delta
}
```

**Timing limitation**: Watch companion apps sync to the phone every ~2-5 minutes (Samsung Health) or ~1-5 minutes (Garmin Connect, Fitbit). So HC deltas arrive in bursts rather than smoothly:

```
t=0:    HC total = 5000 → delta = 0
t=30s:  HC total = 5000 → delta = 0   (companion app hasn't synced)
t=60s:  HC total = 5000 → delta = 0
t=90s:  HC total = 5000 → delta = 0
t=120s: HC total = 5045 → delta = 45  (companion app synced a batch)
```

The 45 steps get attributed to the t=120s bucket. In reality they were spread across the prior 2 minutes. This is imperfect but far better than the alternative (10-minute or daily buckets from Health Connect's raw records).

#### Merging the two sources

On each 30-second timer tick, the service computes deltas from both sources and writes `MAX(phoneDelta, hcDelta)` to SQLite:

```kotlin
fun onTimerTick() {
    val phoneDelta = recordPhoneSensorInterval()
    val hcDelta = recordHcInterval()
    val steps = max(phoneDelta, hcDelta)
    val bucketStart = floorTo30Seconds(Instant.now().minusSeconds(30))
    val bucketEnd = bucketStart.plusSeconds(30)
    if (steps > 0) {
        db.insertOrUpdate(bucketStart, bucketEnd, steps)
    }
}
```

**Why MAX, not SUM**: The phone sensor and Health Connect often count the same steps. On Android 14+, the OS automatically writes `TYPE_STEP_COUNTER` data to Health Connect as `DataOrigin("android")`. If we summed both, we'd double-count phone steps. `MAX` ensures we take the higher of the two — usually the phone sensor (which has precise timing) unless the phone was stationary and only the watch recorded steps.

| Scenario                       | Phone delta | HC delta                         | MAX | Correct?                  |
| ------------------------------ | ----------- | -------------------------------- | --- | ------------------------- |
| Phone in pocket, walking       | 45          | 0 (HC hasn't synced yet)         | 45  | Yes — phone caught it     |
| Phone in pocket, HC syncs      | 45          | 45 (same steps)                  | 45  | Yes — no double-count     |
| Phone on desk, watch walking   | 0           | 45 (watch synced)                | 45  | Yes — HC caught it        |
| Phone in pocket, watch also on | 45          | 50 (watch counted slightly more) | 50  | Acceptable — takes higher |

**Edge case**: When HC syncs a batch that covers multiple 30-second windows, the entire batch delta appears in a single bucket. This is a known imprecision — the steps are attributed to the sync moment, not spread across the actual activity period. This is still better than Health Connect's native granularity (10-minute Samsung buckets).

### Data storage: local SQLite, not notifyListeners

The service writes step data to a local SQLite table, NOT via Capacitor's `notifyListeners`. This is a critical design decision:

- `notifyListeners` only works when the Capacitor WebView is alive. If the user swipes the app away, the WebView is destroyed but the foreground service keeps running. Events emitted to a dead WebView are lost.
- A local SQLite table persists regardless of WebView state. When the app returns to the foreground, the plugin reads accumulated data from the table and returns it to JS.

```sql
CREATE TABLE IF NOT EXISTS step_sensor_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bucket_start TEXT NOT NULL,     -- ISO 8601 timestamp
    bucket_end TEXT NOT NULL,       -- ISO 8601 timestamp
    steps INTEGER NOT NULL,         -- Step count for this 30-second interval
    created_at TEXT NOT NULL,
    UNIQUE(bucket_start)
);
```

The `getTrackedSteps()` plugin method reads from this table and returns the rows to JS. `FitnessSyncService` can then process these like any other step data — running them through `rollupStepsTo30sBuckets` is unnecessary since they're already in 30-second buckets.

### Plugin API surface

Four methods for step tracking:

```typescript
interface StepSensorPlugin {
  /**
   * Schedule the foreground service to start and stop at specific times.
   * Registers AlarmManager exact alarms for each window. The service starts
   * automatically at each startAt time (even if the app is closed) and stops
   * at each endAt time. Alarms survive app backgrounding and Doze mode.
   *
   * Call this when commitments are created/modified and on app open
   * (to re-register in case alarms were cleared by force-stop).
   * Replaces any previously scheduled windows.
   */
  scheduleStepTracking(options: {
    windows: Array<{
      startAt: string; // ISO 8601 timestamp
      endAt: string; // ISO 8601 timestamp
    }>;
  }): Promise<void>;

  /**
   * Start the foreground step counter service immediately.
   * Shows a persistent notification. Registers TYPE_STEP_COUNTER sensor
   * and Health Connect poller. Records 30-second step buckets to SQLite.
   * No-op if already running. Called internally by the alarm receiver,
   * but can also be called directly from JS for immediate start.
   */
  startStepTracking(): Promise<void>;

  /**
   * Stop the foreground step counter service immediately.
   * Removes the notification. Unregisters sensor and HC poller.
   * No-op if not running.
   */
  stopStepTracking(): Promise<void>;

  /**
   * Read accumulated step data from the local sensor log.
   * Returns all rows since the given timestamp (or all rows if no timestamp).
   * Optionally deletes returned rows after reading (consume pattern).
   */
  getTrackedSteps(options?: {
    since?: string; // ISO 8601 timestamp
    deleteAfterRead?: boolean;
  }): Promise<{
    steps: Array<{
      bucketStart: string;
      bucketEnd: string;
      steps: number;
    }>;
  }>;
}
```

### Android permissions and manifest

```xml
<!-- Foreground service permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- AlarmManager scheduling -->
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- Re-register alarms after device reboot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Service declaration (inside <application>) -->
<service
    android:name=".StepCounterService"
    android:foregroundServiceType="health"
    android:exported="false" />

<!-- Alarm receiver: starts/stops the service at scheduled times -->
<receiver
    android:name=".StepTrackingAlarmReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.deloreanhovercraft.capacitor.stepsensor.ACTION_START_TRACKING" />
        <action android:name="com.deloreanhovercraft.capacitor.stepsensor.ACTION_STOP_TRACKING" />
    </intent-filter>
</receiver>

<!-- Boot receiver: re-registers alarms after device reboot -->
<receiver
    android:name=".BootCompletedReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

| Permission                  | Type                           | Purpose                                                                                                            |
| --------------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `FOREGROUND_SERVICE`        | Normal (auto-granted)          | Required for any foreground service                                                                                |
| `FOREGROUND_SERVICE_HEALTH` | Normal (auto-granted)          | Required for health-type foreground service on Android 14+                                                         |
| `ACTIVITY_RECOGNITION`      | **Dangerous (runtime prompt)** | Required for `TYPE_STEP_COUNTER` sensor access                                                                     |
| `POST_NOTIFICATIONS`        | **Dangerous on Android 13+**   | Required for the persistent notification                                                                           |
| `USE_EXACT_ALARM`           | Normal (auto-granted)          | Required for AlarmManager exact alarms. Auto-granted for apps with timer/scheduling functionality (Flow qualifies) |
| `RECEIVE_BOOT_COMPLETED`    | Normal (auto-granted)          | Required to receive the `BOOT_COMPLETED` broadcast and re-register alarms                                          |

`ACTIVITY_RECOGNITION` must be requested at runtime before starting the service. The plugin's `startStepTracking()` method should check and request this permission if not already granted, similar to how `requestAuthorization()` handles Health Connect permissions today.

### Service lifecycle

| Event                                   | Behavior                                                                                                                                                                                                                                                                                                                |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `scheduleStepTracking()` called         | Registers AlarmManager exact alarms for each window's start and end time. Persists the window list to SharedPreferences (for reboot recovery). No service started yet.                                                                                                                                                  |
| Alarm fires (start time)                | `StepTrackingAlarmReceiver` receives the broadcast and calls `startForegroundService()`. This is an Android 12 exemption — AlarmManager-triggered receivers are allowed to start foreground services from the background. Service starts within 5 seconds with persistent notification, sensor listener, and HC poller. |
| Alarm fires (end time)                  | `StepTrackingAlarmReceiver` receives the broadcast and stops the service. Unregisters sensor, cancels timer, removes notification.                                                                                                                                                                                      |
| `startStepTracking()` called directly   | Starts the service immediately (same as alarm-triggered start). Used for preemptive start when the app is open and a commitment window is imminent.                                                                                                                                                                     |
| App goes to background                  | Service continues running. Sensor listener continues receiving events. Timer continues writing to SQLite.                                                                                                                                                                                                               |
| App swiped away (Activity destroyed)    | WebView is destroyed, but service continues (`START_STICKY`). Sensor data continues accumulating in SQLite. Scheduled alarms remain registered.                                                                                                                                                                         |
| App returns to foreground               | Plugin re-binds to the running service. `getTrackedSteps()` reads accumulated data from SQLite.                                                                                                                                                                                                                         |
| `stopStepTracking()` called directly    | Stops the service immediately. Does NOT cancel scheduled alarms — future windows still fire.                                                                                                                                                                                                                            |
| Device rebooted                         | Service stops. `BootCompletedReceiver` fires, reads persisted windows from SharedPreferences, and re-registers AlarmManager alarms for any future windows. Next scheduled window starts the service automatically. Steps during the reboot gap are lost (unavoidable).                                                  |
| OS kills service (battery optimization) | With `START_STICKY`, the OS attempts to restart. If Samsung battery optimization prevents restart, steps during the gap are lost. User should whitelist the app. Scheduled alarms are unaffected — the next window's alarm will start a fresh service.                                                                  |
| App force-stopped by user               | All alarms are cleared. Service stops. On next app launch, `scheduleStepTracking()` must be called again to re-register alarms.                                                                                                                                                                                         |

### Starting the service from background: AlarmManager

Android 12 (API 31) introduced **foreground service launch restrictions**: apps cannot call `startForegroundService()` from the background. The plugin uses `AlarmManager.setExactAndAllowWhileIdle()` to work around this. AlarmManager-triggered `BroadcastReceiver`s are one of the explicit exemptions that allow starting a foreground service from the background.

The user should never have to open the app or interact with their phone at a specific time for accurate tracking. The plugin handles this autonomously.

#### How it works

1. **JS calls `scheduleStepTracking({ windows })`** when commitments are created or modified. The plugin registers two AlarmManager exact alarms per window: one for the start time, one for the end time.
2. **At start time**: `StepTrackingAlarmReceiver` fires, calls `startForegroundService()`, and the service begins tracking. This works even if the app is in the background, swiped away, or the device is in Doze mode.
3. **At end time**: `StepTrackingAlarmReceiver` fires and stops the service.
4. **On device reboot**: `BootCompletedReceiver` fires, reads persisted windows from SharedPreferences, and re-registers alarms for any future windows.
5. **On app open**: JS calls `scheduleStepTracking()` again as defense-in-depth (re-registers alarms in case they were cleared by force-stop).

#### Native components the plugin must include

| Component              | Class                       | Purpose                                                                                                                                                                                                                 |
| ---------------------- | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Foreground service** | `StepCounterService`        | Runs the dual-source step tracking (sensor + HC poller). Manages notification, sensor listener, timer.                                                                                                                  |
| **Alarm receiver**     | `StepTrackingAlarmReceiver` | `BroadcastReceiver` that receives AlarmManager intents and starts/stops the service. Handles two actions: `ACTION_START_TRACKING` and `ACTION_STOP_TRACKING`.                                                           |
| **Boot receiver**      | `BootCompletedReceiver`     | `BroadcastReceiver` for `android.intent.action.BOOT_COMPLETED`. Reads persisted windows from SharedPreferences, re-registers AlarmManager alarms for future windows.                                                    |
| **Alarm scheduler**    | `StepTrackingScheduler`     | Utility class that wraps `AlarmManager.setExactAndAllowWhileIdle()`. Called by the plugin method, the boot receiver, and (optionally) directly from JS for preemptive start. Persists window list to SharedPreferences. |

#### Edge cases

- **Force-stop**: If the user force-stops the app via Settings, all alarms are cleared and the boot receiver is disabled until the app is reopened. On next app launch, `scheduleStepTracking()` re-registers everything.
- **`USE_EXACT_ALARM` permission**: Auto-granted for apps with timer/scheduling functionality. Flow qualifies since it has time-blocked commitments. No user prompt required.
- **Multiple overlapping windows**: The scheduler should merge overlapping windows into a single start/stop pair to avoid unnecessary service restarts.
- **Past windows**: `scheduleStepTracking()` should silently skip any window whose `endAt` is already in the past. If a window's `startAt` is in the past but `endAt` is in the future, start immediately.

#### Alternatives considered but not used

**FCM push notification**: Server sends a high-priority message at commitment start time. Rejected as primary mechanism because it requires internet, server infrastructure (pg_cron or Edge Function), and risks FCM deprioritization. Could be added later for server-triggered commitment changes.

**Preemptive start on app open**: Start the service early when the app opens. Used as supplemental defense-in-depth (JS calls `startStepTracking()` directly if the next window is within 30 minutes), but not relied on as the primary mechanism since it requires the user to open the app.

### Understanding the two data sources

#### Phone sensor (`TYPE_STEP_COUNTER`): what it reads and what it doesn't

`TYPE_STEP_COUNTER` reads the phone's built-in hardware accelerometer/motion coprocessor. It does **not** receive step data from a connected wearable (Galaxy Watch, Pixel Watch, Garmin, Fitbit, etc.).

- The sensor counts steps detected by the phone's own accelerometer. Phone in pocket = steps counted. Phone on desk = 0 steps, even if the user is walking with a watch.
- Cumulative since last device reboot. Never decreases (except on reboot when it resets to 0).
- Fires `onSensorChanged` callbacks when the count changes. No guaranteed cadence.
- Driven by a low-power coprocessor independent of the main CPU. Battery impact is negligible.

Watch step data follows a completely separate path: watch accelerometer → watch companion app → Bluetooth sync to phone → companion app writes to Health Connect. This data never touches the phone's hardware step sensor.

#### Health Connect poller: catching watch steps

The Health Connect poller in the foreground service closes the gap left by the phone-only sensor. By polling `getChanges()` every 30 seconds, the service detects watch step data as soon as the companion app syncs it to Health Connect.

On Android 14+, the OS automatically writes `TYPE_STEP_COUNTER` data to Health Connect as `StepsRecord` entries with `DataOrigin("android")`. This means Health Connect may contain two sources:

- `DataOrigin("android")` — phone sensor steps, written by the OS
- `DataOrigin("com.sec.android.app.shealth")` (or similar) — watch steps, synced via companion app

Health Connect's built-in deduplication is priority-based selection: it picks the highest-priority source per time window and uses that source entirely. It does NOT sum across sources (that would double-count).

The `MAX(phoneDelta, hcDelta)` merge strategy in our service handles deduplication regardless of Health Connect's priority settings — see "Merging the two sources" above.

#### Remaining limitation: companion app sync delay

The HC poller's temporal resolution is limited by how fast the watch companion app syncs to the phone:

| Companion app            | Typical sync cadence | Steps attributed to...                  |
| ------------------------ | -------------------- | --------------------------------------- |
| Samsung Health           | ~2-5 minutes         | The 30-sec bucket when the sync arrives |
| Garmin Connect           | ~1-5 minutes         | The 30-sec bucket when the sync arrives |
| Fitbit                   | ~1-5 minutes         | The 30-sec bucket when the sync arrives |
| Google Fit (Pixel Watch) | ~1-2 minutes         | The 30-sec bucket when the sync arrives |

When the user carries their phone, the phone sensor provides exact 30-second timing. When the phone is stationary, the HC poller captures watch steps but with a ~2-5 minute attribution delay. This delay is acceptable for duration and quantity commitments but can cause inaccuracy for tight interval commitments (e.g., "walk 200 steps every 30 minutes" — the steps might be attributed to the wrong 30-second window within the interval).

### Long-term improvement: Wear OS companion app

_A dedicated watch app eliminates the companion app sync delay entirely by reading the watch's step sensor directly and streaming data to the phone in real time._

The foreground service's HC poller gets watch steps within ~2-5 minutes. A Wear OS companion app running on the watch could stream step counts to the phone over Wear OS `MessageClient` with sub-second latency.

#### How it would work

1. **Watch app**: A lightweight Wear OS app registers a `SensorEventListener` for `TYPE_STEP_COUNTER` on the watch. On a 30-second timer (same cadence as the phone service), it computes the delta and sends it to the phone via `MessageClient.sendMessage()`.
2. **Phone service**: The foreground service's `MessageClient.OnMessageReceivedListener` receives the watch delta and merges it with the phone delta using the same `MAX` strategy.
3. **Result**: True 30-second step buckets from BOTH phone and watch sensors, with no companion app sync delay.

#### What it requires

- A **Wear OS app** (separate APK, distributed via Google Play paired with the phone app)
- Wear OS 3+ (Samsung Galaxy Watch 4 and later, Pixel Watch, other Wear OS 3 devices)
- **Does NOT work** with non-Wear OS devices: Garmin watches, Fitbit trackers (non-Pixel), older Samsung watches (Tizen-based Galaxy Watch 3 and earlier)
- Wear OS development environment (Android Studio, Wear OS emulator or physical device for testing)

#### Scope

This is a significant effort — a separate APK with its own manifest, activity, and tile/complication support. It should be built after the phone foreground service is proven and stable. The watch app could also eventually support HR monitoring (direct watch sensor access for HR commitments without requiring a workout start), but that's a further extension.

#### Why not build this first?

The foreground service with HC polling covers the majority of use cases without a watch app:

- Phone in pocket: phone sensor provides exact 30-second data
- Phone on desk: HC poller catches watch steps with ~2-5 min delay (good enough for duration/quantity commitments)
- The watch app only adds value for the narrow case of watch-only activity with tight interval timing requirements

Build the phone foreground service first. Add the watch app when interval commitment accuracy for watch-only activity becomes a priority.

### Integration with FitnessSyncService

`FitnessSyncService` would call `startStepTracking()` when entering a fitness commitment window and `stopStepTracking()` when leaving it. On each sync cycle:

1. Call `getTrackedSteps({ since: lastSyncTimestamp, deleteAfterRead: true })`.
2. The returned rows are already in 30-second buckets (merged from phone sensor + HC poller) — no rollup needed.
3. Write directly to `fitness_steps_30s` via the existing persist logic (upsert by `user_id + bucket_start`).
4. These service-sourced buckets take priority over Health Connect-sourced buckets from the regular sync pipeline (they're more granular and already deduplicated).

The existing `readStepSamples()` + rollup pipeline continues to run in parallel as a fallback for periods when the foreground service wasn't running (app not launched, service killed, device rebooted).

### iOS: Swift stubs (no-ops)

iOS does not need a step sensor service. The iPhone motion coprocessor writes ~1-minute interval step samples to HealthKit automatically, and the existing `readSamples` / `getChanges` pipeline handles them.

The Capacitor plugin generator creates an iOS Swift source file alongside the Android Kotlin source. All four plugin methods must be implemented in Swift as immediate no-ops that resolve successfully:

```swift
// StepSensorPlugin.swift
@objc func scheduleStepTracking(_ call: CAPPluginCall) {
    // No-op on iOS — HealthKit handles step tracking natively
    call.resolve()
}

@objc func startStepTracking(_ call: CAPPluginCall) {
    call.resolve()
}

@objc func stopStepTracking(_ call: CAPPluginCall) {
    call.resolve()
}

@objc func getTrackedSteps(_ call: CAPPluginCall) {
    // Return empty array — iOS step data comes through the health plugin's getChanges() pipeline
    call.resolve(["steps": []])
}
```

No iOS permissions, background modes, or native dependencies are needed for this plugin.
