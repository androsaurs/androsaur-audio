"""Download public-domain Russian speech and make an isolated wake-debug control set.

uv run --with miniaudio python scripts/prepare_wake_control.py artifacts/wake/control
Source: LibriVox / Internet Archive, Goncharov's «Обрыв», public-domain mark.
No family recordings are sent anywhere. Generated files belong outside Git.
"""

import argparse
import json
import urllib.request
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import miniaudio

ITEM = "obryv_1906_librivox"


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("folder", type=Path)
    cli.add_argument("--minutes", type=int, default=60)
    args = cli.parse_args()
    if not 1 <= args.minutes <= 120:
        raise ValueError("Expected 1–120 minutes")
    args.folder.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(f"https://archive.org/metadata/{ITEM}", timeout=30) as r:
        metadata = json.load(r)
    candidates = sorted(
        (f for f in metadata["files"] if f["name"].endswith("_64kb.mp3")),
        key=lambda f: f["name"],
    )
    selected, duration = [], 0
    for f in candidates:
        selected.append(f)
        fields = [int(n) for n in f["length"].split(":")]
        seconds = 0
        for n in fields:
            seconds = seconds * 60 + n
        duration += seconds
        if duration >= args.minutes * 60:
            break

    def download(f):
        path = args.folder / f["name"]
        if path.exists() and path.stat().st_size == int(f["size"]):
            return path
        print("Download", f["name"], flush=True)
        part = path.with_suffix(".part")
        url = f"https://archive.org/download/{ITEM}/{f['name']}"
        with urllib.request.urlopen(url, timeout=60) as response, part.open("wb") as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        if part.stat().st_size != int(f["size"]):
            raise ValueError("Incomplete download")
        part.replace(path)
        return path

    with ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(download, selected))
    remaining = args.minutes * 60 * 16000
    index = []
    for path in paths:
        decoded = miniaudio.decode_file(
            str(path), output_format=miniaudio.SampleFormat.SIGNED16,
            nchannels=1, sample_rate=16000,
        )
        samples = decoded.samples
        for start in range(0, len(samples), 60 * 16000):
            count = min(60 * 16000, len(samples) - start, remaining)
            if count <= 0:
                break
            name = f"{len(index) + 1:06}-control.wav"
            with wave.open(str(args.folder / name), "wb") as out:
                out.setnchannels(1)
                out.setsampwidth(2)
                out.setframerate(16000)
                out.writeframes(samples[start:start + count].tobytes())
            index.append({"file": name, "source": path.name, "start_s": start / 16000, "duration_s": count / 16000, "wake": False})
            remaining -= count
        if remaining == 0:
            break
    if remaining:
        raise ValueError("Not enough source speech")
    (args.folder / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2))
    provenance = {"url": f"https://archive.org/details/{ITEM}", "license": metadata["metadata"].get("licenseurl"), "duration_s": args.minutes * 60, "files": selected}
    (args.folder / "source.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2))
    print(f"Prepared {args.minutes} minutes in {len(index)} WAV files", flush=True)


if __name__ == "__main__":
    main()
