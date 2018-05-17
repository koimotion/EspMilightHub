/**
 * Copyright (c) 2014,2018 by the respective copyright holders.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.espmilighthub.handler;

/**
 * The {@link EspMilightHubBridgeHandler} is responsible for handling the bridge commands and all MQTT comms, which are
 * sent to one of the channels.
 *
 * @author Matthew Skinner - Initial contribution
 */

import static org.openhab.binding.espmilighthub.EspMilightHubBindingConstants.*;

import java.util.Collections;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

public class EspMilightHubBridgeHandler extends BaseBridgeHandler implements MqttCallbackExtended {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public static String confirmedAddress = "empty";
    public static String confirmedUser = "empty";
    public static String confirmedPassword = "empty";
    public static ThingUID confirmedBridgeUID;
    public static int readyToRefresh = 0;

    private final ScheduledExecutorService schedulerOut = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> sendQueuedMQTTTimerJob = null;
    private final ScheduledExecutorService schedulerIn = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> processIncommingMQTTTimerJob = null;

    private LinkedList<String> fifoOutgoingTopic = new LinkedList<String>();
    private LinkedList<String> fifoOutgoingPayload = new LinkedList<String>();
    private LinkedList<String> fifoIncommingTopic = new LinkedList<String>();
    private LinkedList<String> fifoIncommingPayload = new LinkedList<String>();

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

