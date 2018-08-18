/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.espmilighthub.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.openhab.binding.espmilighthub.handler.EspMilightHubBridgeHandler;
import org.openhab.binding.espmilighthub.handler.EspMilightHubHandler;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EspMilightHubHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Matthew Skinner - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, immediate = true, configurationPid = "binding.espmilighthub")
@NonNullByDefault
public class EspMilightHubHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(EspMilightHubHandlerFactory.class);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return EspMilightHubBridgeHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)
                | EspMilightHubHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        logger.debug("The thing factory: createHandler {} of type {}", thing.getThingTypeUID(), thing.getUID());

        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (EspMilightHubBridgeHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            return new EspMilightHubBridgeHandler((Bridge) thing);
        }
        if (EspMilightHubHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            return new EspMilightHubHandler(thing);
        }
        return null;
    }
}
