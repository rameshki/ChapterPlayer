# Setting up audiobooks in Jellyfin for ChapterPlayer

A short guide for Robert (2026-11-07). It goes with the design `docs/superpowers/specs/2026-11-07-jellyfin-source-design.md`. Checked against Jellyfin 12.4 (the newest release, 2026-10-26).

## 1. Check your Jellyfin version
Dashboard, at the top. **Jellyfin 12.0 or newer** is best: before 12.0, Jellyfin reads no chapters and no narrators from audiobooks. ChapterPlayer will not work below 10.10.

## 2. Create a Books library
Dashboard, Libraries, Add Media Library. Pick the content type **Books** (not Music). Only in a Books library does Jellyfin keep your listening position: in a Music library it forgets where you stopped, the same problem Plex has. Point it at a folder that holds only audiobooks, for example `/media/Audiobooks`.

## 3. One file per book, one folder per book
**Each book should be ONE audio file, ideally `.m4b` with chapters inside.** Jellyfin cannot yet treat a folder of several MP3s as one book. It shows each file as a separate "book" with no play button for the whole thing, or hides the files altogether. This is a known, still open Jellyfin bug (#10668). Layout:

```
Audiobooks/
  Author Name/
    Book Title/
      Book Title.m4b
      cover.jpg
    Series Name 1 - First Book/
      Series Name 1 - First Book.m4b
      cover.jpg
  Another Author/
    Their Book/
      Their Book.m4b
```

The author folder is optional; name the file like its folder. `.m4b`, `.m4a`, `.mp3` and `.flac` all play on Android. On the iPhone, avoid `.ogg` / `.opus`.

**Turning a folder of MP3s into one `.m4b`:** your Audiobookshelf can do it. Open the book, then Tools, then the M4B encoder. It makes one file from the book's files. Point Jellyfin at a copy of that output, or at the ABS folder once the book is converted. Other options: m4b-tool or tone (free command-line tools; third party, not checked here).

## 4. Tags that matter
Jellyfin 12 reads these from the file's own tags:

| What you want | Tag to fill |
|---|---|
| Title | the folder / file name (fill the Title and Album tags with it too; which one wins is not checked yet) |
| Author | Album Artist (if empty: Artist) |
| Narrator | **Composer** (the convention Audiobookshelf uses too) |
| Description | Description (or Comment) |
| Publisher | Publisher |
| Chapters | chapter markers inside the `.m4b` (or ID3 chapter frames in an MP3) |

The easiest way to get them right: in Audiobookshelf, match the book (Audible or Google metadata), then Tools, **Embed metadata**, which writes the tags into the file (it keeps a backup first). Mp3tag (Windows, free) edits tags by hand. Jellyfin does not read a series from the tags. Jellyfin 12 says it reads series from book file names, but whether that works for audiobooks is not checked yet, so the series-style name in the example is a guess. ChapterPlayer will show no series for a book where Jellyfin has none.

## 5. Covers and online metadata
- Put a `cover.jpg` (or `folder.jpg`) next to each book file. Since 12.0 Jellyfin uses it for audiobooks. Otherwise Jellyfin falls back to the art inside the file.
- Plugins are optional, and all of them are third party. **Bookshelf** (books metadata from Google Books and others) was archived on 2026-06-30; Jellyfin 12 handles books itself, so you should not need it. **Smart Covers** (GeiserX, on GitHub) pulls covers out of audiobook files. Untested with ChapterPlayer. Install plugins only from Dashboard, Plugins, Catalog or from a repository you trust.
- Leave the settings for resuming audiobooks at their defaults (Dashboard, Playback, Resume: 5 minutes at the start and 5 at the end). ChapterPlayer knows about them.

## 6. Check it in the Jellyfin web app
1. After a library scan, open the book. It should show **one item** with a Play button (not a folder), the right author, and a cover.
2. Scroll down to **Chapters** (or use the chapter button in the player). The chapter names should be listed. If none are shown, the file has no chapter markers.
3. Under Cast & Crew the narrator should appear (only if the Composer tag is filled).
4. Play it, skip to about 20 minutes, let it play a minute, then stop. Close the book and reopen it: a **Resume** button and a progress bar should show. If they do not, the library is probably Music rather than Books.
5. Make a normal Jellyfin user for ChapterPlayer (or use your own). Just tell me the username when it is ready. Never put the password into a chat, a prompt or the repo.
