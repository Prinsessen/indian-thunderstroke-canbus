// =============================================================================
// CAN bus: how hard the Springfield starts - crank time, and whether the board
// browned out doing it
// =============================================================================
// The ECM is silent while the starter turns, so the bus cannot show the voltage
// dip itself. What it does show is the engine turning: the first rpm after the
// engine has stood still is the starter, and the first rpm at or above FIRED_RPM
// is the engine running. The seconds between are how hard the start was.
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
// Fixed 2026-11-04 after the ride of 2026-11-03 (tests/canbus-start-health.test.js):
//  - FIRED_RPM 900 -> 600. 900 was set from the cold idle (1100-1200); a warm
//    Thunder Stroke idles at 703-870, so a warm engine could "never fire".
//    Cranking is 200-390 rpm in every capture, well under 600. Crank times are
//    shorter from here on: the cold start of 11-03 15:17 was 1.2 s at 900 and is
//    0.8 s at 600 (the replay of its capture) - compare a start before 2026-11-04
//    with one after only with that in mind.
//  - "Stood still" is rpm 0 for STOPPED_MS, not 60 s since the last rpm: the
//    restart at 15:20:03, 12 s after a stall, was not recorded at all. No lone
//    zero sample in 32 811 rpm samples of that ride, so 2 s is no hair trigger.
//  - An engine already running at the first sample after a stop means the start
//    happened while the board was not listening. At 15:41:49 the board came back
//    from a reset with the engine idling at 788 and was logged "did not fire". The
//    board knows why it booted: `reset` in meta (main.cpp resetReasonName). That
//    arrives at uptime 30 s, after the first rpm, so the rule waits for the next
//    meta: any reason but deepsleep (a normal wake) and sw (OTA) is a reset during
//    the start, counted in CanBus_Start_Resets. No crank time - it was not seen.
//
// Only reports. Items: CanBus_Start_* (items/canbus.items), frame "Start health"
// on the SpringCommand page.
// =============================================================================
const { rules, triggers, items, cache, time } = require('openhab');

const LOG = 'canbus-start-health';
const FIRED_RPM = 600;          // cranking 200-390; warm idle 703-870, cold idle 1100-1200 (2026-11-03)
const STOPPED_MS = 2000;        // rpm 0 this long = the engine stood still, so the next turn is a start
const GIVE_UP_MS = 40000;       // no fire within this = the start failed
const RESET_GAP_MS = 5000;      // rpm samples come every 0.2-0.5 s while cranking; a hole this long is the board gone
const META_WAIT_MS = 45000;     // meta comes every 30 s, the first after a boot at uptime 30
const NORMAL_BOOTS = ['deepsleep', 'sw'];   // a wake from sleep, an OTA restart; anything else is a reset
const HISTORY = 6;

function num(name) {
  try { const v = parseFloat(String(items.getItem(name).state)); return isNaN(v) ? null : v; } catch (e) { return null; }
}
function str(name) { try { return String(items.getItem(name).state); } catch (e) { return ''; } }
function post(name, value) { try { items.getItem(name).postUpdate(value); } catch (e) { console.warn(LOG + ': ' + name + ': ' + e.message); } }
function metaOf(s) { try { const m = JSON.parse(String(s)); return m && typeof m === 'object' ? m : null; } catch (e) { return null; } }

function st() {
  let s = cache.private.get('st');
  if (!s) {
    // zeroSince null: after a reload the engine has not been SEEN standing, so an
    // engine already running is not taken for a start (code review 2026-10-23)
    s = { zeroSince: null, lastRpmAt: 0, crank: null, unseen: null, history: [] };
    // the list survives a reload through the item (restoreOnStartup), not the cache
    try { const h = String(items.getItem('CanBus_Start_History').state); if (h && h !== 'NULL' && h !== 'UNDEF') s.history = h.split(' | ').slice(0, HISTORY); } catch (e) { /* first run */ }
    cache.private.put('st', s);
  }
  return s;
}

function startAt(now) {
  return { t0: now, ambient: num('CanBus_Ambient'), head: num('CanBus_Coolant'), cleanMsgs: num('CanBus_Bus_CleanMsgs'),
    reconnected: false, metaAtStart: str('CanBus_MetaRaw') };
}

