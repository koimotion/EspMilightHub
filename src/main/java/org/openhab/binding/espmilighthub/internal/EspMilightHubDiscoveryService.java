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

package org.openhab.binding.espmilighthub.internal;

import static org.openhab.binding.espmilighthub.EspMilightHubBindingConstants.*;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.espmilighthub.handler.EspMilightHubBridgeHandler;
import org.openhab.binding.espmilighthub.handler.EspMilightHubHandler;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EspMilightHubDiscoveryService} is responsible for finding globes
 * and setting them up for the handlers.
 *
 * @author Matthew Skinner - Initial contribution
 */

@Component(service = DiscoveryService.class, immediate = true, configurationPid = "binding.espmilighthub")
public class EspMilightHubDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(EspMilightHubDiscoveryService.class);

    public EspMilightHubDiscoveryService() {
        super(EspMilightHubHandler.SUPPORTED_THING_TYPES, 5, true);
    }

    private void newThingFound(String globeType, String remoteGroupID, String remoteCode) {

        logger.info("A Thing which may already exsist has been found:{}:{}:{}", globeType, remoteCode, remoteGroupID);

        ThingTypeUID thingtypeuid = new ThingTypeUID(BINDING_ID, globeType);
        ThingUID thingUID = new ThingUID(thingtypeuid, EspMilightHubBridgeHandler.confirmedBridgeUID,
                remoteCode + remoteGroupID);

        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                .withBridge(EspMilightHubBridgeHandler.confirmedBridgeUID)
                .withLabel("Milight " + globeType + " Globe :" + remoteCode + remoteGroupID).build();
        thingDiscovered(discoveryResult);
    }

    private MqttClient client;

    private void findThings() {

        try {
            client = new MqttClient(EspMilightHubBridgeHandler.confirmedAddress, MqttClient.generateClientId(),
                    new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();

            if (EspMilightHubBridgeHandler.confirmedUser != null
                    && !EspMilightHubBridgeHandler.confirmedUser.contains("empty")) {
                options.setUserName(EspMilightHubBridgeHandler.confirmedUser);
            }

            if (EspMilightHubBridgeHandler.confirmedPassword != null
                    && !EspMilightHubBridgeHandler.confirmedPassword.equals("empty")) {
                options.setPassword(EspMilightHubBridgeHandler.confirmedPassword.toCharArray());
            }

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {

                    if (topic.contains("milight/states/")) {
                        logger.debug("Discovery Service just recieved the following new Milight state:{}:{}", topic,
                                message);

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

                                if (!remoteGroupID.matches("0")) { // It is not a thing if it is a Group of 0
                                    newThingFound(globeType, remoteGroupID, remoteCode);
                                }
                            }
                        }
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {

                }
            });

            client.connect(options);
            client.subscribe("milight/states/#", 1);

        } catch (MqttException e) {
            logger.error("Error: Could not connect to MQTT broker to search for New Things.{}", e);
        }
    }

    @Override
    protected void startScan() {
        removeOlderResults(getTimestampOfLastScan());

        if (EspMilightHubBridgeHandler.confirmedBridgeUID == null) {
            logger.info(
                    "No ONLINE EspMilightHub bridges were found. You need to add then edit a Bridge with your MQTT details before any of your globes can be found.");
            ThingTypeUID thingtypeuid = THING_TYPE_BRIDGE;
            ThingUID thingUID = new ThingUID(thingtypeuid, "Auto001");
            DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withLabel("EspMilightHub")
                    .withThingType(THING_TYPE_BRIDGE).build();
            thingDiscovered(discoveryResult);
        }

        else if (!"empty".equals(EspMilightHubBridgeHandler.confirmedAddress)) {
            logger.info("EspMilightHubDiscoveryService is now looking for new things");
            findThings();

            try {
                Thread.sleep(3000);
                try {
                    client.disconnect();
                    deactivate();
                } catch (MqttException e) {

                }
            } catch (InterruptedException e) {

            }

        } else {
            logger.error(
                    "ERROR: Can not scan if no Bridges are setup with valid MQTT broker details. Setup an EspMilightHub bridge then try again.");
        }
    }

    @Override
    protected void startBackgroundDiscovery() {

    };

    @Override
    protected void deactivate() {
        super.deactivate();
    }
}
