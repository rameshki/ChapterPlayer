"""The whole tool through the fake ffmpeg/ffprobe: modes, safety, resume, cleanup."""

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
        self.backup = self.base / "backup"
        self._env = mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "ok"})
        self._env.start()

    def tearDown(self):
        self._env.stop()
        self.tmp.cleanup()

    def make_book(self, rel="Author/Book", n=3, cover=True, tags=True):
        for i in range(1, n + 1):
            t = {"album": "Book", "artist": "Author", "track": str(i), "title": f"Part {i}"} if tags else {}
            fake_audio(self.lib / rel / f"{i:02d}.mp3", 100.0 + i, tags=t)
        if cover:
            fake_cover(self.lib / rel / "cover.jpg")
        return self.lib / rel


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
        # nothing in the library besides what was there
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
        self.assertEqual(list(p.name for p in self.out.iterdir()), ["to_m4b-report.csv", "to_m4b-report.json"])

    def test_limit_only_exclude(self):
        lib = helpers.build_demo_tree(self.base)
        code, text = run_tool([str(lib), "--limit", "1"])
        self.assertEqual(text.count("      -> ") + text.count("[1] "), text.count("[1] "))
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

    def test_in_place_needs_backup_root_and_confirmation(self):
        self.make_book()
        before = snapshot(self.lib)
        code, text = run_tool([str(self.lib), "--apply", "--in-place"])
        self.assertEqual(code, 2)
        self.assertIn("--backup-root", text)
        code, text = run_tool([str(self.lib), "--apply", "--in-place", "--backup-root", str(self.backup)])
        self.assertEqual(code, 2)
        self.assertIn("--i-understand-this-changes-my-library", text)
        self.assertEqual(snapshot(self.lib), before)
        self.assertFalse(self.backup.exists())

    def test_backup_and_output_must_be_outside_the_library(self):
        self.make_book()
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.lib / "converted")])
        self.assertEqual(code, 2)
        self.assertIn("OUTSIDE", text)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.base)])
        self.assertEqual(code, 2)
        code, text = run_tool([str(self.lib), "--apply", "--in-place", "--backup-root", str(self.lib / "bk"),
                               "--i-understand-this-changes-my-library"])
        self.assertEqual(code, 2)
        self.assertIn("OUTSIDE", text)

    def test_missing_tools_explain_how_to_install(self):
        self.make_book()
        import io
        out = io.StringIO()
        with mock.patch("shutil.which", return_value=None):
            code = to_m4b.main([str(self.lib)], out=out)
        self.assertEqual(code, 2)
        self.assertIn("ffprobe was not found", out.getvalue())
        self.assertIn("winget install", out.getvalue())
        self.assertIn("brew install ffmpeg", out.getvalue())
        out = io.StringIO()
        with mock.patch("shutil.which", return_value=None):
            code = to_m4b.main([str(self.lib), "--apply", "--output-root", str(self.out),
                                "--ffprobe", str(helpers.FAKE_FFPROBE)], out=out)
        self.assertIn("ffmpeg was not found", out.getvalue())


