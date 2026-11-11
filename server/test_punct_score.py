import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from punct_score import score, tokens, tr_fold  # noqa: E402

REF = "Bugün branch açtım, ama hata duruyor. Sence sorun nerede?"


class PunctScoreTest(unittest.TestCase):
    def test_identical(self):
        s = score(REF, REF)
        self.assertEqual(s["punct_f1"], 1.0)
        self.assertEqual(s["wer"], 0.0)

    def test_no_punctuation(self):
        hyp = REF.replace(",", "").replace(".", "").replace("?", "")
        s = score(REF, hyp)
        self.assertEqual(s["punct_f1"], 0.0)
        self.assertEqual(s["wer"], 0.0)
        self.assertEqual(s["density"], 0.0)

    def test_question_vs_stop_mismatch(self):
        s = score("Nerede?", "Nerede.")
        self.assertEqual(s["punct_f1"], 0.0)
        self.assertEqual(s["wer"], 0.0)

    def test_turkish_casefold(self):
        self.assertEqual(tr_fold("İstanbul"), "istanbul")
        self.assertEqual(tr_fold("IŞIK"), "ışık")
        self.assertEqual(score("İstanbul IŞIK.", "istanbul ışık.")["wer"], 0.0)
        self.assertEqual(score("İstanbul IŞIK.", "istanbul ışık.")["punct_f1"], 1.0)

    def test_tokens_classes(self):
        self.assertEqual(
            tokens("a, b. c? d! e"),
            [("a", "COMMA"), ("b", "STOP"), ("c", "QUESTION"), ("d", "STOP"), ("e", "NONE")],
        )

    def test_missing_word_keeps_pairs(self):
        ref = "bir iki üç, dört beş."
        hyp = "bir üç, dört beş."
        s = score(ref, hyp)
        self.assertAlmostEqual(s["wer"], 1 / 5)
        self.assertEqual(s["punct_f1"], 1.0)

    def test_sentence_boundary_ignores_commas(self):
        hyp = "Bugün branch açtım ama hata duruyor. Sence sorun nerede?"
        self.assertEqual(score(REF, hyp)["sent_f1"], 1.0)

    def test_sentence_boundary_question_equals_stop(self):
        self.assertEqual(score("Nerede?", "Nerede.")["sent_f1"], 1.0)

    def test_run_on(self):
        hyp = "Bugün branch açtım ama hata duruyor sence sorun nerede"
        self.assertEqual(score(REF, hyp)["sent_f1"], 0.0)


if __name__ == "__main__":
    unittest.main()
