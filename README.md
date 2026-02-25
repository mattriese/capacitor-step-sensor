# capacitor-step-sensor

A Capacitor plugin that runs a foreground service on Android to track steps from the phone's hardware step sensor and Health Connect, saving 30-second rollup buckets to a local SQLite database. On iOS and web, all methods are no-ops (iOS step tracking is handled natively by HealthKit).

## Install

```bash
npm install capacitor-step-sensor
npx cap sync
```

## Platform Setup

### Android

**No manual permission setup required.** The plugin's `AndroidManifest.xml` declares all necessary permissions and they are automatically merged into your app's manifest during `npx cap sync`:

- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_HEALTH` -- running the step tracking service
- `ACTIVITY_RECOGNITION` -- accessing the phone's step counter sensor (runtime prompt)
- `POST_NOTIFICATIONS` -- showing the persistent notification on Android 13+ (runtime prompt)
- `SCHEDULE_EXACT_ALARM` -- scheduling service start/stop at precise times
- `RECEIVE_BOOT_COMPLETED` -- re-registering alarms after device reboot
- `health.READ_STEPS` -- required for the "health" foreground service type on Android 14+

The plugin handles runtime permission requests automatically when you call `startStepTracking()`. The user will see system prompts for `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS`.

**Optional: Battery optimization whitelist.** On some OEMs (Samsung, Xiaomi, Huawei), aggressive battery optimization may kill the foreground service. Advise users to whitelist your app in battery settings for reliable background tracking.

### iOS

**No setup required.** All plugin methods are no-ops on iOS. Step data on iOS should come through HealthKit via a separate health plugin.

## Usage

### 1. Import the plugin

```typescript
import { StepSensor } from 'capacitor-step-sensor';
```

### 2. Recommended integration

The plugin captures steps through two complementary mechanisms:

| Mechanism | When it runs | What it captures | Limitation |
| --- | --- | --- | --- |
| **Foreground service** (`scheduleStepTracking`) | During commitment windows | Real-time phone sensor + HC data as it arrives | Misses watch data that syncs after the service stops |
| **On-demand backfill** (`backfillFromHealthConnect`) | Whenever your app calls it | Health Connect data for any past time range | No phone sensor data (HC only) |

**You need both.** The foreground service gives you high-resolution, real-time step data during each commitment window. But smartwatch data often syncs to Health Connect 15+ minutes after the walk ends -- after the service has already stopped. The backfill reads that late-arriving data from Health Connect and merges it into your existing step buckets.

You can pass your full list of commitment windows to both calls. Each handles the right subset automatically:
- `scheduleStepTracking` **skips past windows** and schedules alarms for future ones
- `backfillFromHealthConnect` **processes past windows** by querying Health Connect for each time range

On every app open or resume, call both, then read the latest data:

```typescript
import { App } from '@capacitor/app';
import { StepSensor } from 'capacitor-step-sensor';

let syncToken: string | undefined;

