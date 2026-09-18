// =============================================================================
// CAN bus: probe switches flipped while the board sleeps are applied when it
// wakes — added 2026-09-19
// =============================================================================
// The probe switches (CanBus_Probe_Scan/Cruise/Throttle/Claims/Rates) publish a
// NON-retained ON/OFF to canbus/springfield/probe/en/<name>. The board keeps
// the flags in NVS, so whatever was on when the ignition went off is still on
// at the next ride — and a switch flipped from the phone while the board is
// asleep is simply lost. This rule remembers the last command sent for each
// probe while CanBus_Status is not "online" and resends it a few seconds after
// the board reports online. Nothing is resent when the board already agrees.
//
// Memory only: an openHAB restart forgets the queue. Flip the switch again.
// =============================================================================

const { rules, triggers, items, actions, time } = require('openhab');

const LOG = 'canbus-probe-queue';
const PROBES = ['CanBus_Probe_Scan', 'CanBus_Probe_Cruise', 'CanBus_Probe_Throttle',
                'CanBus_Probe_Claims', 'CanBus_Probe_Rates'];
const RESEND_DELAY_S = 5;   // let the board publish its own probe/enabled first

const queued = {};          // item name -> 'ON' | 'OFF'
let timer = null;

function boardOnline() {
  return String(items.getItem('CanBus_Status').state) === 'online';
}

rules.JSRule({
  name: 'CAN bus: remember probe switches flipped while the board is asleep',
  triggers: PROBES.map((n) => triggers.ItemCommandTrigger(n)),
  execute: (event) => {
    const cmd = String(event.receivedCommand);
    if (cmd !== 'ON' && cmd !== 'OFF') return;
    if (boardOnline()) { delete queued[event.itemName]; return; }
    queued[event.itemName] = cmd;
    console.info(LOG + ': board offline, ' + event.itemName + ' ' + cmd + ' queued for the next wake');
  }
});

rules.JSRule({
  name: 'CAN bus: apply queued probe switches when the board comes online',
  triggers: [triggers.ItemStateChangeTrigger('CanBus_Status', undefined, 'online')],
  execute: () => {
    const names = Object.keys(queued);
    if (names.length === 0) return;
    try { if (timer !== null && !timer.hasTerminated()) timer.cancel(); } catch (e) { /* expired */ }
    timer = actions.ScriptExecution.createTimer(time.ZonedDateTime.now().plusSeconds(RESEND_DELAY_S), () => {
      names.forEach((n) => {
        const want = queued[n];
        delete queued[n];
        if (want === undefined) return;
        if (String(items.getItem(n).state) === want) {
          console.info(LOG + ': ' + n + ' already ' + want + ' on the board, nothing sent');
          return;
        }
        items.getItem(n).sendCommand(want);
        console.info(LOG + ': ' + n + ' -> ' + want + ' sent now that the board is online');
      });
    });
  }
});
