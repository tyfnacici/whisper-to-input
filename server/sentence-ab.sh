#!/usr/bin/env bash
# Mixed-sentence root-cause A/B on a temporary whisper-server (never 8815).
# Usage: server/sentence-ab.sh [all|C0-live|D1-carry|S1-vadgap|S2-novad|S3-noctx|P1-noprompt|P2-trprompt|L1-auto]
# Env: WHISPER_BIN WHISPER_MODEL WHISPER_VAD AB_PORT (default 8816) PIPER_VOICE FFMPEG REPS (default 2)
# Every hypothesis is a per-request multipart field, so one server start covers all configs.
# Clips: 8 short corpus clips (set=short) plus stress clips (set=stress): L1/L2 = long dictations
# (>30 s) and H01-H08 = each text split at ", " with a 0.6 s pause. All audio is local TTS.
# Prints: clip<TAB>set<TAB>config<TAB>rep<TAB>ms<TAB>wer<TAB>sent_f1<TAB>f1<TAB>text
# then SUMMARY<TAB>config<TAB>stress_wer<TAB>stress_sent_f1<TAB>short_wer<TAB>median_ms and WINNER<TAB><config|none>.
# Winner (vs C0-live): stress WER <= C0-0.02 or sent_f1 >= C0+0.05; no regression (WER <= C0+0.01,
# sent_f1 >= C0-0.02); short WER <= C0+0.02; median ms <= 2x C0; biggest stress WER drop wins, ties
# go to the config with fewer parameters. D1-carry and L1-auto are diagnostic and never win.
# Exit: 0 ran; 1 harness error.
set -u
SEL=${1:-all}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
AB_PORT=${AB_PORT:-8816}
REPS=${REPS:-2}
[[ $AB_PORT != 8815 ]] || { echo "refusing AB_PORT=8815 (live service)" >&2; exit 1; }

WHISPER_RUN_SOURCED=1 source "$HERE/whisper-stt-run.sh"
set -u
FFMPEG=${FFMPEG:-/usr/bin/ffmpeg}
VOICE_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/whisper-to-input/piper
CONFIGS=(C0-live D1-carry S1-vadgap S2-novad S3-noctx P1-noprompt P2-trprompt L1-auto)
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
SR=$(python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["audio"]["sample_rate"])' "$VOICE_DIR/$VOICE.onnx.json") || exit 1

mkdir -p "$TMP/clips" "$TMP/parts"
"$FFMPEG" -v error -y -f lavfi -i "anullsrc=r=$SR:cl=mono" -t 1.0 "$TMP/sil1.wav" || exit 1
"$FFMPEG" -v error -y -f lavfi -i "anullsrc=r=$SR:cl=mono" -t 0.6 "$TMP/sil06.wav" || exit 1

join() { # name silence_wav wav... -> clip m4a (16 kHz mono like punctuation-ab.sh)
  local n=$1 sil=$2; shift 2
  : >"$TMP/$n.list"
  local first=1 w
  for w in "$@"; do
    [[ $first == 1 ]] || echo "file '$sil'" >>"$TMP/$n.list"
    echo "file '$w'" >>"$TMP/$n.list"; first=0
  done
  "$FFMPEG" -v error -y -f concat -safe 0 -i "$TMP/$n.list" -ar 16000 -ac 1 -c:a aac "$TMP/clips/$n.m4a" || exit 1
}

