"""Shared test helpers: synthetic "audio" files and a demo library.

Every file made here is synthetic: small JSON files that the fake ffprobe
(tests/fakes) reads as audio. No real media, no real names or paths.

Run ``python tests/helpers.py DIR`` to build the demo library in DIR, then try
the dry run on it with the fake ffprobe:

    python to_m4b.py DIR/library --ffprobe tests/fakes/fake_ffprobe.py
"""

import io
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOL_DIR = HERE.parent
FAKE_FFPROBE = HERE / "fakes" / "fake_ffprobe.py"
FAKE_FFMPEG = HERE / "fakes" / "fake_ffmpeg.py"

if str(TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(TOOL_DIR))

import to_m4b  # noqa: E402


def ext(p):
    return to_m4b.ext(p)


def write_bytes(path, data):
    path = Path(path)
    os.makedirs(ext(path.parent), exist_ok=True)
    with open(ext(path), "wb") as fh:
        fh.write(data)
    return path


def fake_audio(path, duration=60.0, tags=None, bit_rate=64000, channels=2, sample_rate=44100,
               picture=None, codec="mp3", **extra):
    """A synthetic audio file the fake ffprobe understands."""
    info = {"duration": duration, "tags": tags or {}, "bit_rate": bit_rate, "channels": channels,
            "sample_rate": sample_rate, "codec": codec}
    if picture:
        info["picture"] = picture
    info.update(extra)
    return write_bytes(path, json.dumps({"fake_audio": info}).encode("utf-8"))


def fake_cover(path):
    return write_bytes(path, b"\xff\xd8\xff\xe0 synthetic cover for tests \xff\xd9")


def fake_tools():
    return to_m4b.Tools(ffmpeg=[sys.executable, str(FAKE_FFMPEG)], ffprobe=[sys.executable, str(FAKE_FFPROBE)])


def run_tool(args):
    """Run to_m4b.main with the fake tools. Returns (exit code, console text)."""
    out = io.StringIO()
    argv = list(args) + ["--ffprobe", str(FAKE_FFPROBE), "--ffmpeg", str(FAKE_FFMPEG)]
    code = to_m4b.main(argv, out=out)
    return code, out.getvalue()


def snapshot(root):
    """Every file below root with its size and modification time (to prove nothing changed)."""
    snap = {}
    for dirpath, _dirs, files in os.walk(ext(root)):
        for f in files:
            p = os.path.join(dirpath, f)
            st = os.stat(p)
            snap[os.path.relpath(p, ext(root))] = (st.st_size, st.st_mtime_ns)
    return snap


def build_demo_tree(base):
    """A small library with the layouts the tool must handle. Returns the library root."""
    lib = Path(base) / "library"
    # 1. A plain multi-file book with good tags (Author/Book).
    for i, name in enumerate(["Opening", "The Storm", "Landfall"], start=1):
        fake_audio(lib / "Jane Doe" / "Standalone Book" / f"{i:02d} - {name}.mp3", 600 + i,
                   tags={"album": "Standalone Book", "artist": "Jane Doe", "composer": "Sam Reader",
                         "track": f"{i}/3", "title": name, "date": "2001"})
    fake_cover(lib / "Jane Doe" / "Standalone Book" / "cover.jpg")
    # 2. Nested Author/Series/Book, no tags at all, names that sort wrongly as plain text.
    for n in (1, 2, 10):
        fake_audio(lib / "Jane Doe" / "Sea Trilogy" / "01 - First Voyage" / f"Chapter {n}.mp3", 300.5,
                   bit_rate=32000, channels=1, sample_rate=22050)
    # 3. Disc folders CD1, CD2, CD10 with per-disc track numbers and an embedded cover.
    for d in (1, 2, 10):
        for t in (1, 2):
            fake_audio(lib / "John Roe" / "Big Book" / f"CD{d}" / f"Track {t:02d}.mp3", 1200,
                       tags={"album": f"Big Book (Disc {d})", "album_artist": "John Roe", "track": str(t),
                             "disc": str(d), "title": f"Track {t:02d}"},
                       bit_rate=192000, picture="mjpeg" if d == 1 and t == 1 else None)
    # 4. Already a single file.
    fake_audio(lib / "John Roe" / "Short Story" / "Short Story.m4b", 1800, codec="aac")
    # 5. Several unrelated books dumped in one folder: different album tags.
    fake_audio(lib / "Unsorted" / "a1.mp3", 100, tags={"album": "Book A"})
    fake_audio(lib / "Unsorted" / "a2.mp3", 100, tags={"album": "Book A"})
    fake_audio(lib / "Unsorted" / "b1.mp3", 100, tags={"album": "Book B"})
    # 6. Track tags with a gap and an order that disagrees with the file names.
    for name, track in (("a.mp3", 2), ("b.mp3", 1), ("c.mp3", 4)):
        fake_audio(lib / "Odd Cases" / "Gappy Book" / name, 200, tags={"track": str(track), "album": "Gappy Book"})
    # 7. Mixed formats in one folder.
    fake_audio(lib / "Odd Cases" / "Mixed Formats" / "part1.mp3", 100)
    fake_audio(lib / "Odd Cases" / "Mixed Formats" / "part2.m4a", 100, codec="aac")
    # 8. Unicode names.
    for i in (1, 2):
        fake_audio(lib / "Émile Zoë" / "Le Livre Ünïcode" / f"{i:02d} – Partie {i}.mp3", 400,
                   tags={"album": "Le Livre Ünïcode", "artist": "Émile Zoë", "track": str(i)})
    return lib


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    print(build_demo_tree(target))
