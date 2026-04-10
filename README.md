# MQTT Soil Moisture Monitor

An MQTT client that monitors soil moisture and controls an irrigation system. The client publishes sensor readings on a regular interval and responds to remote control commands — simulating a real-world IoT field device.

Built with Java 17 and Eclipse Paho, with CI/CD via GitHub Actions and GitLab CI.

## Features

- Publishes soil moisture readings every 10 seconds
- Responds to on-demand data requests (`get-humidity`, `get-status`)
- Starts and stops irrigation on command (`start-irrigation`, `stop-irrigation`)
- Auto-stops irrigation 30 seconds after the last start command (watchdog timer)
- Reports hardware faults for both the sensor and the irrigation system
- Physics-based simulation of soil moisture dynamics (evaporation + irrigation)
- Graceful shutdown: timers cancelled, MQTT connection cleanly closed

## Architecture

```
App.java
 └── SoilMoistureMQTTClient      # MQTT logic, command dispatch, state management
      ├── HumiditySensor (API)   # Interface — reads moisture level, reports faults
      └── IrrigationSystem (API) # Interface — activate/deactivate, reports faults
           └── Simulation        # Physics engine: evaporation rate, moisture level
```

The sensor and irrigation system are injected via interfaces, making it straightforward to swap the simulation for real hardware drivers.

## MQTT Protocol

**Broker:** `tcp://localhost:1883`

| Direction | Topic               |
|-----------|---------------------|
| Incoming  | `topic/device1/in`  |
| Outgoing  | `topic/device1/out` |

**Incoming commands:**

| Message            | Effect                                    |
|--------------------|-------------------------------------------|
| `get-humidity`     | Immediately publish current reading       |
| `get-status`       | Publish current irrigation state          |
| `start-irrigation` | Activate irrigation, reset 30s timeout    |
| `stop-irrigation`  | Deactivate irrigation immediately         |

**Outgoing messages:**

| Message                    | Trigger                              |
|----------------------------|--------------------------------------|
| `humidity;<value>`         | Every 10 s or on `get-humidity`      |
| `status;irrigation_on`     | Irrigation activated                 |
| `status;irrigation_off`    | Irrigation deactivated               |
| `fault;HUMIDITY_SENSOR`    | Sensor fault detected                |
| `fault;IRRIGATION_SYSTEM`  | Irrigation system fault detected     |

## Getting Started

**Requirements:** Java 17+, a running MQTT broker on `localhost:1883`

```bash
# Build
./gradlew build

# Run with defaults (seed=643953953, 5% fault probability for both devices)
java -jar app/build/libs/app.jar

# Run with custom parameters
java -jar app/build/libs/app.jar <seed> <humidity_fault_prob> <irrigation_fault_prob>
# Example: seed=12345, 10% sensor fault, 2% irrigation fault
java -jar app/build/libs/app.jar 12345 0.10 0.02
```

**Manual testing** — send commands interactively:

```bash
java -jar TestingMQTTClient.jar
# Then inside the tool:
/set-url tcp://localhost:1883
/subscribe topic/device1/out
/send-message topic/device1/in start-irrigation
```

See [testing-tool.md](./testing-tool.md) for full usage.

## Running automated tests

The repo includes NATT (Network App Testing Tool) for black-box integration testing.

```bash
# Linux
./run_local_test.sh

# Windows
run_local_test.bat
```

Results are written to `test_report.html`.

## Tech Stack

| Technology | Role |
|------------|------|
| Java 17 | Language |
| Gradle 8.7 | Build system |
| Eclipse Paho MQTT 1.2.5 | MQTT client library |
| GitHub Actions / GitLab CI | CI/CD pipelines |
