#!/usr/bin/env python3
"""Calibra el gate RAG con embeddings reales de OpenAI."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path



def evaluate(rows: list[dict], threshold: float) -> dict:
    counts: dict[str, int | float] = {
        "gate_true_positive": 0,
        "gate_true_negative": 0,
        "gate_false_positive": 0,
        "gate_false_negative": 0,
        "retrieval_hit": 0,
        "retrieval_miss": 0,
    }
    for row in rows:
        answerable = row["type"] == "answerable"
        accepted_candidates = [
            candidate for candidate in row["candidates"]
            if candidate["score"] >= threshold
        ]
        accepted = bool(accepted_candidates)

        if answerable:
            if accepted:
                counts["gate_true_positive"] += 1
            else:
                counts["gate_false_negative"] += 1

            expected_retrieved = any(
                candidate["id"] == row["expected"]
                for candidate in accepted_candidates
            )
            if expected_retrieved:
                counts["retrieval_hit"] += 1
            else:
                counts["retrieval_miss"] += 1
        elif accepted:
            counts["gate_false_positive"] += 1
        else:
            counts["gate_true_negative"] += 1

    gate_tp = counts["gate_true_positive"]
    gate_tn = counts["gate_true_negative"]
    gate_fp = counts["gate_false_positive"]
    gate_fn = counts["gate_false_negative"]
    retrieval_hit = counts["retrieval_hit"]
    retrieval_miss = counts["retrieval_miss"]
    precision = gate_tp / (gate_tp + gate_fp) if gate_tp + gate_fp else 0.0
    gate_recall = gate_tp / (gate_tp + gate_fn) if gate_tp + gate_fn else 0.0
    retrieval_recall = (
        retrieval_hit / (retrieval_hit + retrieval_miss)
        if retrieval_hit + retrieval_miss else 0.0
    )
    f1 = (
        2 * precision * retrieval_recall / (precision + retrieval_recall)
        if precision + retrieval_recall else 0.0
    )
    total = len(rows)
    gate_correct = gate_tp + gate_tn
    counts.update({
        "precision": round(precision, 6),
        "recall": round(retrieval_recall, 6),
        "gate_recall": round(gate_recall, 6),
        "retrieval_recall": round(retrieval_recall, 6),
        "f1": round(f1, 6),
        "accuracy": round(gate_correct / total, 6) if total else 0.0,
    })
    return counts


def choose_threshold(rows: list[dict], initial: float = 0.70) -> dict:
    if not rows:
        raise ValueError("No hay filas evaluables")
    thresholds = sorted({
        0.0,
        1.0,
        *(float(candidate["score"]) for row in rows for candidate in row["candidates"]),
    })
    candidates = []
    for threshold in thresholds:
        metrics = evaluate(rows, threshold)
        candidates.append({"threshold": round(threshold, 6), **metrics})
    return max(
        candidates,
        key=lambda item: (item["f1"], item["accuracy"], -abs(item["threshold"] - initial)),
    )


def cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return dot / (left_norm * right_norm)


def validate_embeddings(payload: dict, expected_count: int, expected_dimensions: int = 1536) -> list[list[float]]:
    data = payload.get("data")
    if not isinstance(data, list) or len(data) != expected_count:
        raise ValueError("La respuesta de embeddings no coincide con la cardinalidad solicitada")

    by_index = {}
    for item in data:
        if not isinstance(item, dict) or not isinstance(item.get("index"), int):
            raise ValueError("La respuesta de embeddings contiene un índice inválido")
        index = item["index"]
        if index in by_index or index < 0 or index >= expected_count:
            raise ValueError("La respuesta de embeddings contiene índices duplicados o fuera de rango")
        vector = item.get("embedding")
        if not isinstance(vector, list) or len(vector) != expected_dimensions:
            raise ValueError("La respuesta contiene un embedding con dimensión inválida")
        if any(
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(float(value))
            for value in vector
        ):
            raise ValueError("La respuesta contiene valores de embedding inválidos")
        by_index[index] = [float(value) for value in vector]

    if set(by_index) != set(range(expected_count)):
        raise ValueError("La respuesta de embeddings no contiene todos los índices solicitados")
    return [by_index[index] for index in range(expected_count)]


def request_embeddings(api_key: str, model: str, texts: list[str]) -> list[list[float]]:
    request = urllib.request.Request(
        "https://api.openai.com/v1/embeddings",
        data=json.dumps({"model": model, "input": texts, "encoding_format": "float"}).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"OpenAI devolvió HTTP {error.code}: {detail}") from error
    return validate_embeddings(payload, expected_count=len(texts))


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def run(dataset: Path, api_key: str, model: str) -> dict:
    knowledge = read_csv(dataset / "knowledge.csv")
    questions = [row for row in read_csv(dataset / "questions.csv") if row["type"] != "follow_up"]
    knowledge_vectors = request_embeddings(api_key, model, [row["question"] for row in knowledge])
    question_vectors = request_embeddings(api_key, model, [row["question"] for row in questions])

    scored = []
    for question, vector in zip(questions, question_vectors):
        ranked = sorted(
            ((cosine(vector, candidate), entry["external_id"]) for entry, candidate in zip(knowledge, knowledge_vectors)),
            reverse=True,
        )[:5]
        scored.append({
            "id": question["id"],
            "type": question["type"],
            "expected": question["expected_knowledge_id"],
            "candidates": [
                {"id": candidate_id, "score": round(score, 6), "rank": rank}
                for rank, (score, candidate_id) in enumerate(ranked, start=1)
            ],
        })

    selected = choose_threshold(scored)
    initial = {"threshold": 0.70, **evaluate(scored, 0.70)}
    return {
        "dataset": dataset.name,
        "model": model,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "evaluated_questions": len(scored),
        "skipped_follow_up": sum(1 for row in read_csv(dataset / "questions.csv") if row["type"] == "follow_up"),
        "initial": initial,
        "selected": selected,
        "questions": scored,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "datasets" / "v1")
    parser.add_argument("--model", default="text-embedding-3-small")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        parser.error("Define OPENAI_API_KEY para ejecutar embeddings reales")

    report = run(args.dataset, api_key, args.model)
    output = args.output or Path(__file__).parent / "reports" / f"{args.dataset.name}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
