import unittest

from calibrate import choose_threshold, evaluate, validate_embeddings


class CalibrationTest(unittest.TestCase):
    def test_chooses_threshold_that_separates_answerable_from_rejected(self):
        rows = [
            {"type": "answerable", "expected": "kb1", "candidates": [{"id": "kb1", "score": 0.86}]},
            {"type": "answerable", "expected": "kb2", "candidates": [{"id": "kb2", "score": 0.78}]},
            {"type": "unanswerable", "expected": "", "candidates": [{"id": "kb1", "score": 0.62}]},
            {"type": "ambiguous", "expected": "", "candidates": [{"id": "kb2", "score": 0.70}]},
        ]

        result = choose_threshold(rows)

        self.assertGreater(result["threshold"], 0.70)
        self.assertLessEqual(result["threshold"], 0.78)
        self.assertEqual(1.0, result["f1"])

    def test_expected_candidate_in_top_five_counts_as_true_positive(self):
        metrics = evaluate([{
            "type": "answerable",
            "expected": "kb1",
            "candidates": [
                {"id": "kb2", "score": 0.90},
                {"id": "kb1", "score": 0.82},
            ],
        }], 0.70)

        self.assertEqual(1, metrics["gate_true_positive"])
        self.assertEqual(1, metrics["retrieval_hit"])
        self.assertEqual(0, metrics["retrieval_miss"])

    def test_accepted_answerable_with_wrong_candidate_separates_gate_and_retrieval(self):
        metrics = evaluate([{
            "type": "answerable",
            "expected": "kb1",
            "candidates": [{"id": "kb2", "score": 0.90}],
        }], 0.70)

        self.assertEqual(1, metrics["gate_true_positive"])
        self.assertEqual(0, metrics["gate_false_positive"])
        self.assertEqual(0, metrics["retrieval_hit"])
        self.assertEqual(1, metrics["retrieval_miss"])
        self.assertEqual(1.0, metrics["precision"])
        self.assertEqual(0.0, metrics["recall"])
        self.assertEqual(0.0, metrics["retrieval_recall"])
        self.assertEqual(0.0, metrics["f1"])

    def test_unanswerable_above_threshold_is_false_positive(self):
        metrics = evaluate([{
            "type": "unanswerable",
            "expected": "",
            "candidates": [{"id": "kb1", "score": 0.90}],
        }], 0.70)

        self.assertEqual(1, metrics["gate_false_positive"])
        self.assertEqual(0, metrics["gate_true_negative"])

    def test_embedding_response_requires_complete_unique_indices(self):
        payload = {
            "data": [
                {"index": 0, "embedding": [0.0] * 1536},
                {"index": 0, "embedding": [0.0] * 1536},
            ]
        }

        with self.assertRaises(ValueError):
            validate_embeddings(payload, expected_count=2)


if __name__ == "__main__":
    unittest.main()
