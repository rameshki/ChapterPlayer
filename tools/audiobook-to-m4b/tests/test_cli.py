"""The whole tool through the fake ffmpeg/ffprobe: modes, safety, resume, cleanup."""

import io
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import helpers
from helpers import fake_audio, fake_cover, run_tool, snapshot

import to_m4b


def read_json(p):
    with open(to_m4b.ext(p), "r", encoding="utf-8") as fh:
        return json.load(fh)


class CliTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.lib = self.base / "library"
        self.out = self.base / "out"
        self._env = mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "ok"})
        self._env.start()

    def tearDown(self):
        self._env.stop()
        helpers.remove_tree(self.tmp.name)
        try:
            self.tmp.cleanup()
        except OSError:
            pass

    def make_book(self, rel="Author/Book", n=3, cover=True, tags=True, **audio):
        for i in range(1, n + 1):
            t = {"album": "Book", "artist": "Author", "track": str(i), "title": f"Part {i}"} if tags else {}
            fake_audio(self.lib / rel / f"{i:02d}.mp3", 100.0 + i, tags=t, **audio)
        if cover:
            fake_cover(self.lib / rel / "cover.jpg")
        return self.lib / rel

    def apply(self, *extra):
        return run_tool([str(self.lib), "--apply", "--output-root", str(self.out), *extra])


class DryRunTest(CliTestBase):
    def test_dry_run_touches_nothing(self):
        lib = helpers.build_demo_tree(self.base)
        before = snapshot(lib)
        code, text = run_tool([str(lib)])
        self.assertEqual(code, 0, text)
        self.assertEqual(snapshot(lib), before)
        self.assertFalse(self.out.exists())
        self.assertIn("DRY-RUN", text)
        self.assertIn("would convert", text)
        self.assertIn("Unsorted: UNSURE", text)
        self.assertIn("different album tags", text)
        self.assertIn("Mixed Formats: UNSURE", text)
        self.assertIn("Loose Books: UNSURE", text)
        self.assertFalse(list(lib.rglob("*.m4b.part")))

    def test_dry_run_writes_report_into_output_root_only(self):
        lib = helpers.build_demo_tree(self.base)
        before = snapshot(lib)
        code, text = run_tool([str(lib), "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertEqual(snapshot(lib), before)
        report = read_json(self.out / "to_m4b-report.json")
        books = report["books"]
        self.assertEqual(books["Jane Doe/Standalone Book"]["action"], "dry-run")
        self.assertEqual(books["John Roe/Short Story"]["action"], "skipped")
        self.assertEqual(books["Unsorted"]["action"], "ambiguous")
        self.assertEqual(books["Loose Books"]["action"], "ambiguous")
        big = books["John Roe/Big Book"]["plan"]
        self.assertEqual(big["files"][:3], ["CD1/Track 01.mp3", "CD1/Track 02.mp3", "CD2/Track 01.mp3"])
        self.assertEqual(big["files"][-1], "CD10/Track 02.mp3")
        self.assertEqual(big["cover_kind"], "embedded")
        self.assertEqual(big["bitrate_k"], 128)
        sea = books["Jane Doe/Sea Trilogy/01 - First Voyage"]["plan"]
        self.assertEqual([c["title"] for c in sea["chapters"]], ["Chapter 1", "Chapter 2", "Chapter 10"])
        self.assertEqual(sea["tags"]["series"], "Sea Trilogy")
        self.assertEqual(sea["channels"], 1)
        self.assertEqual(sea["bitrate_k"], 48)
        gappy = books["Odd Cases/Gappy Book"]
        self.assertTrue(any("missing: 3" in w for w in gappy["warnings"]))
        self.assertTrue(any("differs from file-name order" in w for w in gappy["warnings"]))
        self.assertTrue((self.out / "to_m4b-report.csv").exists())
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), ["to_m4b-report.csv", "to_m4b-report.json"])

    def test_limit_only_exclude(self):
        lib = helpers.build_demo_tree(self.base)
        code, text = run_tool([str(lib), "--limit", "1"])
        self.assertIn("[1] ", text)
        self.assertNotIn("[2] ", text)
        code, text = run_tool([str(lib), "--only", "john roe/*"])
        self.assertIn("John Roe/Big Book", text)
        self.assertNotIn("Standalone Book", text)
        code, text = run_tool([str(lib), "--exclude", "Jane Doe/*", "--exclude", "*Unsorted*"])
        self.assertNotIn("Standalone Book", text)
        self.assertNotIn("Unsorted", text)
        self.assertIn("Big Book", text)


