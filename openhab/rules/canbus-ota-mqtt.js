// =============================================================================
// Indian CAN Bus — Firmware OTA over the MQTT link
// =============================================================================
// canbus-ota.js sends "update" and the board pulls the image over HTTP from the
// server's LAN address. That works in the garage and nowhere else, and the
// image is not to be served to the internet: it carries the compiled WiFi and
// MQTT credentials. The broker, though, is reached from anywhere the board has
// a network (the phone's hotspot included), over TLS. So for the command "mqtt"
// the image goes the same way, and this rule is the server half of it.
//
// The board pulls (firmware: net.cpp, "OTA over the MQTT link"):
//
//   <base>/ota/req  "info"                         -> <base>/ota/info  "<size> <md5>"
//   <base>/ota/req  "<md5> <offset> <count> <len>" -> <base>/ota/chunk, <count> times:
//                                                     4 bytes offset (LE) + <len> bytes
//
// Nothing is kept between requests but the image itself: every request names
// the md5 it wants, so a file replaced half-way is noticed here ("ERR ...") and
// not after 1.4 MB. The board checks the md5 of the whole image before it
// switches, and the roll-back rule covers what happens after the reboot.
//
// The requests arrive on a TRIGGER channel, not an item: ninety identical-looking
// updates in a minute belong neither in events.log nor in persistence.
//
// Start one:  CanBus_OTA or CanBench_OTA <- "mqtt"   (the image is the same file "update" serves)
// =============================================================================

const { rules, triggers, actions } = require('openhab');

const BROKER_UID = 'mqtt:broker:broker';
const MAX_CHUNK  = 2048;   // the board's MQTT client buffer is 3072
const MAX_WINDOW = 16;

// One entry per board identity: the channel its requests arrive on, its base
// topic and the one image it may have. A board cannot ask for another's file.
const BOARDS = {
  'mqtt:topic:broker:canbus:otaReq': {
    name: 'springfield',
    base: 'canbus/springfield',
    file: '/etc/openhab/html/indian-canbus-firmware.bin'
  },
  'mqtt:topic:canbus-bench:otaReq': {
    name: 'bench',
    base: 'canbus/bench',
    file: '/etc/openhab/html/indian-canbus-bench-firmware.bin'
  }
};

const File          = Java.type('java.io.File');
const Files         = Java.type('java.nio.file.Files');
const MessageDigest = Java.type('java.security.MessageDigest');
const ByteBuffer    = Java.type('java.nio.ByteBuffer');
const ByteOrder     = Java.type('java.nio.ByteOrder');

const images = {};   // base topic -> { md5, bytes, size }

function loadImage(board) {
  const bytes  = Files.readAllBytes(new File(board.file).toPath());
  const digest = MessageDigest.getInstance('MD5').digest(bytes);
  let md5 = '';
  for (let i = 0; i < digest.length; i++) {
    md5 += ((digest[i] & 0xff) + 0x100).toString(16).substring(1);
  }
  images[board.base] = { md5: md5, bytes: bytes, size: bytes.length };
  return images[board.base];
}

rules.JSRule({
  name: 'Indian CAN Bus - Firmware OTA over MQTT',
  description: 'Answers a board\'s ota/req with the image info and the chunks it asks for',
  triggers: Object.keys(BOARDS).map((uid) => triggers.ChannelEventTrigger(uid)),
  execute: (event) => {
    const board = BOARDS[String(event.channelUID)];
    if (!board) {
      return;
    }
    const req = String(event.receivedEvent).trim();

    const mqtt = actions.Things.getActions('mqtt', BROKER_UID);
    if (mqtt === null) {
      console.error('canbus-ota-mqtt: MQTT broker actions not available (' + BROKER_UID + ')');
      return;
    }

    try {
      if (req === 'info') {
        const img = loadImage(board);   // always from disk: this is where a new image is picked up
        mqtt.publishMQTT(board.base + '/ota/info', img.size + ' ' + img.md5, false);
        console.info('canbus-ota-mqtt: ' + board.name + ' asked for the image: ' + img.size + ' bytes, md5 ' + img.md5);
        return;
      }

      const p = req.split(' ');
      const off   = parseInt(p[1], 10);
      const count = Math.min(parseInt(p[2], 10), MAX_WINDOW);
      const len   = Math.min(parseInt(p[3], 10), MAX_CHUNK);
      if (p.length !== 4 || !(off >= 0) || !(count > 0) || !(len > 0)) {
        console.warn('canbus-ota-mqtt: ' + board.name + ' sent a request that cannot be read: "' + req.substring(0, 80) + '"');
        return;
      }

      let img = images[board.base];
      if (!img || img.md5 !== p[0]) {
        img = loadImage(board);         // this script was reloaded, or the file was replaced
      }
      if (img.md5 !== p[0]) {
        mqtt.publishMQTT(board.base + '/ota/info', 'ERR the image on the server changed during the download', false);
        console.warn('canbus-ota-mqtt: ' + board.name + ' asked for md5 ' + p[0] + ', the file is now ' + img.md5);
        return;
      }

      for (let i = 0; i < count; i++) {
        const o = off + i * len;
        if (o >= img.size) {
          break;
        }
        const n = Math.min(len, img.size - o);
        const chunk = ByteBuffer.allocate(4 + n).order(ByteOrder.LITTLE_ENDIAN).putInt(o).put(img.bytes, o, n).array();
        mqtt.publishMQTT(board.base + '/ota/chunk', chunk, false);
      }
      if (off + count * len >= img.size) {
        console.info('canbus-ota-mqtt: ' + board.name + ' has been sent the last window (' + img.size + ' bytes)');
      }
    } catch (e) {
      console.error('canbus-ota-mqtt: ' + board.name + ' request "' + req.substring(0, 80) + '" failed: ' + e);
    }
  }
});