App.addListener('appStateChange', async ({ isActive }) => {
  if (!isActive) return;

  const windows = getCommitmentWindows(); // your app logic

  // Re-register alarms for future windows (defense-in-depth against force-stop)
  await StepSensor.scheduleStepTracking({ windows });

  // Backfill past windows with late-arriving watch data from Health Connect
  await StepSensor.backfillFromHealthConnect({ windows });

  // Read only buckets that changed since last sync
  const result = await StepSensor.getTrackedSteps({
    modifiedSince: syncToken,
  });
  syncToken = result.syncToken;

  if (result.steps.length > 0) {
    upsertToYourDatabase(result.steps);
  }
});
```

The `syncToken` / `modifiedSince` pattern ensures you only receive buckets that were created or updated since your last read. This is important because backfill can retroactively update old buckets when late-arriving watch data arrives. Without `modifiedSince`, you'd need to re-read and diff all buckets on every poll.

Also call `scheduleStepTracking` whenever commitments are created or modified -- not just on app open.

The sections below explain each method in more detail.

### 3. Schedule tracking windows

`scheduleStepTracking` registers AlarmManager exact alarms that start and stop the foreground service automatically -- even if the app is closed or the phone is in Doze mode.

```typescript
await StepSensor.scheduleStepTracking({
  windows: [
    {
      startAt: '2025-03-15T09:00:00.000Z',
      endAt: '2025-03-15T10:00:00.000Z',
    },
    {
      startAt: '2025-03-15T14:00:00.000Z',
      endAt: '2025-03-15T15:30:00.000Z',
    },
  ],
});
```

Each call replaces all previously scheduled windows. Overlapping windows are automatically merged. Past windows are skipped. If a window's `startAt` is in the past but `endAt` is in the future, the service starts immediately.

### 4. Backfill from Health Connect

`backfillFromHealthConnect` queries Health Connect using `readRecords()` (explicit time-range queries) for each window, then runs the subtract-and-fill algorithm against your existing SQLite data. This is idempotent -- safe to call repeatedly, since the MAX upsert in SQLite prevents double-counting.

```typescript
const { backedUp } = await StepSensor.backfillFromHealthConnect({
  windows: [
    {
      startAt: '2025-03-15T09:00:00.000Z',
      endAt: '2025-03-15T10:00:00.000Z',
    },
  ],
});
// backedUp === false if Health Connect is unavailable or permissions not granted
```

Unlike the foreground service (which uses `getChanges` with a token), backfill has no token to manage -- it works even if the service never started, or if the app was force-stopped and tokens were lost.

On iOS/web, this resolves with `{ backedUp: false }` (no-op).

### 5. Read accumulated step data

Both the foreground service and backfill write to the same SQLite database. Read step data back anytime -- even while the service is running:

```typescript
// Get all tracked steps
const result = await StepSensor.getTrackedSteps();

for (const bucket of result.steps) {
  console.log(`${bucket.bucketStart} - ${bucket.bucketEnd}: ${bucket.steps} steps`);
}

// Get steps since a specific time
const recent = await StepSensor.getTrackedSteps({
  since: '2025-03-15T09:00:00.000Z',
});

// Incremental sync: only get buckets modified since last poll
const changed = await StepSensor.getTrackedSteps({
  modifiedSince: lastSyncToken,
});
// Save changed.syncToken for next call
```

**Incremental sync with `modifiedSince`:** Each response includes a `syncToken`. Pass it back as `modifiedSince` on the next call to get only buckets that were created or updated since that point. This is useful for syncing to an external database without re-reading everything. Buckets updated by backfill (retroactive watch data) will appear in the next incremental read.

### 6. Start/stop tracking manually (optional)

You can also start and stop the foreground service directly without scheduling:

```typescript
// Start immediately (requests permissions if needed)
await StepSensor.startStepTracking();

// With custom notification text
await StepSensor.startStepTracking({
  notificationTitle: 'Tracking your walk',
  notificationText: 'Step counting is active',
});

// Stop immediately
await StepSensor.stopStepTracking();
```

If no custom notification text is provided, the notification defaults to "Tracking steps for \<Your App Name\>" using your app's display name from `AndroidManifest.xml`.

### 7. Clear data

Use `clearData()` to delete step data from the local database. This replaces the old `deleteAfterRead` option on `getTrackedSteps`, which was removed because deleting rows before late watch syncs arrive would break subtract-and-fill.

```typescript
// Delete all data
await StepSensor.clearData();

