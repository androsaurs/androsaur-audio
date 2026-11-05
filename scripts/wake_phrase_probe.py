"""Compare wake phrases on the same local recordings without restricting Vosk's vocabulary.

uv run --with vosk --with numpy python scripts/wake_phrase_probe.py FOLDER \
    --model MODEL --phrase 'зюзя' --phrase 'зюзя|зюзин|зюзина' --report REPORT.json

FOLDER is a copy of files/wake-debug (index.json + WAV). Reports and family recordings
belong under ignored artifacts/, never in Git. A hit means a recording matched, not
that every repeated call inside that recording was detected. Include separate negative
recordings when evaluating false wakes. Grammar mode is intentionally not used.
"""

import argparse
import json
import wave
from pathlib import Path

import numpy as np
from vosk import KaldiRecognizer, Model, SetLogLevel

from wake_eval import FRAME, matches, parse_phrase


def probe(model, path, phrases, alternatives=1, max_margin=3.0):
    with wave.open(str(path)) as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise ValueError(f"Unsupported WAV format: {path.name}")
        pcm = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2")
    rec = KaldiRecognizer(model, 16000)
    if alternatives > 1:
        rec.SetMaxAlternatives(alternatives)
    # Finalize the last word, as trailing silence on the live microphone would.
    padded = np.concatenate([pcm, np.zeros(16000, dtype=np.int16)])
    hits = {}
    finals = []
    nbest = []

    def consume(result, final, at_s):
        if final:
            choices = result.get("alternatives", [{"text": result.get("text", "")}])
            if choices and choices[0].get("text"):
                finals.append(choices[0]["text"])
                nbest.append(choices)
        else:
            choices = [{"text": result.get("partial", "")}]
        top_score = choices[0].get("confidence", 0) if choices else 0
        for rank, choice in enumerate(choices):
            margin = top_score - choice.get("confidence", top_score)
            if margin > max_margin:
                continue
            for phrase, parsed in phrases.items():
                text = choice.get("text", "")
                if phrase not in hits and matches(parsed, text):
                    hits[phrase] = {"text": text, "at_s": round(at_s, 3), "rank": rank + 1, "score_margin": round(margin, 3)}

    for offset in range(0, len(padded), FRAME):
        final = rec.AcceptWaveform(padded[offset:offset + FRAME].tobytes())
        result = json.loads(rec.Result() if final else rec.PartialResult())
        consume(result, final, offset / 16000)
    consume(json.loads(rec.FinalResult()), True, len(padded) / 16000)
    return {"duration_s": round(len(pcm) / 16000, 3), "finals": finals, "hits": hits, "alternatives": nbest if alternatives > 1 else []}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("folder", type=Path)
    cli.add_argument("--model", type=Path, required=True)
    cli.add_argument("--phrase", action="append", required=True)
    cli.add_argument("--since-ms", type=int, default=0)
    cli.add_argument("--report", type=Path)
    cli.add_argument("--alternatives", type=int, choices=range(1, 21), default=1)
    cli.add_argument("--max-margin", type=float, default=3.0, help="Vosk relative sentence score gap, not a probability")
    args = cli.parse_args()
    SetLogLevel(-1)
    model = Model(str(args.model))
    phrases = {phrase: parse_phrase(phrase) for phrase in args.phrase}
    words = sorted({word for phrase in phrases.values() for variants in phrase for word, _ in variants})
    vocabulary = {word: model.vosk_model_find_word(word) for word in words}
    print("Vocabulary (-1 = cannot be emitted literally):", json.dumps(vocabulary, ensure_ascii=False), flush=True)
    index = json.loads((args.folder / "index.json").read_text())
    rows = []
    for item in index:
        if int(item["file"].split("-")[0]) < args.since_ms:
            continue
        path = args.folder / item["file"]
        if path.parent.resolve() != args.folder.resolve():
            raise ValueError("Invalid recording path")
        result = probe(model, path, phrases, args.alternatives, args.max_margin)
        row = {"file": item["file"], "on_phone": item.get("wake"), "peak_dbfs": item.get("peakDbfs"), **result}
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    totals = {phrase: sum(phrase in row["hits"] for row in rows) for phrase in phrases}
    report = {"vocabulary": vocabulary, "recordings": len(rows), "duration_s": sum(row["duration_s"] for row in rows), "matched_recordings": totals, "rows": rows}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print("Summary:", json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
