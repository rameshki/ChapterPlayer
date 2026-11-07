"""A stand-in for ffmpeg used by the tests (no real encoding).

Understands the command to_m4b builds: a concat list input, an ffmetadata
input, an optional cover input, and the output file as the last argument. It
writes {"fake_m4b": {...}} describing what a real ffmpeg would have produced
(duration = sum of the inputs, chapters and tags from the ffmetadata file,
cover when one is mapped), and prints -progress lines on stdout.

Environment knobs for the tests:
  FAKE_FFMPEG_MODE  ok (default) | fail | short | shortaudio | nochapters | nocover | slow | errorline
  FAKE_FFMPEG_LOG   append each argv as one JSON line to this file
"""

import json
import os
import re
import sys


def ext(p):
    """Extended Windows path so the fakes can open very long paths too."""
    s = os.path.abspath(p)
    if os.name != "nt" or s.startswith("\\\\?\\"):
        return s
    if s.startswith("\\\\"):
        return "\\\\?\\UNC\\" + s[2:]
    return "\\\\?\\" + s
import time


def parse_concat(path):
    files = []
    with open(ext(path), "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("file "):
                v = line[5:].strip()
                if v.startswith("'") and v.endswith("'"):
                    v = v[1:-1].replace("'\\''", "'")
                files.append(v)
    return files


def unescape(v):
    return re.sub(r"\\(.)", r"\1", v, flags=re.S)


def parse_ffmeta(path):
    with open(ext(path), "r", encoding="utf-8") as fh:
        text = fh.read()
    assert text.startswith(";FFMETADATA1"), "not an ffmetadata file"
    # Join escaped newlines, then split.
    lines, buf = [], ""
    for raw in text.split("\n")[1:]:
        if raw.endswith("\\") and not raw.endswith("\\\\"):
            buf += raw[:-1] + "\n"
            continue
        lines.append(buf + raw)
        buf = ""
    tags, chapters, cur = {}, [], None
    for line in lines:
        if not line or line.startswith(";") or line.startswith("#"):
            continue
        if line == "[CHAPTER]":
            cur = {}
            chapters.append(cur)
            continue
        m = re.match(r"((?:[^=\\]|\\.)*)=(.*)$", line, re.S)
        if not m:
            continue
        k, v = unescape(m.group(1)), unescape(m.group(2))
        (cur if cur is not None else tags)[k] = v
    chs = []
    for c in chapters:
        assert c.get("TIMEBASE") == "1/1000", "chapters must use TIMEBASE=1/1000"
        chs.append({"start": int(c["START"]), "end": int(c["END"]), "title": c.get("title", "")})
    return tags, chs


def main(argv):
    log = os.environ.get("FAKE_FFMPEG_LOG")
    if log:
        with open(log, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(argv) + "\n")
    mode = os.environ.get("FAKE_FFMPEG_MODE", "ok")
    inputs, fmt = [], None
    i = 0
    opts = {}
    while i < len(argv) - 1:
        a = argv[i]
        if a == "-f":
            fmt = argv[i + 1]
            i += 2
            continue
        if a == "-i":
            inputs.append((fmt, argv[i + 1]))
            fmt = None
            i += 2
            continue
        if a.startswith("-") and i + 1 < len(argv) - 1 and not argv[i + 1].startswith("-"):
            opts.setdefault(a, []).append(argv[i + 1])
            i += 2
            continue
        i += 1
    out = argv[-1]
    if "-n" in argv and os.path.exists(ext(out)):
        sys.stderr.write(f"File '{out}' already exists. Exiting.\n")
        return 1
    assert inputs and inputs[0][0] == "ffmetadata", "input 0 must be the ffmetadata file"
    tags, chapters = parse_ffmeta(inputs[0][1])
    audio_inputs = {}   # input index -> list of fake audio files
    for idx, (fmt_, path) in enumerate(inputs):
        if fmt_ == "concat":
            audio_inputs[idx] = parse_concat(path)
        elif fmt_ is None:
            try:
                with open(ext(path), "r", encoding="utf-8") as fh:
                    if "fake_audio" in json.load(fh):
                        audio_inputs[idx] = [path]
            except (ValueError, UnicodeDecodeError):
                pass   # the cover picture
    maps = opts.get("-map", [])
    graph = (opts.get("-filter_complex") or [None])[0]
    if graph:
        used = [int(n) for n in re.findall(r"\[(\d+):a:0\]", graph)]
        assert used and all(u in audio_inputs for u in used), "filter must take audio from the audio inputs"
        assert "[aout]" in maps, "the filter output must be mapped"
        files = [f for u in used for f in audio_inputs[u]]
        join = "filter"
    else:
        audio_maps = [int(m.split(":")[0]) for m in maps if m.endswith(":a:0")]
        assert len(audio_maps) == 1 and audio_maps[0] in audio_inputs, "one audio map onto the concat input"
        files = audio_inputs[audio_maps[0]]
        join = "demuxer"
    assert "-af" in opts or graph, "audio must be normalised with aresample/aformat"
    duration = 0.0
    for f in files:
        with open(ext(f), "r", encoding="utf-8") as fh:
            duration += json.load(fh)["fake_audio"]["duration"]
    cover = any(m.endswith(":v:0") for m in maps)

    if mode == "fail":
        with open(ext(out), "wb") as fh:
            fh.write(b"PARTIAL")
        sys.stderr.write("[error] Error while encoding: fake failure for the tests\n")
        return 1
    if mode == "slow":
        with open(ext(out), "wb") as fh:
            fh.write(b"PARTIAL")
        for n in range(400):
            sys.stdout.write(f"out_time_us={n * 1000}\nprogress=continue\n")
            sys.stdout.flush()
            time.sleep(0.05)
        return 0
    audio_duration = duration
    if mode == "short":
        duration -= 10
        audio_duration = duration
    if mode == "shortaudio":
        # Audio dropped part-way, but the chapter track still runs to the last chapter end,
        # so the container (format) duration looks complete.
        audio_duration = duration - 30
    if mode == "nochapters":
        chapters = []
    if mode == "nocover":
        cover = False
    if mode == "errorline":
        sys.stderr.write("[mp3float @ 0x1] [error] Header missing\n")
    sys.stdout.write(f"out_time_us={int(audio_duration * 500000)}\nprogress=continue\n")
    sys.stdout.write(f"out_time_us={int(audio_duration * 1000000)}\nprogress=end\n")
    sys.stdout.flush()
    result = {"fake_m4b": {
        "duration": duration, "audio_duration": audio_duration, "chapters": chapters, "cover": cover,
        "tags": tags, "join": join, "files": len(files),
        "bit_rate": int(opts.get("-b:a", ["64k"])[0].rstrip("k")) * 1000,
        "channels": int(opts.get("-ac", ["2"])[0]), "sample_rate": int(opts.get("-ar", ["44100"])[0]),
    }}
    with open(ext(out), "w", encoding="utf-8") as fh:
        json.dump(result, fh)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
