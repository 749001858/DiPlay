# CS11 installation and steering-wheel test

This build targets Android 4.4.3 / API 19 and the original Lynk & Co CS11 head
unit. It is experimental and does not write to CAN or the MCU.

## Install

1. Keep the existing car software available so the system can be restored.
2. Install `DiPlay-Android443-CS11-Wireless-Experimental.apk` with the head unit's
   normal APK installer or `adb install -r`.
3. Pair the iPhone in the stock Bluetooth settings and enable the car hotspot.
4. Open DiPlay, leave **领克 CS11 方控（OneOS 后台监听）** enabled and select the
   paired iPhone.
5. The CS11 build defaults to **1920x1080** and **60Hz / 60fps**. If the image is
   black, unstable or drops frames, switch to 30Hz first, then retry at 1280x720,
   1024x600 and 800x480. Both settings take effect after reconnecting CarPlay.

## Steering-wheel mapping

| CS11 control | OneOS code | CarPlay action |
|---|---:|---|
| Voice | 200231 | Open DiPlay / Siri |
| Play-pause | 200085 | Play-pause HID |
| Next | 200087 | Next-track HID |
| Previous | 200088 | Previous-track HID |
| Volume | Native | Left to the CS11 audio system |

The listener uses the stock Geely OneOS input service. Android media-button
events are a fallback. A duplicate gate prevents the same physical press being
forwarded twice when both paths report it.

## First hardware validation

Open **诊断 → 方向盘按键检测（15秒）**, then press voice, play-pause, previous and
next once each. Export the report. A healthy CS11 path contains
`CS11 OneOS方控注册成功` followed by `CS11 OneOS按键` or `CS11 OneOS短按` lines.

If registration reports a permission or service error, capture these read-only
checks over ADB:

```sh
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell pm list packages | grep -E 'geely|ecarx|oneos'
adb shell dumpsys package com.geely.service.oneosapi
adb logcat -d | grep -E 'DiPlay|OneOS|IInputManager|SecurityException'
```

Do not grant broad system privileges or modify the vehicle firmware merely to
make the callback bind. Share the exported DiPlay report first so the actual
CS11 firmware contract can be checked.
