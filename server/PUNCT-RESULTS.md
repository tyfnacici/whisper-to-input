# Punctuation A/B results

- Date: 2026-11-11
- Model: `ggml-large-v3-turbo-q8_0` (server flags from `whisper-stt-run.sh`: `-sns -nth 0.8`, no `--carry-initial-prompt`)
- Voice: piper `tr_TR-dfki-medium` (synthetic Turkish TTS of `server/punct-corpus/*.txt`, 8 clips, all local)
- Command: `server/punctuation-ab.sh all`

## Configs

| Config | Fields | mean WER | mean F1 | density /100w | median ms |
|---|---|---|---|---|---|
| C0-live | `prompt=$PROMPT_LIVE` | 0.071 | 0.800 | 20.7 | 378 |
| C1-trprompt | `prompt=$PROMPT_PUNCT` | 0.083 | 0.817 | 21.1 | 378 |
| C2-trprompt-bs5 | `prompt=$PROMPT_PUNCT`, `beam_size=5` | 0.076 | 0.817 | 21.2 | 513 |
| C3-live-bs5 | `prompt=$PROMPT_LIVE`, `beam_size=5` | 0.063 | 0.786 | 20.1 | 497 |

WINNER: none (rule: F1 >= C0 + 0.05, WER <= C0 + 0.02, median ms <= 2x C0).

## C0 vs best candidate (C1-trprompt), synthetic texts only

| Clip | C0-live | C1-trprompt |
|---|---|---|
| 01 | Bugün yeni bir branca açtım. Sonra değişiklikleri kontrol ettim ama hata hala duruyor. Sence sorun nerede olabilir? | Bugün yeni bir branca açtım. Sonra değişiklikleri kontrol ettim ama hata hala duruyor. Sence sorun nerede olabilir? |
| 02 | Deploy işlemi bitti fakat servis yanıt vermiyor. Kubernetes üzerindeki pobları yeniden başlattım. Bu sefer çalışacak mı? | Deploy işlemi bitti fakat servis yanıt vermiyor. Kubernetes üzerindeki podları yeniden başlattım. Bu sefer çalışacak mı? |
| 03 | Supabase tarafında yeni bir tablo oluşturdum. İzinleri ayarladım, sonra test verisi ekledim. Her şey yolunda görünüyor. Yarın yayına alacağım. | Supo base tarafında yeni bir tablo oluşturdum. İzinleri ayarladım, sonra test verisi ekledim. Her şey yolunda görünüyor. Yarın yayına alacağım. |
| 04 | PR inceledin mi? Yorumları okudum ama birkaç noktada emin değilim. Yarın toplantıda konuşalım. | PR inceledin mi? Yorumları okudum ama birkaç noktada emin değilim. Yarın toplantıda konuşalım. |
| 05 | İstanbul'a gitmeden önce bilet almam gerekiyor. Akşam trenle mi yoksa uçakla mı gideceğiz? Hava durumuna bakıp karar veririz. Sen ne dersin? | İstanbul'a gitmeden önce bilet almam gerekiyor. Akşam trenle mi yoksa uçakla mı gideceğiz? Hava durumuna bakıp karar veririz. Sen ne dersin? |
| 06 | Sunucu yeniden başladı ancak yok dosyası boş kaldı. Disk dolu muydu? Önce yer açtım sonra servisi tekrar çalıştırdım. Şimdi sorun yok. | Sunucu yeniden başladı ancak yok dosyası boş kaldı. Disk dolu muydu? Önce yer açtım sonra servisi tekrar çalıştırdım. Şimdi sorun yok. |
| 07 | Toplantı saat 3'te başlayacak. Sunumu hazırladım ama grafikler eksik. Kim bu verileri güncelleyebilir? Ben akşama kadar bitiririm. Merak etme. | Toplantı saat 3'te başlayacak. Sunumu hazırladım ama grafikler eksik. Kim bu verileri güncelleyebilir? Ben akşama kadar bitiririm. Merak etme. |
| 08 | Önce Bancı'yı güncelleyelim sonra Pere birleştirelim. Depto için ona gerekiyor mu? Gerekirse yöneticiye yazarım çünkü acil bir durum var. | Önce bu anciyi güncelleyelim, sonra pere birleştirelim. Deptoy için ona gerekiyor mu? Gerekirse yöneticiye yazarım çünkü acil bir durum var. |

## Findings

- On clean TTS speech the live prompt already reaches F1 0.80: sentence marks (`.`, `?`) are mostly right; what is missed are mid-sentence commas.
- A Turkish, punctuated prompt moves F1 by only +0.017 (C1) and beam 5 adds latency (~1.35x) without a gain; nothing reaches the +0.05 bar, so the launcher is unchanged (`PROMPT` stays `PROMPT_LIVE`; `PROMPT_PUNCT` is kept for further experiments).
- Differences between runs of C0 in WER (0.071 vs 0.108) come from whisper sampling/spacing noise; F1 reproduced at 0.800.
- TTS prosody is cleaner than real speech, so this likely overstates the baseline. The owner can measure real recordings with `PUNCT_CLIPS_DIR=<dir with name.wav + name.txt> server/punctuation-ab.sh all` (output to stdout only). A bigger model is a separate card.

RESULT: no qualifying improvement
