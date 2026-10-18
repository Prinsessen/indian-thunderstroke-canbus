// =============================================================================
// CAN bus: the PGN inventory of each wake, from the probe's NEW announcements
// =============================================================================
// The firmware's scan probe announces every (PGN, SA) pair the first time it
// sees it after a boot - "NEW pgn=65265 sa=0 dlc=8" on canbus/springfield/probe
// - and then goes quiet about it. Those lines were never bound to an item, so
// on 2026-10-18 the question "was PGN 65253 on yesterday's 480 km?" had no
// answer in openHAB at all. This keeps them: each announcement into
// CanBus_Probe_NewPgn (persisted everyUpdate, so influx holds the inventory of
// every ride), and the sorted list of the current wake into CanBus_Probe_Pgns.
//
// What it cannot see: the firmware only announces with the scan probe ON and
// the bike stationary (speed < 2 km/h), so a PGN that exists only while moving
// is not in the list. Moving the NEW announcement above that gate in the
// firmware is one line and the next step if the inventory turns out useful.
//
// The byte-change lines and the address claims on the same topic pass through
// untouched: CanBus_Probe_Line is not persisted, on purpose - up to twelve a
// second while parked with the scan on.
// =============================================================================
const { rules, triggers, items, cache } = require('openhab');

const LOG = 'canbus-probe-inventory';
const RE_NEW = /^NEW pgn=(\d+) sa=(\d+) dlc=(\d+)/;

function seen() {
  let m = cache.private.get('seen');
  if (!m) { m = {}; cache.private.put('seen', m); }
  return m;
}

function publish(m) {
  const pgns = Object.keys(m).map(Number).sort((a, b) => a - b);
  const text = pgns.length + ' PGNs: ' + pgns.map(p => p + '/' + Object.keys(m[p]).map(Number).sort((a, b) => a - b).join(',')).join(' ');
  items.getItem('CanBus_Probe_Pgns').postUpdate(text);
}

rules.JSRule({
  name: 'CAN bus - PGN inventory from the probe',
  description: 'Keeps every "NEW pgn=" announcement and the PGN list of the current wake',
  triggers: [triggers.ItemStateUpdateTrigger('CanBus_Probe_Line')],
  execute: (event) => {
    const line = String(event.receivedState || items.getItem('CanBus_Probe_Line').state);
    const hit = RE_NEW.exec(line);
    if (!hit) return;
    const pgn = hit[1], sa = hit[2];
    const m = seen();
    if (!m[pgn]) m[pgn] = {};
    const isNew = !m[pgn][sa];
    m[pgn][sa] = true;
    items.getItem('CanBus_Probe_NewPgn').postUpdate(line);
    if (isNew) {
      publish(m);
      console.info(LOG + ': ' + line + ' (' + Object.keys(m).length + ' PGNs this wake)');
    }
  }
});

// A new wake is a new boot: the firmware's own table is empty again and it
// re-announces everything, so the list starts over too. The announcements of
// the previous wake stay in influx under CanBus_Probe_NewPgn.
// On the wake itself (SleepState asleep -> awake), NOT on CanBus_Status going
// online: the MQTT link drops and comes back within a wake (20:11:15 on the
// first garage test, 2026-10-18), and that emptied a list of ten PGNs.
rules.JSRule({
  name: 'CAN bus - PGN inventory starts over on wake',
  description: 'Empties the per-wake PGN list when the board wakes',
  triggers: [triggers.ItemStateChangeTrigger('CanBus_SleepState', 'asleep', 'awake')],
  execute: () => {
    cache.private.put('seen', {});
    items.getItem('CanBus_Probe_Pgns').postUpdate('0 PGNs: (just woke)');
  }
});