declare -A SET
NAMES=()
for t in "$HERE"/punct-corpus/*.txt; do
  n=$(basename "$t" .txt)
  piper "$VOICE" "$t" "$TMP/$n.wav" || { echo "tts failed for $n" >&2; exit 1; }
  "$FFMPEG" -v error -y -i "$TMP/$n.wav" -ar 16000 -ac 1 -c:a aac "$TMP/clips/$n.m4a" || exit 1
  cp "$t" "$TMP/clips/$n.txt"; NAMES+=("$n"); SET[$n]=short
done
# L1/L2: long dictations crossing Whisper's 30 s window.
for spec in "L1:01 02 03 04" "L2:05 06 07 08"; do
  n=${spec%%:*}; ids=(${spec#*:}); w=(); txt=""
  for i in "${ids[@]}"; do w+=("$TMP/$i.wav"); txt+="$(tr '\n' ' ' <"$HERE/punct-corpus/$i.txt" | sed -E 's/ +$//') "; done
  join "$n" "$TMP/sil1.wav" "${w[@]}"
  printf '%s\n' "${txt% }" >"$TMP/clips/$n.txt"; NAMES+=("$n"); SET[$n]=stress
done
# H*: split at ", " and re-join with a hesitation pause in the middle of the sentence.
for t in "$HERE"/punct-corpus/*.txt; do
  b=$(basename "$t" .txt); n=H$b; w=(); k=0
  while IFS= read -r piece; do
    [[ -n $piece ]] || continue
    k=$((k + 1)); printf '%s\n' "$piece" >"$TMP/parts/$n-$k.txt"
    piper "$VOICE" "$TMP/parts/$n-$k.txt" "$TMP/parts/$n-$k.wav" || { echo "tts failed for $n-$k" >&2; exit 1; }
    w+=("$TMP/parts/$n-$k.wav")
  done < <(tr '\n' ' ' <"$t" | sed -E 's/ +$//; s/, /\n/g')
  join "$n" "$TMP/sil06.wav" "${w[@]}"
  cp "$t" "$TMP/clips/$n.txt"; NAMES+=("$n"); SET[$n]=stress
done

"$BIN" -m "$MODEL" "${WHISPER_ARGS[@]}" --host 127.0.0.1 --port "$AB_PORT" >"$TMP/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 150); do
  curl -s -o /dev/null "http://127.0.0.1:$AB_PORT/" && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died:" >&2; tail -20 "$TMP/server.log" >&2; exit 1; }
  sleep 0.5
done

transcribe() { # clip config
  local f=("-F" "prompt=$PROMPT_LIVE" "-F" "language=tr")
  case $2 in
    D1-carry) f+=("-F" "carry_initial_prompt=true") ;;
    S1-vadgap) f+=("-F" "vad_min_silence_duration_ms=500" "-F" "vad_speech_pad_ms=200") ;;
    S2-novad) f+=("-F" "vad=false") ;;
    S3-noctx) f+=("-F" "max_context=0") ;;
    P1-noprompt) f=("-F" "prompt=" "-F" "language=tr") ;;
    P2-trprompt) f=("-F" "prompt=$PROMPT_PUNCT" "-F" "language=tr") ;;
    L1-auto) f=("-F" "prompt=$PROMPT_LIVE" "-F" "language=auto") ;;
  esac
  curl -s --max-time 180 -F "file=@$TMP/clips/$1.m4a" -F response_format=text "${f[@]}" \
    "http://127.0.0.1:$AB_PORT/inference"
}

transcribe "${NAMES[0]}" C0-live >/dev/null # warm-up, not measured
: >"$TMP/rows.tsv"
for cfg in "${RUN[@]}"; do
  for rep in $(seq 1 "$REPS"); do
    for n in "${NAMES[@]}"; do
      t0=$(date +%s%N)
      raw=$(transcribe "$n" "$cfg")
      ms=$(( ($(date +%s%N) - t0) / 1000000 ))
      flat=$(printf '%s' "$raw" | tr '\n\t' '  ' | sed -E 's/^ +//; s/ +$//')
      printf '%s\n' "$flat" >"$TMP/hyp.txt"
      sc=$(python3 -I - "$HERE" "$TMP/clips/$n.txt" "$TMP/hyp.txt" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from punct_score import score
s = score(open(sys.argv[2], encoding="utf-8").read(), open(sys.argv[3], encoding="utf-8").read())
print("%.3f\t%.3f\t%.3f" % (s["wer"], s["sent_f1"], s["punct_f1"]))
PY
) || { echo "scorer failed" >&2; exit 1; }
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "${SET[$n]}" "$cfg" "$rep" "$ms" "$sc" "$flat" | tee -a "$TMP/rows.tsv"
    done
  done
done

python3 -I - "$TMP/rows.tsv" <<'PY'
import statistics, sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1], encoding="utf-8")]
cfgs = list(dict.fromkeys(r[2] for r in rows))
S = {}
for c in cfgs:
    rs = [r for r in rows if r[2] == c]
    st = [r for r in rs if r[1] == "stress"]
    sh = [r for r in rs if r[1] == "short"]
    S[c] = (statistics.mean(float(r[5]) for r in st), statistics.mean(float(r[6]) for r in st),
            statistics.mean(float(r[5]) for r in sh), statistics.median(int(r[4]) for r in rs))
    print("SUMMARY\t%s\t%.3f\t%.3f\t%.3f\t%d" % ((c,) + S[c]))
PARAMS = {"S1-vadgap": 2, "S2-novad": 1, "S3-noctx": 1, "P1-noprompt": 1, "P2-trprompt": 1}
best = "none"
if "C0-live" in S:
    b = S["C0-live"]
    ok = [c for c in S if c in PARAMS
          and (S[c][0] <= b[0] - 0.02 or S[c][1] >= b[1] + 0.05)
          and S[c][0] <= b[0] + 0.01 and S[c][1] >= b[1] - 0.02
          and S[c][2] <= b[2] + 0.02 and S[c][3] <= 2 * b[3]]
    if ok:
        best = min(ok, key=lambda c: (S[c][0] - b[0], PARAMS[c]))
print("WINNER\t" + best)
PY
exit 0
