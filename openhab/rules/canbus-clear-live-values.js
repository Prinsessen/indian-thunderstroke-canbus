const { rules, triggers, items, time } = require('openhab');

/**
 * What survives the machine being switched off, and what does not.
 *
 * REVISED 2026-09-07, after the first version cleared far too much.
 *
 * The board takes permanent 12 V and now sleeps five minutes after the bus goes
 * quiet, so it outlives the machine's own modules and watches the bus taper off
 * over about forty seconds. What it is left holding is therefore a real
 * description of a parked motorcycle, not an arbitrary cut mid-ride. Blanking
 * that throws away true information.
 *
 * So three categories, and only the third is cleared here:
 *
 *  1. STILL TRUE, and nothing here touches them. Throttle and gear keep their
 *     last reading, because a throttle plate really does rest at its idle stop
 *     around 6 % and the gearbox really is in whatever gear it was left in.
 *
 *     Engine speed, road speed and fuel rate are handled in the FIRMWARE from
 *     2026.09.07-9: clearLiveValues() sets them to 0 rather than leaving the
 *     last frame's value in place. Keeping it did not work -- switched off from
 *     idle, the ECU stops transmitting before the engine has wound down, and the
 *     reading sat at 1180 rpm on a parked motorcycle. Those zeros are DERIVED
 *     from the bus being silent, the same inference already behind
 *     CanBus_Ignition, and both the firmware and this file say so out loud.
 *
 *  2. TRUE, BUT NEEDS ITS AGE. The kill switch, the sidestand and the tyres.
 *     Worth keeping: walking up to a machine that will not start, STOP is
 *     exactly what you want to see, and the pressures are what you pump
 *     against. But their messages are not periodic. PGN 65381 SA 0 carries both
 *     interlocks and is event-driven -- 13 frames in a 22-minute ride, 7 in an
 *     18-minute one, falling silent three and seventeen minutes respectively
 *     before the bus did. TPMS sensors sleep with the wheels. So these get a
 *     timestamp beside them rather than a bare value, written once as the
 *     machine goes off.
 *
 *  3. WHAT A RIDER DID, NOT WHAT THE MACHINE IS. A brake that was pressed, an
 *     indicator that was on, a start button that was held. The last one says
 *     nothing about a machine nobody is sitting on. Cleared.
 *
 * WHY ANY OF THIS IS HERE RATHER THAN IN THE FIRMWARE
 * ---------------------------------------------------
 * Category 3 IS in the firmware -- clearLiveValues() sets those fields to NAN,
 * and over BLE that becomes the sentinels the fast packet defines, so the phone
 * app draws dashes. MQTT has no such route, and this was measured rather than
 * assumed: an omitted field cannot clear a cached value; an explicit JSON null
 * is discarded by the binding after JSONPATH returns null for it (openHAB's own
 * JSonPathTransformationServiceTest.testNullValue asserts this); and the string
 * "UNDEF" is rejected by TypeParser with a WARN per message. So the same
 * decision has to be re-expressed on this side.
 *
 * KEEP THE CLEARED LIST IN STEP WITH clearLiveValues() IN main.cpp.
 */
const MOMENTARY = [
  'CanBus_BrakeRear', 'CanBus_Clutch',
  'CanBus_IndLeft', 'CanBus_IndRight', 'CanBus_Hazard',
  'CanBus_Cruise', 'CanBus_CruiseEnable', 'CanBus_CruiseSwitch',
  'CanBus_StartButton',
  // Litres per 100 km at zero speed is undefined, not zero.
  'CanBus_FuelEconInst'
];

/** "sidst set 15:27" from an age in seconds, or null if the age is unknown. */
function seenAt(ageItemName) {
  try {
    const age = items.getItem(ageItemName).numericState;
    if (age === null || age === undefined || age < 0) return null;
    return 'sidst set ' + time.ZonedDateTime.now().minusSeconds(Math.round(age))
                              .format(time.DateTimeFormatter.ofPattern('HH:mm'));
  } catch (e) {
    console.warn(`canbus-clear-live-values: ${ageItemName}: ${e.message}`);
    return null;
  }
}

function settle(why) {
  let cleared = 0;
  for (const name of MOMENTARY) {
    try {
      const item = items.getItem(name);
      if (item.state !== 'UNDEF') { item.postUpdate('UNDEF'); cleared++; }
    } catch (e) {
      console.warn(`canbus-clear-live-values: ${name}: ${e.message}`);
    }
  }
  // Stamp the two that are kept, while the ages are still the ones the ESP32
  // reported. Once the board sleeps, nothing further arrives to correct them.
  const stamps = [['CanBus_Interlock_Age', 'CanBus_Interlocks_Seen'],
                  ['CanBus_Tyre_Age',      'CanBus_Tyres_Seen']];
  for (const [ageItem, seenItem] of stamps) {
    const s = seenAt(ageItem);
    if (s !== null) { try { items.getItem(seenItem).postUpdate(s); } catch (e) { /* item absent */ } }
  }
  console.info(`canbus-clear-live-values: cleared ${cleared} momentary readings (${why})`);
}

rules.JSRule({
  name: 'CAN bus: settle readings when the machine goes off',
  description: 'Clears the momentary rider inputs and timestamps the interlock '
             + 'and tyre readings that are kept.',
  triggers: [
    // Bus activity IS the ignition, so this fires a few seconds after the key
    // goes off -- while the board is still awake and the ages are still live.
    triggers.ItemStateChangeTrigger('CanBus_Ignition', 'ON', 'OFF'),
    // The board itself has gone: asleep, out of range, or crashed.
    triggers.ItemStateChangeTrigger('CanBus_Status', 'online', 'offline')
  ],
  execute: (event) => settle(event.itemName)
});
