"""One real end-to-end run with the real ffmpeg. SKIPPED when ffmpeg/ffprobe are not on PATH.

Makes three tiny synthetic MP3s (sine tones from ffmpeg's lavfi source), joins
them, and checks the result with the real ffprobe.
"""

import io
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import helpers  # noqa: F401  (puts the tool on sys.path)

import to_m4b

FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")


@unittest.skipUnless(FFMPEG and FFPROBE, "ffmpeg and ffprobe are not installed (real encoding test skipped)")
class RealFfmpegTest(unittest.TestCase):
    def test_three_mp3s_become_one_chaptered_m4b(self):
        with tempfile.TemporaryDirectory() as tmp:
            book = Path(tmp) / "library" / "Test Author" / "Tone Book"
            book.mkdir(parents=True)
            for i, (freq, secs) in enumerate(((440, 3), (550, 4), (660, 5)), start=1):
                subprocess.run([
                    FFMPEG, "-hide_banner", "-v", "error", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration={secs}",
                    "-ac", "1", "-ar", "22050", "-c:a", "libmp3lame", "-b:a", "64k",
                    "-metadata", "album=Tone Book", "-metadata", "artist=Test Author",
                    "-metadata", f"title=Tone {i}", "-metadata", f"track={i}",
                    str(book / f"{i:02d}.mp3")], check=True)
            out = Path(tmp) / "out"
            buf = io.StringIO()
            code = to_m4b.main([str(Path(tmp) / "library"), "--apply", "--output-root", str(out)], out=buf)
            self.assertEqual(code, 0, buf.getvalue())
            m4b = out / "Test Author" / "Tone Book" / "Tone Book.m4b"
            self.assertTrue(m4b.exists(), buf.getvalue())
            r = subprocess.run([FFPROBE, "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                                "-show_chapters", str(m4b)], stdout=subprocess.PIPE, check=True)
            data = json.loads(r.stdout)
            self.assertAlmostEqual(float(data["format"]["duration"]), 12.0, delta=1.0)
            self.assertEqual([c["tags"]["title"] for c in data["chapters"]], ["Tone 1", "Tone 2", "Tone 3"])
            audio = [s for s in data["streams"] if s["codec_type"] == "audio"]
            self.assertEqual(audio[0]["codec_name"], "aac")
            self.assertEqual(int(audio[0]["channels"]), 1)
            tags = {k.lower(): v for k, v in data["format"].get("tags", {}).items()}
            self.assertEqual(tags.get("title"), "Tone Book")
            self.assertEqual(tags.get("genre"), "Audiobook")


if __name__ == "__main__":
    unittest.main()