        // logger.debug("Could not find {} in the incomming MQTT message which was: {}", jsonPath, messageJSON);
        return "";
    }

    private void processIncomingState(String globeType, String remoteCode, String remoteGroupID, String messageJSON) {

        logger.debug("** Processing new incoming MQTT message to update Openhab's controls.");
        // logger.debug("globeType\t={}", globeType);
        // logger.debug("remoteCode\t={}", remoteCode);
        // logger.debug("remoteGroupID\t={}", remoteGroupID);

        String channelPrefix = "espmilighthub:" + globeType + ":" + thing.getUID().getId() + ":" + remoteCode
                + remoteGroupID + ":";
        // logger.debug("Chan Prefix\t={}", channelPrefix);

        // Need to handle State and Level at the same time to process level=0 as off//
        int iBulbLevel = 1;
        String bulbState = resolveJSON(messageJSON, "\"state\":\"", 3);
        String bulbLevel = resolveJSON(messageJSON, "\"level\":", 3);

        if (!bulbLevel.isEmpty()) {// level is not empty

            if ("0".equals(bulbLevel) && bulbState.contains("ON")) {
                // logger.debug("bulbState\t={}", bulbState);
                // logger.debug("bulbLevel\t={}", bulbLevel);
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), new PercentType("1"));
            }

            else if ("0".equals(bulbLevel) || bulbState.contains("OFF")) {
                // logger.debug("bulbState\t={}", bulbState);
                // logger.debug("bulbLevel\t={}", bulbLevel);
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), OnOffType.valueOf("OFF"));
            }

            else {
                // logger.debug("bulbState\t={}", bulbState);
                // logger.debug("bulbLevel\t={}", bulbLevel);
                iBulbLevel = Math.round(Float.valueOf(bulbLevel));
                updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), new PercentType(iBulbLevel));
            }

        } else if (bulbState.contains("ON") || bulbState.contains("OFF")) { // Level is missing
            // logger.debug("bulbState\t={}", bulbState);
            // logger.debug("bulbLevel\t={}", bulbLevel);
            updateState(new ChannelUID(channelPrefix + CHANNEL_LEVEL), OnOffType.valueOf(bulbState));
        }

        String bulbMode = resolveJSON(messageJSON, "\"bulb_mode\":\"", 5);
        // logger.debug("bulbMode\t={}", bulbMode);
        if ("white".equals(bulbMode)) {

            if (!"cct".equals(globeType)) {
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("white"));
                updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE), new DecimalType("-1"));
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("white"));
            }

            String bulbCTemp = resolveJSON(messageJSON, "\"color_temp\":", 3);
            if (!bulbCTemp.isEmpty()) {
                // logger.debug("bulbCTemp\t={}", bulbCTemp);
                int ibulbCTemp = (int) Math.round(((Float.valueOf(bulbCTemp) / 2.17) - 171) * -1);
                updateState(new ChannelUID(channelPrefix + CHANNEL_COLOURTEMP), new PercentType(ibulbCTemp));
                // logger.debug("CTemp Int\t={}", ibulbCTemp);
            }

        } else if ("color".equals(bulbMode)) {

            if (!"cct".equals(globeType)) {
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("color"));
                updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE), new DecimalType("-1"));
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("color"));
            }

            String bulbHue = resolveJSON(messageJSON, "\"hue\":", 3);
            // logger.debug("bulbHue\t={}", bulbHue);
            String bulbSaturation = resolveJSON(messageJSON, "\"saturation\":", 3);
            // logger.debug("bulbSaturation\t={}", bulbSaturation);

            if ("".equals(bulbHue) || "".equals(bulbSaturation)) {
                ;
            } else {

                updateState(new ChannelUID(channelPrefix + CHANNEL_COLOUR),
                        new HSBType(bulbHue + "," + bulbSaturation + "," + iBulbLevel));
            }

        } else if ("scene".equals(bulbMode)) {

            if (!"cct".equals(globeType)) {
                updateState(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("scene"));
                postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("scene"));
            }

            String bulbDiscoMode = resolveJSON(messageJSON, "\"mode\":", 1);
            // logger.debug("bulbDiscoMode=\t{}", bulbDiscoMode);

            if ("".equals(bulbDiscoMode)) {
                ;
            } else {

                updateState(new ChannelUID(channelPrefix + CHANNEL_DISCO_MODE),
                        new DecimalType(bulbDiscoMode.toString()));
            }
        } else if ("night".equals(bulbMode)) {
            postCommand(new ChannelUID(channelPrefix + CHANNEL_BULB_MODE), new StringType("night"));

            if (!"cct".equals(globeType)) {
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
            }

            else if (!fifoIncommingTopic.isEmpty()) {

                String topic = fifoIncommingTopic.removeFirst();
                String payload = fifoIncommingPayload.removeFirst();
                String cutTopic = topic.replace("milight/states/", "");

                int index = cutTopic.indexOf("/");
                if (index != -1) // -1 means "not found"
                {
                    String remoteCode = (cutTopic.substring(0, index)); // Store the remote code for use later
                    cutTopic = topic.replace("milight/states/" + remoteCode + "/", "");

                    index = cutTopic.indexOf("/");
                    if (index != -1) // -1 means "not found"
                    {
                        String globeType = (cutTopic.substring(0, index));
                        String remoteGroupID = (cutTopic.substring(++index, ++index));
                        processIncomingState(globeType, remoteCode, remoteGroupID, payload);
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
        fifoIncommingTopic.addLast(topic);
        fifoIncommingPayload.addLast(payload.toString());

        if (processIncommingMQTTTimerJob == null) {
            processIncommingMQTTTimerJob = schedulerIn.scheduleAtFixedRate(pollingIncommingQueuedMQTT, 10, 10,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, java.lang.String serverURI) {

        logger.info("MQTT sucessfully connected");

        try {
            client.subscribe("milight/states/#", 1);
            updateStatus(ThingStatus.ONLINE);
        } catch (MqttException e) {
            logger.error("Error: Could not subscribe to 'milight/states/#' cause is:{}", e);
        }

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
            // options.setConnectionTimeout(4); // connection must be made in under 4 seconds

            client.setCallback(this);
            client.connect(options);
        } catch (MqttException e) {
            logger.error("Error: Could not connect to MQTT broker.{}", e);
            return false;
        }
        confirmedAddress = bridgeConfig.get(CONFIG_MQTT_ADDRESS).toString();
        return true;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // logger.debug("MQTT broker replied an outgoing command was recieved:{}", token);
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
                if (fifoOutgoingTopic.size() > 1) {
                    try {
                        sendMQTT(fifoOutgoingTopic.removeFirst(), fifoOutgoingPayload.removeFirst());
                    } catch (NoSuchElementException e) {
                        logger.info(
                                "********************* >1 outgoing queue *CATCH* Triggered, wiping the outgoing FIFO buffer clean ********************");
                        fifoOutgoingTopic.clear();
                        fifoOutgoingPayload.clear();
                    }
                } else if (fifoOutgoingTopic.size() == 1) {
                    try {
                        sendMQTT(fifoOutgoingTopic.element(), fifoOutgoingPayload.element());
                        if (fifoOutgoingTopic.size() == 1) {
                            fifoOutgoingTopic.removeFirst();
                            fifoOutgoingPayload.removeFirst();
                        }

                    } catch (NoSuchElementException e) {
                        logger.info(
                                "********************* ==1 outgoing queue *CATCH* Triggered, wiping the outgoing FIFO buffer clean ********************");
                        fifoOutgoingTopic.clear();
                        fifoOutgoingPayload.clear();
                    }
                }

                else {

                    logger.debug("MQTT sending queue is getting cancelled");
                    sendQueuedMQTTTimerJob.cancel(true);
                    sendQueuedMQTTTimerJob = null;
                    logger.debug("MQTT sending queue made NULL");
                    return;
                }
                logger.debug("MQTT message just sent, there are now {} more messages in the queue",
                        fifoOutgoingTopic.size());

            } // end of isConnected
        }
    };

    public void queueToSendMQTT(String topic, String payload) {

        if (topic == null || payload == null) {
            logger.error("null was found in requested outgoing message:{}:{}:", topic, payload);
            return;
        }

        if (fifoOutgoingTopic.size() > 1 && fifoOutgoingTopic.getLast().equals(topic)
                && !fifoOutgoingPayload.getLast().equals("{\"state\":\"ON\",\"level\":0}")) {
            fifoOutgoingTopic.removeLast();
            fifoOutgoingPayload.removeLast();
            if (increaseChoke == false) {
                increaseChoke = true;
                logger.debug("changing queue to DELAY_BETWEEN_SAME_GLOBE speed.");
                if (sendQueuedMQTTTimerJob != null) {
                    sendQueuedMQTTTimerJob.cancel(false);
                }
                sendQueuedMQTTTimerJob = schedulerOut.scheduleAtFixedRate(pollingSendQueuedMQTT,
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_SAME_GLOBE).toString()),
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_SAME_GLOBE).toString()),
                        TimeUnit.MILLISECONDS);
            }

            logger.debug("Message reduction has removed a command as the queue contains multiples for the same globe.");
        } else {
            if (increaseChoke == true) {
                increaseChoke = false;
                logger.debug("changing queue back to normal speed.");
                if (sendQueuedMQTTTimerJob != null) {
                    sendQueuedMQTTTimerJob.cancel(false);
                }
                sendQueuedMQTTTimerJob = schedulerOut.scheduleAtFixedRate(pollingSendQueuedMQTT,
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()),
                        Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()),
                        TimeUnit.MILLISECONDS);
            }
        }

        fifoOutgoingTopic.addLast(topic);
        fifoOutgoingPayload.addLast(payload);

        try {
            if (topic != fifoOutgoingTopic.getLast()) {
                logger.debug("queue mis match occured with topic");
            }

            if (payload != fifoOutgoingPayload.getLast()) {
                logger.debug("queue mis match occured with payload");
            }

        } catch (NoSuchElementException e) {
            logger.info(
                    "*********************  queueToSend *CATCH* Triggered, wiping the outgoing FIFO buffer clean and trying to resend ********************");
            fifoOutgoingTopic.clear();
            fifoOutgoingPayload.clear();
            fifoOutgoingTopic.addLast(topic);
            fifoOutgoingPayload.addLast(payload);
        }

        if (sendQueuedMQTTTimerJob == null) {
            sendQueuedMQTTTimerJob = schedulerOut.scheduleAtFixedRate(pollingSendQueuedMQTT, 0,
                    Integer.parseInt(bridgeConfig.get(CONFIG_DELAY_BETWEEN_MQTT).toString()), TimeUnit.MILLISECONDS);
            logger.debug("started timer because it was null.");
        }
    }

    public void disconnectMQTT() {
        try {
            client.disconnect();
        } catch (MqttException e) {
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

    @Override
    public void initialize() {
        logger.debug("Initializing Bridge handler.");
        bridgeConfig = getThing().getConfiguration();

        if (connectMQTT(true)) {// connect to get a full list of globe states//
            updateStatus(ThingStatus.ONLINE);
            confirmedBridgeUID = this.getThing().getUID();

        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Could not connect to the MQTT broker, check the address, user and pasword are correct and the broker is online.");
        }
    }

    @Override
    public void dispose() {
        logger.debug("Bridge dispose called, about to disconnect the MQTT broker.");
        disconnectMQTT();

        if (sendQueuedMQTTTimerJob != null) {
            sendQueuedMQTTTimerJob.cancel(true);
        }

        if (processIncommingMQTTTimerJob != null) {
            processIncommingMQTTTimerJob.cancel(true);
        }
    }

    public void run() {
        logger.debug("Starting bridge RUN method");
    }

}
