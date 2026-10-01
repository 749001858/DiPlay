# DiPlay Android 4.3 receiver

**Experimental wireless CarPlay receiver for an Android 4.3 / API 18 head unit.**
Adaptation version: **0.13-experimental**. Install on the **car**, not the iPhone.
This optional receiver preserves the upstream Android 9+ application and its build.
It uses a separate package, `local.airuize.diplaylegacy`.

Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) public preview 0.2.8,
commit `f2d06951b4e8114dbb62f551c12a32a845a3042f`. Upstream focuses on BYD;
this contribution proposes a separate legacy target and does not expand the
maintainer's supported vehicle list. Run one projection receiver at a time.

## Scope and implementation

- Traditional Activity/View interface compiled against SDK18, single DEX035,
  v1 APK signing, no AndroidX/Compose dependency and no native libraries.
- Upstream iAP2 and AirPlay protocol/media sources are selected by
  `prepare_sources.py`. The generated manifest records their original hashes;
  API18 substitutions are explicit and the shared source files are unchanged.
- Bluetooth startup and car-hotspot wireless connection, IPv4 listeners,
  local authentication, encrypted control/events, H.264 Surface decoding,
  PCM/AAC audio, touch, remembered-phone connection and diagnostic export.
- Upstream Bluetooth-to-Wi-Fi handoff conditions: `disableBluetooth` plus
  authenticated/subscribed tunnel readiness. The 45-second watchdog retains
  established video, or closes an inactive stack and restores retry controls.
- Vehicle GPS follows upstream location identification, shared subscriptions,
  GGA/RMC encoding and rate limits. Fresh cached GPS seeds the first update;
  STOP cancels each link's provider. Invalid, stale, mock and network fixes are
  rejected; unknown satellite/HDOP/altitude values are not fabricated.
- Optional Skypine MCU voice-button adapter: open/connect while disconnected,
  request Siri while connected. It registers/unregisters callbacks, sends no
  MCU/CAN commands, deduplicates events and offers a 15-second key capture.
  An enabled foreground listener survives Activity closure; boot restores only
  the listener. Actual key delivery and reboot behavior require device testing.

## Evidence and limitations

Development feedback from one Android 4.3, 32-bit ARM, i.MX6 firmware confirms
wireless picture, full-screen H.264, basic touch, remembered-phone connection,
music, navigation synchronization and Siri activation. GPS was readable through
Android LocationManager. This is not a model compatibility list or a guarantee
for other vehicles. The interface currently uses Simplified Chinese.

Vehicle GPS transmission and iPhone adoption remain unverified: observed
subscriptions were immediately cancelled. Full authenticated Wi-Fi iAP2 handoff,
physical steering-button callbacks, telephone duplex audio, microphone routing,
long journeys and persistent iPhone activities require further evidence.
USB/NCM, Wi-Fi Direct, BYD HUD/dashboard, battery/gear/wheel-speed reporting,
HEVC/Opus, and continuous automatic reconnection are outside this target's
current implementation. Do not substitute GPS speed for vehicle wheel speed.

The upstream experimental authentication caveat applies: **this is not an
Apple-certified product**. Runtime acceptance, future iOS compatibility and
suitability of the experimental identity for general distribution are unresolved.
As upstream documents, a release APK containing that identity makes its private
key extractable. The Git repository excludes runtime identities and signing keys;
source builds omit runtime authentication unless explicitly selected externally. Tests generate synthetic identities at runtime, following upstream credential policy.

## Install and test

Install the legacy APK on a head unit that permits APK installation. Pair the
phone and use the car hotspot; Wi-Fi Direct is not used on API18. Start the app,
choose a paired phone if requested, and accept the phone's CarPlay prompts.
Use a lower display mode if the decoder cannot sustain the larger frame size.

For voice-button testing, open the app once and leave the optional background
listener enabled. Test the voice button while connected, then from the car desktop
after stopping the session. If no event arrives, use the 15-second diagnostic
capture and export a report. Disable the checkbox to stop the persistent listener.
Force-stopping the app requires opening it again.

Leave vehicle GPS enabled and reconnect after changing it. Reports distinguish
START/STOP, valid GPS fixes, NMEA provision and iAP2 send counters. An increasing
send counter proves transmission, not that a navigation app chose vehicle GPS.

Reports stay local until shared deliberately. This contribution contains no
terminal paths, firmware images, personal device reports, Wi-Fi credentials,
Bluetooth addresses, accessory keys or Android signing keys. Source file names
and numeric firmware interface constants are retained for reproducibility.

## Build and validation

See [BUILD.md](BUILD.md). The legacy target is independent of the upstream Gradle
application. Local validation of v0.13 passed **63 JVM tests**, SDK18 platform
class/member checks, single-DEX035/CRC checks, v1 signature verification and the
post-R8 JmDNS/EnumMap reflection test. These are local checks, not hardware proof.

## Credits and licenses

Preserve upstream [credits and licenses](../../docs/THIRD_PARTY_NOTICES.md),
[LICENSE](../../LICENSE), and the notices copied into `assets/licenses`.
DiPlay is based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0;
the upstream DiAuto interface and website carry AGPL-3.0 notices. This adaptation
uses a traditional Android interface and preserves the bundled notices.
CarPlay belongs to Apple Inc.; no Apple, BYD or original-maintainer endorsement
of this legacy receiver is implied.