/** kind: 'fired' | 'nofire' | 'reset' (seen) | 'reset-unseen' | 'unseen'. */
function record(s, start, now, kind, detail) {
  const secs = (now - start.t0) / 1000;
  const when = time.ZonedDateTime.now();
  const seen = kind === 'fired' || kind === 'nofire' || kind === 'reset';
  const reset = kind === 'reset' || kind === 'reset-unseen';
  let result;
  if (kind === 'nofire') result = 'did not fire within ' + Math.round(GIVE_UP_MS / 1000) + ' s';
  else if (kind === 'reset') result = 'BOARD RESET during the crank - a deep voltage dip; the engine was running ' + secs.toFixed(1) + ' s after the first turn';
  else if (kind === 'reset-unseen') result = 'BOARD RESET during the start (reset: ' + detail + ') - the board came back with the engine already running, the crank was not seen';
  else if (kind === 'unseen') result = 'engine already running when the board came back (' + detail + ') - the crank was not seen';
  else result = 'fired after ' + secs.toFixed(1) + ' s';
  post('CanBus_Start_Last', when.toString());
  if (seen) post('CanBus_Start_CrankSeconds', secs.toFixed(1));
  post('CanBus_Start_Result', result);
  if (start.ambient !== null) post('CanBus_Start_Ambient', start.ambient);
  if (start.head !== null) post('CanBus_Start_HeadTemp', start.head);
  if (reset) post('CanBus_Start_Resets', (num('CanBus_Start_Resets') || 0) + 1);
  const line = when.format(time.DateTimeFormatter.ofPattern('dd/MM HH:mm')) + ' '
    + (reset ? 'RESET' : kind === 'unseen' ? 'unseen' : (kind === 'fired' ? secs.toFixed(1) + ' s' : 'no fire'))
    + (start.ambient !== null ? ' ' + Math.round(start.ambient) + '°C' : '');
  s.history.unshift(line); s.history = s.history.slice(0, HISTORY);
  post('CanBus_Start_History', s.history.join(' | '));
  console.info(LOG + ': ' + result + (start.ambient !== null ? ' (ambient ' + start.ambient + ' C, head ' + start.head + ' C)' : '')
    + (kind === 'reset' && detail ? ' - ' + detail.toFixed(1) + ' s hole in the rpm samples' : ''));
}

/** The meta after a start the board did not see: did it boot, and why. null = none came in time. */
function settleUnseen(s, now, metaState) {
  const u = s.unseen;
  if (metaState !== null) {
    if (String(metaState) === u.metaAtStart) return;          // the retained one from before the wake
    const m = metaOf(metaState);
    if (!m || typeof m.reset !== 'string') return;
    s.unseen = null;
    // booted no longer ago than the wait plus the wake itself: this boot, not an old one
    const fresh = typeof m.uptime === 'number' && m.uptime * 1000 <= (now - u.t0) + META_WAIT_MS;
    if (fresh && NORMAL_BOOTS.indexOf(m.reset) < 0) record(s, u, now, 'reset-unseen', m.reset);
    else record(s, u, now, 'unseen', 'reset: ' + m.reset);
    return;
  }
  s.unseen = null;
  record(s, u, now, 'unseen', 'no meta within ' + Math.round(META_WAIT_MS / 1000) + ' s');
}

rules.JSRule({
  name: 'CAN bus - start health',
  description: 'Crank-to-fire time for every start, and whether the board reset during it',
  triggers: [triggers.ItemStateUpdateTrigger('CanBus_RPM'), triggers.ItemStateUpdateTrigger('CanBus_Debug'),
    triggers.ItemStateUpdateTrigger('CanBus_MetaRaw')],
  execute: (event) => {
    const s = st(); const now = Date.now();
    if (event.itemName === 'CanBus_Debug') {
      if (s.crank && String(event.receivedState || '').indexOf('[mqtt] connected') >= 0) s.crank.reconnected = true;
      return;
    }
    if (event.itemName === 'CanBus_MetaRaw') {
      if (s.unseen) settleUnseen(s, now, event.receivedState);
      return;
    }
    const v = parseFloat(String(event.receivedState));
    if (isNaN(v)) return;                        // NULL/UNDEF: the board is away
    if (s.unseen && now - s.unseen.t0 > META_WAIT_MS) settleUnseen(s, now, null);
    const stopped = s.zeroSince !== null && now - s.zeroSince >= STOPPED_MS;
    if (!s.crank && !s.unseen && stopped && v > 0) {
      if (v < FIRED_RPM) {
        s.crank = startAt(now);
        console.info(LOG + ': starter turning (' + Math.round(v) + ' rpm)');
      } else {
        s.unseen = startAt(now);
        console.info(LOG + ': engine already at ' + Math.round(v) + ' rpm at the first sample after a stop - waiting for meta to say whether the board reset');
      }
    } else if (s.crank) {
      const gap = (now - s.lastRpmAt) / 1000;
      if (v >= FIRED_RPM) {
        const clean = num('CanBus_Bus_CleanMsgs');
        const crank = s.crank; s.crank = null;
        const reset = crank.reconnected || gap * 1000 > RESET_GAP_MS || (clean !== null && crank.cleanMsgs !== null && clean < crank.cleanMsgs);
        record(s, crank, now, reset ? 'reset' : 'fired', gap * 1000 > RESET_GAP_MS ? gap : 0);
      } else if (now - s.crank.t0 > GIVE_UP_MS) {
        const crank = s.crank; s.crank = null;
        record(s, crank, now, 'nofire', 0);
      }
    }
    s.zeroSince = v === 0 ? (s.zeroSince === null ? now : s.zeroSince) : null;
    s.lastRpmAt = now;
  }
});
