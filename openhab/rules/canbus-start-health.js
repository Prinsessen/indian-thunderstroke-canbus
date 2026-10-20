// =============================================================================
// CAN bus: how hard the Springfield starts - crank time, and whether the board
// browned out doing it
// =============================================================================
// The ECM is silent while the starter turns, so the bus cannot show the voltage
// dip itself. What it does show is the engine turning: the first rpm after a
// quiet spell is the starter, and the first rpm at or above FIRED_RPM is the
// engine running. The seconds between are how hard the start was.
//
// Found 2026-10-19 (head 4 C, ambient 7 C): 27 starts in a month fired in
// 0.2-1.1 s - and that one showed rpm 208 at 11:07:50.5, then NOTHING for 17.5 s,
// then 1237 rpm with "[mqtt] connected" and the message counter starting over.
// The board had rebooted: the cranking sag on a cold Thunder Stroke 111 pulled
// the 12 V below what the board's regulator rides out. The ABS module logged
// undervoltage in the same second. So a reboot during a start is itself the
// measurement: a dip deep enough to kill a buck converter. Counted, dated and
// kept, so a weakening battery shows up as a trend before a frosty morning does.
//
// Only reports. Items: CanBus_Start_* (items/canbus.items), frame "Start health"
// on the SpringCommand page.
// =============================================================================
const { rules, triggers, items, cache, time } = require('openhab');

const LOG = 'canbus-start-health';
const FIRED_RPM = 900;          // idle is 1100-1200; cranking is 200-250
const QUIET_MS = 60000;         // rpm at 0 this long before = a real start, not a stall
const GIVE_UP_MS = 40000;       // no fire within this = the start failed
const RESET_GAP_MS = 5000;      // rpm samples come every 0.2-0.5 s while cranking; a hole this long is the board gone
const HISTORY = 6;

function num(name) {
  try { const v = parseFloat(String(items.getItem(name).state)); return isNaN(v) ? null : v; } catch (e) { return null; }
}
function post(name, value) { try { items.getItem(name).postUpdate(value); } catch (e) { console.warn(LOG + ': ' + name + ': ' + e.message); } }

function st() {
  let s = cache.private.get('st');
  if (!s) {
    s = { lastRpm: 0, lastRpmAt: 0, lastRunAt: 0, crank: null, history: [] };
    // the list survives a reload through the item (restoreOnStartup), not the cache
    try { const h = String(items.getItem('CanBus_Start_History').state); if (h && h !== 'NULL' && h !== 'UNDEF') s.history = h.split(' | ').slice(0, HISTORY); } catch (e) { /* first run */ }
    cache.private.put('st', s);
  }
  return s;
}

function record(s, now, fired, reset, gapS) {
  const crank = s.crank; s.crank = null;
  const secs = (now - crank.t0) / 1000;
  const when = time.ZonedDateTime.now();
  let result;
  if (!fired) result = 'did not fire within ' + Math.round(GIVE_UP_MS / 1000) + ' s';
  else if (reset) result = 'BOARD RESET during the crank - a deep voltage dip; the engine was running ' + secs.toFixed(1) + ' s after the first turn';
  else result = 'fired after ' + secs.toFixed(1) + ' s';
  post('CanBus_Start_Last', when.toString());
  post('CanBus_Start_CrankSeconds', secs.toFixed(1));
  post('CanBus_Start_Result', result);
  if (crank.ambient !== null) post('CanBus_Start_Ambient', crank.ambient);
  if (crank.head !== null) post('CanBus_Start_HeadTemp', crank.head);
  if (reset) post('CanBus_Start_Resets', (num('CanBus_Start_Resets') || 0) + 1);
  const line = when.format(time.DateTimeFormatter.ofPattern('dd/MM HH:mm')) + ' '
    + (reset ? 'RESET' : (fired ? secs.toFixed(1) + ' s' : 'no fire'))
    + (crank.ambient !== null ? ' ' + Math.round(crank.ambient) + '°C' : '');
  s.history.unshift(line); s.history = s.history.slice(0, HISTORY);
  post('CanBus_Start_History', s.history.join(' | '));
  console.info(LOG + ': ' + result + (crank.ambient !== null ? ' (ambient ' + crank.ambient + ' C, head ' + crank.head + ' C)' : '') + (gapS ? ' - ' + gapS.toFixed(1) + ' s hole in the rpm samples' : ''));
}

rules.JSRule({
  name: 'CAN bus - start health',
  description: 'Crank-to-fire time for every start, and whether the board reset during it',
  triggers: [triggers.ItemStateUpdateTrigger('CanBus_RPM'), triggers.ItemStateUpdateTrigger('CanBus_Debug')],
  execute: (event) => {
    const s = st(); const now = Date.now();
    if (event.itemName === 'CanBus_Debug') {
      if (s.crank && String(event.receivedState || '').indexOf('[mqtt] connected') >= 0) s.crank.reconnected = true;
      return;
    }
    const v = parseFloat(String(event.receivedState));
    if (isNaN(v)) return;                        // NULL/UNDEF: the board is away
    if (!s.crank && v > 0 && s.lastRpm <= 0 && now - s.lastRunAt > QUIET_MS) {
      s.crank = { t0: now, ambient: num('CanBus_Ambient'), head: num('CanBus_Coolant'), cleanMsgs: num('CanBus_Bus_CleanMsgs'), reconnected: false };
      console.info(LOG + ': starter turning (' + Math.round(v) + ' rpm)');
    } else if (s.crank) {
      const gap = (now - s.lastRpmAt) / 1000;
      if (v >= FIRED_RPM) {
        const clean = num('CanBus_Bus_CleanMsgs');
        const reset = s.crank.reconnected || gap * 1000 > RESET_GAP_MS || (clean !== null && s.crank.cleanMsgs !== null && clean < s.crank.cleanMsgs);
        record(s, now, true, reset, gap * 1000 > RESET_GAP_MS ? gap : 0);
      } else if (now - s.crank.t0 > GIVE_UP_MS) {
        record(s, now, false, false, 0);
      }
    }
    s.lastRpm = v; s.lastRpmAt = now; if (v > 0) s.lastRunAt = now;
  }
});
