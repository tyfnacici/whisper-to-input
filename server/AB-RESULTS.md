# Hallucination A/B results

Date: 2026-11-11. Model `ggml-large-v3-turbo-q8_0`, Silero VAD, `-dev 0`, temporary port 8816
(`server/hallucination-ab.sh before` then `after`; live service on 8815 untouched).

## before (live flags: `--carry-initial-prompt`, no `-sns`/`-nth`)

| clip | raw server text | verdict | after app filter |
|---|---|---|---|
| silence-5s | (empty) | OK_EMPTY | (empty) |
| noise-5s | (empty) | OK_EMPTY | (empty) |
| short-0.4s | (empty) | OK_EMPTY | (empty) |
| jfk-speech (`language=en`) | And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country. | SPEECH_OK | unchanged |

## after (`server/whisper-stt-run.sh`: `-sns -nth 0.8`, no `--carry-initial-prompt`)

| clip | raw server text | verdict | after app filter |
|---|---|---|---|
| silence-5s | (empty) | OK_EMPTY | (empty) |
| noise-5s | (empty) | OK_EMPTY | (empty) |
| short-0.4s | (empty) | OK_EMPTY | (empty) |
| jfk-speech (`language=en`) | And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country. | SPEECH_OK | unchanged |

## Findings

- On these synthetic clips (pure digital silence, seeded pink noise, 0.4 s silence) the Silero VAD
  already blanks the input in **both** modes, so the server never hallucinated here and the new
  flags show no measurable difference. The owner's real-world "Altyazı M.K." cases come from
  quiet-room recordings that the VAD lets through; they could not be reproduced synthetically.
- No extra boilerplate strings were produced, so the blocklist is the plan's initial list.
  The app filter is therefore the guaranteed layer; its unit tests use the owner's observed
  strings (`HallucinationFilterTest`).
- The server change is a hedge: `-sns`/`-nth 0.8` are low-risk, and removing
  `--carry-initial-prompt` did not break speech (jfk still contains "country"). The term
  benefit of the prompt on Turkish/dev vocabulary was not re-measured (no Turkish sample).

RESULT: silence/noise/short -> empty after filter; speech control intact
