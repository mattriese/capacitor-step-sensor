export interface TrackingWindow {
  /** ISO 8601 timestamp (e.g. from `new Date().toISOString()`) */
  startAt: string;
  /** ISO 8601 timestamp */
  endAt: string;
}

export interface ScheduleStepTrackingOptions {
  windows: TrackingWindow[];
}

export interface StartStepTrackingOptions {
  /** Custom notification title. Defaults to "Tracking steps for <app name>". */
  notificationTitle?: string;
  /** Custom notification body text. Defaults to "Step counting is active". */
  notificationText?: string;
}

export interface GetTrackedStepsOptions {
  /** ISO 8601 timestamp. Only return buckets starting at or after this time. */
  since?: string;
  /** ISO 8601 timestamp. Only return buckets modified after this time. Pass the `syncToken` from a previous `getTrackedSteps` call to get only changed rows. */
  modifiedSince?: string;
}

export interface BackfillOptions {
  windows: TrackingWindow[];
}

export interface BackfillResult {
  /** false if Health Connect is unavailable or permissions not granted */
  backedUp: boolean;
}

export interface ClearDataOptions {
  /** ISO 8601 timestamp. Delete all buckets with bucketStart before this time. If omitted, deletes all data. */
  before?: string;
}

export interface HcRecord {
  startTime: string;
  endTime: string;
  count: number;
  dataOrigin: string;
}

export interface StepBucket {
  /** ISO 8601 timestamp for the start of this 30-second bucket. */
  bucketStart: string;
  /** ISO 8601 timestamp for the end of this 30-second bucket. */
  bucketEnd: string;
  /** Number of steps recorded in this bucket. */
  steps: number;
  /** Raw Health Connect records JSON, if HC data contributed to this bucket. */
  hcMetadata?: string;
}

export interface GetTrackedStepsResult {
  steps: StepBucket[];
  /** ISO 8601 timestamp. Pass this as `modifiedSince` on the next call to get only rows that changed since this query. */
  syncToken: string;
}

export interface ExactAlarmPermissionResult {
  /** Whether the app can schedule exact alarms. Always true on Android < 12 and on web/iOS. */
  granted: boolean;
}

export interface StepSensorPlugin {
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
  scheduleStepTracking(options: ScheduleStepTrackingOptions): Promise<void>;

  /**
   * Start the foreground step counter service immediately.
   * Shows a persistent notification. Registers TYPE_STEP_COUNTER sensor
   * and Health Connect poller. Records 30-second step buckets to SQLite.
   * No-op if already running. Called internally by the alarm receiver,
   * but can also be called directly from JS for immediate start.
   */
  startStepTracking(options?: StartStepTrackingOptions): Promise<void>;

  /**
   * Stop the foreground step counter service immediately.
   * Removes the notification. Unregisters sensor and HC poller.
   * No-op if not running.
   */
  stopStepTracking(): Promise<void>;

  /**
   * Read accumulated step data from the local sensor log.
   * Returns all rows since the given timestamp (or all rows if no timestamp).
   */
  getTrackedSteps(options?: GetTrackedStepsOptions): Promise<GetTrackedStepsResult>;

  /**
   * Backfill step data from Health Connect for past commitment windows.
   * Reads HC data using readRecords() (explicit time-range queries), runs
   * subtract-and-fill against existing SQLite data, and writes the results.
   * Safe to call multiple times (idempotent via MAX upsert).
   *
   * Call this on every app resume to capture steps recorded after the
   * foreground service stopped (watch data may sync 15+ minutes late).
   *
   * On iOS/web, resolves with { backedUp: false } (no-op).
   */
  backfillFromHealthConnect(options: BackfillOptions): Promise<BackfillResult>;

  /**
   * Delete step data from the local database.
   * If `before` is provided, deletes all buckets with bucketStart before that time.
   * If omitted, deletes all data.
   */
  clearData(options?: ClearDataOptions): Promise<void>;

  /**
   * Check whether the app has permission to schedule exact alarms.
   * On Android 12+ (API 31+), SCHEDULE_EXACT_ALARM requires explicit user grant.
   * Returns { granted: true } on Android < 12, iOS, and web.
   */
  checkExactAlarmPermission(): Promise<ExactAlarmPermissionResult>;

  /**
   * Open the system settings screen where the user can grant exact alarm permission.
   * On Android 12+, opens Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM.
   * Resolves immediately with { granted: false } — the caller should re-check
   * permission after the user returns to the app (e.g. on app resume).
   * Returns { granted: true } on Android < 12, iOS, and web (no action needed).
   */
  requestExactAlarmPermission(): Promise<ExactAlarmPermissionResult>;
}
