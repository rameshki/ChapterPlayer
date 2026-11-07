"""Pure planning rules: order, chapters, tags, bitrate, cover, ffmpeg inputs, checks."""

import unittest
from pathlib import Path

import helpers  # noqa: F401  (puts the tool on sys.path)

import to_m4b
from to_m4b import Probe, SourceFile


def cand(names, rel="Author/Book", discs=None, covers=()):
    folder = Path("/lib") / rel
    files = []
    for i, n in enumerate(names):
        d = discs[i] if discs else 1
        files.append(SourceFile(folder / n, disc=d, disc_folder=f"CD{d}" if discs else ""))
    c = to_m4b.Candidate(folder, rel, "discs" if discs else "files", files=files, covers=[folder / x for x in covers])
    return c


def probes_for(c, durations=None, tags=None, **kw):
    out = {}
    for i, f in enumerate(c.files):
        t = tags[i] if tags else {}
        out[f.path] = Probe(duration=(durations[i] if durations else 100.0), tags=t,
                            bit_rate=kw.get("bit_rate", 64000), channels=kw.get("channels", 2),
                            sample_rate=kw.get("sample_rate", 44100), codec="mp3",
                            picture_codec=(kw.get("pictures") or {}).get(i))
    return out


class NaturalSortTest(unittest.TestCase):
    def test_numbers_sort_by_value(self):
        names = ["Chapter 10.mp3", "Chapter 2.mp3", "chapter 1.mp3", "Chapter 02b.mp3"]
        self.assertEqual(sorted(names, key=to_m4b.natural_key),
                         ["chapter 1.mp3", "Chapter 2.mp3", "Chapter 02b.mp3", "Chapter 10.mp3"])


class OrderTest(unittest.TestCase):
    def test_tag_order_used_when_all_unique_and_disagreement_reported(self):
        c = cand(["a.mp3", "b.mp3", "c.mp3"])
        p = probes_for(c, tags=[{"track": "3/3"}, {"track": "1/3"}, {"track": "2/3"}])
        order, source, warnings = to_m4b.choose_order(c.files, p)
        self.assertEqual(source, "tags")
        self.assertEqual([f.path.name for f in order], ["b.mp3", "c.mp3", "a.mp3"])
        self.assertTrue(any("differs from file-name order" in w for w in warnings))

    def test_filename_order_when_some_tags_missing(self):
        c = cand(["Chapter 10.mp3", "Chapter 2.mp3"])
        c.files.sort(key=lambda f: to_m4b.natural_key(f.path.name))
        p = probes_for(c, tags=[{"track": "1"}, {}])
        order, source, warnings = to_m4b.choose_order(c.files, p)
        self.assertEqual(source, "filenames")
        self.assertEqual([f.path.name for f in order], ["Chapter 2.mp3", "Chapter 10.mp3"])
        self.assertTrue(any("only 1 of 2" in w for w in warnings))

    def test_duplicate_track_numbers_fall_back_and_are_reported(self):
        c = cand(["a.mp3", "b.mp3"])
        p = probes_for(c, tags=[{"track": "1"}, {"track": "1"}])
        order, source, warnings = to_m4b.choose_order(c.files, p)
        self.assertEqual(source, "filenames")
        self.assertTrue(any("duplicate track numbers 1" in w for w in warnings))
        self.assertTrue(any("not unique" in w for w in warnings))

    def test_gaps_are_reported(self):
        c = cand(["a.mp3", "b.mp3", "c.mp3"])
        p = probes_for(c, tags=[{"track": "1"}, {"track": "2"}, {"track": "5"}])
        _o, source, warnings = to_m4b.choose_order(c.files, p)
        self.assertEqual(source, "tags")
        self.assertIn("track numbers missing: 3, 4", warnings)

    def test_file_number_gaps_reported_without_tags(self):
        c = cand(["01.mp3", "02.mp3", "04.mp3"])
        _o, source, warnings = to_m4b.choose_order(c.files, probes_for(c))
        self.assertEqual(source, "filenames")
        self.assertIn("file numbers missing: 3", warnings)

    def test_discs_with_restarting_track_numbers(self):
        c = cand(["1.mp3", "2.mp3", "1.mp3", "2.mp3"], discs=[1, 1, 2, 2])
        c.files[2].path = c.folder / "CD2" / "1.mp3"
        c.files[3].path = c.folder / "CD2" / "2.mp3"
        c.files[0].path = c.folder / "CD1" / "1.mp3"
        c.files[1].path = c.folder / "CD1" / "2.mp3"
        p = probes_for(c, tags=[{"track": "1"}, {"track": "2"}, {"track": "1"}, {"track": "2"}])
        order, source, warnings = to_m4b.choose_order(c.files, p)
        self.assertEqual(source, "tags")
        self.assertEqual(order, c.files)
        self.assertEqual([w for w in warnings if "missing" in w or "duplicate" in w], [])


