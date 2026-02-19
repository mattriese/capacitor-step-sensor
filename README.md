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

### 2. Schedule tracking windows

Call this when your app knows the time windows during which it needs step tracking (e.g., commitment windows). The plugin registers AlarmManager alarms that start and stop the foreground service automatically -- even if the app is closed or the phone is in Doze mode.

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

Call this:
- When commitments are created or modified
- On every app open (defense-in-depth -- re-registers alarms in case they were cleared by a force-stop)

Each call replaces all previously scheduled windows. Overlapping windows are automatically merged. Past windows are skipped. If a window's `startAt` is in the past but `endAt` is in the future, the service starts immediately.

### 3. Start/stop tracking manually (optional)

You can also start and stop the service directly without scheduling:

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

### 4. Read accumulated step data

The service writes step counts to a local SQLite database in 30-second buckets. Read them back anytime -- even while the service is running:

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

// Read and delete (consume pattern -- clears data after reading)
const consumed = await StepSensor.getTrackedSteps({
  since: lastSyncTimestamp,
  deleteAfterRead: true,
});
```

## How It Works

The Android foreground service uses two data sources:

| Source | What it reads | Granularity | Limitation |
| --- | --- | --- | --- |
| Phone sensor (`TYPE_STEP_COUNTER`) | Phone's hardware accelerometer | Exact 30-second buckets | Misses steps when the phone is on a desk |
| Health Connect poller (`getChanges`) | Watch steps synced via companion app | ~1-15 minute records | Depends on companion app sync cadence |

Every 30 seconds, the service writes phone sensor steps to the current bucket. When Health Connect records arrive (e.g., from a watch sync), the service uses a **subtract-and-fill** algorithm: it subtracts the phone steps already recorded in the covered buckets, then distributes the surplus into only the zero-step buckets (gaps the phone didn't capture). This avoids double-counting while preserving the phone's per-bucket temporal accuracy.

**Commitment boundary handling:** HC records that overlap the start or end of a scheduled tracking window are fully credited (all steps count), but only written to buckets within the window. Each zero-step bucket is capped at 90 steps/30s (180 steps/min) to filter physically impossible values.

See [HC_TEMPORAL_ACCURACY.md](HC_TEMPORAL_ACCURACY.md) for the full algorithm design.

## API Reference

<docgen-index>

* [`scheduleStepTracking(...)`](#schedulesteptracking)
* [`startStepTracking(...)`](#startsteptracking)
* [`stopStepTracking()`](#stopsteptracking)
* [`getTrackedSteps(...)`](#gettrackedsteps)
* [Interfaces](#interfaces)

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
Optionally deletes returned rows after reading (consume pattern).

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#gettrackedstepsoptions">GetTrackedStepsOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#gettrackedstepsresult">GetTrackedStepsResult</a>&gt;</code>

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

| Prop        | Type                      |
| ----------- | ------------------------- |
| **`steps`** | <code>StepBucket[]</code> |


#### StepBucket

| Prop              | Type                | Description                                                              |
| ----------------- | ------------------- | ------------------------------------------------------------------------ |
| **`bucketStart`** | <code>string</code> | ISO 8601 timestamp for the start of this 30-second bucket.               |
| **`bucketEnd`**   | <code>string</code> | ISO 8601 timestamp for the end of this 30-second bucket.                 |
| **`steps`**       | <code>number</code> | Number of steps recorded in this bucket.                                 |
| **`hcMetadata`**  | <code>string</code> | Raw Health Connect records JSON, if HC data contributed to this bucket.   |


#### HcRecord

The `hcMetadata` field, when present, is a JSON string containing an array of HC record objects:

| Prop              | Type                | Description                                      |
| ----------------- | ------------------- | ------------------------------------------------ |
| **`startTime`**   | <code>string</code> | ISO 8601 timestamp for the start of the record.  |
| **`endTime`**     | <code>string</code> | ISO 8601 timestamp for the end of the record.    |
| **`count`**       | <code>number</code> | Number of steps in this HC record.               |
| **`dataOrigin`**  | <code>string</code> | Package name of the app that wrote the record.   |


#### GetTrackedStepsOptions

| Prop                  | Type                 | Description                                                             |
| --------------------- | -------------------- | ----------------------------------------------------------------------- |
| **`since`**           | <code>string</code>  | ISO 8601 timestamp. Only return buckets starting at or after this time. |
| **`deleteAfterRead`** | <code>boolean</code> | If true, delete returned rows from the local database after reading.    |

</docgen-api>