// Delete data older than a specific time
await StepSensor.clearData({
  before: '2025-03-01T00:00:00.000Z',
});
```

## How It Works

### Real-time tracking (foreground service)

The Android foreground service uses two data sources:

| Source | What it reads | Granularity | Limitation |
| --- | --- | --- | --- |
| Phone sensor (`TYPE_STEP_COUNTER`) | Phone's hardware accelerometer | Exact 30-second buckets | Misses steps when the phone is on a desk |
| Health Connect poller (`getChanges`) | Watch steps synced via companion app | ~1-15 minute records | Depends on companion app sync cadence |

Every 30 seconds, the service writes phone sensor steps to the current bucket. When Health Connect records arrive (e.g., from a watch sync), the service uses a **subtract-and-fill** algorithm: it subtracts the phone steps already recorded in the covered buckets, then distributes the surplus into only the zero-step buckets (gaps the phone didn't capture). This avoids double-counting while preserving the phone's per-bucket temporal accuracy.

**Commitment boundary handling:** HC records that overlap the start or end of a scheduled tracking window are fully credited (all steps count), but only written to buckets within the window. Each zero-step bucket is capped at 90 steps/30s (180 steps/min) to filter physically impossible values.

### Late-sync backfill

The foreground service stops at each window's `endAt`, but watch data may not sync to Health Connect for 15+ minutes after the walk ends. `backfillFromHealthConnect` fills this gap by querying Health Connect directly with `readRecords(timeRangeFilter)` for each past commitment window. It runs the same subtract-and-fill algorithm against existing database data, so phone sensor steps recorded during the service are preserved and only gaps are filled.

Because `readRecords` queries by time range (not a changes token), backfill is idempotent and stateless -- it works correctly even after app force-stops, crashes, or if the service never ran at all.

See [HC_TEMPORAL_ACCURACY.md](HC_TEMPORAL_ACCURACY.md) for the full algorithm design.

## Local Development (with flow-ionic)

This plugin is consumed by flow-ionic via [yalc](https://github.com/wclr/yalc). After making changes, you need to push the updated plugin and clean the Gradle cache so the new Kotlin code is compiled fresh.

### Quick version (recommended)

From flow-ionic, run the all-in-one script:

```bash
cd ~/webdev/flow-ionic
npm run plugin:push
```

This runs `scripts/yalc-push.sh` in this repo (stamps a git hash build ID, builds TS, pushes to yalc), then runs `scripts/plugin-sync.sh` in flow-ionic (yalc update, gradlew clean, cap sync).

### Manual version

```bash
# 1. In this repo — stamp build ID, build, and push
cd ~/webdev/capacitor-step-sensor
bash scripts/yalc-push.sh

