# Legacy receiver validation

Adaptation: 0.14-cs11. Upstream baseline: DiPlay 0.2.8,
with the isolated Android 4.3 receiver branch as the compatibility base.

- 66 JVM tests: upstream protocol/media tests plus API19 compatibility,
  pairing, IPv4 transport, handoff, lifecycle, GPS and CS11 key-policy checks.
- API19 platform class/member checks; single DEX035; ZIP CRC; no native libraries.
- Post-R8 JmDNS service/TXT startup and reflective EnumMap method preservation.
- APK v1 signature verified with minimum SDK19.
- Upstream public-tree credential check and separate personal-data review.
- Tests generate synthetic authentication identities; no private inputs are tracked.

The publication changes only documentation, asset text, toolchain paths and test
provisioning around the existing v0.13 implementation. No original firmware,
device report, terminal output or contributor's local path is published. The CS11
adaptation adds OneOS listener-only key handling and 1920x1080 at 60fps as the
default AirPlay display request, with lower-resolution/30fps fallbacks.

The upstream Gradle build/lint suite has not been run locally for this optional
target; its source and build configuration are unchanged. These local checks do
not establish complete phone call/microphone behavior, vehicle GPS adoption,
physical CS11 steering-button delivery, panel-level 60Hz switching or full wireless
tunnel handoff. The refresh setting controls the CarPlay/AirPlay stream request;
the vehicle display panel remains governed by its firmware.
