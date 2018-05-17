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

package org.openhab.binding.espmilighthub;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link EspMilightHubBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Matthew Skinner - Initial contribution
 */
@NonNullByDefault
public class EspMilightHubBindingConstants {

    public static final String BINDING_ID = "espmilighthub";

    // List of all Thing Type UIDs //Dont forget to add any new ones to the Set<ThingTypeUID> SUPPORTED_THING_TYPES so
    // FooHandlerFactory.java can use the thing
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "esp8266Bridge");
    public static final ThingTypeUID THING_TYPE_RGB_CCT = new ThingTypeUID(BINDING_ID, "rgb_cct");
    public static final ThingTypeUID THING_TYPE_CCT = new ThingTypeUID(BINDING_ID, "cct");
    public static final ThingTypeUID THING_TYPE_RGBW = new ThingTypeUID(BINDING_ID, "rgbw");
    public static final ThingTypeUID THING_TYPE_RGB = new ThingTypeUID(BINDING_ID, "rgb");
    public static final ThingTypeUID THING_TYPE_FUT089 = new ThingTypeUID(BINDING_ID, "fut089");

    // Bridge Things//
    public static final String CONFIG_MQTT_ADDRESS = "ADDR";
    public static final String CONFIG_MQTT_USER_NAME = "MQTT_USERNAME";
    public static final String CONFIG_MQTT_PASSWORD = "MQTT_PASSWORD";
    public static final String CONFIG_DEFAULT_COMMAND = "DEFAULT_COMMAND";
    public static final String CONFIG_TRIGGER_WHITE_HUE = "TRIGGER_WHITE_HUE";
    public static final String CONFIG_TRIGGER_WHITE_SAT = "TRIGGER_WHITE_SAT";
    public static final String CONFIG_FAVOURITE_WHITE = "FAVOURITE_WHITE";
    public static final String CONFIG_AUTOCTEMP_MAXDIMMED_TEMPERATURE = "AUTOCTEMP_MAXDIMMED_TEMPERATURE";
    public static final String CONFIG_DELAY_BETWEEN_MQTT = "DELAY_BETWEEN_MQTT";
    public static final String CONFIG_DELAY_BETWEEN_SAME_GLOBE = "DELAY_BETWEEN_SAME_GLOBE";
    public static final String CONFIG_1TRIGGERS_NIGHT_MODE = "1TRIGGERS_NIGHT_MODE";
    public static final String CONFIG_RGBW_WHITEMODE_SAT_THRESHOLD = "RGBW_WHITEMODE_SAT_THRESHOLD";
    public static final String CONFIG_POWERFAILS_TO_MINDIM = "POWERFAILS_TO_MINDIM";

    // Globe Things
    public static final String CHANNEL_LEVEL = "level";
    public static final String CHANNEL_COLOUR = "colour";
    public static final String CHANNEL_COLOURTEMP = "colourtemperature";
    public static final String CHANNEL_DISCO_MODE = "discomode";
    public static final String CHANNEL_BULB_MODE = "bulbmode";
    public static final String CHANNEL_COMMAND = "bulbcommand";
    public static final String CHANNEL_SEND_COMMAND = "sendbulbcommand";
}