# 2. In flow-ionic — sync the update
cd ~/webdev/flow-ionic
npm run plugin:sync
```

### Verifying the build

Every build is stamped with the git short hash from this repo. You can verify what code is running:

- **Logcat:** When the step tracking service starts, it logs: `Step tracking started | plugin build: <hash>`
- **JS:** Call `StepSensor.getPluginInfo()` — returns `{ buildId: "<hash>" }`
- **Settings UI:** In flow-ionic's GeneralSettings (non-production), the plugin build ID is shown next to the environment badge

If the build ID is `"dev"`, the plugin was built without the push script (e.g. raw `npm run build && yalc push`).

### Why Gradle clean matters

Even when Kotlin source files change on disk, Gradle may use cached compiled `.class` files from a previous build. `npm run plugin:sync` (and `plugin:push`) run `./gradlew clean` to force recompilation. Without this, you may deploy an APK that still runs old plugin code.

## API Reference

<docgen-index>

* [`scheduleStepTracking(...)`](#schedulesteptracking)
* [`startStepTracking(...)`](#startsteptracking)
* [`stopStepTracking()`](#stopsteptracking)
* [`getTrackedSteps(...)`](#gettrackedsteps)
* [`backfillFromHealthConnect(...)`](#backfillfromhealthconnect)
* [`clearData(...)`](#cleardata)
* [`checkExactAlarmPermission()`](#checkexactalarmpermission)
* [`requestExactAlarmPermission()`](#requestexactalarmpermission)
* [`getPluginInfo()`](#getplugininfo)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### scheduleStepTracking(...)

```typescript
scheduleStepTracking(options: ScheduleStepTrackingOptions) => Promise<void>
```

Schedule the foreground service to start and stop at specific times.
Registers AlarmManager exact alarms for each window. The service starts
automatically at each startAt time (even if the app is closed) and stops
at each endAt time. Alarms survive app backgrounding and Doze mode.

Call this when commitments are created/modified and on app open
(to re-register in case alarms were cleared by force-stop).
Replaces any previously scheduled windows.

| Param         | Type                                                                                |
| ------------- | ----------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#schedulesteptrackingoptions">ScheduleStepTrackingOptions</a></code> |

--------------------


### startStepTracking(...)

```typescript
startStepTracking(options?: StartStepTrackingOptions | undefined) => Promise<void>
```

Start the foreground step counter service immediately.
Shows a persistent notification. Registers TYPE_STEP_COUNTER sensor
and Health Connect poller. Records 30-second step buckets to SQLite.
No-op if already running. Called internally by the alarm receiver,
but can also be called directly from JS for immediate start.

| Param         | Type                                                                          |
| ------------- | ----------------------------------------------------------------------------- |
| **`options`** | <code><a href="#startsteptrackingoptions">StartStepTrackingOptions</a></code> |

--------------------


### stopStepTracking()

```typescript
stopStepTracking() => Promise<void>
```

Stop the foreground step counter service immediately.
Removes the notification. Unregisters sensor and HC poller.
No-op if not running.

--------------------


### getTrackedSteps(...)

```typescript
getTrackedSteps(options?: GetTrackedStepsOptions | undefined) => Promise<GetTrackedStepsResult>
```

Read accumulated step data from the local sensor log.
Returns all rows since the given timestamp (or all rows if no timestamp).

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#gettrackedstepsoptions">GetTrackedStepsOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#gettrackedstepsresult">GetTrackedStepsResult</a>&gt;</code>

--------------------


### backfillFromHealthConnect(...)

```typescript
backfillFromHealthConnect(options: BackfillOptions) => Promise<BackfillResult>
```

Backfill step data from Health Connect for past commitment windows.
Reads HC data using readRecords() (explicit time-range queries), runs
subtract-and-fill against existing SQLite data, and writes the results.
Safe to call multiple times (idempotent via MAX upsert).

Call this on every app resume to capture steps recorded after the
foreground service stopped (watch data may sync 15+ minutes late).

On iOS/web, resolves with { backedUp: false } (no-op).

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#backfilloptions">BackfillOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#backfillresult">BackfillResult</a>&gt;</code>

--------------------


### clearData(...)

```typescript
clearData(options?: ClearDataOptions | undefined) => Promise<void>
```

Delete step data from the local database.
If `before` is provided, deletes all buckets with bucketStart before that time.
If omitted, deletes all data.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#cleardataoptions">ClearDataOptions</a></code> |

--------------------


### checkExactAlarmPermission()

```typescript
checkExactAlarmPermission() => Promise<ExactAlarmPermissionResult>
```

Check whether the app has permission to schedule exact alarms.
On Android 12+ (API 31+), SCHEDULE_EXACT_ALARM requires explicit user grant.
Returns { granted: true } on Android &lt; 12, iOS, and web.

**Returns:** <code>Promise&lt;<a href="#exactalarmpermissionresult">ExactAlarmPermissionResult</a>&gt;</code>

--------------------


### requestExactAlarmPermission()

```typescript
requestExactAlarmPermission() => Promise<ExactAlarmPermissionResult>
```

Open the system settings screen where the user can grant exact alarm permission.
On Android 12+, opens Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM.
Resolves immediately with { granted: false } — the caller should re-check
permission after the user returns to the app (e.g. on app resume).
Returns { granted: true } on Android &lt; 12, iOS, and web (no action needed).

**Returns:** <code>Promise&lt;<a href="#exactalarmpermissionresult">ExactAlarmPermissionResult</a>&gt;</code>

--------------------


### getPluginInfo()

```typescript
getPluginInfo() => Promise<PluginInfoResult>
```

Returns build metadata for the plugin. The buildId is a git short hash
stamped at yalc push time, or "dev" if built without the push script.
Use this to verify the running plugin matches the expected source version.

**Returns:** <code>Promise&lt;<a href="#plugininforesult">PluginInfoResult</a>&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<StepSensorPermissionStatus>
```

Check the grant status of ACTIVITY_RECOGNITION and POST_NOTIFICATIONS
permissions. These are auto-generated by Capacitor's base Plugin class
because the native Kotlin plugin declares them via @CapacitorPlugin
permissions aliases.

