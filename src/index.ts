import { registerPlugin } from '@capacitor/core';

import type { StepSensorPlugin } from './definitions';

const StepSensor = registerPlugin<StepSensorPlugin>('StepSensor', {
  web: () => import('./web').then((m) => new m.StepSensorWeb()),
});

export * from './definitions';
export { StepSensor };
