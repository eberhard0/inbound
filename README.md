# Inbound

How long until I get there? A GPS trip display for train rides: Speedo's speedometer plus a destination,
with the time to go measured **along the actual railway line**, not as the crow flies.

## How the estimate works

1. You search for a destination (stations are listed first) once you're at the station or on the train.
2. The app looks up the track from your position to the destination – once, online – including the
   router's free-flow running time for every stretch of line (OpenRailRouting; BRouter's rail profile and
   finally a padded straight line are the fallbacks).
3. From then on everything is on the phone: each GPS fix is snapped onto the track, which gives the exact
   distance and free-flow time still ahead.
4. **ETA = free-flow time ahead × slowdown.** The slowdown starts at 1.3 (stops, not running at line speed)
   and is pulled towards what this ride really shows: seconds spent per free-flow second covered. Waiting on
   the platform doesn't count – the clock starts with the first movement.

Because that only needs two positions, GPS runs **only while the app is on screen**. Close it, reopen it
half an hour later, and one fix is enough for a fresh estimate. The countdown keeps ticking through tunnels.

If the train turns out to be on a different line (more than 750 m off for 20 s) the track is planned again from
where you are, keeping what the ride has taught so far. The journey survives the app being killed.

## Privacy

No accounts, no analytics. The search text goes to OpenStreetMap's Nominatim; your position and the
destination go to the rail router once per journey (and once per re-plan). Nothing else leaves the phone.

## Building

GitHub Actions builds every push; a `v*` tag produces a signed release (`KEYSTORE_*` / `KEY_*` secrets).