class SafetyRefusalTest(CliTestBase):
    def test_apply_needs_an_output_root(self):
        self.make_book()
        code, text = run_tool([str(self.lib), "--apply"])
        self.assertEqual(code, 2)
        self.assertIn("--output-root", text)

    def test_there_is_no_in_place_mode(self):
        self.make_book()
        before = snapshot(self.lib)
        for flag in ("--in-place", "--backup-root", "--i-understand-this-changes-my-library"):
            with mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
                to_m4b.main([str(self.lib), "--apply", flag, str(self.base / "x")], out=io.StringIO())
        self.assertEqual(snapshot(self.lib), before)
        self.assertNotIn("in-place", to_m4b.build_parser().format_help())

    def test_output_must_be_outside_the_library(self):
        self.make_book()
        code, text = self.apply()  # sanity: a proper output root works
        self.assertEqual(code, 0, text)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.lib / "converted")])
        self.assertEqual(code, 2)
        self.assertIn("OUTSIDE", text)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.base)])
        self.assertEqual(code, 2)

    def test_output_root_through_a_link_into_the_library_is_refused(self):
        self.make_book()
        alias = self.base / "alias"
        try:
            if to_m4b.IS_WINDOWS:
                import _winapi
                _winapi.CreateJunction(str(self.lib), str(alias))
            else:
                os.symlink(str(self.lib), str(alias))
        except (OSError, AttributeError, ImportError) as e:
            self.skipTest(f"cannot create a link here: {e}")
        before = snapshot(self.lib)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(alias / "converted")])
        self.assertEqual(code, 2, text)
        self.assertIn("OUTSIDE", text)
        code, text = run_tool([str(alias), "--apply", "--output-root", str(self.lib / "converted")])
        self.assertEqual(code, 2, text)
        self.assertEqual(snapshot(self.lib), before)

    def test_report_path_must_be_safe(self):
        book = self.make_book()
        before = snapshot(self.lib)
        cases = [
            (book / "01.mp3", "must end in .json"),
            (self.base / "report.csv", "must end in .json"),
            (self.lib / "report.json", "OUTSIDE the library"),
        ]
        helpers.write_bytes(self.base / "notes.json", b'{"my": "data"}')
        cases.append((self.base / "notes.json", "is not an earlier to_m4b report"))
        helpers.write_bytes(self.base / "r2.csv", b"name,phone\n")
        cases.append((self.base / "r2.json", "is not an earlier to_m4b CSV report"))
        for path, words in cases:
            code, text = run_tool([str(self.lib), "--output-root", str(self.out), "--report", str(path)])
            self.assertEqual(code, 2, path)
            self.assertIn(words, text, path)
        self.assertEqual(snapshot(self.lib), before)
        self.assertEqual((self.base / "notes.json").read_bytes(), b'{"my": "data"}')
        self.assertEqual((self.base / "r2.csv").read_bytes(), b"name,phone\n")
        # A report this tool wrote earlier may be updated.
        code, text = run_tool([str(self.lib), "--report", str(self.base / "mine.json")])
        self.assertEqual(code, 0, text)
        code, text = run_tool([str(self.lib), "--report", str(self.base / "mine.json")])
        self.assertEqual(code, 0, text)
        self.assertEqual(read_json(self.base / "mine.json")["tool"], "to_m4b")

    def test_missing_tools_explain_how_to_install(self):
        self.make_book()
        out = io.StringIO()
        with mock.patch("shutil.which", return_value=None):
            code = to_m4b.main([str(self.lib)], out=out)
        self.assertEqual(code, 2)
        self.assertIn("ffprobe was not found", out.getvalue())
        self.assertIn("winget install", out.getvalue())
        self.assertIn("brew install ffmpeg", out.getvalue())
        out = io.StringIO()
        with mock.patch("shutil.which", return_value=None):
            to_m4b.main([str(self.lib), "--apply", "--output-root", str(self.out),
                         "--ffprobe", str(helpers.FAKE_FFPROBE)], out=out)
        self.assertIn("ffmpeg was not found", out.getvalue())


