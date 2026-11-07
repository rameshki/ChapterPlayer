"""Which folders are books: flat sets, disc folders, nesting, skips and ambiguity."""

import os
import tempfile
import unittest
from pathlib import Path

import helpers
from helpers import fake_audio, fake_cover, write_bytes

import to_m4b


def by_rel(cands):
    return {c.rel: c for c in cands}


class DetectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "lib"

    def tearDown(self):
        helpers.remove_tree(self.tmp.name)
        try:
            self.tmp.cleanup()
        except OSError:
            pass

    def scan(self, **kw):
        return by_rel(to_m4b.scan_library(self.root, **kw))

    def test_flat_book_is_found_and_author_folder_is_not_a_book(self):
        for i in (1, 2, 3):
            fake_audio(self.root / "Author" / "Book" / f"{i}.mp3")
        c = self.scan()
        self.assertEqual(list(c), ["Author/Book"])
        self.assertEqual(c["Author/Book"].status, "candidate")
        self.assertEqual(c["Author/Book"].layout, "files")
        self.assertEqual(len(c["Author/Book"].files), 3)

    def test_nested_author_series_book(self):
        for b in ("01 - One", "02 - Two"):
            for i in (1, 2):
                fake_audio(self.root / "Author" / "Series" / b / f"{i}.mp3")
        c = self.scan()
        self.assertEqual(sorted(c), ["Author/Series/01 - One", "Author/Series/02 - Two"])
        self.assertTrue(all(x.status == "candidate" for x in c.values()))

    def test_single_file_book_is_skipped(self):
        fake_audio(self.root / "A" / "One" / "book.mp3")
        fake_audio(self.root / "A" / "Done" / "book.m4b")
        c = self.scan()
        self.assertEqual(c["A/One"].status, "single")
        self.assertEqual(c["A/Done"].status, "single")

    def test_min_files(self):
        for i in (1, 2):
            fake_audio(self.root / "B" / f"{i}.mp3")
        self.assertEqual(self.scan(min_files=3)["B"].status, "too-few")
        self.assertEqual(self.scan(min_files=2)["B"].status, "candidate")

    def test_disc_folders_merge_in_natural_order(self):
        for d in ("CD10", "CD2", "CD1"):
            for t in ("Track 2.mp3", "Track 1.mp3"):
                fake_audio(self.root / "Book" / d / t)
        fake_cover(self.root / "Book" / "folder.jpg")
        c = self.scan()
        self.assertEqual(list(c), ["Book"])
        book = c["Book"]
        self.assertEqual(book.layout, "discs")
        self.assertEqual([(f.disc_folder, f.path.name) for f in book.files], [
            ("CD1", "Track 1.mp3"), ("CD1", "Track 2.mp3"), ("CD2", "Track 1.mp3"),
            ("CD2", "Track 2.mp3"), ("CD10", "Track 1.mp3"), ("CD10", "Track 2.mp3")])
        self.assertEqual([f.disc for f in book.files], [1, 1, 2, 2, 3, 3])
        self.assertEqual([p.name for p in book.covers], ["folder.jpg"])

    def test_disc_folder_names(self):
        for name in ("CD1", "cd 2", "Disc 3", "Disk 01", "Part 3", "disc_4", "CD 1 of 2", "pt.2"):
            self.assertIsNotNone(to_m4b.disc_number(name), name)
        for name in ("Partridge", "Discworld", "Volume 1", "Book 2", "CD", "Extras", "Disc 1 - The Start",
                     "Part 1 - The Final Empire", "Part 2 (Unabridged)"):
            self.assertIsNone(to_m4b.disc_number(name), name)

    def test_disc_folders_with_one_file_each_make_one_book(self):
        fake_audio(self.root / "Book" / "Disc 1" / "a.mp3")
        fake_audio(self.root / "Book" / "Disc 2" / "a.mp3")
        c = self.scan()
        self.assertEqual(c["Book"].status, "candidate")
        self.assertEqual(len(c["Book"].files), 2)

    def test_audio_next_to_disc_folders_is_ambiguous(self):
        fake_audio(self.root / "Book" / "intro.mp3")
        fake_audio(self.root / "Book" / "CD1" / "a.mp3")
        fake_audio(self.root / "Book" / "CD2" / "a.mp3")
        c = self.scan()
        self.assertEqual(c["Book"].status, "ambiguous")

    def test_loose_audio_in_a_folder_with_book_subfolders_is_ambiguous(self):
        fake_audio(self.root / "Author" / "loose1.mp3")
        fake_audio(self.root / "Author" / "loose2.mp3")
        fake_audio(self.root / "Author" / "Book" / "1.mp3")
        fake_audio(self.root / "Author" / "Book" / "2.mp3")
        c = self.scan()
        self.assertEqual(c["Author"].status, "ambiguous")
        self.assertEqual(c["Author/Book"].status, "candidate")

    def test_mixed_formats_and_m4b_next_to_mp3_are_ambiguous(self):
        fake_audio(self.root / "Mixed" / "1.mp3")
        fake_audio(self.root / "Mixed" / "2.flac")
        fake_audio(self.root / "Half" / "1.mp3")
        fake_audio(self.root / "Half" / "2.mp3")
        fake_audio(self.root / "Half" / "Half.m4b")
        c = self.scan()
        self.assertEqual(c["Mixed"].status, "ambiguous")
        self.assertEqual(c["Half"].status, "ambiguous")

    def test_non_audio_files_are_ignored_and_covers_found(self):
        fake_audio(self.root / "Book" / "1.mp3")
        fake_audio(self.root / "Book" / "2.mp3")
        write_bytes(self.root / "Book" / "notes.txt", b"x")
        write_bytes(self.root / "Book" / "Cover.JPG", b"x")
        c = self.scan()["Book"]
        self.assertEqual(len(c.files), 2)
        self.assertEqual([p.name for p in c.covers], ["Cover.JPG"])

    def test_hidden_nas_and_excluded_folders_are_skipped(self):
        for d in (".hidden", "@eaDir", "_logs", "Out"):
            fake_audio(self.root / d / "1.mp3")
            fake_audio(self.root / d / "2.mp3")
        c = self.scan(exclude_dirs=[self.root / "Out"])
        self.assertEqual(c, {})

    def test_the_library_root_is_never_a_book(self):
        fake_audio(self.root / "Book A.mp3")
        fake_audio(self.root / "Book B.mp3")
        fake_audio(self.root / "CD1" / "x.mp3")
        c = self.scan()
        self.assertEqual(list(c), ["."])
        self.assertEqual(c["."].status, "ambiguous")
        self.assertIn("never treated as a book", c["."].reasons[0])

    def test_part_folders_with_titles_are_separate_books_not_discs(self):
        for part in ("Part 1 - The First", "Part 2 - The Second"):
            for i in (1, 2):
                fake_audio(self.root / "Series" / part / f"{i:02d}.mp3")
        c = self.scan()
        self.assertEqual(sorted(c), ["Series/Part 1 - The First", "Series/Part 2 - The Second"])
        self.assertTrue(all(x.layout == "files" and x.status == "candidate" for x in c.values()))

    def test_symlinks_and_junctions_are_reported_not_followed(self):
        for i in (1, 2):
            fake_audio(self.root / "Author" / "Book" / f"{i}.mp3")
        link = self.root / "Alias"
        try:
            if to_m4b.IS_WINDOWS:
                import _winapi
                _winapi.CreateJunction(str(self.root / "Author"), str(link))
            else:
                os.symlink(str(self.root / "Author"), str(link))
        except (OSError, AttributeError, ImportError) as e:
            self.skipTest(f"cannot create a link here: {e}")
        loop = self.root / "Author" / "Book" / "Loop"
        try:
            if to_m4b.IS_WINDOWS:
                _winapi.CreateJunction(str(self.root), str(loop))
            else:
                os.symlink(str(self.root), str(loop))
        except OSError:
            pass
        c = self.scan()
        self.assertEqual(c["Author/Book"].status, "candidate")
        self.assertEqual(c["Alias"].status, "ambiguous")
        self.assertEqual(c["Alias"].layout, "link")
        self.assertNotIn("Alias/Book", c)
        if "Author/Book/Loop" in c:
            self.assertEqual(c["Author/Book/Loop"].layout, "link")

    @unittest.skipUnless(to_m4b.IS_WINDOWS, "trailing dots only matter on Windows")
    def test_names_ending_in_a_dot_are_unsure_on_windows(self):
        for i in (1, 2):
            fake_audio(Path(to_m4b.ext(self.root / "Author")) .joinpath("Title.", f"{i}.mp3"))
        c = self.scan()
        self.assertEqual(c["Author/Title."].status, "ambiguous")
        self.assertIn("ends with a dot or space", c["Author/Title."].reasons[0])

    @unittest.skipIf(to_m4b.IS_WINDOWS, "Windows does not allow line breaks in names")
    def test_line_breaks_in_names_are_unsure(self):
        fake_audio(self.root / "Book" / "01\nfile '02.mp3")
        fake_audio(self.root / "Book" / "02.mp3")
        self.assertEqual(self.scan()["Book"].status, "ambiguous")

    def test_other_subfolders_of_a_disc_book_are_noted(self):
        fake_audio(self.root / "Book" / "CD1" / "a.mp3")
        fake_audio(self.root / "Book" / "CD2" / "a.mp3")
        fake_audio(self.root / "Book" / "Extras" / "x1.mp3")
        fake_audio(self.root / "Book" / "Extras" / "x2.mp3")
        c = self.scan()
        self.assertEqual(c["Book"].status, "candidate")
        self.assertTrue(any("Extras" in n for n in c["Book"].notes))
        self.assertEqual(c["Book/Extras"].status, "ambiguous")
        self.assertIn("next to its disc folders", c["Book/Extras"].reasons[0])

    def test_demo_tree(self):
        lib = helpers.build_demo_tree(Path(self.tmp.name) / "demo")
        c = by_rel(to_m4b.scan_library(lib))
        self.assertEqual(c["Jane Doe/Standalone Book"].status, "candidate")
        self.assertEqual(c["Jane Doe/Sea Trilogy/01 - First Voyage"].status, "candidate")
        self.assertEqual(c["John Roe/Big Book"].layout, "discs")
        self.assertEqual(c["John Roe/Short Story"].status, "single")
        self.assertEqual(c["Odd Cases/Mixed Formats"].status, "ambiguous")
        self.assertEqual(c["Unsorted"].status, "candidate")  # the album check happens after probing
        self.assertNotIn("Jane Doe", c)


if __name__ == "__main__":
    unittest.main()
