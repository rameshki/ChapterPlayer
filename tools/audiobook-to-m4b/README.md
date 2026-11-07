# Audiobook to M4B

A small helper that turns every audiobook made of **many audio files** (one MP3
per chapter, per track or per CD) into **one `.m4b` file with chapters**, one
chapter per original file.

Why: some players (Jellyfin, for one) handle a book that is many files badly.
A single `.m4b` with chapters works everywhere: Jellyfin, Audiobookshelf, Plex,
Apple Books, most phone players.

This tool is separate from the ChapterPlayer app. It is a single Python script
(`to_m4b.py`) that needs only Python and ffmpeg.

> **Status: the real encoding has NOT been run yet.** ffmpeg was not available
> on the machine this was written on, so everything was tested with stand-in
> programs that pretend to be ffmpeg (96 automated tests). The first trial on
> two or three of your own books (step 3 below) is the real test. Check those
> results before converting the rest.

## What it does

1. Walks your library folder and finds the books that are a set of audio files
   (`.mp3 .m4a .aac .flac .ogg .opus .wma .wav`):
   * a folder with two or more audio files in it, or
   * a folder whose sub-folders are discs or parts named just that (`CD1`,
     `Disc 2`, `Disk 01`, `Part 3`, `CD 1 of 2`), which are joined in order
     (CD1, CD2, ... CD10). A folder like `Part 1 - The Final Empire` is NOT a
     disc: such folders are often separate books, so each is treated on its own.
   * Nested layouts like `Author/Series/Book` work. An author folder is not a book,
     and the library folder itself is never a book.
   * Links (symbolic links, Windows junctions) are reported and not followed, so
     nothing is scanned twice and a link loop cannot trap the scan.
2. Skips what it should not touch: folders that already hold a single file
   (an `.m4b` or one MP3), and anything it is **not sure about**:
   * files with clearly different album tags in one folder (several books dumped
     together),
   * files without (or only partly with) album tags whose names do not look like
     numbered parts of one book (`Book A.mp3`, `Another Story.mp3`: probably
     separate single-file books),
   * a `Bonus` or `Extras` folder with audio next to a book's disc folders,
   * names containing a line break, or (on Windows) ending in a dot or space,
   * different audio formats mixed in one folder,
   * an `.m4b` next to other audio files,
   * loose audio files next to sub-folders with more audio.
   These are listed as "UNSURE, skipped" with the reason, so you can sort them out
   by hand.
3. Puts the files in order: by the disc and track number tags when **every** file
   has them and none repeat, otherwise by file name in natural order
   (`Chapter 2` before `Chapter 10`). Gaps, repeated numbers and a tag order that
   differs from the file name order are reported in the plan, not hidden.
4. Builds one `.m4b`: AAC audio, one chapter per source file (times measured by
   ffprobe), the cover picture (embedded in the files, or `cover.jpg`,
   `folder.jpg`, `front.jpg`, `cover.png` in the folder), and the tags:
   title, author (artist and album artist), narrator (composer), album, genre
   "Audiobook", year, comment, and series (see "Tags" below).