class AlbumTest(unittest.TestCase):
    def test_different_albums_are_a_conflict(self):
        ps = [Probe(1, tags={"album": "Book A"}), Probe(1, tags={"album": "Book A"}), Probe(1, tags={"album": "Book B"})]
        why = to_m4b.album_conflict(ps)
        self.assertIn("'Book A' (2 files)", why)
        self.assertIn("'Book B' (1 files)", why)

    def test_disc_suffixes_and_missing_tags_are_not_a_conflict(self):
        ps = [Probe(1, tags={"album": "Big Book (Disc 1)"}), Probe(1, tags={"album": "Big Book - CD 2"}),
              Probe(1, tags={"album": "big book, disk 3 of 3"}), Probe(1, tags={})]
        self.assertIsNone(to_m4b.album_conflict(ps))
        self.assertEqual(to_m4b.strip_disc_suffix("Big Book (Disc 1)"), "Big Book")

    def test_part_suffixes_stay_different(self):
        ps = [Probe(1, tags={"album": "Saga Part 1"}), Probe(1, tags={"album": "Saga Part 2"})]
        self.assertIsNotNone(to_m4b.album_conflict(ps))

    def test_make_plan_marks_conflict_ambiguous(self):
        c = cand(["a.mp3", "b.mp3"])
        plan = to_m4b.make_plan(c, probes_for(c, tags=[{"album": "X"}, {"album": "Y"}]))
        self.assertEqual(plan.status, "ambiguous")


