#!/usr/bin/env bash
# Repeatable before/after hallucination test against a temporary whisper-server.
# Usage: server/hallucination-ab.sh before|after
#   before = live flag set (with --carry-initial-prompt); after = server/whisper-stt-run.sh flags.
# Env: WHISPER_BIN WHISPER_MODEL WHISPER_VAD AB_PORT (default 8816; 8815 is refused).
# Prints: clip<TAB>mode<TAB>raw_text<TAB>verdict. Never touches unit whisper-stt / port 8815.
# Exit: 0 ok; 2 mode=after still hallucinates (server output alone); 1 harness error.
set -u
MODE=${1:-}
[[ $MODE == before || $MODE == after ]] || { echo "usage: $0 before|after" >&2; exit 1; }
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
AB_PORT=${AB_PORT:-8816}
[[ $AB_PORT != 8815 ]] || { echo "refusing AB_PORT=8815 (live service)" >&2; exit 1; }

# Defaults, PROMPT and the "after" argument list come from the proposed launcher.
WHISPER_RUN_SOURCED=1 source "$HERE/whisper-stt-run.sh"
set -u
if [[ $MODE == before ]]; then
  ARGS=(-dev 0 -l tr -nfa --convert --vad -vm "$VAD" --carry-initial-prompt --prompt "$PROMPT")
else
  ARGS=("${WHISPER_ARGS[@]}")
fi

JFK=${JFK_WAV:-$(dirname "$(dirname "$(dirname "$BIN")")")/samples/jfk.wav}
FFMPEG=${FFMPEG:-/usr/bin/ffmpeg}
TMP=$(mktemp -d)
SERVER_PID=""

cleanup() {
  if [[ -n $SERVER_PID ]]; then
    kill "$SERVER_PID" 2>/dev/null
    for _ in $(seq 1 50); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 0.2
    done
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  for _ in $(seq 1 50); do
    ss -ltn 2>/dev/null | grep -q ":$AB_PORT " || break
    sleep 0.2
  done
  rm -rf "$TMP"
}
trap cleanup EXIT

# Deterministic clips, 16 kHz mono m4a like the app's uploads.
gen() { "$FFMPEG" -v error -y "${@:2}" -ar 16000 -ac 1 -c:a aac "$TMP/$1.m4a"; }
gen silence-5s -f lavfi -i anullsrc=r=16000:cl=mono -t 5
gen noise-5s -f lavfi -i "anoisesrc=color=pink:amplitude=0.05:seed=42:r=16000" -t 5
gen short-0.4s -f lavfi -i anullsrc=r=16000:cl=mono -t 0.4
[[ -f $JFK ]] || { echo "missing speech sample $JFK" >&2; exit 1; }

"$BIN" -m "$MODEL" "${ARGS[@]}" --host 127.0.0.1 --port "$AB_PORT" >"$TMP/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 150); do
  curl -s -o /dev/null "http://127.0.0.1:$AB_PORT/" && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died:" >&2; tail -20 "$TMP/server.log" >&2; exit 1; }
  sleep 0.5
done

transcribe() { # file [extra curl args]
  curl -s --max-time 120 -F "file=@$1" -F response_format=text "${@:2}" "http://127.0.0.1:$AB_PORT/inference"
}

HALLU=0
for clip in silence-5s noise-5s short-0.4s jfk-speech; do
  if [[ $clip == jfk-speech ]]; then
    raw=$(transcribe "$JFK" -F language=en)
  else
    raw=$(transcribe "$TMP/$clip.m4a")
  fi
  flat=$(printf '%s' "$raw" | tr '\n\t' '  ' | sed -E 's/^ +//; s/ +$//')
  if [[ $clip == jfk-speech ]]; then
    if grep -qi 'country' <<<"$flat"; then verdict=SPEECH_OK; else verdict=SPEECH_BROKEN; fi
  else
    stripped=$(sed -E 's/\[BLANK_AUDIO\]//g' <<<"$flat" | tr -d '[:space:]')
    if [[ -z $stripped ]]; then verdict=OK_EMPTY; else verdict=HALLUCINATION; HALLU=1; fi
  fi
  printf '%s\t%s\t%s\t%s\n' "$clip" "$MODE" "$flat" "$verdict"
  [[ $verdict == SPEECH_BROKEN && $MODE == after ]] && HALLU=1
done

if [[ $MODE == after && $HALLU -ne 0 ]]; then exit 2; fi
exit 0
