"""Offline experiment: test a Sherpa KWS model on local wake-debug recordings.

This does not change the phone. Use a model for the target language; phonetic
spellings with an English model and child voices require separate measurements.
"""

import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
import sentencepiece as spm
import sherpa_onnx


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("folder", type=Path)
    cli.add_argument("--model", type=Path, required=True)
    cli.add_argument("--since-ms", type=int, default=0)
    cli.add_argument("--keyword", action="append", required=True)
    cli.add_argument("--threshold", type=float, default=0.25)
    cli.add_argument("--score", type=float, default=1.0)
    cli.add_argument("--paths", type=int, default=4)
    cli.add_argument("--report", type=Path, required=True)
    args = cli.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    sp = spm.SentencePieceProcessor(model_file=str(args.model / "bpe.model"))
    keywords = {k: sp.encode(k.upper() if k.isascii() else k.lower(), out_type=str) for k in args.keyword}
    known = {line.rsplit(" ", 1)[0] for line in (args.model / "tokens.txt").read_text().splitlines()}
    if any(token not in known for tokens in keywords.values() for token in tokens):
        raise ValueError("A keyword contains tokens unsupported by this model")
    keyword_file = args.report.with_suffix(".keywords.txt")
    keyword_file.write_text("\n".join(" ".join(tokens) + " @" + k for k, tokens in keywords.items()) + "\n")
    kws = sherpa_onnx.KeywordSpotter(
        tokens=str(args.model / "tokens.txt"),
        encoder=str(next(args.model.glob("encoder*.int8.onnx"))),
        decoder=str(next(args.model.glob("decoder*.onnx"))),
        joiner=str(next(args.model.glob("joiner*.int8.onnx"))),
        keywords_file=str(keyword_file),
        num_threads=2,
        keywords_score=args.score,
        keywords_threshold=args.threshold,
        max_active_paths=args.paths,
    )
    rows = []
    for item in json.loads((args.folder / "index.json").read_text()):
        if int(item["file"].split("-")[0]) < args.since_ms:
            continue
        path = args.folder / item["file"]
        if path.parent.resolve() != args.folder.resolve():
            raise ValueError("Invalid recording path")
        with wave.open(str(path)) as f:
            assert (f.getnchannels(), f.getsampwidth(), f.getframerate()) == (1, 2, 16000)
            pcm = np.frombuffer(f.readframes(f.getnframes()), dtype="<i2").astype(np.float32) / 32768
        samples = np.concatenate([pcm, np.zeros(16000, dtype=np.float32)])
        stream = kws.create_stream()
        hits = []
        begin = time.perf_counter()
        for offset in range(0, len(samples), 512):
            stream.accept_waveform(16000, samples[offset:offset + 512])
            while kws.is_ready(stream):
                kws.decode_stream(stream)
                result = kws.get_result(stream)
                if result:
                    hits.append({"keyword": result, "at_s": round(offset / 16000, 3)})
                    kws.reset_stream(stream)
        elapsed = time.perf_counter() - begin
        row = {"file": item["file"], "duration_s": round(len(pcm) / 16000, 3), "compute_s": round(elapsed, 3), "hits": hits}
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    report = {"keywords": keywords, "threshold": args.threshold, "score": args.score, "recordings": len(rows), "matched_recordings": sum(bool(r["hits"]) for r in rows), "duration_s": sum(r["duration_s"] for r in rows), "compute_s": sum(r["compute_s"] for r in rows), "rows": rows}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print("Summary:", json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