**Returns:** <code>Promise&lt;<a href="#stepsensorpermissionstatus">StepSensorPermissionStatus</a>&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<StepSensorPermissionStatus>
```

Request ACTIVITY_RECOGNITION and POST_NOTIFICATIONS permissions.
Returns the updated grant status after the user responds to the prompt.

**Returns:** <code>Promise&lt;<a href="#stepsensorpermissionstatus">StepSensorPermissionStatus</a>&gt;</code>

--------------------


### Interfaces


#### ScheduleStepTrackingOptions

| Prop          | Type                          |
| ------------- | ----------------------------- |
| **`windows`** | <code>TrackingWindow[]</code> |


#### TrackingWindow

| Prop          | Type                | Description                                               |
| ------------- | ------------------- | --------------------------------------------------------- |
| **`startAt`** | <code>string</code> | ISO 8601 timestamp (e.g. from `new Date().toISOString()`) |
| **`endAt`**   | <code>string</code> | ISO 8601 timestamp                                        |


#### StartStepTrackingOptions

| Prop                    | Type                | Description                                                                   |
| ----------------------- | ------------------- | ----------------------------------------------------------------------------- |
| **`notificationTitle`** | <code>string</code> | Custom notification title. Defaults to "Tracking steps for &lt;app name&gt;". |
| **`notificationText`**  | <code>string</code> | Custom notification body text. Defaults to "Step counting is active".         |


#### GetTrackedStepsResult

| Prop            | Type                      | Description                                                                                                       |
| --------------- | ------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| **`steps`**     | <code>StepBucket[]</code> |                                                                                                                   |
| **`syncToken`** | <code>string</code>       | ISO 8601 timestamp. Pass this as `modifiedSince` on the next call to get only rows that changed since this query. |


#### StepBucket

| Prop              | Type                | Description                                                             |
| ----------------- | ------------------- | ----------------------------------------------------------------------- |
| **`bucketStart`** | <code>string</code> | ISO 8601 timestamp for the start of this 30-second bucket.              |
| **`bucketEnd`**   | <code>string</code> | ISO 8601 timestamp for the end of this 30-second bucket.                |
| **`steps`**       | <code>number</code> | Number of steps recorded in this bucket.                                |
| **`hcMetadata`**  | <code>string</code> | Raw Health Connect records JSON, if HC data contributed to this bucket. |


#### GetTrackedStepsOptions

| Prop                | Type                | Description                                                                                                                                             |
| ------------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`since`**         | <code>string</code> | ISO 8601 timestamp. Only return buckets starting at or after this time.                                                                                 |
| **`modifiedSince`** | <code>string</code> | ISO 8601 timestamp. Only return buckets modified after this time. Pass the `syncToken` from a previous `getTrackedSteps` call to get only changed rows. |


#### BackfillResult

| Prop           | Type                 | Description                                                       |
| -------------- | -------------------- | ----------------------------------------------------------------- |
| **`backedUp`** | <code>boolean</code> | false if Health Connect is unavailable or permissions not granted |


#### BackfillOptions

| Prop          | Type                          |
| ------------- | ----------------------------- |
| **`windows`** | <code>TrackingWindow[]</code> |


#### ClearDataOptions

| Prop         | Type                | Description                                                                                             |
| ------------ | ------------------- | ------------------------------------------------------------------------------------------------------- |
| **`before`** | <code>string</code> | ISO 8601 timestamp. Delete all buckets with bucketStart before this time. If omitted, deletes all data. |


#### ExactAlarmPermissionResult

| Prop          | Type                 | Description                                                                               |
| ------------- | -------------------- | ----------------------------------------------------------------------------------------- |
| **`granted`** | <code>boolean</code> | Whether the app can schedule exact alarms. Always true on Android &lt; 12 and on web/iOS. |


#### PluginInfoResult

| Prop          | Type                | Description                                                                          |
| ------------- | ------------------- | ------------------------------------------------------------------------------------ |
| **`buildId`** | <code>string</code> | Git short hash stamped at yalc push time, or "dev" if built without the push script. |


#### StepSensorPermissionStatus

| Prop                      | Type                                                        | Description                                         |
| ------------------------- | ----------------------------------------------------------- | --------------------------------------------------- |
| **`activityRecognition`** | <code><a href="#permissionstate">PermissionState</a></code> | Android ACTIVITY_RECOGNITION runtime permission     |
| **`notifications`**       | <code><a href="#permissionstate">PermissionState</a></code> | Android POST_NOTIFICATIONS permission (Android 13+) |


### Type Aliases


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
