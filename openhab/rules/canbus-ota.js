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
//
// IGNITION FIRST (code review 2026-10-23, M1). The owner's rule since 2026-10-03 - prove
// the ignition is on from the bus before any OTA to the bike (firmware OTA.md, "Before
// pressing Update") - was only in her checklist: the rule published whatever was pressed,
// also with the bike parked and the board awake for its five minutes after key-off or its
// hourly wake. Power lost mid-download leaves a half OTA nobody can read. Now the command
// goes out only when the bus itself shows life: CanBus_Ignition ON, and efmsg (the
// MCP2518's error-free message counter, CanBus_Bus_CleanMsgs, reported every 30 s) has
// CLIMBED between the last two reports, the latest under 40 s old. "Online", Meta and the
// state values are not proof: they come from WiFi, or are repeated while the bus is quiet.
// =============================================================================

const { rules, triggers, actions, items, cache } = require('openhab');

const BROKER_UID = 'mqtt:broker:broker';
const OTA_TOPIC  = 'canbus/springfield/ota';
const EFMSG_FRESH_MS = 40 * 1000;    // the latest health report at most this old
const EFMSG_PAIR_MS = 80 * 1000;     // ... and the one before it at most this old

/** Is the bus alive right now? Returns null when proven, or the reason it is not. */
function busNotProven() {
  if (String(items.getItem('CanBus_Ignition').state) !== 'ON') return 'ignition is not ON';
  const s = cache.private.get('efmsgSamples') || [];
  if (s.length < 2) return 'waiting for two bus reports (30 s apart) - press again in a minute';
  const [a, b] = s.slice(-2);
  const now = Date.now();
  if (now - b.at > EFMSG_FRESH_MS || now - a.at > EFMSG_PAIR_MS) return 'no fresh bus report';
  if (!(b.v > a.v)) return 'the bus is silent (efmsg ' + a.v + ' -> ' + b.v + ')';
  return null;
}

rules.JSRule({
  name: 'Indian CAN Bus - OTA ignition proof (efmsg samples)',
  description: 'Keeps the last two CanBus_Bus_CleanMsgs reports, for the ignition proof before an OTA',
  triggers: [triggers.ItemStateUpdateTrigger('CanBus_Bus_CleanMsgs')],
  execute: (event) => {
    const v = parseFloat(String(event.receivedState !== undefined ? event.receivedState : items.getItem('CanBus_Bus_CleanMsgs').state));
    if (isNaN(v)) return;
    const s = (cache.private.get('efmsgSamples') || []).concat([{ at: Date.now(), v }]).slice(-2);
    cache.private.put('efmsgSamples', s);
  }
});

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

    const why = busNotProven();
    if (why) {
      console.warn('canbus-ota: OTA "' + command + '" REFUSED - ignition not proven from the bus: ' + why);
      items.getItem('CanBus_OTA').postUpdate('Refused - ignition not proven (' + why + ')');
      return;
    }
    console.info('canbus-ota: OTA command "' + command + '" received, bus alive — publishing to MQTT broker');

    const mqtt = actions.Things.getActions('mqtt', BROKER_UID);
    if (mqtt === null) {
      console.error('canbus-ota: MQTT broker actions not available (' + BROKER_UID + ') — is the broker ONLINE?');
      items.getItem('CanBus_OTA').postUpdate('Broker offline');
      return;
    }

    // Fire the OTA trigger to the ESP32.
    mqtt.publishMQTT(OTA_TOPIC, command);
    console.info('canbus-ota: published "' + command + '" to ' + OTA_TOPIC);

    // Initial feedback only. From here the ESP32 drives CanBus_OTA live via
    // canbus/springfield/ota/status: "Starting download..." -> "Downloading NN%" ->
    // "OK — rebooting" -> "Running <ver>". No auto-clear: we want the result
    // (and the running version after reboot) to stay visible.
    items.getItem('CanBus_OTA').postUpdate('Requested — waiting for device...');
  }
});
