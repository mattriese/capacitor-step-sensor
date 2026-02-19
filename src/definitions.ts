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
  /** If true, delete returned rows from the local database after reading. */
  deleteAfterRead?: boolean;
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
   * Optionally deletes returned rows after reading (consume pattern).
   */
  getTrackedSteps(options?: GetTrackedStepsOptions): Promise<GetTrackedStepsResult>;
}