class ApplyMirrorTest(CliTestBase):
    def test_converts_into_the_mirror_and_never_touches_originals(self):
        self.make_book("Author/Book")
        self.make_book("Author/Series/Other", n=2, cover=False, tags=False)
        before = snapshot(self.lib)
        log = self.base / "argv.log"
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_LOG": str(log)}):
            code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertEqual(snapshot(self.lib), before)
        m4b = self.out / "Author" / "Book" / "Book.m4b"
        self.assertTrue(m4b.exists(), text)
        data = read_json(m4b)["fake_m4b"]
        self.assertAlmostEqual(data["duration"], 101 + 102 + 103)
        self.assertEqual([c["title"] for c in data["chapters"]], ["Part 1", "Part 2", "Part 3"])
        self.assertEqual(data["chapters"][1]["start"], 101000)
        self.assertTrue(data["cover"])
        self.assertEqual(data["tags"]["genre"], "Audiobook")
        self.assertEqual(data["tags"]["artist"], "Author")
        other = read_json(self.out / "Author" / "Series" / "Other" / "Other.m4b")["fake_m4b"]
        self.assertEqual(other["tags"]["series"], "Series")
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

    def test_second_run_skips_verified_books(self):
        self.make_book()
        run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        m4b = self.out / "Author" / "Book" / "Book.m4b"
        mtime = m4b.stat().st_mtime_ns
        log = self.base / "argv.log"
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_LOG": str(log)}):
            code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertFalse(log.exists())  # ffmpeg was not run again
        self.assertEqual(m4b.stat().st_mtime_ns, mtime)
        self.assertIn("already done", text)
        self.assertEqual(read_json(self.out / "to_m4b-report.json")["books"]["Author/Book"]["action"], "skipped")

    def test_an_existing_file_that_does_not_match_is_left_alone(self):
        self.make_book()
        target = self.out / "Author" / "Book" / "Book.m4b"
        helpers.write_bytes(target, b"someone else's file")
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 1)
        self.assertEqual(target.read_bytes(), b"someone else's file")
        self.assertIn("left untouched", text)

    def test_ffmpeg_failure_cleans_up_and_logs(self):
        self.make_book()
        before = snapshot(self.lib)
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "fail"}):
            code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 1)
        self.assertIn("FAILED", text)
        self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists())
        self.assertFalse(list(self.out.rglob("*.part")))
        logs = list((self.out / "_logs").glob("*.log"))
        self.assertEqual(len(logs), 1)
        self.assertIn("fake failure for the tests", logs[0].read_text(encoding="utf-8"))
        self.assertEqual(snapshot(self.lib), before)
        # A later good run converts it.
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Author" / "Book" / "Book.m4b").exists())

    def test_verification_failures_never_reach_the_final_name(self):
        self.make_book()
        for mode, words in (("short", "duration"), ("nochapters", "chapters"), ("nocover", "cover")):
            with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": mode}):
                code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
            self.assertEqual(code, 1, mode)
            self.assertIn("did not pass the checks", text)
            self.assertIn(words, text)
            self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists(), mode)
            self.assertFalse(list(self.out.rglob("*.part")), mode)

    def test_stale_part_from_an_interrupted_run_is_replaced(self):
        self.make_book()
        helpers.write_bytes(self.out / "Author" / "Book" / "Book.m4b.part", b"half")
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertFalse((self.out / "Author" / "Book" / "Book.m4b.part").exists())
        self.assertTrue((self.out / "Author" / "Book" / "Book.m4b").exists())

    def test_stop_during_encoding_removes_only_temporary_files(self):
        book = self.make_book()
        before = snapshot(self.lib)
        opts = to_m4b.build_parser().parse_args([str(self.lib), "--apply", "--output-root", str(self.out)])
        opts.root = os.path.abspath(opts.root)
        import io
        run = to_m4b.Run(opts, helpers.fake_tools(), io.StringIO())
        cand = to_m4b.scan_library(self.lib)[0]
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
            code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 130)
        self.assertIn("Stopping", text)
        self.assertTrue((self.out / "to_m4b-report.json").exists())

    def test_not_enough_space_stops_with_a_clear_message(self):
        self.make_book()
        with mock.patch.object(to_m4b, "free_space", return_value=1024):
            code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 1)
        self.assertIn("not enough free space", text)
        self.assertFalse((self.out / "Author" / "Book" / "Book.m4b").exists())

    def test_parallel_jobs(self):
        for i in range(5):
            self.make_book(f"Author/Book {i}", n=2, cover=False)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out), "--jobs", "3"])
        self.assertEqual(code, 0, text)
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 5)

    def test_limit_counts_only_books_converted(self):
        for i in range(4):
            self.make_book(f"Author/Book {i}", n=2, cover=False)
        run_tool([str(self.lib), "--apply", "--output-root", str(self.out), "--limit", "2"])
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 2)
        run_tool([str(self.lib), "--apply", "--output-root", str(self.out), "--limit", "2"])
        self.assertEqual(len(list(self.out.rglob("*.m4b"))), 4)
        report = read_json(self.out / "to_m4b-report.json")
        self.assertEqual(sorted(r["action"] for r in report["books"].values()),
                         ["converted", "converted", "skipped", "skipped"])

    def test_unicode_and_awkward_names(self):
        rel = "Émile Zoë/L'Été — «Ünïcode» 日本語"
        self.make_book(rel, n=2)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Émile Zoë" / "L'Été — «Ünïcode» 日本語" / "L'Été — «Ünïcode» 日本語.m4b").exists())

    def test_names_invalid_on_windows_are_made_safe_in_the_mirror(self):
        if to_m4b.IS_WINDOWS:
            self.skipTest("such names cannot exist on Windows")
        self.make_book('Author/What? A "Book"', n=2)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        self.assertTrue((self.out / "Author" / "What_ A _Book_" / "What_ A _Book_.m4b").exists())

    @unittest.skipUnless(to_m4b.IS_WINDOWS, "the 260 character limit is a Windows thing")
    def test_very_long_windows_paths(self):
        rel = "/".join(["A very long folder name for testing paths " + str(i) for i in range(6)])
        book = self.make_book(rel, n=2)
        self.assertGreater(len(str(book)), 260)
        code, text = run_tool([str(self.lib), "--apply", "--output-root", str(self.out)])
        self.assertEqual(code, 0, text)
        found = [p for p in os.listdir(to_m4b.ext(to_m4b.mirror_path(self.out, rel, book.name).parent))]
        self.assertIn(book.name + ".m4b", found)


