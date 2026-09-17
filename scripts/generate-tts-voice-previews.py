#!/usr/bin/env python3
"""Generate the bundled EchoFlow voice-preview WAV pack.

Each catalog line is sent as one independent request and forced below the
server's internal chunking threshold. The resulting files are Android assets;
the app never needs a network connection to audition a voice.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import urllib.error
import urllib.request
import wave
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/com/echoflow/data/TtsVoicePreviews.kt"
OUTPUT = ROOT / "app/src/main/assets/tts_voice_previews"


@dataclass(frozen=True)
class Preview:
    voice: str
    language: str
    number: int
    text: str

    @property
    def filename(self) -> str:
        return f"{self.voice.lower()}_{self.language}_{self.number}.wav"


def parse_catalog() -> list[Preview]:
    source = CATALOG.read_text(encoding="utf-8")
    block_pattern = re.compile(
        r'TtsPreviewVoice\("(?P<voice>[MF]\d)",\s*"[^"]+",\s*listOf\((?P<en>.*?)\),\s*listOf\((?P<bg>.*?)\)\)',
        re.DOTALL,
    )
    string_pattern = re.compile(r'"((?:[^"\\]|\\.)*)"')
    previews: list[Preview] = []
    for block in block_pattern.finditer(source):
        voice = block.group("voice")
        for language in ("en", "bg"):
            encoded = string_pattern.findall(block.group(language))
            lines = [json.loads(f'"{value}"') for value in encoded]
            if len(lines) != 5:
                raise ValueError(f"Expected 5 {language} lines for {voice}, found {len(lines)}")
            previews.extend(
                Preview(voice, language, number, text)
                for number, text in enumerate(lines, start=1)
            )
    if len(previews) != 100:
        raise ValueError(f"Expected 100 previews, found {len(previews)}")
    return previews


def validate_wav(data: bytes) -> tuple[int, int, int, float]:
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("response is not a RIFF/WAVE file")
    with tempfile.NamedTemporaryFile(suffix=".wav") as temp:
        temp.write(data)
        temp.flush()
        with wave.open(temp.name, "rb") as wav:
            channels = wav.getnchannels()
            sample_rate = wav.getframerate()
            sample_width = wav.getsampwidth()
            duration = wav.getnframes() / sample_rate
    if channels != 1 or sample_width != 2 or sample_rate != 44_100:
        raise ValueError(
            f"unexpected WAV format: channels={channels}, width={sample_width}, rate={sample_rate}"
        )
    return channels, sample_rate, sample_width, duration


def synthesize(preview: Preview, base_url: str, steps: int, force: bool) -> dict[str, object]:
    destination = OUTPUT / preview.filename
    if destination.exists() and not force:
        data = destination.read_bytes()
        _, sample_rate, _, duration = validate_wav(data)
        return {
            "file": preview.filename,
            "voice": preview.voice,
            "language": preview.language,
            "number": preview.number,
            "text": preview.text,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "sample_rate": sample_rate,
            "duration_seconds": round(duration, 6),
        }

    payload = json.dumps(
        {
            "text": preview.text,
            "voice": preview.voice,
            "lang": preview.language,
            "speed": 1.0,
            "steps": steps,
            "max_chunk_length": 10_000,
            "silence_duration": 0.0,
            "response_format": "wav",
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/tts",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{preview.filename}: HTTP {error.code}: {detail}") from error

    _, sample_rate, _, duration = validate_wav(data)
    temporary = destination.with_suffix(".wav.partial")
    temporary.write_bytes(data)
    os.replace(temporary, destination)
    return {
        "file": preview.filename,
        "voice": preview.voice,
        "language": preview.language,
        "number": preview.number,
        "text": preview.text,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "sample_rate": sample_rate,
        "duration_seconds": round(duration, 6),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://192.168.1.117:7788")
    parser.add_argument("--steps", type=int, default=12)
    parser.add_argument("--jobs", type=int, default=3)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.steps < 12:
        parser.error("preview assets must be generated with at least 12 steps")

    previews = parse_catalog()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = {
            executor.submit(synthesize, preview, args.base_url, args.steps, args.force): preview
            for preview in previews
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            record = future.result()
            records.append(record)
            print(f"[{completed:3}/100] {record['file']} ({record['duration_seconds']:.2f}s)", flush=True)

    records.sort(key=lambda item: (str(item["voice"]), str(item["language"]), int(item["number"])))
    manifest = {
        "generator": "scripts/generate-tts-voice-previews.py",
        "request": {
            "steps": args.steps,
            "speed": 1.0,
            "max_chunk_length": 10_000,
            "silence_duration": 0.0,
            "response_format": "wav",
        },
        "clips": records,
    }
    (OUTPUT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    total_bytes = sum(int(record["bytes"]) for record in records)
    total_duration = sum(float(record["duration_seconds"]) for record in records)
    print(f"Generated {len(records)} clips: {total_duration:.1f}s, {total_bytes / 1024 / 1024:.1f} MiB")


if __name__ == "__main__":
    main()
