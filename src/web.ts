import { WebPlugin } from '@capacitor/core';

import type {
  BackfillResult,
  ExactAlarmPermissionResult,
  FitnessNotificationDebugState,
  PluginInfoResult,
  StepSensorPermissionStatus,
  StepSensorPlugin,
  TickAuditHistoryResult,
  TickAuditResult,
} from './definitions';

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
    syncToken: string;
  }> {
    return { steps: [], syncToken: new Date().toISOString() };
  }

  async backfillFromHealthConnect(): Promise<BackfillResult> {
    return { backedUp: false };
  }

  async clearData(): Promise<void> {
    // No-op on web
  }

  async checkExactAlarmPermission(): Promise<ExactAlarmPermissionResult> {
    return { granted: true };
  }

  async requestExactAlarmPermission(): Promise<ExactAlarmPermissionResult> {
    return { granted: true };
  }

  async getLastTickAudit(): Promise<TickAuditResult> {
    return { available: false };
  }

  async getTickAuditHistory(): Promise<TickAuditHistoryResult> {
    return { ticks: [], anomalousTickCount: 0, bufferSize: 0 };
  }

  async getPluginInfo(): Promise<PluginInfoResult> {
    return { buildId: 'web' };
  }

  async configureFitnessNotifications(): Promise<void> {
    // No-op on web
  }

  async clearFitnessNotifications(): Promise<void> {
    // No-op on web
  }

  async getFitnessNotificationDebugState(): Promise<FitnessNotificationDebugState> {
    return {
      generatedAt: null,
      commitments: [],
      scheduledReminders: [],
    };
  }

  async checkPermissions(): Promise<StepSensorPermissionStatus> {
    return { activityRecognition: 'granted', notifications: 'granted' };
  }

  async requestPermissions(): Promise<StepSensorPermissionStatus> {
    return { activityRecognition: 'granted', notifications: 'granted' };
  }
}
