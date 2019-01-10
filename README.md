# <bindingName> Binding

This is a new openhab 2.x binding that allows a single opensource esp8266 bridge (created by Chris Mullins aka Sidoh) to automatically find and add milight globes into OpenHab2. The first question Openhab 2 users may have is “Why another binding when one already exists?”, The short answer/s to this is the new OPENSOURCE bridge allows:

+ Almost unlimited groups so you can have individual control over an entire house of milight globes without multiple OEM bridges. A single bridge uses less power for one of many advantages of having only 1 hub/bridge.

+ If using the milight remotes to control the globes, this binding will update the openhab controls the moment a key is pressed on the remote.

+ Auto scan and adding of the globes via paper UI.

+ If you reboot Openhab2 the state of the globes will refresh and display correctly after the reboot due to the hub tracking the states and recording them in the MQTT broker.

+ Many other reasons besides just being opensource and hence can get firmware updates to support new globes and wifi KRACK patches.

## Supported Things

A bridge can have any of these things added which are the types of globes the opensource bridge supports:

+ cct
+ rgb_cct
+ rgbw
+ rgb

## Discovery

To use paperui to setup your lights, first add an EspMilightHub (aka Bridge) and then fill in the MQTT broker details in the properties of this bridge. Click on the pencil icon to reach this page where the parameters are seen. After doing this and the Bridge shows up as ONLINE you can do a scan for things and the globes should be auto found and added to your inbox if you have the auto approve and link settings ticked in paperUI’s settings.

Setting up the globes to be auto found:
For globes to be auto found you either need to use an OEM Milight remote with a linked globe, OR send a command to link the bulb first via the open source bridges control panel. Using the second method you do not need to own a physical remote and can make up any “Remote Device ID” you wish to invent. The mqtt broker saves the state when any command is sent and this allows the globe to be auto found.

To remove a globe from the saved states of your MQTT broker use this command:


```
mosquitto_pub -u username -P password -p 1883 -t 'milight/states/0x0/rgb_cct/1' -n -r
```

Replace the topic with the one you wish to remove and this will stop the globe getting autodetected by this binding.


## Binding Configuration

TODO: PR with content are welcome.

## Thing Configuration

TODO: PR with content are welcome.

## Channels

TODO: PR with content are welcome.


## Full Example

Manual configuration of the binding is my preferred method as I find it far faster to setup and also to backup. It is also handy having a list of the names and the codes that the globes are linked to.

Place the contents in a file called 'espmilighthub.things' and save it to your "things" folder.

```   
Bridge espmilighthub:esp8266Bridge:001 [ADDR="tcp://192.168.1.100:1883", MQTT_USERNAME="myusername", MQTT_PASSWORD="Suitcase123456"]
{
        Thing   rgb_cct 0xEC591 "Front Hall"    //comments are possible after double /  
        Thing   cct 0xb4c81 "Lounge Lamp 1"
        Thing   rgb_cct 0xAB13 "Linen Hall 2"       
        Thing   rgbw 20 "Bathroom Mirror All"
        Thing   rgbw 21 "Bathroom Mirror 1" //Street end
        Thing   rgbw 22 "Bathroom Mirror 2"  
        Thing   rgbw 23 "Bathroom Mirror 3"   
        Thing   rgbw 24 "Bathroom Mirror 4"          
        Thing   rgb 0xe671 "Bed2 Hall"
}
```

Additional bridge settings can be made with these:

+ FAVOURITE_WHITE
+ DELAY_BETWEEN_MQTT
+ DELAY_BETWEEN_SAME_GLOBE
+ TRIGGER_WHITE_SAT
+ TRIGGER_WHITE_HUE
+ DEFAULT_COMMAND
+ 1TRIGGERS_NIGHT_MODE
+ RGBW_WHITEMODE_SAT_THRESHOLD
+ POWERFAILS_TO_MINDIM
+ AUTOCTEMP_MAXDIMMED_TEMPERATURE


By looking in PaperUI at your bridge (click on the pencil icon) you will get descriptions on what these do and what valid ranges are. If you use manual text configuration you can not change them in paperUI otherwise you get a conflict message in paperUI. 


Example of my items file:

