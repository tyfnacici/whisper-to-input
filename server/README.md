# server/

- `whisper-stt-run.sh` — proposed replacement for `~/.hermes/scripts/whisper-stt-run.sh`. Adds
  `-sns` and `-nth 0.8`, drops `--carry-initial-prompt`; everything else is identical (port 8815).
- `hallucination-ab.sh before|after` — starts a temporary whisper-server on port 8816 (never 8815),
  sends generated silence/noise/short clips plus `jfk.wav`, prints `clip<TAB>mode<TAB>text<TAB>verdict`,
  and stops the server on exit. Exit 2 in `after` mode means the server alone still hallucinates.
- `punctuation-ab.sh [all|<config>]` — synthesises `punct-corpus/*.txt` with local piper TTS, runs 4 prompt/beam
  configs against a temporary server on 8816, prints per-clip, `SUMMARY` and `WINNER` lines. Set
  `PUNCT_CLIPS_DIR` to a dir of your own `name.wav` + `name.txt` pairs to include them (stdout only).
- `punct_score.py REF HYP` — stdlib scorer (WER, punctuation F1, density); tests: `python3 -m unittest discover -s server -p 'test_punct_score.py'`.
- `sentence-ab.sh [all|<config>]` — mixed-sentence root-cause A/B (segmentation, language, prompt) on a temporary
  server on 8816, using long-dictation and mid-sentence-pause TTS clips; prints per-row, `SUMMARY` and `WINNER` lines.
- `SENTENCE-RESULTS.md` — recorded sentence-coherence before/after (no config qualified; launcher unchanged).
- `PUNCT-RESULTS.md` — recorded punctuation before/after (no config qualified; launcher prompt unchanged).
- `AB-RESULTS.md` — recorded output of one before/after run.

The owner applies the launcher (not done by this repo):

```
cp server/whisper-stt-run.sh ~/.hermes/scripts/ && systemctl --user restart whisper-stt
```