class ChapterTest(unittest.TestCase):
    def test_times_from_probed_durations(self):
        c = cand(["1.mp3", "2.mp3", "3.mp3"])
        p = probes_for(c, durations=[61.2346, 0.5, 3600.0004])
        chapters, _ = to_m4b.make_chapters(c.files, p, "numbered", None)
        self.assertEqual([(ch.start_ms, ch.end_ms) for ch in chapters],
                         [(0, 61235), (61235, 61735), (61735, 3661735)])
        self.assertEqual([ch.title for ch in chapters], ["Chapter 01", "Chapter 02", "Chapter 03"])

    def test_tag_titles_used_when_sensible(self):
        c = cand(["1.mp3", "2.mp3"])
        p = probes_for(c, tags=[{"title": "The Start"}, {"title": "The End"}])
        chapters, source = to_m4b.make_chapters(c.files, p, "tags", "Book")
        self.assertEqual(source, "tags")
        self.assertEqual([ch.title for ch in chapters], ["The Start", "The End"])

    def test_bad_tag_titles_fall_back_to_file_names(self):
        c = cand(["01 - The Start.mp3", "02 - The End.mp3"])
        for tags in ([{"title": "Book"}, {"title": "Book"}], [{"title": "1"}, {"title": "2"}], [{"title": "A"}, {}]):
            chapters, source = to_m4b.make_chapters(c.files, probes_for(c, tags=tags), "tags", "Book")
            self.assertTrue(source.startswith("files"), source)
            self.assertEqual([ch.title for ch in chapters], ["The Start", "The End"])

    def test_files_mode_ignores_tags(self):
        c = cand(["Chapter 03.mp3", "Chapter 04.mp3"])
        p = probes_for(c, tags=[{"title": "x"}, {"title": "y"}])
        chapters, source = to_m4b.make_chapters(c.files, p, "files", None)
        self.assertEqual(source, "files")
        self.assertEqual([ch.title for ch in chapters], ["Chapter 03", "Chapter 04"])

    def test_repeated_file_names_on_discs_get_the_disc_name(self):
        c = cand(["Track 01.mp3", "Track 02.mp3", "Track 01.mp3", "Track 02.mp3"], discs=[1, 1, 2, 2])
        for i, f in enumerate(c.files):
            f.path = c.folder / f.disc_folder / f.path.name
        chapters, source = to_m4b.make_chapters(c.files, probes_for(c), "files", None)
        self.assertEqual([ch.title for ch in chapters],
                         ["CD1 - Track 01", "CD1 - Track 02", "CD2 - Track 01", "CD2 - Track 02"])
        self.assertIn("disc folder", source)

    def test_repeated_file_names_without_discs_are_numbered(self):
        c = cand(["a/Intro.mp3", "b/Intro.mp3"])
        chapters, source = to_m4b.make_chapters(c.files, probes_for(c), "files", None)
        self.assertEqual([ch.title for ch in chapters], ["Chapter 01", "Chapter 02"])

    def test_clean_file_title(self):
        self.assertEqual(to_m4b.clean_file_title("01 – Partie 1", 1, 2), "Partie 1")
        f = to_m4b.clean_file_title
        self.assertEqual(f("03 - The Storm", 3, 2), "The Storm")
        self.assertEqual(f("003", 3, 2), "Chapter 03")
        self.assertEqual(f("Track_07", 7, 2), "Track 07")
        self.assertEqual(f("My Book - 05", 5, 2, "My Book"), "Chapter 05")
        self.assertEqual(f("My Book - Epilogue", 9, 2, "My Book"), "Epilogue")
        self.assertEqual(f("Chapter 12", 12, 2), "Chapter 12")
        self.assertEqual(f("01. Prologue", 1, 3), "Prologue")

    def test_ffmetadata_text_and_escaping(self):
        text = to_m4b.ffmetadata_text({"title": "A=B; #1 \\ x", "comment": "line1\nline2"},
                                      [to_m4b.Chapter("One", 0, 1500), to_m4b.Chapter("Two;2", 1500, 3000)])
        lines = text.split("\n")
        self.assertEqual(lines[0], ";FFMETADATA1")
        self.assertIn("title=A\\=B\\; \\#1 \\\\ x", lines)
        self.assertIn("comment=line1\\", lines)
        self.assertEqual(text.count("[CHAPTER]"), 2)
        self.assertEqual(text.count("TIMEBASE=1/1000"), 2)
        self.assertIn("START=1500\nEND=3000\ntitle=Two\\;2", text)

    def test_ffmetadata_round_trip_through_fake_parser(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("fake_ffmpeg", str(helpers.FAKE_FFMPEG))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        import tempfile
        import os
        tags = {"title": "A=B; #1 \\ x", "comment": "line1\nline2", "artist": "Zoë"}
        chs = [to_m4b.Chapter("Ünï = 1", 0, 10)]
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "m.txt")
            with open(p, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(to_m4b.ffmetadata_text(tags, chs))
            got_tags, got_chs = mod.parse_ffmeta(p)
        self.assertEqual(got_tags, tags)
        self.assertEqual(got_chs, [{"start": 0, "end": 10, "title": "Ünï = 1"}])

    def test_concat_list_escapes_quotes(self):
        text = to_m4b.concat_list_text([Path("/lib/It's here/01.mp3")])
        self.assertIn("file '", text)
        self.assertIn("It'\\''s here", text)


class AudioSettingsTest(unittest.TestCase):
    def test_bitrate_is_duration_weighted_and_clamped(self):
        self.assertEqual(to_m4b.choose_bitrate([Probe(100, bit_rate=64000), Probe(300, bit_rate=96000)]), 88)
        self.assertEqual(to_m4b.choose_bitrate([Probe(100, bit_rate=32000)]), 48)
        self.assertEqual(to_m4b.choose_bitrate([Probe(100, bit_rate=320000)]), 128)
        self.assertEqual(to_m4b.choose_bitrate([Probe(100, bit_rate=None)]), 64)
        self.assertEqual(to_m4b.choose_bitrate([Probe(100, bit_rate=320000)], 40), 40)

    def test_parse_bitrate(self):
        self.assertEqual(to_m4b.parse_bitrate("64k"), 64)
        self.assertEqual(to_m4b.parse_bitrate("96"), 96)
        self.assertEqual(to_m4b.parse_bitrate("64000"), 64)
        with self.assertRaises(Exception):
            to_m4b.parse_bitrate("fast")

    def test_mono_stays_mono_and_sample_rate_capped(self):
        self.assertEqual(to_m4b.choose_channels([Probe(1, channels=1), Probe(1, channels=1)]), 1)
        self.assertEqual(to_m4b.choose_channels([Probe(1, channels=1), Probe(1, channels=2)]), 2)
        self.assertEqual(to_m4b.choose_channels([Probe(1, channels=6)]), 2)
        self.assertEqual(to_m4b.choose_sample_rate([Probe(1, sample_rate=22050)]), 22050)
        self.assertEqual(to_m4b.choose_sample_rate([Probe(1, sample_rate=48000)]), 44100)


class TagsAndCoverTest(unittest.TestCase):
    def test_tags_from_files(self):
        c = cand(["1.mp3", "2.mp3"])
        t = {"album": "The Book", "artist": "A. Writer", "composer": "N. Reader", "date": "1999",
             "series": "Saga", "series-part": "2"}
        plan = to_m4b.make_plan(c, probes_for(c, tags=[dict(t, track="1"), dict(t, track="2")]))
        self.assertEqual(plan.status, "ok")
        self.assertEqual(plan.tags["title"], "The Book")
        self.assertEqual(plan.tags["album"], "The Book")
        self.assertEqual(plan.tags["artist"], "A. Writer")
        self.assertEqual(plan.tags["album_artist"], "A. Writer")
        self.assertEqual(plan.tags["composer"], "N. Reader")
        self.assertEqual(plan.tags["genre"], "Audiobook")
        self.assertEqual(plan.tags["date"], "1999")
        self.assertEqual(plan.tags["grouping"], "Saga #2")
        self.assertEqual(plan.tags["series"], "Saga")
        self.assertEqual(plan.tags["series-part"], "2")

    def test_untagged_book_uses_folders(self):
        c = cand(["1.mp3", "2.mp3"], rel="Some Author/Some Series/03 - Third Book")
        plan = to_m4b.make_plan(c, probes_for(c))
        self.assertEqual(plan.tags["title"], "03 - Third Book")
        self.assertEqual(plan.tags["artist"], "Some Author")
        self.assertEqual(plan.tags["series"], "Some Series")
        self.assertEqual(plan.tags["series-part"], "3")
        self.assertNotIn("composer", plan.tags)
        self.assertTrue(any("from folder" in ch or "folder name" in ch for ch in plan.choices))

    def test_author_book_layout_has_no_series(self):
        c = cand(["1.mp3", "2.mp3"], rel="Some Author/A Book")
        plan = to_m4b.make_plan(c, probes_for(c))
        self.assertNotIn("series", plan.tags)
        self.assertNotIn("grouping", plan.tags)

    def test_cover_embedded_first_then_folder_file(self):
        c = cand(["1.mp3", "2.mp3"], covers=["cover.jpg"])
        plan = to_m4b.make_plan(c, probes_for(c, pictures={1: "mjpeg"}))
        self.assertEqual((plan.cover_kind, plan.cover.name), ("embedded", "2.mp3"))
        plan = to_m4b.make_plan(c, probes_for(c, pictures={0: "bmp"}))
        self.assertEqual((plan.cover_kind, plan.cover.name), ("file", "cover.jpg"))
        self.assertTrue(any("bmp" in w for w in plan.warnings))
        plan = to_m4b.make_plan(cand(["1.mp3", "2.mp3"]), probes_for(cand(["1.mp3", "2.mp3"])))
        self.assertIsNone(plan.cover)


class FfmpegCommandTest(unittest.TestCase):
    def test_command_has_the_required_options(self):
        c = cand(["1.mp3", "2.mp3"], covers=["cover.jpg"])
        plan = to_m4b.make_plan(c, probes_for(c, channels=1, sample_rate=22050, bit_rate=32000))
        tools = to_m4b.Tools(["ffmpeg"], ["ffprobe"])
        cmd = to_m4b.build_ffmpeg_cmd(tools, plan, Path("/t/list"), Path("/t/meta"), Path("/o/B.m4b.part"))
        s = " ".join(cmd)
        for piece in ("-f concat -safe 0 -i", "-f ffmetadata -i", "-map 0:a:0", "-map 2:v:0", "-c:v copy",
                      "-disposition:v:0 attached_pic", "-map_metadata 1", "-map_chapters 1", "-c:a aac",
                      "-b:a 48k", "-ac 1", "-ar 22050", "-movflags +faststart", "-f mp4"):
            self.assertIn(piece, s)
        self.assertTrue(cmd[-1].endswith("B.m4b.part"))
        self.assertIsInstance(cmd, list)

    def test_no_cover_no_video_map(self):
        c = cand(["1.mp3", "2.mp3"])
        plan = to_m4b.make_plan(c, probes_for(c))
        cmd = to_m4b.build_ffmpeg_cmd(to_m4b.Tools(["ffmpeg"], ["ffprobe"]), plan, Path("l"), Path("m"), Path("o"))
        self.assertNotIn("2:v:0", cmd)


class VerifyTest(unittest.TestCase):
    def data(self, dur, chapters, cover):
        streams = [{"codec_type": "audio"}]
        if cover:
            streams.append({"codec_type": "video", "disposition": {"attached_pic": 1}})
        return {"format": {"duration": str(dur)}, "streams": streams, "chapters": [{}] * chapters}

    def test_tolerance_is_one_second_or_a_tenth_of_a_percent(self):
        self.assertEqual(to_m4b.verify_output(self.data(100.9, 3, False), 100, 3, False), [])
        self.assertTrue(to_m4b.verify_output(self.data(101.2, 3, False), 100, 3, False))
        self.assertEqual(to_m4b.verify_output(self.data(36003.5, 3, False), 36000, 3, False), [])
        self.assertTrue(to_m4b.verify_output(self.data(36037, 3, False), 36000, 3, False))

    def test_chapters_audio_and_cover(self):
        self.assertIn("2 chapters, expected 3", to_m4b.verify_output(self.data(100, 2, False), 100, 3, False))
        self.assertIn("cover picture missing", to_m4b.verify_output(self.data(100, 3, False), 100, 3, True))
        self.assertEqual(to_m4b.verify_output(self.data(100, 3, True), 100, 3, True), [])
        self.assertIn("no audio stream", to_m4b.verify_output({"format": {"duration": "1"}, "streams": []}, 1, 0, False))


class PathTest(unittest.TestCase):
    def test_mirror_path_keeps_layout(self):
        p = to_m4b.mirror_path(Path("/out"), "Author/Series/Book", "Book")
        self.assertEqual(p.as_posix(), "/out/Author/Series/Book/Book.m4b")
        self.assertEqual(to_m4b.mirror_path(Path("/out"), ".", "lib").as_posix(), "/out/lib.m4b")

    def test_safe_name(self):
        self.assertEqual(to_m4b.safe_name('What? A "Book": Part 1.'), "What_ A _Book__ Part 1")
        self.assertEqual(to_m4b.safe_name("CON"), "CON_")
        self.assertEqual(to_m4b.safe_name("Émile"), "Émile")

    def test_same_or_inside(self):
        self.assertTrue(to_m4b.same_or_inside(Path("/a/b/c"), Path("/a/b")))
        self.assertTrue(to_m4b.same_or_inside(Path("/a/b"), Path("/a/b")))
        self.assertFalse(to_m4b.same_or_inside(Path("/a/bc"), Path("/a/b")))

    @unittest.skipUnless(to_m4b.IS_WINDOWS, "Windows path forms")
    def test_windows_extended_paths(self):
        self.assertEqual(to_m4b.ext(r"C:\x\y"), "\\\\?\\C:\\x\\y")
        self.assertEqual(to_m4b.ext(r"\\nas\share\books"), "\\\\?\\UNC\\nas\\share\\books")
        self.assertEqual(to_m4b.plain("\\\\?\\UNC\\nas\\share\\books"), r"\\nas\share\books")
        self.assertEqual(to_m4b.plain("\\\\?\\C:\\x"), r"C:\x")


if __name__ == "__main__":
    unittest.main()
