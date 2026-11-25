# Eufy Doorbell Intercom (Kiosk Satellite plugin)

A fork of patrick11994's Reolink Doorbell Intercom plugin (Apache-2.0) that rings a Kiosk Satellite panel when a Eufy
doorbell is pressed and opens the call screen with a Eufy camera's live picture and sound.

It talks to the **eufy-security-ws** bridge (the Home Assistant add-on, port 3002 by default), not to the doorbell:

- **Ring:** the bridge's `rings` event for the *Doorbell serial number* you enter.
- **Picture and sound:** `device.start_livestream` for the *Camera serial number* you enter (H.264 and AAC through
  the bridge). It can be the doorbell itself or another camera shared with the Eufy account the bridge signs in with.
- **Not available yet:** talking to the door. The call screen says so.

## Settings
Bridge address, Bridge port, Doorbell serial number, Camera serial number, and the upstream chime, screen, music,
call button and card settings. The **Check** button connects to the bridge and waits for a picture.

## Known limits
- A Video Doorbell's own livestream can fail after the first one until the bridge add-on restarts (seen on one Video Doorbell model);
  a floodlight camera streamed reliably. Pick the camera serial accordingly.
- The bridge only knows devices shared with its Eufy account, and `driver.poll_refresh` (or a restart) loads new ones.
- The picture is whatever resolution the camera's streaming quality is set to (640x360 at level 1 on one floodlight camera).

## Building (Windows)
JDK 17 and an Android SDK with `platforms;android-34` and `build-tools;34.0.0`; set `JAVA_HOME` and `ANDROID_HOME`, then
`python tools/build.py`. The ZIP lands in `dist/`. Install it with Plugin Manager > Developer Tools > Install from ZIP.
