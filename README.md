# Whisper To Input (fork)

An Android keyboard (IME) that performs speech-to-text with Whisper and types the
recognized text into any text field. Press the mic, speak, press again — done.

This fork is set up for **self-hosted Whisper servers** over Tailscale/LAN, with:

- **Adaptive request timeout** — read timeout scales with the recording length
  (`duration × 4 + 30s`), so long dictations are never cut off by the client
  (the original app failed after 10 seconds). Configurable in Settings:
  `Auto` (default), `60s`, `300s`, `600s`.
- **CI releases** — APKs are built automatically and attached to
  [Releases](https://github.com/tyfnacici/whisper-to-input/releases).

Based on [j3soon/whisper-to-input](https://github.com/j3soon/whisper-to-input) — licensed under GPLv3.

## Installation

1. Download the APK from the
   [latest release](https://github.com/tyfnacici/whisper-to-input/releases/latest)
   to your phone and install it.
   An `Unsafe app blocked` warning may appear — click `More details` → `Install anyway`.
   If you previously had the upstream app installed, uninstall it first (different
   signing keys).

2. Allow the app to record audio and send notifications.

3. Open the app and configure the connection to your Whisper server (see below).

4. Enable the keyboard: system settings → Manage keyboards → enable `Whisper Input`.

5. Focus any text field, switch to `Whisper Input` via the keyboard picker, and start
   dictating.

   ![Choose input method](docs/images/17-choose-input-method.jpg)

## Server Setup (self-hosted, recommended)

This fork is designed to talk to a **whisper.cpp server** (or any OpenAI-compatible
transcription endpoint) on your own machine.

Start `whisper-server` with the OpenAI-compatible `/inference` endpoint and make it
listen on all interfaces so your phone can reach it:

```sh
whisper-server -m <model>.bin --host 0.0.0.0 --port 8814 --convert
```

- `--convert` lets the server accept the `.m4a` audio the app records
  (requires `ffmpeg` on the server).
- Expose the port only to trusted networks (e.g. Tailscale, CGNAT range
  `100.64.0.0/10`) — the server has no authentication.

Then configure the app:

```text
Speech to Text Backend:  OpenAI API
Endpoint:                http://<SERVER_IP>:8814/inference
Language Code:                      (leave empty for auto-detect)
```

The upstream [Whisper ASR Webservice](https://github.com/ahmetoner/whisper-asr-webservice)
backend is also still supported (`Endpoint: http://<SERVER_IP>:9000/asr`).

## Keyboard Usage

![Keyboard layout](docs/images/keyboard-layout.jpg)

- `Microphone` (center): start/stop recording and input the recognized text.
- `Cancel` (bottom left, while recording): discard the current recording.
- `Backspace` (top right): delete the previous character (hold to repeat).
- `Enter` (bottom right): newline; while recording it stops and inputs the text plus
  a newline.
- `Settings` (top left): open the app settings.
- `Switch keyboard` (top left): switch back to the previous input method.

## Troubleshooting

- All release APKs are debug builds; capture logs with `adb logcat` (enable USB
  debugging first) if something fails:

  ```sh
  adb logcat *:E
  ```

- If transcription fails with a timeout error, check that the server is reachable from
  the phone and raise the `Request Timeout` setting.

## Permissions

- `RECORD_AUDIO`: record audio for voice input.
- `POST_NOTIFICATIONS`: show error messages while the keyboard is in the background.

## License

This fork is licensed under GPLv3, same as the original project — see the
[LICENSE](android/LICENSE) file.
