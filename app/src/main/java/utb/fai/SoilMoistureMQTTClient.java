package utb.fai;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import utb.fai.API.HumiditySensor;
import utb.fai.API.IrrigationSystem;
import utb.fai.Types.FaultType;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MQTT client for soil moisture monitoring and irrigation control.
 *
 * <p>Connects to a local MQTT broker, publishes humidity readings every 10 seconds,
 * and handles control commands for starting/stopping the irrigation system.
 */
public class SoilMoistureMQTTClient {

    private static final Logger logger = Logger.getLogger(SoilMoistureMQTTClient.class.getName());

    private MqttClient client;
    private final HumiditySensor humiditySensor;
    private final IrrigationSystem irrigationSystem;
    private Timer humidityTimer;
    private Timer irrigationTimer;
    /** Tracks whether irrigation is currently active. Accessed from multiple threads. */
    private final AtomicBoolean irrigationActive = new AtomicBoolean(false);
    /** Guards irrigationTimer creation/cancellation against concurrent access. */
    private final Object irrigationLock = new Object();

    /**
     * Creates a new MQTT client for soil moisture monitoring and irrigation control.
     *
     * @param sensor     humidity sensor to read from
     * @param irrigation irrigation system to control
     */
    public SoilMoistureMQTTClient(HumiditySensor sensor, IrrigationSystem irrigation) {
        this.humiditySensor = sensor;
        this.irrigationSystem = irrigation;
    }

    /**
     * Connects to the broker, subscribes to the control topic, and begins
     * publishing humidity readings. Blocks until the thread is interrupted.
     */
    public void start() {
        try {
            client = new MqttClient(Config.BROKER, Config.CLIENT_ID);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    logger.log(Level.WARNING, "MQTT connection lost", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleIncomingMessage(new String(message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            client.connect();
            client.subscribe(Config.TOPIC_IN);

            sendHumidity();

            humidityTimer = new Timer("humidity-timer", true);
            humidityTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    sendHumidity();
                }
            }, 10000, 10000);

            // Block until interrupted (e.g. Ctrl+C via shutdown hook)
            final Object lock = new Object();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdown();
                synchronized (lock) {
                    lock.notifyAll();
                }
            }, "shutdown-hook"));

            synchronized (lock) {
                lock.wait();
            }

        } catch (MqttException e) {
            logger.log(Level.SEVERE, "MQTT error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.INFO, "Client interrupted, shutting down");
        } finally {
            shutdown();
        }
    }

    /**
     * Cancels timers and disconnects from the broker cleanly.
     */
    private void shutdown() {
        if (humidityTimer != null) {
            humidityTimer.cancel();
        }
        synchronized (irrigationLock) {
            if (irrigationTimer != null) {
                irrigationTimer.cancel();
                irrigationTimer = null;
            }
        }
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            logger.log(Level.WARNING, "Error while disconnecting MQTT client", e);
        }
    }

    /**
     * Dispatches an incoming control message to the appropriate handler.
     *
     * @param message raw payload string received on the control topic
     */
    private void handleIncomingMessage(String message) {
        if (message.equals(Config.REQUEST_GET_HUMIDITY)) {
            sendHumidity();
        } else if (message.equals(Config.REQUEST_GET_STATUS)) {
            sendStatus();
        } else if (message.equals(Config.REQUEST_START_IRRIGATION)) {
            startIrrigation();
        } else if (message.equals(Config.REQUEST_STOP_IRRIGATION)) {
            stopIrrigation();
        } else {
            logger.log(Level.WARNING, "Unknown command received: {0}", message);
        }
    }

    /**
     * Reads the current humidity from the sensor and publishes it.
     * If the sensor has faulted, a fault message is sent instead.
     */
    private void sendHumidity() {
        float humidity = humiditySensor.readRAWValue();

        if (humiditySensor.hasFault()) {
            sendFault(FaultType.HUMIDITY_SENSOR_FAULT);
        } else {
            publishMessage(Config.RESPONSE_HUMIDITY + ";" + humidity);
        }
    }

    /**
     * Publishes the current irrigation status ({@code irrigation_on} or {@code irrigation_off}).
     */
    private void sendStatus() {
        String status = irrigationActive.get() ? "irrigation_on" : "irrigation_off";
        publishMessage(Config.RESPONSE_STATUS + ";" + status);
    }

    /**
     * Activates the irrigation system (if not already active) and schedules
     * an automatic stop after 30 seconds. Each call resets the 30-second
     * timeout from the last received start command.
     */
    private void startIrrigation() {
        synchronized (irrigationLock) {
            if (irrigationTimer != null) {
                irrigationTimer.cancel();
                irrigationTimer = null;
            }

            if (!irrigationActive.get()) {
                irrigationSystem.activate();

                if (irrigationSystem.hasFault()) {
                    sendFault(FaultType.IRRIGATION_SYSTEM_FAULT);
                    return;
                }

                irrigationActive.set(true);
                publishMessage(Config.RESPONSE_STATUS + ";irrigation_on");
            }

            irrigationTimer = new Timer("irrigation-timer", true);
            irrigationTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    stopIrrigation();
                }
            }, 30000);
        }
    }

    /**
     * Deactivates the irrigation system immediately and cancels any pending timeout.
     * A fault message is sent if deactivation fails, but the active flag is cleared
     * regardless so the system does not get stuck in a permanently-on state.
     */
    private void stopIrrigation() {
        synchronized (irrigationLock) {
            if (irrigationTimer != null) {
                irrigationTimer.cancel();
                irrigationTimer = null;
            }

            if (irrigationActive.get()) {
                irrigationSystem.deactivate();
                irrigationActive.set(false);

                if (irrigationSystem.hasFault()) {
                    sendFault(FaultType.IRRIGATION_SYSTEM_FAULT);
                }

                publishMessage(Config.RESPONSE_STATUS + ";irrigation_off");
            }
        }
    }

    /**
     * Publishes a fault notification for the given device fault type.
     *
     * @param faultType the type of fault that occurred
     */
    private void sendFault(FaultType faultType) {
        publishMessage(Config.RESPONSE_FAULT + ";" + faultType.toString());
    }

    /**
     * Publishes a message to the outgoing topic.
     *
     * @param payload the message payload to publish
     */
    private void publishMessage(String payload) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            client.publish(Config.TOPIC_OUT, message);
        } catch (MqttException e) {
            logger.log(Level.SEVERE, "Failed to publish message: " + payload, e);
        }
    }

}
