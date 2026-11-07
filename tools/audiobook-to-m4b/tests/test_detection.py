"""Which folders are books: flat sets, disc folders, nesting, skips and ambiguity."""

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
        self.tmp.cleanup()

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
        for name in ("CD1", "cd 2", "Disc 3", "Disk 01", "Part 3", "disc_4", "CD 1 of 2", "Disc 1 - The Start"):
            self.assertIsNotNone(to_m4b.disc_number(name), name)
        for name in ("Partridge", "Discworld", "Volume 1", "Book 2", "CD", "Extras"):
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

    def test_root_itself_can_be_a_book(self):
        fake_audio(self.root / "1.mp3")
        fake_audio(self.root / "2.mp3")
        c = self.scan()
        self.assertEqual(list(c), ["."])
        self.assertEqual(c["."].name, "lib")

    def test_other_subfolders_of_a_disc_book_are_noted(self):
        fake_audio(self.root / "Book" / "CD1" / "a.mp3")
        fake_audio(self.root / "Book" / "CD2" / "a.mp3")
        fake_audio(self.root / "Book" / "Extras" / "x1.mp3")
        fake_audio(self.root / "Book" / "Extras" / "x2.mp3")
        c = self.scan()
        self.assertEqual(c["Book"].status, "candidate")
        self.assertTrue(any("Extras" in n for n in c["Book"].notes))
        self.assertEqual(c["Book/Extras"].status, "candidate")

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
