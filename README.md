# Eufy Doorbell Intercom (Kiosk Satellite plugin)

Rings a Kiosk Satellite panel when a Eufy doorbell is pressed and opens a call screen with a Eufy camera's live
picture and sound, plus the doorbell's own snapshot of the visitor underneath. It is a fork of patrick11994's
Reolink Doorbell Intercom plugin (Apache-2.0) with the Reolink code replaced by a client for the
**eufy-security-ws** bridge.

Not affiliated with or endorsed by Eufy or Anker, or by the Kiosk Satellite project.

## What you need
- The **eufy-security-ws** bridge, for example the Home Assistant add-on (port 3002 by default), signed in to a Eufy
  account that has the doorbell and the camera shared with it.
- Kiosk Satellite with plugins enabled.

## How it works
- **Ring:** the bridge's `rings` event for the *Doorbell serial number* you enter.
- **Picture and sound:** `device.start_livestream` for the *Camera serial number* you enter (H.264 and AAC through
  the bridge). It can be the doorbell itself or another camera shared with the bridge's Eufy account.
- **Visitor snapshot:** the doorbell's own event picture, which the bridge sends a few seconds after a press or motion.
  A Home Assistant image entity can be added as a second source. A picture older than the *Snapshot max age* is not shown.
- **Not available yet:** talking to the door. The call screen says so.

## Settings
Bridge address, Bridge port, Doorbell serial number, Camera serial number, Extra snapshot source (a Home Assistant
image entity, optional), Home Assistant URL, Snapshot max age, and the upstream chime, screen, music, call button and
card settings. The **Check** button connects to the bridge and waits for a picture. **Test ring** shows the call screen.

## Where data goes
The plugin connects only to the bridge address you enter (a plain `ws://` WebSocket on your network) and, when you set
an image entity or a Home Assistant URL, fetches that entity's picture from your Home Assistant. Nothing goes anywhere
else, and the plugin stores only its settings.

## Known limits
- A Video Doorbell's own livestream can fail after the first one until the bridge add-on restarts (seen on one Video
  Doorbell model); a floodlight camera streamed reliably. Pick the camera serial accordingly.
- The bridge only knows devices shared with its Eufy account, and `driver.poll_refresh` (or a restart) loads new ones.
- The picture is whatever resolution the camera's streaming quality is set to (640x360 at level 1 on one floodlight camera).

## Installation

### Community Plugins (Recommended)

Eufy Doorbell Intercom is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=starman2k01/kiosk-satellite-eufy-doorbell)

Or search for `Eufy Doorbell Intercom` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/starman2k01/kiosk-satellite-eufy-doorbell` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Building
JDK 17 and an Android SDK with `platforms;android-34` and `build-tools;34.0.0`; set `JAVA_HOME` and `ANDROID_HOME`, then
`python tools/build.py`. The ZIP lands in `dist/`. Releases are built by the GitHub Actions workflow.

## License
Apache-2.0. See `LICENSE` and `NOTICE`.
