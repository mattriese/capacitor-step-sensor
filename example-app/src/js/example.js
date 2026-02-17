import { StepSensor } from 'capacitor-step-sensor';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    StepSensor.echo({ value: inputValue })
}
