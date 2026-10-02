"""Create a traceable API-18 protocol snapshot without altering the upstream checkout."""
from pathlib import Path
import json
import shutil
import hashlib

root = Path(__file__).resolve().parents[2]
upstream = root / 'shared/src/main/java/com/shilapi/xcertplay'
out = Path(__file__).resolve().parent / 'build/upstream'
out.mkdir(parents=True, exist_ok=True)
chosen = list((upstream / 'airplay').glob('*.kt')) + list((upstream / 'iap2').rglob('*.kt'))
transport = ['BlockingDuplexByteStream', 'BluetoothRfcommDuplexStream', 'I2cTransport',
             'Iap2CsmChannel', 'Iap2LinkChannel', 'Iap2LinkEngine', 'Iap2IdentificationClient',
             'Iap2WirelessControlClient', 'Iap2WiredControlClient', 'Iap2ControlDeadline',
             'Iap2LocationClient', 'Iap2VehicleStatus', 'VehicleSpeedNmea']
chosen += [upstream / 'transport' / (name + '.kt') for name in transport]
chosen += [upstream / 'mfi' / (name + '.kt') for name in
           ['MfiAuthenticationClient', 'Iap2MfiAuthenticationClient']]
chosen += [upstream / 'media/MediaCodecSupport.kt']
chosen += [upstream / 'media/TouchLatencyProbe.kt']
manifest = []
for source in chosen:
    text = source.read_text()
    original = text
    # This receiver advertises only the tested IPv4 hotspot endpoint. Android 4.3
    # datagram implementations may reject IPv6 wildcard binds even with IPv6
    # interface addresses present. Apply the same family to all media listeners.
    if source.name == 'IapTunnel.kt':
        start = text.index('        val secondaryAddress =')
        end = text.index('        servers.forEach', start)
        text = text[:start] + text[end:]
    text = text.replace('InetAddress.getByName("::")', 'InetAddress.getByName("0.0.0.0")')
    # StandardCharsets is API 19; java.util.Base64 is API 26.
    text = text.replace('import java.util.Base64', 'import local.airuize.receiver.LegacyBase64 as Base64')
    text = text.replace('import java.nio.charset.StandardCharsets', 'import local.airuize.receiver.LegacyCharsets as StandardCharsets')
    # AutoCloseable is API 19. These classes do not need its larger close contract.
    text = text.replace('AutoCloseable', 'java.io.Closeable')
    text = text.replace('KeyEvent.KEYCODE_VOICE_ASSIST', '231')
    text = text.replace('java.lang.Long.toUnsignedString', 'local.airuize.receiver.LegacyNumbers.unsigned')
    text = text.replace('java.lang.Long::toUnsignedString', 'local.airuize.receiver.LegacyNumbers::unsigned')
    text = text.replace('Integer.toUnsignedString', 'local.airuize.receiver.LegacyNumbers.unsigned')
    text = text.replace('Math.floorDiv', 'local.airuize.receiver.LegacyNumbers.floorDiv')
    text = text.replace('Math.floorMod', 'local.airuize.receiver.LegacyNumbers.floorMod')
    if source.name == 'AirPlayInfoPlist.kt':
        # The API19 sink implements PCM and AAC only: do not negotiate unsupported Opus streams.
        text = text.replace('val opus = 0x70000000', 'val opus = 0')
    if source.name == 'CarPlayMediaEngine.kt':
        target = '        val microphone = microphoneConfig(session, type, stream, format)'
        assert target in text
        text = text.replace(target, target + '\n        session.logDebug("receiver audio setup type=$type codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} purpose=${local.airuize.receiver.AudioDiagnostics.purpose(audioType)} microphone=${microphone != null}")')
        target = '        val audio = AudioStream(key, type, session::logDebug)'
        assert target in text
        text = text.replace(target, '        var rejectedPackets = 0\n' + target)
        target = '                    capture?.record(wire, rtp, sample, error)'
        assert target in text
        text = text.replace(target, target + '''
                    if (error != null) {
                        rejectedPackets++
                        if (rejectedPackets <= 3 || rejectedPackets % 100 == 0)
                            session.logDebug("receiver audio rejected type=$type count=$rejectedPackets")
                    }''')
    if source.name in ('PairSetup.kt', 'PairVerify.kt'):
        # The upstream responder converts crypto exceptions to TLV errors. Preserve
        # that behavior but make the exception location available to exported logs.
        phase = 'pair-setup' if source.name == 'PairSetup.kt' else 'pair-verify'
        text = text.replace('    private val pairings: PairingStore,',
            '    private val pairings: PairingStore,\n    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },')
        text = text.replace('        } catch (_: Exception) {\n            err(state ?: 0)',
            f'        }} catch (error: Exception) {{\n            onFailure("{phase}", error)\n            err(state ?: 0)\n        }} catch (error: LinkageError) {{\n            onFailure("{phase}", error)\n            err(state ?: 0)')
    if source.name == 'AirPlaySession.kt':
        text = text.replace('    fun onDebugLog(message: String) {}',
            '    fun onDebugLog(message: String) {}\n    fun onControlFailure(stage: String, error: Throwable) {}')
        text = text.replace('PairSetup(identity, pairings)', 'PairSetup(identity, pairings, ::reportControlFailure)')
        text = text.replace('PairVerify(identity, pairings)', 'PairVerify(identity, pairings, ::reportControlFailure)')
        text = text.replace('            eventCipher = ControlCipher(readKey, writeKey)',
            '            eventCipher = ControlCipher(readKey, writeKey)\n            debugLog("airplay event encryption ready")')
        for stage, marker in [('event-accept', 'airplay event accept failed'),
                              ('event-decrypt', 'airplay event decrypt failed encrypted=${encrypted.size}'),
                              ('event-read', 'airplay event read failed')]:
            target = f'Log.e(TAG, "{marker}", error)'
            assert target in text
            text = text.replace(target, f'run {{ reportControlFailure("{stage}", error); {target} }}')
        text = text.replace('                        closeReason = "control decrypt failed:',
            '                        reportControlFailure("control-decrypt", error)\n                        closeReason = "control decrypt failed:')
        text = text.replace('                    } catch (error: Exception) {\n                        Log.e(',
            '                    } catch (error: Exception) {\n                        reportControlFailure("request-handler", error)\n                        Log.e(')
        text = text.replace('            closeReason = "control I/O failed:',
            '            reportControlFailure("control-io", error)\n            closeReason = "control I/O failed:')
        text = text.replace('        } finally {\n            debugLog("airplay control closing',
            '        } catch (error: LinkageError) {\n            closeReason = "control runtime failed"\n            reportControlFailure("control-runtime", error)\n        } finally {\n            debugLog("airplay control closing')
        for phase, responder in [('pair-setup', 'pairSetup'), ('pair-verify', 'pairVerify')]:
            text = text.replace(f'body = {responder}.handle(request.body),',
                f'body = pairingResponse("{phase}", request.body, {responder}.handle(request.body)),')
        marker = '    private fun debugLog(message: String, uiVisible: Boolean = true) {'
        additions = '''    private fun reportControlFailure(stage: String, error: Throwable) {
        try { listener.onControlFailure(stage, error) }
        catch (_: Exception) {}
    }

    private fun pairingResponse(phase: String, request: ByteArray, response: ByteArray): ByteArray {
        fun value(body: ByteArray, key: Int): String = try {
            Tlv8Codec.decode(body)[key]?.firstOrNull()?.let { (it.toInt() and 255).toString() } ?: "none"
        } catch (_: Exception) { "invalid" }
        debugLog("airplay pairing phase=$phase requestState=${value(request, 6)} responseState=${value(response, 6)} error=${value(response, 7)}")
        return response
    }

'''
        assert marker in text
        text = text.replace(marker, additions + marker)
    text = text.replace('if (failure != null) throw failure', 'failure?.let { throw it }')
    # The legacy Android sockets predate their Closeable interfaces.
    import re
    text = re.sub(r'fun safeClose\((\w+): Closeable\?\)', r'fun safeClose(\1: Any?)', text)
    if 'fun safeClose' in text:
        text = text.replace('closeable?.close()', 'local.airuize.receiver.LegacyCloseables.close(closeable)')
    if source.name == 'Iap2LocationClient.kt':
        # A stop received on Bluetooth also stops the independent Wi-Fi provider.
        target = """        if (continueRequest && continued && request?.components == null) {
            active = false
            sentLogged = false
            return
        }"""
        assert target in text
        text = text.replace(target, target.replace('            return', '            provider?.stop()\n            return'))
        text = text.replace('java.time.Instant.ofEpochMilli(millis)\n            .atZone(java.time.ZoneOffset.UTC)',
                            'java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }')
        for old, new in [('hour', 'HOUR_OF_DAY'), ('minute', 'MINUTE'), ('second', 'SECOND'),
                         ('dayOfMonth', 'DAY_OF_MONTH'), ('year', 'YEAR')]:
            text = text.replace('fields.' + old, 'fields.get(java.util.Calendar.' + new + ')')
        text = text.replace('fields.monthValue', '(fields.get(java.util.Calendar.MONTH) + 1)')
    if source.name == 'Srp6a.kt':
        # Kotlin 1.9 requires the compile-time modulus declaration above its first use.
        start = text.index('    private const val N_HEX =')
        end = text.index('\n\n', start)
        modulus = text[start:end]
        text = text[:start] + text[end:]
        text = text.replace('object Srp6a {', 'object Srp6a {\n' + modulus)
    destination = out / source.relative_to(upstream)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text)
    manifest.append({'path': str(source.relative_to(upstream)),
                     'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                     'modified': text != original})
(out.parent / 'upstream-manifest.json').write_text(json.dumps(manifest, indent=2))
# Remove only a previous generated version of the replaced platform-provider implementation.
(out / 'mfi/LocalMfiAuthenticationClient.kt').unlink(missing_ok=True)
licenses = Path(__file__).resolve().parent / 'assets/licenses'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy(root / 'LICENSE', licenses / 'DiPlay-LICENSE')
shutil.copytree(root / 'docs/licenses', licenses / 'upstream', dirs_exist_ok=True)
print('Prepared', len(chosen), 'protocol/media source files')
