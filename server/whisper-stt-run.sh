#!/usr/bin/env bash
# PROPOSED replacement for ~/.hermes/scripts/whisper-stt-run.sh (not applied by this repo).
#
# Diff against the live script (everything else is identical, incl. model, VAD, prompt, port 8815):
#   + -sns        (--suppress-nst)      suppress non-speech tokens such as [MUSIC], (music)
#   + -nth 0.8    (--no-speech-thold)   drop segments whose no-speech probability exceeds 0.8
#   - --carry-initial-prompt            removed: re-injecting the term prompt into every
#                                       window is a known trigger for text invented over
#                                       silence. `--prompt` itself stays (term hints).
#
# PROMPT_LIVE is the live English term prompt; PROMPT_PUNCT is a punctuated Turkish candidate that
# server/punctuation-ab.sh measured (no qualifying gain, see PUNCT-RESULTS.md), so PROMPT=PROMPT_LIVE.
#
# The argument list lives in WHISPER_ARGS so server/hallucination-ab.sh can source this
# file (WHISPER_RUN_SOURCED=1) and test exactly the same flags without starting the server.
set -u
BIN=${WHISPER_BIN:-/mnt/SN8100/whisper-test/whisper.cpp/build/bin/whisper-server}
MODEL=${WHISPER_MODEL:-/mnt/SN8100/whisper-test/whisper.cpp/models/ggml-large-v3-turbo-q8_0.bin}
VAD=${WHISPER_VAD:-/mnt/SN8100/whisper-test/whisper.cpp/models/ggml-silero-v5.1.2.bin}
PROMPT_LIVE="The conversation may mention these terms: Polymarket, WooCommerce, WordPress, Obsidian, Vikunja, Supabase, Kubernetes, GitHub, GitLab, SonarQube, Hermes, Whisper, Gemma, LLM, machine learning, production, pipeline, Bahista, Bilyoner, Forecasting, MCP, Expo, Pi agent, Luna, subagent, kanban, Qwen, cron job, CI, fail, debug, fix, deploy, merge, branch, commit, push, PR, review, docker, docker-compose, environment variable, TTS, STT, GPU, VRAM, server, service, systemd, router, gateway, relay, plugin, hook, Telegram, token, context, model, quantization, Claude, Claude Code, Opus, Sonnet, GLM, coding agent, CLI, SearXNG, Firecrawl, Local Deep Research."
PROMPT_PUNCT="Merhaba, bugün Polymarket, WooCommerce ve WordPress üzerinde çalışıyoruz. Notlarımı Obsidian'a, görevleri Vikunja'ya yazdım. Supabase ve Kubernetes tarafında her şey yolunda mı? GitHub, GitLab ve SonarQube için bir pipeline kurduk. Hermes, Whisper ve Gemma gibi LLM modellerini, yani machine learning araçlarını production ortamında deniyoruz. Bahista, Bilyoner ve Forecasting projeleri için MCP ile Expo kullanıyorum. Pi agent, Luna ve subagent'lar kanban panosunda çalışıyor. Qwen modelini cron job ile çalıştırınca CI fail oldu, o yüzden debug yapıp fix gönderdim. Deploy etmeden önce merge edelim mi? Branch'i commit edip push yaptım, PR'ı da review bekliyor. Docker ve docker-compose kullanıyoruz, environment variable'ları ayarladım. TTS ve STT için GPU ve VRAM yeterli mi? Server ve service systemd ile çalışıyor; router, gateway ve relay hazır. Plugin ve hook ekledim, Telegram token'ı ve context ayarlandı. Model quantization sonrası Claude, Claude Code, Opus ve Sonnet'i, ayrıca GLM coding agent ve CLI araçlarını denedim. SearXNG, Firecrawl ve Local Deep Research da kurulu."
PROMPT="$PROMPT_LIVE"

# -nfa: flash attention + VAD loops on long clips in this build (see live script history).
WHISPER_ARGS=(-dev 0 -l tr -nfa --convert --vad -vm "$VAD" -sns -nth 0.8 --prompt "$PROMPT")

if [[ -z ${WHISPER_RUN_SOURCED:-} ]]; then
  exec "$BIN" -m "$MODEL" "${WHISPER_ARGS[@]}" --host 127.0.0.1 --port 8815
fi
