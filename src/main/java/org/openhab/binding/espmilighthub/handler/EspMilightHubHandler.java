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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EspMilightHubHandler} is responsible for handling commands of the globes, which are then
 * sent to one of the bridges to be sent out by MQTT.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class EspMilightHubHandler extends BaseThingHandler {
    private String globeType = thing.getThingTypeUID().getId();// eg rgb_cct
    private String globeLocation = this.getThing().getUID().getId();// eg 0x014
    private String remotesGroupID = globeLocation.substring(globeLocation.length() - 1, globeLocation.length());// eg 4
    private String remotesIDCode = globeLocation.substring(0, globeLocation.length() - 1);// eg 0x01
    private String savedLevel = "100";
    private String lastCommand = "empty";
    private EspMilightHubBridgeHandler bridgeHandler;
    private String bulbMode = "empty";
    @SuppressWarnings("unused")
    private Configuration config;
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(Arrays.asList(
            THING_TYPE_RGBW, THING_TYPE_RGB_CCT, THING_TYPE_FUT089, THING_TYPE_FUT091, THING_TYPE_CCT, THING_TYPE_RGB));

    private final Logger logger = LoggerFactory.getLogger(EspMilightHubHandler.class);

    public EspMilightHubHandler(Thing thing) {
        super(thing);
    }

    private int autoColourTemp(int brightness) {
        double maxTemp = bridgeHandler.getFavouriteWhite();
        double minTemp = bridgeHandler.getAutoCTempValue();
        if (minTemp <= maxTemp) {
            logger.error(
                    "AUTOCTEMP_MAXDIMMED_TEMPERATURE is less than the favourite white setting, using the favourite white instead of auto colour temp. Set parameter to null or a higher value to remove this message.");
            return (int) Math.round(maxTemp);
        }
        return (int) Math.round((minTemp - (((minTemp - maxTemp) / 100) * brightness)));
    }

    @Override
    public void handleUpdate(ChannelUID channelUID, State newState) {
        switch (channelUID.getId()) {
            case CHANNEL_BULB_MODE:
                bulbMode = newState.toString();
                break;
        } // end switch
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (command.toString() == "REFRESH") {
            logger.debug("'REFRESH' command has been called for:{}", channelUID);
            // This will cause all retained messages to be resent. Disabled for now.
            // bridgeHandler.subscribeToMQTT();
            return;
        }

        String topic = "milight/commands/" + remotesIDCode + "/" + globeType + "/" + remotesGroupID;

        switch (channelUID.getId())

        {

            case CHANNEL_LEVEL:

                if ("0".equals(command.toString()) || "OFF".equals(command.toString())) {
                    if ("cct".equals(globeType)) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"OFF\"}");
                    } else {
                        if (bridgeHandler.getPowerFailsToMaxDim()) {
                            bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":0}");
                        }
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"OFF\"}");
                    }
                    return;
                } else if ("ON".equals(command.toString())) {
                    if ("cct".equals(globeType)) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\"}");
                    } else {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":" + this.savedLevel + "}");
                    }
                    return;

                } else if ("1".equals(command.toString()) && bridgeHandler.get1TriggersNightMode()) {
                    bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"night_mode\"}");
                    return;
                }

                bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":" + command.toString() + "}");

                if (globeType.equals("rgb_cct") || globeType.equals("fut089")) {

                    if (bridgeHandler.getAutoCTempValue() != 0 && bulbMode.equals("white")) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"color_temp\":"
                                + autoColourTemp(Integer.parseInt(command.toString())) + "}");
                    }
                }

                this.savedLevel = command.toString();
                break;

            case CHANNEL_BULB_MODE:
                logger.debug("bulb mode is {}", command.toString());
                bulbMode = command.toString();
                break;

            case CHANNEL_COLOURTEMP:
                int scaledCommand = (int) Math.round((370 - (2.17 * Float.valueOf(command.toString()))));
                bridgeHandler.queueToSendMQTT(topic,
                        "{\"state\":\"ON\",\"level\":" + savedLevel + ",\"color_temp\":" + scaledCommand + "}");
                break;

            case CHANNEL_COMMAND:
                if (command instanceof StringType) {
                    lastCommand = command.toString();

                    if (lastCommand.equals("favourite_white")) {
                        bridgeHandler.queueToSendMQTT(topic,
                                "{\"state\":\"ON\",\"color_temp\":" + bridgeHandler.getFavouriteWhite() + "}");
                        break;
                    }

                    bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"" + command.toString() + "\"}");
                }
                break;

            case CHANNEL_SEND_COMMAND:
                if (lastCommand == "empty") {
                    lastCommand = bridgeHandler.getDefaultCommand();
                    updateState(CHANNEL_COMMAND, new StringType(lastCommand));
                }

                if (lastCommand.equals("favourite_white")) {
                    bridgeHandler.queueToSendMQTT(topic,
                            "{\"state\":\"ON\",\"color_temp\":" + bridgeHandler.getFavouriteWhite() + "}");
                    break;
                }
                bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"" + lastCommand + "\"}");
                break;

            case CHANNEL_DISCO_MODE:

                bridgeHandler.queueToSendMQTT(topic, "{\"mode\":\"" + command.toString() + "\"}");
                break;

            case CHANNEL_COLOUR:
                if ("ON".equals(command.toString())) {
                    bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":" + this.savedLevel + "}");
                    break;
                } else if ("0".equals(command.toString()) || "OFF".equals(command.toString())) {

                    if (bridgeHandler.getPowerFailsToMaxDim()) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":0}");
                    }

                    bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"OFF\"}");
                    break;
                }

                else if (command instanceof HSBType) {

                    HSBType hsb = new HSBType(command.toString());

                    // This feature allows google home or Echo to trigger white mode when asked to turn color to white.
                    if ((hsb.getHue().intValue()) == bridgeHandler.getTriggerWhiteHue()
                            && (hsb.getSaturation().intValue()) == bridgeHandler.getTriggerWhiteSat()) {

                        if ("rgb_cct".equals(globeType) || "fut089".equals(globeType)) {
                            bridgeHandler.queueToSendMQTT(topic,
                                    "{\"state\":\"ON\",\"color_temp\":" + bridgeHandler.getFavouriteWhite() + "}");
                            break;
                        }
                        // globe must only have 1 type of white so do this//
                        bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"set_white\"}");
                        break;
                    }
                    /////////////////////////////////////////////////////////////////////////////////////////////////////
                    else if (hsb.getBrightness().intValue() == 0) {

                        if (bridgeHandler.getPowerFailsToMaxDim()) {
                            bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":0}");
                        }

                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"OFF\"}");
                        break;
                    }
                    // Handle feature for CONFIG_RGBW_WHITEMODE_SAT_THRESHOLD//////////////////////////////////////
                    else if (bridgeHandler.getRGBWhiteSatThreshold() != -1
                            && hsb.getSaturation().intValue() <= bridgeHandler.getRGBWhiteSatThreshold()
                            && "rgbw".equals(globeType)) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"set_white\"}");
                        break;
                    }

                    // Normal flow for most runs here//
                    bridgeHandler.queueToSendMQTT(topic,
                            "{\"state\":\"ON\",\"level\":" + hsb.getBrightness().intValue() + ",\"hue\":"
                                    + hsb.getHue().intValue() + ",\"saturation\":" + hsb.getSaturation().intValue()
                                    + "}");
                    this.savedLevel = hsb.getBrightness().toString();
                    break;
                } // end of HSB type//

                // this is here for when the command is Percentype and not HSBtype//

                if ("1".equals(command.toString()) && bridgeHandler.get1TriggersNightMode()) {
                    bridgeHandler.queueToSendMQTT(topic, "{\"command\":\"night_mode\"}");
                    break;
                }

                bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"level\":" + command.toString() + "}");
                this.savedLevel = command.toString();

                if (globeType.equals("rgb_cct") || globeType.equals("fut089")) {

                    // logger.debug("BulbMode is:{}", bulbMode);

                    if (bridgeHandler.getAutoCTempValue() != 0 && bulbMode.equals("white")) {
                        bridgeHandler.queueToSendMQTT(topic, "{\"state\":\"ON\",\"color_temp\":"
                                + autoColourTemp(Integer.parseInt(command.toString())) + "}");
                    }
                }

                break;
        } // end switch
    } // end handle command

    @SuppressWarnings("null")
    @Override
    public void initialize() {
        if (getBridge() == null) {
            logger.error("This globe {}{} does not have a bridge selected, please fix.", remotesIDCode, remotesGroupID);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Globe must have a valid bridge selected to be able to come online, check you have a bridge selected.");
        } else {
            updateStatus(ThingStatus.ONLINE);
            globeType = thing.getThingTypeUID().getId();// eg rgb_cct
            globeLocation = this.getThing().getUID().getId();// eg 0x014
            remotesGroupID = globeLocation.substring(globeLocation.length() - 1, globeLocation.length());// eg 4
            remotesIDCode = globeLocation.substring(0, globeLocation.length() - 1);// eg 0x01

            config = getThing().getConfiguration();

            if (getBridge().getHandler() != null) {

                bridgeHandler = (EspMilightHubBridgeHandler) getBridge().getHandler();
            } else {
                logger.error("bridgeHandler is null");
                logger.error("This globe {}{} does not have a bridge selected, please fix.", remotesIDCode,
                        remotesGroupID);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                        "Globe must have a valid bridge selected to be able to come online, check you have a bridge selected.");
            }
        }
    }

    @Override
    public void dispose() {
    }
}
