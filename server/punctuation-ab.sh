#!/usr/bin/env bash
# Before/after punctuation measurement on a temporary whisper-server (never 8815).
# Usage: server/punctuation-ab.sh [all|C0-live|C1-trprompt|C2-trprompt-bs5|C3-live-bs5]
# Env: WHISPER_BIN WHISPER_MODEL WHISPER_VAD AB_PORT (default 8816) PIPER_VOICE FFMPEG
#      PUNCT_CLIPS_DIR  optional dir of owner clips (name.wav + name.txt); stdout only.
# Prints per clip: clip<TAB>config<TAB>ms<TAB>wer<TAB>f1<TAB>density<TAB>text
# then SUMMARY<TAB>config<TAB>mean_wer<TAB>mean_f1<TAB>mean_density<TAB>median_ms and WINNER<TAB><config|none>.
# Winner: highest mean F1 among configs with WER <= C0 WER + 0.02, median ms <= 2x C0, F1 >= C0 F1 + 0.05.
# Exit: 0 ran; 1 harness error. The synthesised corpus audio is local TTS and lives in a temp dir.
set -u
SEL=${1:-all}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
AB_PORT=${AB_PORT:-8816}
[[ $AB_PORT != 8815 ]] || { echo "refusing AB_PORT=8815 (live service)" >&2; exit 1; }

WHISPER_RUN_SOURCED=1 source "$HERE/whisper-stt-run.sh"
set -u
FFMPEG=${FFMPEG:-/usr/bin/ffmpeg}
VOICE_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/whisper-to-input/piper
CONFIGS=(C0-live C1-trprompt C2-trprompt-bs5 C3-live-bs5)
if [[ $SEL == all ]]; then RUN=("${CONFIGS[@]}"); else
  [[ " ${CONFIGS[*]} " == *" $SEL "* ]] || { echo "unknown config $SEL" >&2; exit 1; }
  RUN=("$SEL")
fi

TMP=$(mktemp -d)
SERVER_PID=""
cleanup() {
  if [[ -n $SERVER_PID ]]; then
    kill "$SERVER_PID" 2>/dev/null
    for _ in $(seq 1 50); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  for _ in $(seq 1 50); do ss -ltn 2>/dev/null | grep -q ":$AB_PORT " || break; sleep 0.2; done
  rm -rf "$TMP"
}
trap cleanup EXIT

piper() { # voice text_file out_wav
  uv run --with piper-tts==1.3.0 python -m piper -m "$1" --data-dir "$VOICE_DIR" -f "$3" <"$2" >/dev/null 2>&1
}
VOICE=""
for v in ${PIPER_VOICE:-tr_TR-dfki-medium tr_TR-fahrettin-medium}; do
  if [[ ! -f $VOICE_DIR/$v.onnx ]]; then
    uv run --with piper-tts==1.3.0 python -m piper.download_voices --download-dir "$VOICE_DIR" "$v" >/dev/null 2>&1 || continue
  fi
  if piper "$v" "$HERE/punct-corpus/01.txt" "$TMP/probe.wav" && [[ -s $TMP/probe.wav ]]; then VOICE=$v; break; fi
done
[[ -n $VOICE ]] || { echo "no usable piper voice" >&2; exit 1; }
echo "# voice=$VOICE" >&2

mkdir -p "$TMP/clips"
NAMES=()
for t in "$HERE"/punct-corpus/*.txt; do
  n=$(basename "$t" .txt)
  piper "$VOICE" "$t" "$TMP/$n.wav" || { echo "tts failed for $n" >&2; exit 1; }
  "$FFMPEG" -v error -y -i "$TMP/$n.wav" -ar 16000 -ac 1 -c:a aac "$TMP/clips/$n.m4a" || exit 1
  cp "$t" "$TMP/clips/$n.txt"; NAMES+=("$n")
done
if [[ -n ${PUNCT_CLIPS_DIR:-} ]]; then
  for w in "$PUNCT_CLIPS_DIR"/*.wav; do
    [[ -f $w && -f ${w%.wav}.txt ]] || continue
    n=own-$(basename "$w" .wav)
    "$FFMPEG" -v error -y -i "$w" -ar 16000 -ac 1 -c:a aac "$TMP/clips/$n.m4a" || exit 1
    cp "${w%.wav}.txt" "$TMP/clips/$n.txt"; NAMES+=("$n")
  done
fi

"$BIN" -m "$MODEL" "${WHISPER_ARGS[@]}" --host 127.0.0.1 --port "$AB_PORT" >"$TMP/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 150); do
  curl -s -o /dev/null "http://127.0.0.1:$AB_PORT/" && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died:" >&2; tail -20 "$TMP/server.log" >&2; exit 1; }
  sleep 0.5
done

transcribe() { # clip config
  local f=("-F" "prompt=$PROMPT_LIVE")
  case $2 in
    C1-trprompt) f=("-F" "prompt=$PROMPT_PUNCT") ;;
    C2-trprompt-bs5) f=("-F" "prompt=$PROMPT_PUNCT" "-F" "beam_size=5") ;;
    C3-live-bs5) f+=("-F" "beam_size=5") ;;
  esac
  curl -s --max-time 120 -F "file=@$TMP/clips/$1.m4a" -F response_format=text -F language=tr "${f[@]}" \
    "http://127.0.0.1:$AB_PORT/inference"
}

transcribe "${NAMES[0]}" C0-live >/dev/null # warm-up, not measured
: >"$TMP/rows.tsv"
for cfg in "${RUN[@]}"; do
  for n in "${NAMES[@]}"; do
    t0=$(date +%s%N)
    raw=$(transcribe "$n" "$cfg")
    ms=$(( ($(date +%s%N) - t0) / 1000000 ))
    flat=$(printf '%s' "$raw" | tr '\n\t' '  ' | sed -E 's/^ +//; s/ +$//')
    printf '%s\n' "$flat" >"$TMP/hyp.txt"
    sc=$(python3 "$HERE/punct_score.py" "$TMP/clips/$n.txt" "$TMP/hyp.txt") || { echo "scorer failed" >&2; exit 1; }
    wer=${sc#wer=}; wer=${wer%% *}
    f1=${sc#*f1=}; f1=${f1%% *}
    den=${sc#*density=}
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "$cfg" "$ms" "$wer" "$f1" "$den" "$flat" | tee -a "$TMP/rows.tsv"
  done
done

python3 -I - "$TMP/rows.tsv" <<'PY'
import statistics, sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1], encoding="utf-8")]
cfgs = list(dict.fromkeys(r[1] for r in rows))
S = {}
for c in cfgs:
    rs = [r for r in rows if r[1] == c]
    S[c] = (statistics.mean(float(r[3]) for r in rs), statistics.mean(float(r[4]) for r in rs),
            statistics.mean(float(r[5]) for r in rs), statistics.median(int(r[2]) for r in rs))
    print("SUMMARY\t%s\t%.3f\t%.3f\t%.1f\t%d" % ((c,) + S[c]))
best = "none"
if "C0-live" in S:
    b = S["C0-live"]
    ok = [c for c in S if c != "C0-live" and S[c][0] <= b[0] + 0.02 and S[c][3] <= 2 * b[3] and S[c][1] >= b[1] + 0.05]
    if ok:
        best = max(ok, key=lambda c: S[c][1])
print("WINNER\t" + best)
PY
exit 0
