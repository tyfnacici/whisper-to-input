# server/

- `whisper-stt-run.sh` — proposed replacement for `~/.hermes/scripts/whisper-stt-run.sh`. Adds
  `-sns` and `-nth 0.8`, drops `--carry-initial-prompt`; everything else is identical (port 8815).
- `hallucination-ab.sh before|after` — starts a temporary whisper-server on port 8816 (never 8815),
  sends generated silence/noise/short clips plus `jfk.wav`, prints `clip<TAB>mode<TAB>text<TAB>verdict`,
  and stops the server on exit. Exit 2 in `after` mode means the server alone still hallucinates.
- `AB-RESULTS.md` — recorded output of one before/after run.

The owner applies the launcher (not done by this repo):

```
cp server/whisper-stt-run.sh ~/.hermes/scripts/ && systemctl --user restart whisper-stt
```
