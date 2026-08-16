# Temperature monitoring — Client App 1.9.2

## Source

The Client App does not read the smartphone's local thermal sensor. It uses the temperature supplied by the connected Camera App dashboard. The Camera App reads Pilot One `/sys/class/thermal/thermal_zone0/temp`, matching Labpano's original `CpuTemperatureReader` approach.

## Background operation

`CameraConnectionService` evaluates every successfully received dashboard temperature while the foreground camera connection is active. This continues when MainActivity is not visible, including when the phone screen is off or another app is in the foreground.

## User threshold

The dashboard contains a **Pilot One Device Temperature** card and a **TEMPERATURE ALERT SETTINGS** button. The settings window allows the user to enter a Celsius threshold and test the warning sound.

- Default threshold: 73 °C
- Allowed threshold range: 0–150 °C
- Trigger: measured temperature > threshold
- Rearm: measured temperature <= threshold - 3 °C
- Repeat cooldown: 10 minutes
- The setting persists across app/process restarts.

A new manual camera connection rearms the alert. Restarting/recreating the Activity while the same background connection remains active does not rearm it, preventing nuisance repeat alerts.
