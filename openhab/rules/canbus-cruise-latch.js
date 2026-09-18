// =============================================================================
// Indian CAN Bus — Cruise switch latch
// =============================================================================
// SPN 599 (SET) and SPN 601 (RESUME) are momentary: the bits are only set while
// the rocker is actually held. The state JSON is throttled to one publish per
// second, so a quick tap can fall between publishes and never reach the sitemap
// at all. This holds the last press visible for a few seconds.
//
// Replaces canbus-horn-latch.js and canbus-brake-latch.js, both removed
// 2026-09-05. The horn was proven not to exist on this bus, and the front brake
// signal they were written for had had its decode withdrawn -- but the problem
// those rules solved is real and now belongs to the cruise switch instead, so
// the pattern is kept rather than thrown away with them.
//
// Items (items/canbus.items):
//   String CanBus_CruiseSwitch      <- raw: "SET/DEC", "RES/ACC" or "none" (MQTT)
//   String CanBus_CruiseSw_Latched  <- virtual, held by this rule
// =============================================================================

const { rules, triggers, items } = require('openhab');

const LATCH_MS = 5000;      // how long the last press stays visible
let releaseTimer = null;    // module scope persists across rule runs

rules.JSRule({
  name: 'Indian CAN Bus - Cruise switch latch',
  description: 'Holds the last SET/RESUME press visible for a few seconds',
  triggers: [ triggers.ItemStateChangeTrigger('CanBus_CruiseSwitch') ],
  execute: (event) => {
    const press = String(event.newState);

    // "none" is the resting value between presses, not an event. Let the timer
    // clear the latch instead, so the label does not blink out the instant the
    // rocker is released -- which is the whole reason this rule exists.
    if (press !== 'SET/DEC' && press !== 'RES/ACC') return;

    if (items.CanBus_CruiseSw_Latched.state !== press) {
      items.CanBus_CruiseSw_Latched.postUpdate(press);
    }

    if (releaseTimer !== null) {
      clearTimeout(releaseTimer);
      releaseTimer = null;
    }
    releaseTimer = setTimeout(() => {
      items.CanBus_CruiseSw_Latched.postUpdate('none');
      releaseTimer = null;
    }, LATCH_MS);
  }
});
