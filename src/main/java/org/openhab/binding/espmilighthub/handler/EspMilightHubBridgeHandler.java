/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.espmilighthub.handler;

import static org.openhab.binding.espmilighthub.EspMilightHubBindingConstants.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.IOUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EspMilightHubBridgeHandler} is responsible for handling the bridge commands and all MQTT comms, which are
 * sent to one of the channels.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class EspMilightHubBridgeHandler extends BaseBridgeHandler implements MqttCallbackExtended {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);
    private final ScheduledExecutorService checkConnection = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> checkConnectionJob = null;
    private final Logger logger = LoggerFactory.getLogger(EspMilightHubBridgeHandler.class);

    public static String confirmedAddress = "empty";
    public static String confirmedUser = "empty";
    public static String confirmedPassword = "empty";
    public static ThingUID confirmedBridgeUID;
    public static boolean triggerRefresh = true;

    private final ScheduledExecutorService schedulerOut = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> sendQueuedMQTTTimerJob = null;
    private final ScheduledExecutorService schedulerIn = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> processIncommingMQTTTimerJob = null;

    private LinkedList<String> fifoOutgoingTopic = new LinkedList<String>();
    private LinkedList<String> fifoOutgoingPayload = new LinkedList<String>();
    public ReentrantLock lockOutGoing = new ReentrantLock();
    private LinkedList<String> fifoIncommingTopic = new LinkedList<String>();
    private LinkedList<String> fifoIncommingPayload = new LinkedList<String>();
    public ReentrantLock lockInComming = new ReentrantLock();

    private MqttClient client;
    private Configuration bridgeConfig;
    private boolean increaseChoke = false;
    EspMilightHubHandler childHandler;

    static private String resolveJSON(String messageJSON, String jsonPath, int resultMaxLength) {
        String result = "";
        int index = 0;
        index = messageJSON.indexOf(jsonPath);
        if (index != -1) // It was found as -1 means "not found"
        {
            if ((index + jsonPath.length() + resultMaxLength) > messageJSON.length()) {
                result = (messageJSON.substring(index + jsonPath.length(), messageJSON.length()));
            } else {
                result = (messageJSON.substring(index + jsonPath.length(),
                        index + jsonPath.length() + resultMaxLength));
            }
            index = result.indexOf(','); // need to be careful, only matches first bad char found//
            if (index == -1)// , not found so make second check
            {
                index = result.indexOf('"');
                if (index == -1)// " not found so it passed both checks
                {
                    index = result.indexOf('}');
                    if (index == -1)// } not found so it passed all 3 checks
                    {
                        return result;
                    } else {
                        return result.substring(0, index); // Strip off the } as it is the only bad char.
                    }
                } else { // no , but it found a ", have not checked for } as have not seen that occur yet.
                    return result.substring(0, index); // Strip off the " as it is a bad char, careful as } may still be
                                                       // in string.
                }
            } else { // Found a "," , now we need to check for " in case both are in string.

                result = result.substring(0, index); // Strip off any left over char.
                index = result.indexOf('"');
                if (index == -1)// " not found so it passed both checks
                {
                    return result;
                } else {
                    return result.substring(0, index); // Strip off any left over char.
                }
            }
        }
        return "";
    }

    private void processIncomingState(String globeType, String remoteCode, String remoteGroupID, String messageJSON) {
        String channelPrefix = "espmilighthub:" + globeType + ":" + thing.getUID().getId() + ":" + remoteCode
                + remoteGroupID + ":";

        // Need to handle State and Level at the same time to process level=0 as off//
        int iBulbLevel = 1;
        String bulbState = resolveJSON(messageJSON, "\"state\":\"", 3);
        String bulbLevel = resolveJSON(messageJSON, "\"level\":", 3);

        if (logger.isTraceEnabled()) {
            logger.trace("Processing new incoming MQTT message to update Openhab's controls.");
            logger.trace("Message\t={}", messageJSON);
            logger.trace("globeType\t={}", globeType);
            logger.trace("remoteCode\t={}", remoteCode);
            logger.trace("remoteGroupID\t={}", remoteGroupID);
            logger.trace("Chan Prefix\t={}", channelPrefix);
            logger.trace("bulbState\t={}", bulbState);
            logger.trace("bulbLevel\t={}", bulbLevel);
        }

        if (!bulbLevel.isEmpty()) {

            if ("0".equals(bulbLevel) || bulbState.contains("OFF")) {
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), new PercentType(0));
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), OnOffType.valueOf("OFF"));
                if ((boolean) bridgeConfig.get(CONFIG_LEVEL_FOLLOWS_COLOUR)) {
                    iBulbLevel = 0;
                } else {
                    return;
                }
            } else {
                iBulbLevel = Math.round(Float.valueOf(bulbLevel));
                // logger.trace("iBulbLevel\t={}", iBulbLevel);
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), new PercentType(iBulbLevel));
            }

        } else if (bulbState.contains("ON") || bulbState.contains("OFF")) { // NOTE: Level is missing when this runs
            updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), OnOffType.valueOf(bulbState));
        }

        String bulbMode = resolveJSON(messageJSON, "\"bulb_mode\":\"", 5);
        if ("white".equals(bulbMode)) {
            if (!"cct".equals(globeType) && !"fut091".equals(globeType)) {
                // This is not a double up and is used to update the mode in the espmilighthubhandler
                // Halogen dimming needs CHANNEL_BULB_MODE linked for the feature to work!
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("white"));
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("white"));
                updateState(new ChannelUID(channelPrefix + CHANNEL_COLOUR), new HSBType("0,0," + iBulbLevel));
                updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE), new DecimalType("-1"));
            }
            String bulbCTemp = resolveJSON(messageJSON, "\"color_temp\":", 3);
            if (!bulbCTemp.isEmpty()) {
                int ibulbCTemp = (int) Math.round(((Float.valueOf(bulbCTemp) / 2.17) - 171) * -1);
                updateState(new ChannelUID(channelPrefix + CHANNEL_COLOURTEMP), new PercentType(ibulbCTemp));
            }

        } else if ("color".equals(bulbMode)) {

            updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("color"));
            updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE), new DecimalType("-1"));
            postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("color"));

            String bulbHue = resolveJSON(messageJSON, "\"hue\":", 3);
            // logger.trace("bulbHue\t={}", bulbHue);
            String bulbSaturation = resolveJSON(messageJSON, "\"saturation\":", 3);
            // logger.trace("bulbSaturation\t={}", bulbSaturation);

            if ("".equals(bulbHue)) {
                logger.warn("Milight MQTT message came in as being a colour mode, but was missing a HUE value.");
            } else {
                if ("".equals(bulbSaturation)) {
                    bulbSaturation = "100";
                }
                updateState(new ChannelUID(channelPrefix + CHANNEL_COLOUR),
                        new HSBType(bulbHue + "," + bulbSaturation + "," + iBulbLevel));
            }
        } else if ("scene".equals(bulbMode)) {

            if (!"cct".equals(globeType) && !"fut091".equals(globeType)) {
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("scene"));
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("scene"));
            }

            String bulbDiscoMode = resolveJSON(messageJSON, "\"mode\":", 1);
            // logger.trace("bulbDiscoMode=\t{}", bulbDiscoMode);

            if ("".equals(bulbDiscoMode)) {
                ;
            } else {
                updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE),
                        new DecimalType(bulbDiscoMode.toString()));
            }
        } else if ("night".equals(bulbMode)) {
            if (!"cct".equals(globeType) && !"fut091".equals(globeType)) {
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("night"));
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("night"));
                if (this.get1TriggersNightMode()) {
                    updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), new PercentType("1"));
                }
            }
        }
    }

    Runnable pollingIncommingQueuedMQTT = new Runnable() {
        @Override
        public void run() {
            if (thing.getStatus() == ThingStatus.OFFLINE) {
                // keeps the queue ready until it comes back online to process//
                return;
            } else if (!fifoIncommingTopic.isEmpty()) {
                lockInComming.lock();
                String topic, payload;
                try {
                    topic = fifoIncommingTopic.removeFirst();
                    payload = fifoIncommingPayload.removeFirst();
                } finally {
                    lockInComming.unlock();
                }
                String cutTopic = topic.replace("milight/states/", "");
                int index = cutTopic.indexOf("/");
                if (index != -1) // -1
                                 // means
                                 // "not
                                 // found"
                {
                    String remoteCode = (cutTopic.substring(0, index)); // Store the remote code for use later
                    cutTopic = topic.replace("milight/states/" + remoteCode + "/", "");
                    index = cutTopic.indexOf("/");
                    if (index != -1) // -1 means
                                     // "not found"
                    {
                        String globeType = (cutTopic.substring(0, index));
                        String remoteGroupID = (cutTopic.substring(++index, ++index));
                        if ("0".contentEquals(remoteGroupID) && "fut089".contains(globeType)) {
                            processIncomingState(globeType, remoteCode, "0", payload);
                            processIncomingState(globeType, remoteCode, "1", payload);
                            processIncomingState(globeType, remoteCode, "2", payload);
                            processIncomingState(globeType, remoteCode, "3", payload);
                            processIncomingState(globeType, remoteCode, "4", payload);
                            processIncomingState(globeType, remoteCode, "5", payload);
                            processIncomingState(globeType, remoteCode, "6", payload);
                            processIncomingState(globeType, remoteCode, "7", payload);
                            processIncomingState(globeType, remoteCode, "8", payload);
                        } else if ("0".contentEquals(remoteGroupID)) {
                            processIncomingState(globeType, remoteCode, "0", payload);
                            processIncomingState(globeType, remoteCode, "1", payload);
                            processIncomingState(globeType, remoteCode, "2", payload);
                            processIncomingState(globeType, remoteCode, "3", payload);
                            processIncomingState(globeType, remoteCode, "4", payload);
                        } else {
                            processIncomingState(globeType, remoteCode, remoteGroupID, payload);
                        }
                    }
                }
            } else if (!processIncommingMQTTTimerJob.isCancelled()) {
                processIncommingMQTTTimerJob.cancel(true);
                if (processIncommingMQTTTimerJob.isCancelled()) {
                    processIncommingMQTTTimerJob = null;
                }
            }
        }
    };

    @Override
    public void messageArrived(String topic, MqttMessage payload) throws Exception {
        logger.debug("* Recieved the following new Milight state:{} : {}", topic, payload.toString());
        lockInComming.lock();
        try {
            fifoIncommingTopic.addLast(topic);
            fifoIncommingPayload.addLast(payload.toString());
        } finally {
            lockInComming.unlock();
        }
        if (processIncommingMQTTTimerJob == null) {
            processIncommingMQTTTimerJob = schedulerIn.scheduleWithFixedDelay(pollingIncommingQueuedMQTT, 10, 10,
                    TimeUnit.MILLISECONDS);
        }
    }

    public void subscribeToMQTT() {
        try {
            client.subscribe("milight/states/#", 1);
            logger.info("Sucessfully subscribed to milight/states/#");
        } catch (MqttException e) {
            logger.error("Error: Could not subscribe to 'milight/states/#' cause is:{}", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, java.lang.String serverURI) {
        logger.info("Sucessfully connected to the MQTT broker.");
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void connectionLost(Throwable cause) {
        logger.error("MQTT connection has been lost, cause reported is:{}", cause);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                "MQTT broker connection lost:" + cause);
    }

    public boolean connectMQTT(boolean useCleanSession) {

        try {
            client = new MqttClient(bridgeConfig.get(CONFIG_MQTT_ADDRESS).toString(),
                    "espMilightHub:" + this.getThing().getUID().getId().toString(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(useCleanSession);
            if (bridgeConfig.get(CONFIG_MQTT_USER_NAME) != null) {
                options.setUserName(bridgeConfig.get(CONFIG_MQTT_USER_NAME).toString());
                confirmedUser = bridgeConfig.get(CONFIG_MQTT_USER_NAME).toString();
            }
            if (bridgeConfig.get(CONFIG_MQTT_PASSWORD) != null) {
                options.setPassword(bridgeConfig.get(CONFIG_MQTT_PASSWORD).toString().toCharArray());
                confirmedPassword = bridgeConfig.get(CONFIG_MQTT_PASSWORD).toString();
            }
            options.setMaxInflight(30); // up to 30 messages at once can be sent without a token back
            options.setAutomaticReconnect(true);
            options.setKeepAliveInterval(15);
            options.setConnectionTimeout(20); // connection must be made in under 20 seconds
            client.setCallback(this);
            client.connect(options);
        } catch (MqttException e) {
            logger.error("Error: Could not connect to MQTT broker.{}", e);
            return false;
        }
        confirmedAddress = bridgeConfig.get(CONFIG_MQTT_ADDRESS).toString();
        updateStatus(ThingStatus.ONLINE);
        return true;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    private void sendMQTT(String topic, String payload) {
        try {
            if (client.isConnected()) {
                client.publish(topic, // topic
                        payload.getBytes(), // payload
                        1, // QoS of 1 will garrantee the message gets through without the extra overheads of 2
                        false); // Not retained
            }
        } catch (MqttPersistenceException e) {
            logger.error("Error: Could not connect/send to MQTT broker:{}", e);
        } catch (MqttException e) {
            logger.error("Error: Could not connect/send to MQTT broker:{}", e);
        }
    }

    Runnable pollingSendQueuedMQTT = new Runnable() {
        @Override
        public void run() {
            if (client.isConnected()) {
                if (fifoOutgoingTopic.size() >= 1) {
                    lockOutGoing.lock();
                    try {
                        sendMQTT(fifoOutgoingTopic.removeFirst(), fifoOutgoingPayload.removeFirst());
                        logger.trace("MQTT message just sent, there are now {} more messages in the queue",
                                fifoOutgoingTopic.size());
                    } catch (NoSuchElementException e) {
                        logger.warn(
                                "!!! Outgoing MQTT queue *CATCH* Triggered. Wiping the outgoing FIFO buffer clean !!!");
                        fifoOutgoingTopic.clear();
                        fifoOutgoingPayload.clear();
                    } finally {
                        lockOutGoing.unlock();
                    }
                } else {
                    sendQueuedMQTTTimerJob.cancel(true);
                    sendQueuedMQTTTimerJob = null;
                    return;
                }
            }
        }
    };

    public void queueToSendMQTT(String topic, String payload) {

        if (topic == null || payload == null) {
            logger.error("null was found in requested outgoing message:{}:{}:", topic, payload);
            return;
        }

        try {
            if (fifoOutgoingTopic.size() > 1 && fifoOutgoingTopic.getLast().equals(topic)
                    && !fifoOutgoingPayload.getLast().equals("{\"state\":\"ON\",\"level\":0}")) {
                lockOutGoing.lock();
                try {
                    logger.debug("Message reduction has removed a MQTT message.");
                    fifoOutgoingTopic.removeLast();
                    fifoOutgoingPayload.removeLast();
                } finally {
                    lockOutGoing.unlock();
                }
                if (increaseChoke == false) {
                    increaseChoke = true;
                    logger.debug("changing queue to DELAY_BETWEEN_SAME_GLOBE speed.");
                    if (sendQueuedMQTTTimerJob != null) {
                        sendQueuedMQTTTimerJob.cancel(false);
                    }
                    sendQueuedMQTTTimerJob = schedulerOut.scheduleWithFixedDelay(pollingSendQueuedMQTT,
                            Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_SAME_GLOBE).toString()),
                            Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_SAME_GLOBE).toString()),
                            TimeUnit.MILLISECONDS);
                }
                logger.debug(
                        "Message reduction has removed a command as the queue contains multiples for the same globe.");
            } else if (increaseChoke == true) {
                increaseChoke = false;
                logger.debug("changing queue back to normal speed.");
                if (sendQueuedMQTTTimerJob != null) {
                    sendQueuedMQTTTimerJob.cancel(false);
                }
                sendQueuedMQTTTimerJob = schedulerOut.scheduleWithFixedDelay(pollingSendQueuedMQTT,
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()),
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()),
                        TimeUnit.MILLISECONDS);
            }
            lockOutGoing.lock();
            try {
                fifoOutgoingTopic.addLast(topic);
                fifoOutgoingPayload.addLast(payload);
            } finally {
                lockOutGoing.unlock();
            }
        } catch (NoSuchElementException e) {
            logger.info(
                    "!!!! queueToSend *CATCH* Triggered, wiping the outgoing FIFO buffer clean and trying to resend ********************");
            fifoOutgoingTopic.clear();
            fifoOutgoingPayload.clear();
            fifoOutgoingTopic.addLast(topic);
            fifoOutgoingPayload.addLast(payload);
        }

        if (sendQueuedMQTTTimerJob == null) {
            sendQueuedMQTTTimerJob = schedulerOut.scheduleWithFixedDelay(pollingSendQueuedMQTT, 0,
                    Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()), TimeUnit.MILLISECONDS);
            logger.debug("Started timer because it was null.");
        }
    }

    /*
     * public static void clearRetainedTopicMQTT(String topic) {
     *
     * try {
     * if (client.isConnected()) {
     * client.publish(topic, new byte[0], 1, true);
     * }
     * } catch (MqttPersistenceException e) {
     * logger.error("Error: Could not connect/send to MQTT broker.{}", e);
     * } catch (MqttException e) {
     * logger.error("Error: Could not connect/send to MQTT broker.{}", e);
     * }
     * }
     */

    public void disconnectMQTT() {
        try {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Currently disconnected from the MQTT broker.");
            client.disconnect();
            logger.debug("disconnectMQTT() is going to disconnect from the MQTT broker.");
            // wait needed to fix issue when trying to reconnect too fast after a disconnect.
            Thread.sleep(3000);
        } catch (MqttException | InterruptedException e) {
            logger.error("Could not disconnect from MQTT broker.{}", e);
        }
    }

    public EspMilightHubBridgeHandler(Bridge thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

    }

    public int getTriggerWhiteHue() {
        return Integer.parseInt(bridgeConfig.get(CONFIG_TRIGGER_WHITE_HUE).toString());
    }

    public int getRGBWhiteSatThreshold() {
        return Integer.parseInt(bridgeConfig.get(CONFIG_RGBW_WHITEMODE_SAT_THRESHOLD).toString());
    }

    public int getTriggerWhiteSat() {
        return Integer.parseInt(bridgeConfig.get(CONFIG_TRIGGER_WHITE_SAT).toString());
    }

    public int getFavouriteWhite() {
        return Integer.parseInt((bridgeConfig.get(CONFIG_FAVOURITE_WHITE).toString()));
    }

    public int getAutoCTempValue() {
        if (bridgeConfig.get(CONFIG_AUTOCTEMP_MAXDIMMED_TEMPERATURE) == null) {
            return 0;
        }
        return Integer.parseInt((bridgeConfig.get(CONFIG_AUTOCTEMP_MAXDIMMED_TEMPERATURE).toString()));
    }

    public String getDefaultCommand() {
        return bridgeConfig.get(CONFIG_DEFAULT_COMMAND).toString();
    }

    public boolean get1TriggersNightMode() {
        if ((boolean) bridgeConfig.get(CONFIG_1TRIGGERS_NIGHT_MODE)) {
            return true;
        }
        return false;
    }

    public boolean getPowerFailsToMaxDim() {
        if ((boolean) bridgeConfig.get(CONFIG_POWERFAILS_TO_MINDIM)) {
            return true;
        }
        return false;
    }

    private void recordBridgeID() {
        confirmedBridgeUID = this.getThing().getUID();
    }

    Runnable pollConnection = new Runnable() {
        @Override
        public void run() {
            if (thing.getStatus() == ThingStatus.ONLINE) {
                if (triggerRefresh) {
                    triggerRefresh = false;
                    subscribeToMQTT();
                }
            } else {
                logger.debug("pollFirstConnection() is trying to connect to your MQTT broker now.");
                if (connectMQTT(false)) {// connect
                                         // to
                                         // get
                                         // a
                                         // full
                                         // list
                                         // of
                                         // globe
                                         // states//
                    recordBridgeID();
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Could not connect to the MQTT broker, check the address, user and pasword are correct and the broker is online.");
                }
            }
        }
    };

    private String getHttp(String urlFilePath) throws IOException {

        String urlString = "http://" + bridgeConfig.get(CONFIG_HUB_IP) + urlFilePath;
        URL url;
        url = new URL(urlString);
        URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(3000);
        try {
            String response = IOUtils.toString(urlConnection.getInputStream());
            // logger.info("response = {}", response);
            return response;
        } finally {
            IOUtils.closeQuietly(urlConnection.getInputStream());
        }
    }

    private void putHttp(String urlFilePath, String content) {
        String urlString = "http://" + bridgeConfig.get(CONFIG_HUB_IP) + urlFilePath;
        URL url;
        try {
            url = new URL(urlString);
            HttpURLConnection httpConnection = (HttpURLConnection) url.openConnection();
            httpConnection.setConnectTimeout(3000);
            httpConnection.setDoOutput(true);
            httpConnection.setRequestMethod("PUT");
            OutputStreamWriter outputStream = new OutputStreamWriter(httpConnection.getOutputStream());
            outputStream.write(content);
            outputStream.close();
            String response = IOUtils.toString(httpConnection.getInputStream());
            if (!response.contains("true")) {
                logger.warn(
                        "Can not reach the espMilightHub directly, check you have entered the correct HUB_IP in the Things setup");
            }
        } catch (IOException e) {
            logger.warn(
                    "An error occured when trying to talk to your EspMilightHub directly, check the HUB_IP is correct. The error was :{}",
                    e);
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the EspMilightHub opensource bridge.");
        bridgeConfig = thing.getConfiguration();
        if (bridgeConfig.get(CONFIG_HUB_IP) != null) {
            putHttp("/settings",
                    "{\"mqtt_topic_pattern\":\"milight/commands/:device_id/:device_type/:group_id\",\"mqtt_update_topic_pattern\":\"\",\"mqtt_state_topic_pattern\":\"milight/states/:device_id/:device_type/:group_id\",\"group_state_fields\":[\"state\",\"level\",\"hue\",\"saturation\",\"mode\",\"color_temp\",\"bulb_mode\"]}");
        } else {
            logger.info("No HUB_IP has been provided, binding can not auto setup the Hub for you.");
        }
        checkConnectionJob = checkConnection.scheduleWithFixedDelay(pollConnection, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        disconnectMQTT();
        if (sendQueuedMQTTTimerJob != null) {
            sendQueuedMQTTTimerJob.cancel(true);
            sendQueuedMQTTTimerJob = null;
        }
        if (processIncommingMQTTTimerJob != null) {
            processIncommingMQTTTimerJob.cancel(true);
            processIncommingMQTTTimerJob = null;
        }
        if (checkConnectionJob != null) {
            checkConnectionJob.cancel(true);
            checkConnectionJob = null;
        }
    }
}
