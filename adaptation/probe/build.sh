#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
sdk_dir=${DIPLAY_PROBE_SDK_DIR:-"$workspace_dir/tools/android"}
platform_jar=${DIPLAY_PLATFORM_JAR:-"$sdk_dir/android-4.3.1/android.jar"}
build_tools=${DIPLAY_BUILD_TOOLS_DIR:-"$sdk_dir/android-14"}
build_dir="$project_dir/build"
delivery_dir="$workspace_dir/adaptation/output"
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
mkdir -p "$build_dir/classes" "$build_dir/dex" "$delivery_dir"
test -f "$platform_jar"
test -x "$build_tools/aapt"
"$build_tools/aapt" package -f -M "$project_dir/AndroidManifest.xml" \
    -S "$project_dir/res" -A "$project_dir/assets" -I "$platform_jar" -0 mp4 -F "$build_dir/resources.apk"
find "$project_dir/src" -name '*.java' > "$build_dir/sources.list"
"$jdk_dir/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$platform_jar" \
    -d "$build_dir/classes" @"$build_dir/sources.list"
"$jdk_dir/bin/jar" cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$jdk_dir/bin/java" -cp "$build_tools/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 18 --lib "$platform_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -u "$build_dir/unsigned.apk" classes.dex)
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
if [ ! -f "$build_dir/probe.keystore" ]; then
    "$jdk_dir/bin/keytool" -genkeypair -keystore "$build_dir/probe.keystore" -storepass local-probe-only \
        -keypass local-probe-only -alias probe -dname 'CN=Local DiPlay Capability Probe' \
        -keyalg RSA -keysize 2048 -validity 3650 -storetype PKCS12
fi
"$jdk_dir/bin/java" -jar "$build_tools/lib/apksigner.jar" sign --ks "$build_dir/probe.keystore" \
    --ks-key-alias probe --ks-pass pass:local-probe-only --key-pass pass:local-probe-only \
    --min-sdk-version 18 --v1-signing-enabled true --v2-signing-enabled false \
    --v3-signing-enabled false --v4-signing-enabled false \
    --out "$delivery_dir/DiPlay-Android43-CompatibilityProbe.apk" "$build_dir/aligned.apk"
"$jdk_dir/bin/java" -jar "$build_tools/lib/apksigner.jar" verify --verbose --min-sdk-version 18 \
    "$delivery_dir/DiPlay-Android43-CompatibilityProbe.apk"
"$build_tools/aapt" dump badging "$delivery_dir/DiPlay-Android43-CompatibilityProbe.apk"
shasum -a 256 "$delivery_dir/DiPlay-Android43-CompatibilityProbe.apk"
