# Whisper Input

An Android keyboard (IME) that types what you say. It records your voice, sends it to
**your own whisper.cpp server** over Tailscale/LAN, and types the transcription into any
text field. No cloud service, no live streaming, no extra backends.

Based on [j3soon/whisper-to-input](https://github.com/j3soon/whisper-to-input) (GPLv3).

## Using it

- **Tap** the mic to start, tap again to send.
- **Hold** the mic to talk, release to send.
- **Left key**: cancel while recording or sending; when recordings are waiting, it
  retries them (long-press discards them).
- A dot next to the status text shows whether the server is reachable (checked every
  10 s while the keyboard is visible, and whenever the network changes).

### Nothing is lost when the server is down

Every recording is saved in app-private storage before it is sent and is only deleted
once its text has been typed. If the VPN drops, the request times out or the app is
killed, the audio stays queued (up to 20 recordings / 24 hours) and the retry key shows
how many are waiting. If the text arrives after you moved to another field, it waits
behind a **Hazır · yaz** button instead of being typed into the wrong place.

### Timeouts

Fully automatic: the read timeout is `duration × 4 + 30 s` (at least 60 s) and the
upload timeout `duration × 2 + 30 s`. While a request is running the server is pinged
every 5 s; four failed pings in a row abort the request instead of waiting out the
timeout, and the audio stays queued.

## Server

Run `whisper-server` (whisper.cpp) with `--convert` (needs `ffmpeg`) so it accepts the
AAC `.m4a` the app records. Model, language and prompt are the server's own settings; the
app only sends the audio.

```sh
whisper-server -m ggml-large-v3-turbo-q8_0.bin --host 127.0.0.1 --port 8815 --convert
tailscale serve --bg --tcp 8815 tcp://127.0.0.1:8815   # expose to your tailnet only
```

The server has no authentication — only expose it to a trusted network.

In the app settings, set **Adres** to `http://<server-tailscale-ip>:8815/inference` and
use **Bağlantıyı dene** to check it. The default is `http://100.124.60.71:8815/inference`.

## Install

1. Install the APK. If an older build (different signing key) is installed, uninstall
   it first.
2. Open the app and allow the microphone.
3. Enable the keyboard: system settings → Manage keyboards → `Whisper Input`.
4. Focus a text field and pick `Whisper Input` from the keyboard switcher.

## Build

```sh
cd android
./gradlew testDebugUnitTest assembleDebug   # JDK 17, Android SDK in ANDROID_HOME
```

## Permissions

- `RECORD_AUDIO`: record your voice.
- `INTERNET`, `ACCESS_NETWORK_STATE`: talk to the server and notice network changes.

## License

GPLv3, same as the original project — see [LICENSE](LICENSE).
