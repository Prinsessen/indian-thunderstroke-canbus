// =============================================================================
// Indian CAN Bus — Firmware OTA trigger
// =============================================================================
// The sitemap Switch (item CanBus_OTA, mapping "update"="🔄 Update Now") sends
// the command "update" when pressed. This rule catches that command and
// publishes it straight to the MQTT broker on topic canbus/springfield/ota, which
// the ESP32 firmware listens for (onMqttMessage -> httpUpdate.update()).
//
// We publish via getActions("mqtt", broker).publishMQTT(...) instead of an
// outbound item binding because an outbound-only binding never updates the
// item state, so DSL "changed"/"received command" triggers proved unreliable
// here. A plain JSRule on the command is deterministic.
//
// Items (items/canbus.items):
//   String CanBus_OTA   <- virtual status line, no binding
//
// Flow:
//   UI press -> command "update" -> publishMQTT canbus/springfield/ota "update"
//            -> ESP32 downloads http://openhab.local:8080/static/indian-canbus-firmware.bin
//            -> status shows "Downloading..." then clears after a few seconds
// =============================================================================

const { rules, triggers, actions, items } = require('openhab');

const BROKER_UID = 'mqtt:broker:broker';
const OTA_TOPIC  = 'canbus/springfield/ota';

rules.JSRule({
  name: 'Indian CAN Bus - Firmware OTA trigger',
  description: 'Publishes "update" to canbus/springfield/ota so the ESP32 pulls new firmware over HTTP',
  triggers: [
    triggers.ItemCommandTrigger('CanBus_OTA')
  ],
  execute: (event) => {
    // The sitemap Switch only ever sends "update"; guard anyway.
    if (String(event.receivedCommand) !== 'update') {
      return;
    }

    console.info('canbus-ota: OTA update command received — publishing to MQTT broker');

    const mqtt = actions.Things.getActions('mqtt', BROKER_UID);
    if (mqtt === null) {
      console.error('canbus-ota: MQTT broker actions not available (' + BROKER_UID + ') — is the broker ONLINE?');
      items.CanBus_OTA.postUpdate('Broker offline');
      return;
    }

    // Fire the OTA trigger to the ESP32.
    mqtt.publishMQTT(OTA_TOPIC, 'update');
    console.info('canbus-ota: published "update" to ' + OTA_TOPIC);

    // Initial feedback only. From here the ESP32 drives CanBus_OTA live via
    // canbus/springfield/ota/status: "Starting download..." -> "Downloading NN%" ->
    // "OK — rebooting" -> "Running <ver>". No auto-clear: we want the result
    // (and the running version after reboot) to stay visible.
    items.CanBus_OTA.postUpdate('Requested — waiting for device...');
  }
});
