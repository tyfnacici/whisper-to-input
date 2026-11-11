# Mixed-sentence A/B results

- Date: 2026-11-11
- Model: `ggml-large-v3-turbo-q8_0` (launcher flags from `whisper-stt-run.sh`: `-l tr -nfa --vad -sns -nth 0.8`)
- Voice: piper `tr_TR-dfki-medium` (synthetic Turkish TTS, all local); 8 short clips (no-regression set) + 10 stress clips:
  L1/L2 = clips 01-04 / 05-08 joined with 1.0 s silence (>30 s), H01-H08 = each text split at `, ` with a 0.6 s pause
- Command: `server/sentence-ab.sh all` (REPS=2, averaged)

## Configs

| Config | Fields | stress WER | stress sent_f1 | short WER | median ms |
|---|---|---|---|---|---|
| C0-live | `prompt=$PROMPT_LIVE` | 0.367 | 0.585 | 0.060 | 904 |
| D1-carry | `carry_initial_prompt=true` | 0.367 | 0.585 | 0.060 | 817 |
| S1-vadgap | `vad_min_silence_duration_ms=500`, `vad_speech_pad_ms=200` | 0.384 | 0.566 | 0.051 | 764 |
| S2-novad | `vad=false` | 0.373 | 0.574 | 0.051 | 672 |
| S3-noctx | `max_context=0` | 0.368 | 0.558 | 0.080 | 740 |
| P1-noprompt | `prompt=` | 0.368 | 0.558 | 0.080 | 765 |
| P2-trprompt | `prompt=$PROMPT_PUNCT` | 0.360 | 0.560 | 0.054 | 773 |
| L1-auto | `language=auto` | 0.367 | 0.585 | 0.060 | 1087 |

WINNER: none

Rule: stress WER <= C0-0.02 or sent_f1 >= C0+0.05, no regression (WER <= C0+0.01, sent_f1 >= C0-0.02),
short WER <= C0+0.02, median ms <= 2x C0. D1-carry and L1-auto are diagnostic only.

## C0 vs best candidate (P2-trprompt, lowest stress WER), rep 1, synthetic texts only

| Clip | C0-live | P2-trprompt |
|---|---|---|
| L1 | Bugün yeni bir branca açtım. Sonra değişiklikleri kontrol ettim ama hatı hala duruyor. Sence sorun nerede olabilir? Deploy işlemi bitti fakat servis yanıt vermiyor. (...) Yorumları okudum ama birkaç noktada emin değilim.  Yarın toplantıda konuşalım. | same text (WER 0.077 both) |
| L2 | İstanbul'a gitmeden önce bilet almam gerekiyor. (...) Önce branch'ı güncelleyelim sonra PR birleştirelim. Deploy için ona gerekiyor mu? (...) | same, but `branch'i` / `PR'e` (WER 0.050 -> 0.037) |
| H01 | Bugün yeni bir branca açtım. Sonra değişiklikleri kontrol ettin. Ama hata hala duruyor. Sence? | identical |
| H04 | FR incelediğini, yorumları okudum. | identical |

## Findings

- Long dictations that cross the 30 s window (L1, L2) are coherent in every config: sent_f1 = 1.000, WER 0.04-0.08.
  The window/segmentation is not what breaks sentences, and neither VAD gap/pad (S1), disabling VAD (S2) nor
  clearing context (S3) improves it.
- The remaining stress error comes from the H clips: with a 0.6 s pause inside a sentence the model often
  stops after the first fragments (H02 `Deploy işlemi bitti.`, H04 drops everything after the comma-piece
  boundary) or puts a stop at the pause (H06, H07). Disabling VAD (S2) does not change this (H04 identical), so it
  is the model's own decoding on pause-split audio, not a launcher setting; the prompt is nearly irrelevant
  (P1 vs C0: stress WER +0.001, sent_f1 -0.027; it only changes term spelling, e.g. `PR` -> `Fere`).
- Language: `language=auto` (L1-auto) gave byte-identical scores to C0, so the pinned `-l tr` is not the cause
  and auto-detect brings no gain (it is slower, 1087 ms median).
- D1-carry matches C0 on this corpus, so `--carry-initial-prompt` is not a mixed-sentence driver here either.
- Prompt: the Turkish punctuated prompt (P2) improves stress WER by 0.007 and short WER by 0.006 — within noise,
  below the 0.02 threshold. Not applied.
- Caveat: synthetic TTS is cleaner than real dictation; real hesitations may behave differently. No single launcher
  setting measurably improves coherence, so the launcher is left unchanged.

RESULT: no qualifying improvement
