// =============================================================================
// Example openHAB rule (JS Scripting / GraalJS): a double press on the RIGHT
// handlebar button toggles the garage door, but only when the bike is home.
// =============================================================================
// Sanitized copy of the rule that runs on the author's installation. Replace the three
// UPPER_CASE placeholders with your own items:
//
//   CanBus_Button        String item on canbus/<base>/button (see canbus.items).
//                        Values: left|right|both + short|long|double.
//   TRACKER_GeofenceId   Number item with the id of the geofence the bike is in
//                        (Traccar binding here; any "am I home" item will do).
//   GARAGE_DOOR_PULSE    The item that gives the door opener a 1-second pulse
//                        (Paxton Net2 controlTimed here; a relay Switch with
//                        expire="1s" works the same way).
//   DOOR_BOTTOM / DOOR_TOP  Contact items on the door's limit switches, only
//                        used to say "opening" or "closing" in the result.
//   CanBus_Button_Result String item with no channel: what the last press did,
//                        in words, for the sitemap ("Garage door opening",
//                        "Garage door left alone — bike is not at home", ...).
//
// The door is a toggle: one pulse opens, the next pulse closes, so the rule
// does not care which way it goes. It refuses to fire while the bike is away
// (a stray double press at a traffic light must never open the house) and it
// ignores a second double press inside LOCKOUT_MS so a nervous thumb cannot
// stop the door half way.
//
// What the firmware sends: one message per event, not retained. A press
// shorter than 800 ms is "short", longer is "long", two shorts with less than
// 600 ms between release and the next press are one "double". "both" means the
// two buttons were held together.
// =============================================================================

const { rules, triggers, items } = require('openhab');

const LOG = 'motorcycle-button-garage';
const HOME_GEOFENCE_ID = 1;   // the geofence around your garage
const LOCKOUT_MS = 8000;      // ignore a second double press this soon after one

let lastPulse = 0;

function doorState() {
  // Adjust to your switches. Here: CLOSED = bottom OPEN + top CLOSED.
  const bottom = String(items.getItem('DOOR_BOTTOM').state);
  const top = String(items.getItem('DOOR_TOP').state);
  if (bottom === 'OPEN' && top === 'CLOSED') return 'closed';
  if (bottom === 'CLOSED' && top === 'OPEN') return 'open';
  return 'moving';
}

function report(text) {
  items.getItem('CanBus_Button_Result').postUpdate(text);
  console.info(LOG + ': ' + text);
}

rules.JSRule({
  name: 'Motorcycle: double press on the right button toggles the garage door',
  triggers: [triggers.ItemStateUpdateTrigger('CanBus_Button')],
  execute: () => {
    const ev = String(items.getItem('CanBus_Button').state);
    if (ev !== 'right double') {
      report(ev + ' — no action assigned');
      return;
    }

    const fence = items.getItem('TRACKER_GeofenceId').numericState;
    if (fence !== HOME_GEOFENCE_ID) {
      report('Garage door left alone — bike is not at home');
      return;
    }

    const now = Date.now();
    if (now - lastPulse < LOCKOUT_MS) {
      report('Ignored — pressed again within ' + (LOCKOUT_MS / 1000) + ' s');
      return;
    }
    lastPulse = now;

    const door = doorState();
    items.getItem('GARAGE_DOOR_PULSE').sendCommand(1);
    if (door === 'closed')    report('Garage door opening');
    else if (door === 'open') report('Garage door closing');
    else                      report('Garage door pulsed — it was moving');
  }
});

// A companion rule there makes the garage ceiling light follow the
// door's two limit switches (ON at fully open, OFF at fully closed), so the
// light behaves the same whether the door was opened by GPS, by this button
// or by the remote. That rule has nothing CAN-specific in it and is not
// reproduced here.