```   
Switch Milight_ID0xEC59_G1_State     "Light On/Off"         {channel="espmilighthub:rgb_cct:001:0xEC591:level"}
Dimmer Milight_ID0xEC59_G1_Level     "Front Hall"           {channel="espmilighthub:rgb_cct:001:0xEC591:level"}
Dimmer Milight_ID0xEC59_G1_CTemp     "White Color Temp"     {channel="espmilighthub:rgb_cct:001:0xEC591:colourtemperature"}
Color  Milight_ID0xEC59_G1_Hue    "Front Hall" ["Lighting"] {channel="espmilighthub:rgb_cct:001:0xEC591:colour"}
String Milight_ID0xEC59_G1_Cmd      "Command to Send"      {channel="espmilighthub:rgb_cct:001:0xEC591:bulbcommand"}
Switch Milight_ID0xEC59_G1_SndCmd    "Send Command"            {channel="espmilighthub:rgb_cct:001:0xEC591:sendbulbcommand"}

Switch Milight_ID2_G1_State     "Lounge Lamp 1"  ["Switchable"] {channel="espmilighthub:cct:001:0xb4c81:level"}
Dimmer Milight_ID2_G1_Level     "Brightness [%d %%]"            {channel="espmilighthub:cct:001:0xb4c81:level"}
Dimmer Milight_ID2_G1_CTemp         "White Color Temp"          {channel="espmilighthub:cct:001:0xb4c81:colourtemperature"}
```

And a sample of the sitemap contents:

```   
        Text label="EntryHallway" icon="light" 
        {

            Switch      item=Milight_ID0xEC59_G1_State
            Slider      item=Milight_ID0xEC59_G1_Level
            Slider      item=Milight_ID0xEC59_G1_CTemp
            Colorpicker item=Milight_ID0xEC59_G1_Hue
            Selection   item=Milight_ID0xEC59_G1_Cmd mappings=[next_mode='next_mode', previous_mode='previous_mode', mode_speed_up='mode_speed_up', mode_speed_down='mode_speed_down', set_white='set_white', pair='pair',unpair='unpair',level_down='level_down',level_up='level_up',temperature_down='temperature_down',temperature_up='temperature_up',night_mode='night_mode',favourite_white='favourite_white']
            Switch      item=Milight_ID0xEC59_G1_SndCmd mappings=[ON="Send"]
        
        }

            Text label="Lounge Lamp 1" icon="light" 
            {
                Switch      item=Milight_ID2_G1_State
                Slider      item=Milight_ID2_G1_Level
                Slider      item=Milight_ID2_G1_CTemp
            }
```

## Fault Finding

You can use this linux command to watch all MQTT topics from milight:


```
mosquitto_sub -u usernamehere -P passwordhere -p 1883 -v -t 'milight/#'
```


To see more detailed logs you can do this in the openhab console:

```
log:set TRACE org.openhab.binding.espmilighthub
```

change TRACE to INFO to go back to normal default output in your logs.
Whilst still in the console you can type

```
log:tail
```

And this shows the log output, CTRL + C ends it.

## How to use the light with Google Home or Alexa

The binding is setup by default for google home, so if using Alexa go into the settings of the binding for the hue and saturation triggers for white. Both need to match but any brightness value is fine. The default values 36 and 32 are for Google home, make these 0 and 100 for Alexa. Now when you ask google home or Alexa to change the light to White, it can change the globe to true white which you can select with the bindings setting called FAVOURITE_WHITE.

Example for Google Home:

```   
Bridge espmilighthub:esp8266Bridge:001 [ADDR="tcp://192.168.1.100:1883", MQTT_USERNAME="myusername", MQTT_PASSWORD="Suitcase123456", TRIGGER_WHITE_HUE=36, TRIGGER_WHITE_SAT = 32, FAVOURITE_WHITE = 300]
{
        Thing   rgb_cct 0xEC591 "Front Hall"    //comments are possible after double /  
}
```

Example for Alexa:

```   
Bridge espmilighthub:esp8266Bridge:001 [ADDR="tcp://192.168.1.100:1883", MQTT_USERNAME="myusername", MQTT_PASSWORD="Suitcase123456", TRIGGER_WHITE_HUE=36, TRIGGER_WHITE_SAT = 32, FAVOURITE_WHITE = 300]
{
        Thing   rgb_cct 0xEC591 "Front Hall"    //comments are possible after double /  
}
```

example item for both the above:

```
Color  Milight_Hue    "Front Hall" ["Lighting"] {channel="espmilighthub:rgb_cct:001:0xEC591:colour"}
```


## How to change the lights from a rule

In an Openhab rule, you can use these commands to change the lights.

Send a desired colour in HSB format.

```
Milight_Hue.sendCommand(100,100,100)
```

To go back to white so long as you have not changed the bridges default settings, it is as easy as…

```
Milight_Hue.sendCommand(36,32,100)
```

Turn a globe on and then off

```
Milight_ID0xEC59_G1_Level.sendCommand(ON)
Milight_ID0xEC59_G1_Level.sendCommand(OFF)

```
