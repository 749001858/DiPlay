# Build the legacy receiver

The upstream Android 9+ Gradle app remains unchanged. This optional target uses
JDK 11, Kotlin compiler 1.9.24, Android API18 `android.jar`, Android build tools
34.0.0 (including R8 and apksigner), Python 3, the `zip` command, and OpenSSL for synthetic test identities.

Provide the SDK18 platform and build tools from the Android SDK, and a Kotlin
compiler distribution from JetBrains. Dependency versions and published archive
hashes are recorded in `dependency-sha256.json`. The dependency downloader checks
JAR hashes before accepting files; tools and generated files are ignored by Git.

```sh
export JAVA_HOME=/path/to/jdk-11
export DIPLAY_PLATFORM_JAR=/path/to/android-sdk/platforms/android-18/android.jar
export DIPLAY_BUILD_TOOLS_DIR=/path/to/android-sdk/build-tools/34.0.0
export DIPLAY_KOTLIN_DIR=/path/to/kotlinc
export DIPLAY_DEPS_DIR="$PWD/downloads"
python3 adaptation/receiver/fetch_dependencies.py
sh adaptation/receiver/build.sh
python3 adaptation/receiver/verify_apk.py
sh adaptation/receiver/test-packaging.sh
```

The result is `adaptation/output/DiPlay-Android43-Wireless-Experimental.apk`.
Without runtime identity assets this APK is a source/compatibility build and cannot
complete accessory authentication. No external authentication server is added.

For an intentional local runtime build, set `DIPLAY_AUTH_ASSETS_DIR` to an external
directory containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`,
consistent with upstream [build policy](../../docs/BUILD.md). The inputs are never
committed or downloaded by these scripts. Do not publish personal signing keys.
A release APK including the experimental identity makes that identity extractable.

The script creates a local test signing key under the ignored build directory.
Keep the same local key to update an existing installation. Signing a public
release is a separate local decision; the test key is not a production identity.

Run the JVM tests after building:

```sh
sh adaptation/receiver/test.sh
```

Tests generate a synthetic short-lived P-256 identity in the ignored build directory; no real accessory identity is needed. Network tests bind loopback sockets. `test-packaging.sh` checks the post-shrink
JmDNS startup and reflective enum methods without starting a network service.
`verify_apk.py` checks platform types/members, DEX035, single DEX, CRC and identity
presence/consistency. `build.sh` verifies the v1 signature using apksigner.

The included `adaptation/probe` is an optional API18 capability diagnostic app.
Its build uses the same JDK/platform/build-tools environment variables; it does
not participate in the receiver's normal connection flow.
