"""Проверка слова пробуждения на записях отладки, той же моделью Vosk, что на телефоне.

Записи берутся из режима отладки распознавания модуля звука (`files/wake-debug`: WAV каждой фразы
и index.json). Каждая запись прогоняется через Vosk при разных вариантах — усиление, фраза с
допусками, ограниченный словарь — и сопоставляется по правилам телефона (PhraseMatcher: целые
слова по порядку, у слова допуск ~N правок при той же первой букве; проверяются и промежуточные,
и законченные результаты, как в VoskEngine).

    uv run --with vosk python scripts/wake_eval.py <папка с wav и index.json> [--model <папка модели>]

Записи — голоса семьи: держать только локально, не в git.
"""

import argparse
import json
import sys
import wave
from pathlib import Path

import numpy as np
from vosk import KaldiRecognizer, Model, SetLogLevel

FRAME = 512


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[len(b)]


def parse_phrase(phrase: str) -> list[list[tuple[str, int]]]:
    words = []
    for token in phrase.split():
        variants = []
        for part in token.split("|"):
            text, _, tol = part.partition("~")
            variants.append((text.lower().replace("ё", "е"), int(tol or 0)))
        words.append(variants)
    return words


def matches(phrase: list[list[tuple[str, int]]], text: str) -> bool:
    heard = [w.lower().replace("ё", "е") for w in text.replace(",", " ").split()]
    for start in range(len(heard) - len(phrase) + 1):
        if all(
            any(
                h == v or (t > 0 and h and h[0] == v[0] and edit_distance(h, v) <= t)
                for v, t in phrase[k]
            )
            for k, h in ((k, heard[start + k]) for k in range(len(phrase)))
        ):
            return True
    return False


class AutoGain:
    """Как AutoGain на телефоне: огибающая пиков, цель 12000, усиление до 20 раз."""

    def __init__(self, max_gain: float = 20.0, decay: float = 0.97) -> None:
        self.envelope, self.max_gain, self.decay = 1.0, max_gain, decay

    def apply(self, frame: np.ndarray) -> np.ndarray:
        peak = float(np.abs(frame.astype(np.int32)).max())
        self.envelope = max(peak, self.envelope * self.decay, 1.0)
        gain = min(max(12000.0 / self.envelope, 1.0), self.max_gain)
        return np.clip(frame.astype(np.float64) * gain, -32768, 32767).astype(np.int16)


def run(model: Model, pcm: np.ndarray, phrase: str, grammar: bool, gain: bool) -> tuple[bool, str]:
    words = parse_phrase(phrase)
    if grammar:
        options = [[p.split("~")[0] for p in t.split("|")] for t in phrase.split()]
        phrases = [""]
        for opts in options:
            phrases = [f"{h} {o}".strip() for h in phrases for o in opts]
        rec = KaldiRecognizer(model, 16000, json.dumps(phrases + ["[unk]"], ensure_ascii=False))
    else:
        rec = KaldiRecognizer(model, 16000)
    amp = AutoGain() if gain else None
    heard = []
    # Тишина в конце, чтобы последняя фраза закончилась, как на телефоне.
    pcm = np.concatenate([pcm, np.zeros(16000, dtype=np.int16)])
    for at in range(0, len(pcm) - FRAME + 1, FRAME):
        frame = pcm[at : at + FRAME]
        if amp:
            frame = amp.apply(frame)
        final = rec.AcceptWaveform(frame.tobytes())
        text = json.loads(rec.Result() if final else rec.PartialResult()).get("text" if final else "partial", "")
        if final and text:
            heard.append(text)
        if matches(words, text):
            return True, text
    heard.append(json.loads(rec.FinalResult()).get("text", ""))
    return False, " | ".join(t for t in heard if t)


VARIANTS = [
    ("как сейчас", "железяка~1", False, False),
    ("+ усиление", "железяка~1", False, True),
    ("допуск 2", "железяка~2", False, False),
    ("формы: железяка|железяк|железка", "железяка~1|железяк|железка~1", False, False),
    ("ограниченный словарь", "железяка~1", True, False),
    ("словарь + усиление", "железяка~1", True, True),
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args()
    SetLogLevel(-1)
    model = Model(str(args.model))
    index = json.loads((args.folder / "index.json").read_text())
    print(f"{len(index)} фраз; источник/усиление записи — в колонке «запись»\n")
    totals = {name: 0 for name, *_ in VARIANTS}
    for item in index:
        with wave.open(str(args.folder / item["file"])) as w:
            pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        cells = []
        for name, phrase, grammar, gain in VARIANTS:
            ok, _ = run(model, pcm, phrase, grammar, gain)
            totals[name] += ok
            cells.append("✓" if ok else "·")
        rec = f"{item.get('source', '?')}{'+g' if item.get('wakeGain') else ''}"
        on_phone = "✓" if item.get("wake") else "·"
        print(f"{item['file'][:13]} {rec:22} {item.get('peakDbfs', 0):6.1f} дБ телефон {on_phone} | "
              + " ".join(cells) + f" | «{item.get('text', '')}»")
    print("\nсработало из", len(index))
    for name, *_ in VARIANTS:
        print(f"  {name:36} {totals[name]}")


if __name__ == "__main__":
    sys.exit(main())