5. Checks the new file before it counts: the length of its AUDIO track (not the
   file's overall length, which the chapter list alone can make look complete)
   must match the total of the originals (within 1 second or 0.1%, whichever is
   larger), the last chapter must not end after the audio, and it must have one
   chapter per original file and the cover when there was one. Only then is it
   renamed from `Book.m4b.part` to `Book.m4b`.
6. Files that differ in sample rate or mono/stereo are resampled to one format.
   When the files do not all have the same streams in the same order (for
   example an `.m4a` whose cover comes before the audio), each file's audio is
   taken explicitly instead of relying on the stream order.

## The safety model, in plain words

* **By default nothing is changed.** Without `--apply` the tool only reads your
  files and prints what it would do (a "dry run").
* **With `--apply --output-root DIR`** the new `.m4b` files go into a **separate
  folder** with the same layout as your library (`DIR/Author/Book/Book.m4b`).
  This is the only way the tool writes books.
* **Your originals are only ever read.** The tool never moves, renames or
  deletes any original file, and never writes into the library folder.
  Audiobookshelf and Plex keep seeing exactly what they saw before.
* The output folder (and the report) must be outside the library folder. This is
  checked on the real location, so a mapped drive, a link or a junction that
  leads into the library is refused too.
* **Nothing of yours is deleted.** If a file with the output name already exists
  and does not match the book, it is left alone and the book is reported as
  failed. Two books whose output names would be the same (for example `Book?`
  and `Book_`, or names differing only in capital letters) are both skipped and
  reported.
* **Half-finished files never get the final name.** The file is written as
  `Book.m4b.part` and renamed only after the checks pass. If ffmpeg fails, the
  checks fail, or you press Ctrl-C, the tool removes only the `.part` file it
  created in this run, plus its own temporary files. A `.part` left behind by an
  earlier interrupted run is replaced only when it is more than a minute old
  (the report says so); a newer one is left alone and that book is skipped.
* **Safe to run again.** A book whose `.m4b` already exists and passes the checks
  is skipped, so you can stop at any time and continue later.
* **Replacing the originals is up to you.** Once you have checked the new books,
  you can point Jellyfin at the output folder and leave the library as it is,
  or, if you want the library itself to hold the `.m4b` files, move the
  originals out and the new files in yourself, one book or one author at a time.
  The tool deliberately does not do this step. Audiobookshelf and Plex rescan
  books whose files change, and listening positions on those books may not
  carry over.

## Install

1. **Python 3.9 or newer.** Windows: from python.org or `winget install Python.Python.3.12`.
   macOS: `brew install python`. Linux and most NAS systems already have it
   (`python3 --version`).
2. **ffmpeg** (ffprobe comes with it):
   * Windows: `winget install Gyan.FFmpeg` (or `scoop install ffmpeg`, or
     `choco install ffmpeg`). Open a new terminal afterwards.
   * macOS: `brew install ffmpeg`
   * Debian/Ubuntu: `sudo apt install ffmpeg`; Fedora: `sudo dnf install ffmpeg`
   * Version 5.1 or newer is recommended (it handles very long Windows paths).
   If ffmpeg is installed somewhere unusual, pass `--ffmpeg PATH` and
   `--ffprobe PATH`.
3. Get the script: copy the `tools/audiobook-to-m4b` folder anywhere, or use it
   from a clone of this repository. Nothing else to install.

On Windows type `python`, on macOS/Linux usually `python3`. The examples below
use `python`.

## Recommended first steps

1. **Dry run** (changes nothing; reads every audio file's details, which takes a
   while on a large library):

   ```
   python to_m4b.py "D:\Audiobooks" --output-root "E:\Audiobooks-m4b"
   ```

   Read the plan. Each book shows where its file would go, the order used, the
   chapter names, the tags chosen, and any warnings (`!`). Books marked
   `UNSURE` are skipped; fix them by hand if you want them converted. A full
   report is written to `E:\Audiobooks-m4b\to_m4b-report.json` (and `.csv`, which
   opens in Excel).

2. **Try two or three books for real**:

   ```
   python to_m4b.py "D:\Audiobooks" --output-root "E:\Audiobooks-m4b" --apply --limit 3
   ```

   Or pick them: `--only "Some Author/*"`.

3. **Check those files.** Play them, skip through the chapters, look at the
   cover and the title/author. Add the output folder as a library in
   Jellyfin (and, if you like, as a test library in Audiobookshelf) and see that
   they show up as expected.

4. **Convert the rest** (already finished books are skipped):

   ```
   python to_m4b.py "D:\Audiobooks" --output-root "E:\Audiobooks-m4b" --apply
   ```

   Add `--jobs 2` (or more) to convert several books at once on a machine with
   several CPU cores. The conversion runs at low priority so the computer stays
   usable. You can press Ctrl-C at any time and run the same command again later.

## About quality (lossy re-encode)

MP3 and AAC are both "lossy" formats: each throws away sound the ear hardly
notices. Turning an MP3 into AAC decodes it and encodes it again, which loses a
little more. For speech this is normally not audible at the bitrates used here.

* The tool takes the average bitrate of the originals and keeps it between 48k
  and 128k (more than 128k does not help a voice). A mono book stays mono, and
  the sample rate is kept (up to 44.1 kHz).
* Stereo spoken word at 64k AAC is normally fine; mono at 48k too.
* Choose your own with `--bitrate 64k`.
* Your originals are untouched, so you can always build again with other settings.

## Options

| Option | What it does |
| --- | --- |
| `--apply` | Really convert. Without it: dry run. |
| `--output-root DIR` | Separate folder for the new files, outside the library. Needed with `--apply`. |
| `--report FILE.json` | Report file; must end in `.json`, a `.csv` with the same name is written next to it. It must be outside the library, and an existing file of that name is only replaced when it is an earlier report of this tool. Default: `to_m4b-report.json` in the output folder. |
| `--limit N` | Stop after N books (books already done do not count). |
| `--only PATTERN`, `--exclude PATTERN` | Choose books by their path inside the library, e.g. `"Author/*"`, `"*Discworld*"`. Can be repeated. Not case sensitive. |
| `--jobs N` | Books converted at the same time (default 1). |
| `--bitrate 64k` | Fixed AAC bitrate. |
| `--strict` | Fail a book when ffmpeg reports any decoding error. Without it such a book is kept when all the checks pass, and the errors are noted in the report. |
| `--chapter-names tags\|files\|numbered` | `tags` (default): the files' title tags when they make sense, else the file names; `files`: cleaned file names (`03 - The Storm.mp3` becomes "The Storm", `03.mp3` becomes "Chapter 03"); `numbered`: Chapter 01, 02, ... |
| `--min-files N` | Smallest number of files that makes a book (default 2). |
| `--ffmpeg PATH`, `--ffprobe PATH` | Where ffmpeg/ffprobe are, if not found automatically. |
| `--normal-priority` | Do not lower the priority. |
| `--verbose` | Show every chapter and every skipped folder. |

## Tags

* **Title**: the album tag (without "Disc 1" style endings), else the folder name.
* **Author** (artist and album artist): the album artist tag, else the artist tag,
  else the top folder name (`Author/Book`). The plan says which one it used.
* **Narrator**: the composer tag (that is where Audiobookshelf keeps it), when the
  files have one.
* **Series**: the `series` tag, else the `grouping` tag, else the parent folder
  name in an `Author/Series/Book` layout. The number comes from a `series-part`
  tag or from the start of the book folder name (`03 - Title`, `Book 3 - Title`).
  It is written as `grouping` ("Series #3"), plus `series` and `series-part`.
  Note: ffmpeg's MP4 writer keeps `grouping` but is expected to drop the custom
  `series`/`series-part` names. ffmpeg's `-movflags +use_metadata_tags` would
  keep them, but its documentation only says "use mdta atom for metadata", and
  that mode stores ALL tags in a different place than the usual iTunes tags
  (and, as far as the ffmpeg source shows, without the cover), so many players
  would lose the title, author and cover. The tool therefore does not use it.
  Check what your servers show for the series after the trial.
* **Genre** is always "Audiobook". Year and comment are copied when present.

## Running on a NAS or against a network share

The conversion reads every byte of every book and keeps a CPU core busy.

* **Best: run it on the NAS itself** over SSH, if the NAS has Python 3 and ffmpeg
  (Synology, QNAP, Unraid and TrueNAS can usually get both, e.g. via a package
  centre, Entware or a Docker container). Example:

  ```
  ssh admin@my-nas
  python3 to_m4b.py /volume1/audiobooks --output-root /volume1/audiobooks-m4b --apply --limit 3
  ```

  Use `screen` or `tmux` (or `nohup ... &`) so the run continues after you close
  the SSH window. On a small NAS CPU keep `--jobs 1`.

* **From a PC against a mounted share** works too: Windows paths like
  `\\NAS\audiobooks` or a mapped drive `Z:\`, macOS `/Volumes/audiobooks`, Linux
  `/mnt/audiobooks`. Every book then travels over the network twice (read, then
  the result written back), and a network hiccup fails that book (it is retried
  on the next run). For a big library it is much faster to **copy the books to a
  local disk first**, convert there, and copy the results back, or to write the
  output to a local disk.

* Make sure the output folder is **not** inside a folder that Audiobookshelf,
  Plex or Jellyfin already scan, unless you mean them to see it.

## The report

`to_m4b-report.json` (and `to_m4b-report.csv`) in the output folder lists every
folder looked at, with: the source folder, the number of files, the layout
(files or disc folders), the action (`converted`, `skipped`, `ambiguous`,
`failed`, `dry-run`), the reasons and warnings, the planned order and chapters,
the tags, the sizes, the lengths and how long it took. It is updated after each
book and kept across runs (a later run updates the books it looked at again).

When a book fails, ffmpeg's messages are saved in `_logs/` in the output folder.

A dry run with `--output-root` creates that folder to hold the report (nothing
else); a dry run without `--output-root` or `--report` writes no file at all.

## Troubleshooting

* **"ffprobe was not found" / "ffmpeg was not found"**: install ffmpeg (see
  Install), open a new terminal, or give the paths with `--ffmpeg` / `--ffprobe`.
* **"UNSURE, skipped: files carry different album tags"**: the folder seems to
  hold more than one book. Put each book in its own folder, or fix the album
  tags (e.g. with Mp3tag), then run again.
* **"audio length ... expected ..." after a conversion**: the new file's audio
  does not match the originals. A common cause is an MP3 without a proper length
  header (some old rips): ffprobe then only estimates its length. Re-saving the
  files with a tag editor that rewrites the header, or converting that book
  by hand, helps. The book is not placed until the lengths match.
* **"not enough free space"**: a book that would not fit is skipped (the others
  go on). Free some space or choose another output drive, and run again.
* **"UNSURE, skipped: the files have no album tag and their names do not look
  like numbered parts"**: the folder may hold separate single-file books. If it
  really is one book, give the files an album tag or number them (`01 ...`,
  `02 ...`).
* **"is a symbolic link or junction; not followed"**: run the tool on the link's
  target folder itself if you want those books.
* **"would get the same output file name"**: two folders differ only by
  characters Windows cannot use or by capital letters. Rename one of them.
* **"a file with the output name already exists and does not match"**: an older
  or different `.m4b` is in the output folder. Move it away (the tool never
  deletes it) and run again.
* **Chapter names look wrong**: try `--chapter-names files` or `numbered`.
* **The order is wrong**: look at the warnings in the plan. If the track tags
  are wrong, fix them or rename the files so they sort correctly.
* **Very long paths on Windows**: the script handles them itself; ffmpeg needs
  version 5.1 or newer for paths over 260 characters.

## Tests

```
python -m unittest discover tools/audiobook-to-m4b/tests
```

The tests use only synthetic data and two small stand-in programs
(`tests/fakes/fake_ffprobe.py`, `tests/fakes/fake_ffmpeg.py`) that answer like
ffprobe and ffmpeg, so everything except the real encoding is tested without
ffmpeg. `tests/test_real_ffmpeg.py` makes three short sine-tone MP3s with the real
ffmpeg and converts them; it is skipped automatically when ffmpeg is not installed.

To see a dry run without ffmpeg, build the synthetic demo library and point the
script at the stand-in ffprobe:

```
python tools/audiobook-to-m4b/tests/helpers.py /tmp/demo
python tools/audiobook-to-m4b/to_m4b.py /tmp/demo/library --ffprobe tools/audiobook-to-m4b/tests/fakes/fake_ffprobe.py
```

## How it calls ffmpeg (for the curious)

The tags and chapters come from an ffmetadata file (`;FFMETADATA1`, one
`[CHAPTER]` block per file, `TIMEBASE=1/1000`, input 0), the cover is input 1,
and the audio follows. When every file has the same streams in the same order,
the files are joined with ffmpeg's concat demuxer (a list file):

```
ffmpeg -n -f ffmetadata -i chapters.ffmeta [-i cover] -f concat -safe 0 -i files.ffconcat
       -map 2:a:0 -af aresample=44100,aformat=sample_rates=44100:channel_layouts=stereo
       [-map 1:v:0 -c:v copy -disposition:v:0 attached_pic]
       -map_metadata 0 -map_chapters 0 -c:a aac -b:a 64k -ac 2 -ar 44100
       -movflags +faststart -f mp4 Book.m4b.part
```

Otherwise every file is its own input and its audio is picked explicitly:
`-filter_complex "[2:a:0][3:a:0]...concat=n=N:v=0:a=1,aresample=...,aformat=...[aout]" -map "[aout]"`.
`-n` makes ffmpeg refuse to overwrite anything.

All programs are started with argument lists (never through a shell), so names
with spaces, quotes or other characters are passed exactly as they are.