class InPlaceTest(CliTestBase):
    ARGS = ["--apply", "--in-place", "--i-understand-this-changes-my-library"]

    def test_in_place_moves_originals_to_backup_and_puts_m4b_in_the_folder(self):
        book = self.make_book()
        originals = {p.name: p.read_bytes() for p in book.glob("*.mp3")}
        code, text = run_tool([str(self.lib), *self.ARGS, "--backup-root", str(self.backup)])
        self.assertEqual(code, 0, text)
        self.assertEqual(sorted(p.name for p in book.iterdir()), ["Book.m4b", "cover.jpg"])
        backed = {p.name: p.read_bytes() for p in (self.backup / "Author" / "Book").glob("*.mp3")}
        self.assertEqual(backed, originals)
        self.assertTrue((self.backup / "to_m4b-report.json").exists())
        # Second run: the folder now holds a single file and is skipped.
        code, text = run_tool([str(self.lib), *self.ARGS, "--backup-root", str(self.backup)])
        self.assertEqual(code, 0, text)
        self.assertEqual(sorted(p.name for p in book.iterdir()), ["Book.m4b", "cover.jpg"])

    def test_in_place_refuses_to_overwrite_backups_and_changes_nothing(self):
        book = self.make_book()
        helpers.write_bytes(self.backup / "Author" / "Book" / "02.mp3", b"older backup")
        before = snapshot(self.lib)
        code, text = run_tool([str(self.lib), *self.ARGS, "--backup-root", str(self.backup)])
        self.assertEqual(code, 1)
        self.assertEqual(snapshot(self.lib), before)
        self.assertEqual((self.backup / "Author" / "Book" / "02.mp3").read_bytes(), b"older backup")
        self.assertFalse((book / "Book.m4b").exists())
        self.assertFalse((book / "Book.m4b.part").exists())

    def test_in_place_puts_files_back_when_a_move_fails(self):
        book = self.make_book()
        originals = {p.name: p.read_bytes() for p in book.glob("*.mp3")}
        real_move = to_m4b.move_file
        calls = {"n": 0}

        def flaky(src, dst):
            calls["n"] += 1
            if calls["n"] == 3:
                raise OSError("simulated network error")
            return real_move(src, dst)

        with mock.patch.object(to_m4b, "move_file", side_effect=flaky):
            code, text = run_tool([str(self.lib), *self.ARGS, "--backup-root", str(self.backup)])
        self.assertEqual(code, 1)
        self.assertIn("were put back", text)
        self.assertEqual({p.name: p.read_bytes() for p in book.glob("*.mp3")}, originals)
        self.assertFalse((book / "Book.m4b").exists())
        self.assertFalse((book / "Book.m4b.part").exists())
        self.assertFalse(list(self.backup.rglob("*.mp3")))

    def test_in_place_failed_encoding_leaves_the_book_folder_as_it_was(self):
        self.make_book()
        before = snapshot(self.lib)
        with mock.patch.dict(os.environ, {"FAKE_FFMPEG_MODE": "fail"}):
            code, text = run_tool([str(self.lib), *self.ARGS, "--backup-root", str(self.backup)])
        self.assertEqual(code, 1)
        self.assertEqual(snapshot(self.lib), before)
        self.assertTrue(list((self.backup / "_logs").glob("*.log")))

    def test_in_place_dry_run_changes_nothing(self):
        self.make_book()
        before = snapshot(self.lib)
        code, text = run_tool([str(self.lib), "--in-place", "--backup-root", str(self.backup)])
        self.assertEqual(code, 0, text)
        self.assertEqual(snapshot(self.lib), before)

    def test_move_file_copies_and_verifies_across_drives(self):
        src = helpers.write_bytes(self.base / "a" / "x.mp3", b"data" * 1000)
        dst = self.base / "b" / "x.mp3"
        import errno

        def no_rename(*a, **k):
            raise OSError(errno.EXDEV, "cross-device")

        with mock.patch.object(os, "rename", side_effect=no_rename), mock.patch.object(os, "link", side_effect=no_rename, create=True):
            to_m4b.move_file(src, dst)
        self.assertFalse(src.exists())
        self.assertEqual(dst.read_bytes(), b"data" * 1000)
        src2 = helpers.write_bytes(self.base / "a" / "y.mp3", b"y")
        helpers.write_bytes(self.base / "b" / "y.mp3", b"other")
        with self.assertRaises(OSError):
            to_m4b.move_file(src2, self.base / "b" / "y.mp3")
        self.assertTrue(src2.exists())
        self.assertEqual((self.base / "b" / "y.mp3").read_bytes(), b"other")


if __name__ == "__main__":
    unittest.main()
