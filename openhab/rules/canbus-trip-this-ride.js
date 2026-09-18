// =============================================================================
// Indian CAN Bus — Trip (this ride)
// =============================================================================
// The bus has NO dedicated "current trip" register. Odometer (total) and
// Trip 1 (the big resettable meter, ~4527 km) both live in PGN 65217, but the
// distance of THE RIDE YOU ARE ON RIGHT NOW is not broadcast — the dash
// computes it. So we compute it too:
//
//     Trip (this ride) = Trip 1 now  −  Trip 1 at engine start
//
// A ride starts when the engine starts (RPM goes 0/NULL -> >0). At that moment
// we snapshot Trip 1 into CanBus_TripStart. From then on CanBus_TripThis shows
// how far this ride has covered. CanBus_TripStart is a normal item so the
// inmemory persistence (everyChange, restoreOnStartup) survives an openHAB
// restart mid-ride.
//
// Items (items/canbus.items):
//   Number CanBus_Trip       <- Trip 1, MQTT bound (source of truth)
//   Number CanBus_RPM        <- engine RPM, used to detect a ride start
//   Number CanBus_TripStart  <- virtual snapshot of Trip 1 at engine start
//   Number CanBus_TripThis   <- virtual, distance of the current ride (km)
//
// Note: if the engine stalls and is restarted mid-ride the counter restarts
// (that is the "since engine start" definition). Change the trigger to an
// ignition item later if a key-on definition is preferred.
// =============================================================================

const { rules, triggers, items } = require('openhab');

function num(name) {
    const v = parseFloat(items[name].state);
    return isNaN(v) ? null : v;
}

rules.JSRule({
    name: 'Indian CAN Bus - Trip (this ride)',
    description: 'Distance since engine start, derived from Trip 1 (PGN 65217).',
    triggers: [
        triggers.ItemStateChangeTrigger('CanBus_RPM'),
        triggers.ItemStateChangeTrigger('CanBus_Trip')
    ],
    execute: (event) => {
        const trip = num('CanBus_Trip');
        if (trip === null) return;
        const start = num('CanBus_TripStart');

        // Engine start: RPM went from 0/NULL to >0 -> begin a new ride.
        if (event.itemName === 'CanBus_RPM') {
            const oldRpm = parseFloat(event.oldState);
            const wasStopped = isNaN(oldRpm) || oldRpm === 0;   // NULL/UNDEF/0
            const rpmNow = num('CanBus_RPM') || 0;
            if (rpmNow > 0 && wasStopped) {
                items.CanBus_TripStart.postUpdate(trip.toFixed(1));
                items.CanBus_TripThis.postUpdate('0.0');
                return;
            }
        }

        // First run, or Trip 1 was reset below the snapshot -> re-anchor.
        if (start === null || trip < start) {
            items.CanBus_TripStart.postUpdate(trip.toFixed(1));
            items.CanBus_TripThis.postUpdate('0.0');
            return;
        }

        items.CanBus_TripThis.postUpdate((trip - start).toFixed(1));
    }
});
