"""A stand-in for ffprobe used by the tests (no real media involved).

"Audio" files in the tests are small JSON files: {"fake_audio": {...}} describe a
source file, {"fake_m4b": {...}} is what fake_ffmpeg.py writes. This script
answers like ``ffprobe -v error -print_format json -show_format -show_streams
[-show_chapters] FILE`` would, with the same field names and string values.
Anything else (an empty or partial file) is "Invalid data", exit code 1.
"""

import json
import os
import sys


def ext(p):
    """Extended Windows path so the fakes can open very long paths too."""
    s = os.path.abspath(p)
    if os.name != "nt" or s.startswith("\\\\?\\"):
        return s
    if s.startswith("\\\\"):
        return "\\\\?\\UNC\\" + s[2:]
    return "\\\\?\\" + s


def main(argv):
    path = argv[-1]
    show_chapters = "-show_chapters" in argv
    try:
        with open(ext(path), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except FileNotFoundError:
        sys.stderr.write(f"{path}: No such file or directory\n")
        return 1
    except (ValueError, UnicodeDecodeError):
        sys.stderr.write(f"{path}: Invalid data found when processing input\n")
        return 1
    size = os.path.getsize(ext(path))
    if "fake_audio" in data:
        a = data["fake_audio"]
        streams = [{
            "index": 0, "codec_type": "audio", "codec_name": a.get("codec", "mp3"),
            "sample_rate": str(a.get("sample_rate", 44100)), "channels": a.get("channels", 2),
            "bit_rate": str(a.get("bit_rate", 64000)), "duration": "%.6f" % a["duration"],
            "disposition": {"default": 0, "attached_pic": 0},
        }]
        if a.get("picture"):
            pic = {"index": 1, "codec_type": "video", "codec_name": a["picture"],
                   "disposition": {"default": 0, "attached_pic": 1}}
            if a.get("picture_first"):   # e.g. an .m4a whose cover stream comes before the audio
                streams.insert(0, pic)
            else:
                streams.append(pic)
        fmt = {"filename": path, "nb_streams": len(streams), "format_name": "mp3",
               "duration": "%.6f" % a["duration"], "size": str(size),
               "bit_rate": str(a.get("bit_rate", 64000)), "tags": a.get("tags", {})}
        out = {"streams": streams, "format": fmt}
        if a.get("no_duration"):
            del fmt["duration"]
            del streams[0]["duration"]
    elif "fake_m4b" in data:
        m = data["fake_m4b"]
        streams = [{"index": 0, "codec_type": "audio", "codec_name": "aac",
                    "sample_rate": str(m.get("sample_rate", 44100)), "channels": m.get("channels", 2),
                    "bit_rate": str(m.get("bit_rate", 64000)),
                    "duration": "%.6f" % m.get("audio_duration", m["duration"]),
                    "disposition": {"default": 1, "attached_pic": 0}}]
        if m.get("chapters"):
            # Like ffmpeg's MP4 muxer: chapters are also a text track running to the last chapter end.
            streams.append({"index": len(streams), "codec_type": "data", "codec_name": "bin_data",
                            "duration": "%.6f" % (m["chapters"][-1]["end"] / 1000)})
        if m.get("cover"):
            streams.append({"index": 1, "codec_type": "video", "codec_name": "mjpeg",
                            "disposition": {"default": 0, "attached_pic": 1}})
        out = {"streams": streams,
               "format": {"filename": path, "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                          "duration": "%.6f" % m["duration"], "size": str(size), "tags": m.get("tags", {})}}
        if show_chapters:
            out["chapters"] = [
                {"id": i, "time_base": "1/1000", "start": c["start"], "start_time": "%.6f" % (c["start"] / 1000),
                 "end": c["end"], "end_time": "%.6f" % (c["end"] / 1000), "tags": {"title": c["title"]}}
                for i, c in enumerate(m.get("chapters", []))]
    else:
        sys.stderr.write(f"{path}: Invalid data found when processing input\n")
        return 1
    sys.stdout.write(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
