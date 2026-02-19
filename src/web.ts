import { WebPlugin } from '@capacitor/core';

import type { BackfillResult, StepSensorPlugin } from './definitions';

export class StepSensorWeb extends WebPlugin implements StepSensorPlugin {
  async scheduleStepTracking(): Promise<void> {
    // No-op on web — step tracking requires native sensors
  }

  async startStepTracking(): Promise<void> {
    // No-op on web
  }

  async stopStepTracking(): Promise<void> {
    // No-op on web
  }

  async getTrackedSteps(): Promise<{
    steps: Array<{ bucketStart: string; bucketEnd: string; steps: number }>;
  }> {
    return { steps: [] };
  }

  async backfillFromHealthConnect(): Promise<BackfillResult> {
    return { backedUp: false };
  }

  async clearData(): Promise<void> {
    // No-op on web
  }
}