class ApplyTest(CliTestBase):
    def test_converts_into_the_mirror_and_never_touches_originals(self):
        self.make_book("Author/Book")
        self.make_book("Author/Series/Other", n=2, cover=False, tags=False)
        before = snapshot(self.lib)
        log = self.base / "argv.log"
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_LOG": str(log)}):
            code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertEqual(snapshot(self.lib), before)
        m4b = self.out / "Author" / "Book" / "Book.m4b"
        self.assertTrue(m4b.exists(), text)
        data = read_json(m4b)["fake_m4b"]
        self.assertAlmostEqual(data["duration"], 101 + 102 + 103)
        self.assertEqual([c["title"] for c in data["chapters"]], ["Part 1", "Part 2", "Part 3"])
        self.assertEqual(data["chapters"][1]["start"], 101000)
        self.assertTrue(data["cover"])
        self.assertEqual(data["join"], "demuxer")
        self.assertEqual(data["tags"]["genre"], "Audiobook")
        self.assertEqual(data["tags"]["artist"], "Author")
        other = read_json(self.out / "Author" / "Series" / "Other" / "Other.m4b")["fake_m4b"]
        self.assertEqual(other["tags"]["grouping"], "Series")
        self.assertFalse(other["cover"])
        self.assertFalse(list(self.out.rglob("*.part")))
        argvs = [json.loads(l) for l in log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(argvs), 2)
        self.assertIn("+faststart", argvs[0])
        report = read_json(self.out / "to_m4b-report.json")
        rec = report["books"]["Author/Book"]
        self.assertEqual(rec["action"], "converted")
        self.assertEqual(rec["file_count"], 3)
        self.assertIn("seconds", rec)
        self.assertGreater(rec["output_bytes"], 0)
        self.assertIn("2  converted", text.replace("    ", " "))
        self.assertIn("move the originals yourself", text)

    def test_files_with_different_stream_layouts_are_joined_by_their_audio(self):
        for i in (1, 2, 3):
            fake_audio(self.lib / "A" / "Book" / f"{i:02d}.m4a", 100, codec="aac", tags={"album": "Book"},
                       picture="mjpeg" if i == 2 else None, picture_first=(i == 2),
                       sample_rate=22050 if i == 3 else 44100)
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        data = read_json(self.out / "A" / "Book" / "Book.m4b")["fake_m4b"]
        self.assertEqual(data["join"], "filter")
        self.assertEqual(data["files"], 3)
        self.assertAlmostEqual(data["duration"], 300)

    def test_second_run_skips_verified_books(self):
        self.make_book()
        self.apply()
        m4b = self.out / "Author" / "Book" / "Book.m4b"
        mtime = m4b.stat().st_mtime_ns
        log = self.base / "argv.log"
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_LOG": str(log)}):
            code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertFalse(log.exists())  # ffmpeg was not run again
        self.assertEqual(m4b.stat().st_mtime_ns, mtime)
        self.assertIn("already done", text)
        self.assertEqual(read_json(self.out / "to_m4b-report.json")["books"]["Author/Book"]["action"], "skipped")

    def test_an_existing_file_that_does_not_match_is_left_alone(self):
        self.make_book()
        target = self.out / "Author" / "Book" / "Book.m4b"
        helpers.write_bytes(target, b"someone else's file")
        code, text = self.apply()
        self.assertEqual(code, 1)
        self.assertEqual(target.read_bytes(), b"someone else's file")
        self.assertIn("left untouched", text)

    def test_ffmpeg_failure_cleans_up_and_logs(self):
        self.make_book()
        before = snapshot(self.lib)
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "fail"}):
            code, text = self.apply()
        self.assertEqual(code, 1)
        self.assertIn("FAILED", text)
        self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists())
        self.assertFalse(list(self.out.rglob("*.part")))
        logs = list((self.out / "_logs").glob("*.log"))
        self.assertEqual(len(logs), 1)
        self.assertIn("fake failure for the tests", logs[0].read_text(encoding="utf-8"))
        self.assertEqual(snapshot(self.lib), before)
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Author" / "Book" / "Book.m4b").exists())

    def test_verification_failures_never_reach_the_final_name(self):
        self.make_book()
        for mode, words in (("short", "audio length"), ("shortaudio", "audio length"),
                            ("nochapters", "chapters"), ("nocover", "cover")):
            with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": mode}):
                code, text = self.apply()
            self.assertEqual(code, 1, mode)
            self.assertIn("did not pass the checks", text)
            self.assertIn(words, text)
            self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists(), mode)
            self.assertFalse(list(self.out.rglob("*.part")), mode)

    def test_short_audio_behind_full_length_chapters_is_rejected(self):
        self.make_book()
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "shortaudio"}):
            code, text = self.apply()
        self.assertEqual(code, 1)
        self.assertIn("audio length 276.00 s, expected 306.00 s", text)
        self.assertIn("after the audio", text)

    def test_decoding_errors_are_noted_or_fail_with_strict(self):
        self.make_book()
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "errorline"}):
            code, text = self.apply("--strict")
            self.assertEqual(code, 1)
            self.assertIn("--strict", text)
            self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists())
            code, text = self.apply()
        self.assertEqual(code, 0, text)
        rec = read_json(self.out / "to_m4b-report.json")["books"]["Author/Book"]
        self.assertTrue(any("Header missing" in w for w in rec["warnings"]))

    def test_old_stale_part_is_replaced_and_reported(self):
        self.make_book()
        part = helpers.write_bytes(self.out / "Author" / "Book" / "Book.m4b.part", b"half")
        old = time.time() - 3600
        os.utime(to_m4b.ext(part), (old, old))
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertFalse(part.exists())
        self.assertTrue((self.out / "Author" / "Book" / "Book.m4b").exists())
        rec = read_json(self.out / "to_m4b-report.json")["books"]["Author/Book"]
        self.assertTrue(any("left over from an earlier" in w for w in rec["warnings"]))

    def test_fresh_part_of_someone_else_is_left_alone(self):
        self.make_book()
        part = helpers.write_bytes(self.out / "Author" / "Book" / "Book.m4b.part", b"being written")
        code, text = self.apply()
        self.assertEqual(code, 1)
        self.assertEqual(part.read_bytes(), b"being written")
        self.assertIn("less than a minute old", text)
        self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists())

    def test_stop_during_encoding_removes_only_temporary_files(self):
        book = self.make_book()
        before = snapshot(self.lib)
        opts = to_m4b.build_parser().parse_args([str(self.lib), "--apply", "--output-root", str(self.out)])
        opts.root = os.path.abspath(opts.root)
        run = to_m4b.Run(opts, helpers.fake_tools(), io.StringIO())
        cand = [c for c in to_m4b.scan_library(self.lib) if c.status == "candidate"][0]
        plan = to_m4b.make_plan(cand, to_m4b.probe_files(run.tools, cand.files))
        plan.output_path = to_m4b.target_for(opts, cand)
        part = plan.output_path.with_name("Book.m4b.part")

        def stopper():
            deadline = time.time() + 10
            while not part.exists() and time.time() < deadline:
                time.sleep(0.02)
            run.stop.set()
            run.kill_all()

        t = threading.Thread(target=stopper)
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "slow"}):
            t.start()
            started = time.time()
            rec = to_m4b.convert_book(run, plan, 1)
            t.join()
        self.assertLess(time.time() - started, 15)
        self.assertEqual(rec["action"], "failed")
        self.assertFalse(part.exists())
        self.assertFalse(plan.output_path.exists())
        self.assertEqual(snapshot(self.lib), before)
        self.assertTrue(book.exists())

    def test_keyboard_interrupt_in_main_loop_exits_130_and_writes_report(self):
        self.make_book()
        with mock.patch.object(to_m4b, "probe_files", side_effect=KeyboardInterrupt):
            code, text = self.apply()
        self.assertEqual(code, 130)
        self.assertIn("Stopping", text)
        self.assertTrue((self.out / "to_m4b-report.json").exists())

    def test_not_enough_space_skips_only_that_book(self):
        self.make_book("Author/Big")
        self.make_book("Author/Small")
        real = to_m4b.free_space

        def fake_free(path):
            return 1024 if "Big" in str(path) else real(path)

        with mock.patch.object(to_m4b, "free_space", side_effect=fake_free):
            code, text = self.apply("--jobs", "2")
        self.assertEqual(code, 1)
        self.assertIn("not enough free space", text)
        self.assertFalse((self.out / "Author" / "Big" / "Big.m4b").exists())
        self.assertTrue((self.out / "Author" / "Small" / "Small.m4b").exists())

    def test_parallel_jobs(self):
        for i in range(5):
            self.make_book(f"Author/Book {i}", n=2, cover=False)
        code, text = self.apply("--jobs", "3")
        self.assertEqual(code, 0, text)
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 5)

    def test_limit_counts_only_books_converted(self):
        for i in range(4):
            self.make_book(f"Author/Book {i}", n=2, cover=False)
        self.apply("--limit", "2")
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 2)
        self.apply("--limit", "2")
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 4)
        report = read_json(self.out / "to_m4b-report.json")
        self.assertEqual(sorted(r["action"] for r in report["books"].values()),
                         ["converted", "converted", "skipped", "skipped"])

    def test_unicode_and_awkward_names(self):
        rel = "Émile Zoë/L'Été — «Ünïcode» 日本語"
        self.make_book(rel, n=2)
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Émile Zoë" / "L'Été — «Ünïcode» 日本語" / "L'Été — «Ünïcode» 日本語.m4b").exists())

    @unittest.skipIf(to_m4b.IS_WINDOWS, "such names cannot exist on Windows")
    def test_names_invalid_on_windows_are_made_safe_and_collisions_skipped(self):
        self.make_book('Author/What? A "Book"', n=2)
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Author" / "What_ A _Book_" / "What_ A _Book_.m4b").exists())
        self.make_book("Author/Twin?", n=2)
        self.make_book("Author/Twin_", n=2)
        code, text = self.apply()
        self.assertIn("same output file name", text)
        self.assertFalse((self.out / "Author" / "Twin_" / "Twin_.m4b").exists())

    @unittest.skipUnless(to_m4b.IS_WINDOWS, "the 260 character limit is a Windows thing")
    def test_very_long_windows_paths(self):
        rel = "/".join(["A very long folder name for testing paths " + str(i) for i in range(6)])
        book = self.make_book(rel, n=2)
        self.assertGreater(len(str(book)), 260)
        code, text = self.apply()
        self.assertEqual(code, 0, text)
        found = os.listdir(to_m4b.ext(to_m4b.mirror_path(self.out, rel, book.name).parent))
        self.assertIn(book.name + ".m4b", found)


if __name__ == "__main__":
    unittest.main()
