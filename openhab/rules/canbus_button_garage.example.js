// =============================================================================
// Example openHAB rule (JS Scripting / GraalJS): a double press on the RIGHT
// handlebar button toggles the garage door when the bike is home. Away from
// home it may only CLOSE an open door, never open one.
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
//                        "Garage door stays closed — bike is not at home", ...).
//
// The door is a toggle: one pulse opens, the next pulse closes, so the rule
// does not care which way it goes at home. Away from home it pulses only when
// the limit switches say the door is fully open, so a stray double press at a
// traffic light can never open the house, while a habit-tap after leaving can
// still close a door the geofence rule missed. A second double press inside
// LOCKOUT_MS is ignored so a nervous thumb cannot stop the door half way.
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

// What the other gestures mean, for the result item. The heat ones are done
// by the phone app over BLE; openHAB only reports them.
const MEANING = {
  'left double': 'Heated clothing one step warmer — the app does it over BLE',
  'both short':  'Heated clothing one step colder — the app does it over BLE',
  'both long':   'Heated clothing back to automatic — the app does it over BLE',
  'left short':  'Cluster page — nothing for the house',
  'right short': 'Cluster page — nothing for the house',
  'left long':   'Trip meter reset on the cluster — nothing for the house'
};

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
      report(MEANING[ev] || (ev + ' — no action assigned'));
      return;
    }

    const home = items.getItem('TRACKER_GeofenceId').numericState === HOME_GEOFENCE_ID;
    const door = doorState();

    if (!home && door !== 'open') {
      report(door === 'closed'
        ? 'Garage door stays closed — bike is not at home'
        : 'Garage door left alone — it is moving and the bike is not at home');
      return;
    }

    const now = Date.now();
    if (now - lastPulse < LOCKOUT_MS) {
      report('Ignored — pressed again within ' + (LOCKOUT_MS / 1000) + ' s');
      return;
    }
    lastPulse = now;

    items.getItem('GARAGE_DOOR_PULSE').sendCommand(1);
    if (door === 'open')        report(home ? 'Garage door closing' : 'Garage door closing — bike is away');
    else if (door === 'closed') report('Garage door opening');
    else                        report('Garage door pulsed — it was moving');
  }
});

// A companion rule there makes the garage ceiling light follow the
// door's two limit switches (ON at fully open, OFF at fully closed), so the
// light behaves the same whether the door was opened by GPS, by this button
// or by the remote. That rule has nothing CAN-specific in it and is not
// reproduced here.
