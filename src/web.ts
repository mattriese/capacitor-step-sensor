import { WebPlugin } from '@capacitor/core';

import type { StepSensorPlugin } from './definitions';

export class StepSensorWeb extends WebPlugin implements StepSensorPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
