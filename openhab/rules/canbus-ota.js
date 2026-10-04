// =============================================================================
// Indian CAN Bus — Firmware OTA trigger
// =============================================================================
// The sitemap row (item CanBus_OTA, mappings "update"="🔄 Update Now" and
// "mqtt"="📡 Update via MQTT") sends one of two commands. This rule catches the
// command and publishes it straight to the MQTT broker on topic
// canbus/springfield/ota, which the ESP32 firmware listens for (net.cpp,
// onMqttMessage).
//
// We publish via getActions("mqtt", broker).publishMQTT(...) instead of an
// outbound item binding because an outbound-only binding never updates the
// item state, so DSL "changed"/"received command" triggers proved unreliable
// here. A plain JSRule on the command is deterministic.
//
// Items (items/canbus.items):
//   String CanBus_OTA   <- state from canbus/springfield/ota/status (channel
//                          otaStatus); commands are caught here, not bound
//
// Flow, "update":
//   UI press -> publishMQTT canbus/springfield/ota "update"
//            -> the ESP32 downloads /static/indian-canbus-firmware.bin over HTTP
//               from the server's LAN address (home network only)
//            -> the board writes its progress to ota/status; nothing is cleared
//
// Flow, "mqtt" (firmware 2026.10.04-1 and later): forwarded the same way; the
// board then pulls the same image in chunks over the MQTT link, which also
// works from a hotspot. canbus-ota-mqtt.js answers its requests.
// Either way the new image is on trial until it has reached the broker.
// =============================================================================

const { rules, triggers, actions, items } = require('openhab');

const BROKER_UID = 'mqtt:broker:broker';
const OTA_TOPIC  = 'canbus/springfield/ota';

rules.JSRule({
  name: 'Indian CAN Bus - Firmware OTA trigger',
  description: 'Publishes "update" (HTTP on the LAN) or "mqtt" (over the MQTT link) to canbus/springfield/ota',
  triggers: [
    triggers.ItemCommandTrigger('CanBus_OTA')
  ],
  execute: (event) => {
    // "update" = HTTP from the LAN address (the sitemap Switch), "mqtt" = over the MQTT link.
    const command = String(event.receivedCommand);
    if (command !== 'update' && command !== 'mqtt') {
      return;
    }

    console.info('canbus-ota: OTA command "' + command + '" received — publishing to MQTT broker');

    const mqtt = actions.Things.getActions('mqtt', BROKER_UID);
    if (mqtt === null) {
      console.error('canbus-ota: MQTT broker actions not available (' + BROKER_UID + ') — is the broker ONLINE?');
      items.CanBus_OTA.postUpdate('Broker offline');
      return;
    }

    // Fire the OTA trigger to the ESP32.
    mqtt.publishMQTT(OTA_TOPIC, command);
    console.info('canbus-ota: published "' + command + '" to ' + OTA_TOPIC);

    // Initial feedback only. From here the ESP32 drives CanBus_OTA live via
    // canbus/springfield/ota/status: "Starting download..." -> "Downloading NN%" ->
    // "OK — rebooting" -> "Running <ver>". No auto-clear: we want the result
    // (and the running version after reboot) to stay visible.
    items.CanBus_OTA.postUpdate('Requested — waiting for device...');
  }
});
