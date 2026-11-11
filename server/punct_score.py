#!/usr/bin/env python3
"""Punctuation / WER scorer for the punctuation A/B (stdlib only).

Usage: python3 server/punct_score.py REF_FILE HYP_FILE
Prints: wer=<.3f> p=<.3f> r=<.3f> f1=<.3f> density=<.1f>
"""
import difflib
import re
import sys

MARKS = ".,?!:;"
_STRIP = re.compile(r"[.,?!:;\"“”()\[\]]")


def tr_fold(s: str) -> str:
    """Turkish casefold: İ->i, I->ı, then lower()."""
    return s.replace("İ", "i").replace("I", "ı").lower()


def _mark_class(tok: str) -> str:
    for ch in reversed(tok):
        if ch not in MARKS:
            if ch in "\"”)]’'":
                continue
            break
        if ch == "?":
            return "QUESTION"
        if ch == ",":
            return "COMMA"
        return "STOP"
    return "NONE"


def tokens(text: str) -> list:
    """(normalised word, mark class) per whitespace-separated word."""
    out = []
    for raw in text.split():
        word = tr_fold(_STRIP.sub("", raw.replace("’", "'")))
        if not word:
            continue
        out.append((word, _mark_class(raw)))
    return out


def _edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def _pairs(rw: list, hw: list):
    """Index pairs of aligned words: equal runs, plus equal-length substitutions."""
    sm = difflib.SequenceMatcher(None, rw, hw, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal" or (tag == "replace" and i2 - i1 == j2 - j1):
            for k in range(i2 - i1):
                yield i1 + k, j1 + k


def score(ref: str, hyp: str) -> dict:
    rt, ht = tokens(ref), tokens(hyp)
    rw, hw = [w for w, _ in rt], [w for w, _ in ht]
    ref_marks = sum(1 for _, c in rt if c != "NONE")
    hyp_marks = sum(1 for _, c in ht if c != "NONE")
    tp = sum(1 for i, j in _pairs(rw, hw) if rt[i][1] != "NONE" and rt[i][1] == ht[j][1])
    p = tp / hyp_marks if hyp_marks else 0.0
    r = tp / ref_marks if ref_marks else 0.0
    f1 = 2 * p * r / (p + r) if p + r else 0.0
    wer = _edit_distance(rw, hw) / len(rw) if rw else 0.0
    density = 100.0 * hyp_marks / len(hw) if hw else 0.0
    return {"wer": wer, "punct_p": p, "punct_r": r, "punct_f1": f1,
            "density": density, "ref_marks": ref_marks, "hyp_marks": hyp_marks}


def main(argv: list) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 1
    with open(argv[1], encoding="utf-8") as f:
        ref = f.read()
    with open(argv[2], encoding="utf-8") as f:
        hyp = f.read()
    s = score(ref, hyp)
    print("wer=%.3f p=%.3f r=%.3f f1=%.3f density=%.1f"
          % (s["wer"], s["punct_p"], s["punct_r"], s["punct_f1"], s["density"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
