export interface StepSensorPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
