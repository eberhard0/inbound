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
4. **ETA = free-flow time ahead × (running pace + stop overhead).**
   - *Running pace* is measured live: GPS speed against the mapped line speed while cruising. It follows the
     stretch of line the train is on, and can be below 1 where the map's speeds are too low.
   - *Stop overhead* starts at the train type's typical share and is pulled slowly towards the time this ride
     has really lost to stops, so one cluster of stations can't swing the whole estimate. Waiting on the
     platform doesn't count – the clock starts with the first movement.
   Only the train you're on learns; the other rows are "what if" figures. If the GPS speed is clearly beyond
   what the chosen type can do, the app switches to the faster type by itself.

### Train types, side by side

The card shows an estimate for every kind of train that could be running on the route, all at once; tap the
one you're on and it becomes the big figure (and the track the app follows).

| Type | Runs at most | Typical stop overhead | Track |
|---|---|---|---|
| High-speed | 320 km/h | 8 % | quickest track – only shown where >10 % of it is faster than 200 km/h |
| Express | 160 km/h | 18 % | the line that keeps off high-speed track, if there is a sensible one |
| Local | 120 km/h | 40 % | same as Express |

So nothing depends on the country: whether "High-speed" appears is read off the actual track.

Because that only needs two positions, GPS runs **only while the app is on screen**. Close it, reopen it
half an hour later, and one fix is enough for a fresh estimate. The countdown keeps ticking through tunnels.

If the train turns out to be on a different line (more than 750 m off for 20 s) the track is planned again from
where you are, keeping what the ride has taught so far. The journey survives the app being killed.

## Privacy

No accounts, no analytics. The search text goes to OpenStreetMap's Nominatim; your position and the
destination go to the rail router once per journey (and once per re-plan). Nothing else leaves the phone.

## Building

GitHub Actions builds every push; a `v*` tag produces a signed release (`KEYSTORE_*` / `KEY_*` secrets).
