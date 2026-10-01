# Legacy receiver validation

Adaptation: 0.13-experimental. Upstream baseline: DiPlay 0.2.8,
`f2d06951b4e8114dbb62f551c12a32a845a3042f`.

- 63 JVM tests: upstream protocol/media tests plus API18 compatibility,
  pairing, IPv4 transport, handoff, lifecycle, GPS and voice-key policy checks.
- SDK18 platform class/member checks; single DEX035; ZIP CRC; no native libraries.
- Post-R8 JmDNS service/TXT startup and reflective EnumMap method preservation.
- APK v1 signature verified with minimum SDK18.
- Upstream public-tree credential check and separate personal-data review.
- Tests generate synthetic authentication identities; no private inputs are tracked.

The publication changes only documentation, asset text, toolchain paths and test
provisioning around the existing v0.13 implementation. No original firmware,
device report, terminal output or contributor's local path is published.

The upstream Gradle build/lint suite has not been run locally for this optional
target; its source and build configuration are unchanged. These local checks do
not establish complete phone call/microphone behavior, vehicle GPS adoption,
physical steering-button delivery or full wireless tunnel handoff.
