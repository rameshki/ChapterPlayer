#!/usr/bin/env python3
"""Join audiobooks made of many audio files into one chaptered .m4b each.

Walks a library folder, finds every book that is a SET of audio files (one per
chapter, per track or per disc) and builds ONE .m4b with one chapter per source
file. Safety first:

* The default is a dry run: it only reads the files and prints a plan.
* ``--apply`` writes the new files into a SEPARATE folder (``--output-root``),
  with the same folder layout as the library.
* The originals are only ever read: this tool never moves, renames or deletes
  any of them. Once you have checked the new books you can move the originals
  yourself.

Standard library only (Python 3.9+). Calls ``ffmpeg`` and ``ffprobe`` as
external programs. Run ``python to_m4b.py --help`` for the options and see
README.md next to this file for plain-language instructions.
"""

from __future__ import annotations

import argparse
import csv
import datetime as _dt
import fnmatch
import json
import os
import re
import shutil
import stat as _stat
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
from collections import Counter
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

VERSION = "0.2.0"

AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wma", ".wav"}
# Already-joined books. Counted as audio when deciding what a folder holds.
SINGLE_BOOK_EXTS = {".m4b"}
COVER_NAMES = ["cover.jpg", "cover.jpeg", "folder.jpg", "front.jpg", "cover.png"]
COVER_CODECS = {"mjpeg", "png"}
# Folders that are never books (NAS metadata, recycle bins, our own logs).
SKIP_DIR_NAMES = {
    "_logs", "@eadir", "#recycle", "#snapshot", "$recycle.bin",
    "system volume information", ".trash", ".trashes",
}
REPORT_NAME = "to_m4b-report.json"
CSV_COLUMNS = ["source_folder", "action", "file_count", "layout", "order_source", "duration_s",
               "source_bytes", "output_bytes", "seconds", "output", "reasons", "warnings"]

MIN_BITRATE_K = 48
MAX_BITRATE_K = 128
MAX_SAMPLE_RATE = 44100
STALE_PART_SECONDS = 60
# CreateProcess limit is 32767 characters; stay well below it.
WINDOWS_CMDLINE_LIMIT = 30000

IS_WINDOWS = os.name == "nt"
FILE_ATTRIBUTE_REPARSE_POINT = 0x400

# A bare disc or part folder: "CD1", "Disc 2", "Disk 01", "Part 3", "cd 1 of 2".
# Anything after the number ("Part 1 - The Final Empire") makes it NOT a disc
# folder: such folders are often separate books. Volumes are never discs.
DISC_RE = re.compile(
    r"^(?:cd|dis[ck]|part|pt)[\s._#-]*0*(\d{1,3})(?!\d)(?:\s*(?:of|/)\s*\d+)?\s*$",
    re.IGNORECASE,
)
# A disc suffix on an album tag: "Book (Disc 1)", "Book - CD 2", "Book, Disk 1 of 3".
# "Part" is not stripped, so "Book Part 1" and "Book Part 2" stay different.
DISC_SUFFIX_RE = re.compile(
    r"[\s,:\-\u2013]*[\(\[]?\s*(?:cd|dis[ck])\s*\d+(?:\s*(?:of|/)\s*\d+)?\s*[\)\]]?\s*$",
    re.IGNORECASE,
)
# Series position at the start of a book folder name: "03 - Title", "Book 3 - Title".
SERIES_PART_RE = re.compile(
    r"^(?:book|bk\.?|vol\.?|volume|band|tome|#)?\s*(\d{1,3}(?:\.\d)?)(?!\d)\s*(?:[-\u2013\u2014.:_)]|\s-\s)\s*(?=\S)",
    re.IGNORECASE,
)
LEAD_NUMBER_RE = re.compile(r"^\s*(?:track|trk|tr)?\s*\d{1,4}\s*(?:[-_.:)\]\u2013\u2014]+\s*|\s+)", re.IGNORECASE)
NUMBER_RE = re.compile(r"([0-9]+)")
ONLY_NUMBERS_RE = re.compile(r"[\d\W_]+")

INSTALL_HELP = """\
{name} was not found. Install ffmpeg (ffprobe comes with it):
  Windows:  winget install Gyan.FFmpeg     (or: scoop install ffmpeg  /  choco install ffmpeg)
  macOS:    brew install ffmpeg
  Linux:    sudo apt install ffmpeg        (Fedora: sudo dnf install ffmpeg)
Then open a NEW terminal so it is found, or point to it with --ffmpeg PATH and --ffprobe PATH."""


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

def _absolute(p) -> str:
    """Absolute path WITHOUT Windows' trailing-dot/space stripping for paths already absolute."""
    s = str(p)
    if IS_WINDOWS:
        s = s.replace("/", "\\")
    if os.path.isabs(s) and not (IS_WINDOWS and not os.path.splitdrive(s)[0]):
        return s
    return os.path.abspath(s)


def ext(p) -> str:
    """Path string for Python's own file operations.

    On Windows this is the extended form (``\\\\?\\C:\\...`` or
    ``\\\\?\\UNC\\server\\share\\...``), which lifts the 260 character limit,
    works for network shares and keeps names ending in a dot or space intact.
    Elsewhere it is the absolute path.
    """
    s = _absolute(p)
    if not IS_WINDOWS:
        return s
    if s.startswith("\\\\?\\"):
        return s
    if s.startswith("\\\\"):
        return "\\\\?\\UNC\\" + s[2:]
    return "\\\\?\\" + s


def plain(p) -> str:
    """Path string handed to ffmpeg/ffprobe (absolute, no extended prefix).

    ffmpeg 5.1 and newer handle long Windows paths themselves.
    """
    s = _absolute(p)
    if s.startswith("\\\\?\\UNC\\"):
        return "\\\\" + s[8:]
    if s.startswith("\\\\?\\"):
        return s[4:]
    return s


def exists(p) -> bool:
    return os.path.exists(ext(p))


def file_size(p) -> int:
    try:
        return os.stat(ext(p)).st_size
    except OSError:
        return 0


def real_path(p) -> str:
    """Resolved path (symlinks, junctions, mapped drives), normalised for comparing."""
    try:
        r = os.path.realpath(_absolute(p))
    except (OSError, ValueError):
        r = _absolute(p)
    return os.path.normcase(plain(r)).rstrip("\\/")


def _file_id(p) -> Optional[Tuple[int, int]]:
    try:
        st = os.stat(ext(p))
    except OSError:
        return None
    if not st.st_ino:
        return None
    return (st.st_dev, st.st_ino)


def same_or_inside(child, parent) -> bool:
    """True when ``child`` is ``parent`` or lies below it, also through aliases.

    Compares resolved real paths, then walks up from ``child`` comparing file
    identities, which catches mapped drives versus UNC paths, junctions,
    symlinks, 8.3 short names and case differences.
    """
    c, p = real_path(child), real_path(parent)
    if c == p or c.startswith(p + os.sep):
        return True
    pid = _file_id(parent)
    if pid is None:
        return False
    cur = Path(plain(os.path.realpath(_absolute(child))))
    for anc in [cur, *cur.parents]:
        if exists(anc) and _file_id(anc) == pid:
            return True
    return False


_BAD_NAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_RESERVED = {"con", "prn", "aux", "nul"} | {f"com{i}" for i in range(1, 10)} | {f"lpt{i}" for i in range(1, 10)}


def safe_name(name: str) -> str:
    """A file or folder name that is valid on Windows, macOS and Linux."""
    s = _BAD_NAME_CHARS.sub("_", name).rstrip(" .")
    if not s:
        s = "_"
    if s.split(".")[0].casefold() in _RESERVED:
        s = s + "_"
    return s


def mirror_path(output_root: Path, rel: str, book_name: str) -> Path:
    """Where the m4b of the book at ``rel`` (relative to the source root) goes."""
    parts = [] if rel in ("", ".") else rel.split("/")
    folder = output_root.joinpath(*[safe_name(p) for p in parts])
    return folder / (safe_name(book_name) + ".m4b")


def natural_key(s: str):
    """Sort key so that 'Chapter 2' comes before 'Chapter 10'."""
    s = unicodedata.normalize("NFKC", s).casefold()
    key = []
    for tok in NUMBER_RE.split(s):
        if not tok:
            continue
        if NUMBER_RE.fullmatch(tok):
            key.append((0, int(tok), ""))
        else:
            key.append((1, 0, tok))
    return (key, s)  # ties (e.g. "02" and "2") in a fixed order


def disc_number(folder_name: str) -> Optional[int]:
    m = DISC_RE.match(folder_name.strip())
    return int(m.group(1)) if m else None


def name_problem(name: str) -> Optional[str]:
    """Why a file or folder name cannot be handed to ffmpeg safely."""
    if "\n" in name or "\r" in name:
        return f"the name {name!r} contains a line break"
    if IS_WINDOWS and name != name.rstrip(" ."):
        return f"the name '{name}' ends with a dot or space (Windows programs cannot open it reliably)"
    return None


# ---------------------------------------------------------------------------
# Scanning: which folders are books
# ---------------------------------------------------------------------------

@dataclass
class SourceFile:
    path: Path
    disc: int = 1            # 1 for flat books, the disc folder's position otherwise
    disc_folder: str = ""    # name of the disc folder ("" for flat books)


@dataclass
class Candidate:
    folder: Path
    rel: str                 # posix style, "." for the root itself
    layout: str              # "files" | "discs" | "link"
    files: List[SourceFile] = field(default_factory=list)   # natural order
    covers: List[Path] = field(default_factory=list)        # cover images in the folders
    status: str = "candidate"   # candidate | single | too-few | ambiguous
    reasons: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    @property
    def name(self) -> str:
        return self.folder.name


def _is_link(entry) -> bool:
    """A symbolic link or a Windows junction / other reparse point."""
    try:
        if entry.is_symlink():
            return True
        is_junction = getattr(entry, "is_junction", None)
        if is_junction is not None and is_junction():
            return True
        if IS_WINDOWS:
            attrs = getattr(entry.stat(follow_symlinks=False), "st_file_attributes", 0)
            return bool(attrs & FILE_ATTRIBUTE_REPARSE_POINT)
    except OSError:
        return True
    return False


def _list_dir(folder: Path):
    """(files, dirs, linked dirs) of a folder, each in natural order; (None, None, None) if unreadable."""
    files, dirs, links = [], [], []
    try:
        with os.scandir(ext(folder)) as it:
            for e in it:
                try:
                    if e.is_dir(follow_symlinks=False) or (e.is_symlink() and e.is_dir(follow_symlinks=True)):
                        (links if _is_link(e) else dirs).append(e.name)
                    elif e.is_file(follow_symlinks=True):
                        files.append(e.name)
                except OSError:
                    continue
    except OSError:
        return None, None, None
    files.sort(key=natural_key)
    dirs.sort(key=natural_key)
    links.sort(key=natural_key)
    return files, dirs, links


def _is_book_file(name: str) -> bool:
    return Path(name).suffix.lower() in AUDIO_EXTS | SINGLE_BOOK_EXTS


def _covers_in(folder: Path, names: Sequence[str]) -> List[Path]:
    lower = {n.casefold(): n for n in names}
    return [folder / lower[c] for c in COVER_NAMES if c in lower]


def scan_library(root: Path, min_files: int = 2, exclude_dirs: Sequence[Path] = ()) -> List[Candidate]:
    """Find book folders below ``root``. Never changes anything.

    Returns candidates (to convert) plus folders that were skipped or are
    ambiguous, each with its reasons, in natural path order. Symbolic links and
    junctions are reported, never followed; a folder seen twice is not scanned again.
    """
    root = Path(_absolute(root))
    out: List[Candidate] = []
    seen: set = set()
    excl = [os.path.normcase(_absolute(d)) for d in exclude_dirs]

    def rel_of(folder: Path) -> str:
        r = folder.relative_to(root).as_posix()
        return "." if r in ("", ".") else r

    def skipped_dir(path: Path, name: str) -> bool:
        if name.startswith(".") or name.casefold() in SKIP_DIR_NAMES:
            return True
        p = os.path.normcase(str(path))
        return any(p == d or p.startswith(d + os.sep) for d in excl)

    def ambiguous(folder: Path, reason: str, layout: str = "files") -> Candidate:
        c = Candidate(folder, rel_of(folder), layout, status="ambiguous")
        c.reasons.append(reason)
        out.append(c)
        return c

    def visit(folder: Path) -> bool:
        """Returns True when the folder or anything below it holds audio."""
        fid = _file_id(folder)
        if fid is not None:
            if fid in seen:
                ambiguous(folder, "this folder was already scanned under another name; skipped")
                return False
            seen.add(fid)
        files, dirs, links = _list_dir(folder)
        if files is None:
            ambiguous(folder, "folder could not be read")
            return False
        for name in links:
            if not skipped_dir(folder / name, name):
                ambiguous(folder / name, "is a symbolic link or junction; not followed (scan its target directly)", "link")
        audio = [n for n in files if _is_book_file(n)]
        disc_dirs: List[Tuple[int, str, List[str], List[str], List[str]]] = []
        other_dirs: List[str] = []
        for d in dirs:
            sub = folder / d
            if skipped_dir(sub, d):
                continue
            num = disc_number(d)
            if num is not None:
                sfiles, sdirs, _sl = _list_dir(sub)
                saudio = [n for n in (sfiles or []) if _is_book_file(n)]
                if saudio:
                    disc_dirs.append((num, d, saudio, sfiles or [], sdirs or []))
                    continue
            other_dirs.append(d)

        start = len(out)
        below = False
        other_with_audio = []
        for d in other_dirs:
            if visit(folder / d):
                below = True
                other_with_audio.append(d)

        if not audio and not disc_dirs:
            return below

        if folder == root:
            ambiguous(folder, "the library folder itself is never treated as a book (put the files in a book folder)")
            return True

        rel = rel_of(folder)
        if audio and disc_dirs:
            ambiguous(folder, "folder has its own audio files and disc folders ("
                      + ", ".join(d[1] for d in disc_dirs) + "); not sure how they fit together")
            return True

        if disc_dirs:
            disc_dirs.sort(key=lambda t: (t[0], natural_key(t[1])))
            c = Candidate(folder, rel, "discs")
            nums = [t[0] for t in disc_dirs]
            if len(set(nums)) != len(nums):
                c.status = "ambiguous"
                c.reasons.append("two disc folders have the same number: " + ", ".join(t[1] for t in disc_dirs))
            c.covers = _covers_in(folder, files)
            for pos, (_num, dname, saudio, sall, sdirs) in enumerate(disc_dirs, start=1):
                c.covers += _covers_in(folder / dname, sall)
                if sdirs:
                    c.notes.append(f"sub-folders inside {dname} are ignored: " + ", ".join(sdirs))
                for n in saudio:
                    c.files.append(SourceFile(folder / dname / n, disc=pos, disc_folder=dname))
            _classify(c, min_files)
            if other_with_audio:
                c.notes.append("other sub-folders with audio are NOT part of this book: " + ", ".join(other_with_audio))
                # Bonus / Extras folders next to disc folders: report them, do not convert them on their own.
                for sub in out[start:]:
                    if sub.status in ("candidate", "too-few", "single"):
                        sub.status = "ambiguous"
                        sub.reasons = [f"sits inside the book folder '{rel}' next to its disc folders; "
                                       "not sure whether it belongs to that book"]
            out.append(c)
            return True

        c = Candidate(folder, rel, "files")
        c.files = [SourceFile(folder / n) for n in audio]
        c.covers = _covers_in(folder, files)
        if other_with_audio:
            c.status = "ambiguous"
            c.reasons.append(
                "folder has its own audio files and sub-folders with audio too ("
                + ", ".join(other_with_audio) + "); it may be an author or series folder")
        else:
            _classify(c, min_files)
        out.append(c)
        return True

    if os.path.isdir(ext(root)):
        visit(root)
    out.sort(key=lambda c: natural_key(c.rel))
    return out


def _classify(c: Candidate, min_files: int) -> None:
    if c.status != "candidate":
        return
    names = [f.path.name for f in c.files]
    exts = sorted({Path(n).suffix.lower() for n in names})
    has_m4b = any(Path(n).suffix.lower() in SINGLE_BOOK_EXTS for n in names)
    parts = [c.name] + [f.disc_folder for f in c.files if f.disc_folder] + names
    bad = next((name_problem(n) for n in parts if name_problem(n)), None)
    if len(names) == 1:
        c.status = "single"
        c.reasons.append("already a single audio file")
    elif has_m4b:
        c.status = "ambiguous"
        c.reasons.append("already has an .m4b next to other audio files")
    elif len(exts) > 1:
        c.status = "ambiguous"
        c.reasons.append("mixed audio formats (" + ", ".join(exts) + ")")
    elif len(names) < min_files:
        c.status = "too-few"
        c.reasons.append(f"only {len(names)} audio files (--min-files is {min_files})")
    elif bad:
        c.status = "ambiguous"
        c.reasons.append(bad)


# ---------------------------------------------------------------------------
# Probing
# ---------------------------------------------------------------------------

@dataclass
class Probe:
    duration: float
    bit_rate: Optional[int] = None
    channels: Optional[int] = None
    sample_rate: Optional[int] = None
    codec: Optional[str] = None
    tags: Dict[str, str] = field(default_factory=dict)
    picture_codec: Optional[str] = None
    size: int = 0
    audio_index: int = 0                 # stream index of the (first) audio stream
    layout: Tuple[str, ...] = ("audio",)  # codec_type of every stream, in order


class ProbeError(Exception):
    pass


def _int(v) -> Optional[int]:
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None


def _float(v) -> Optional[float]:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f else None   # NaN


def stream_duration(s: dict) -> Optional[float]:
    """A stream's own duration in seconds (duration, else duration_ts * time_base)."""
    d = _float(s.get("duration"))
    if d is not None:
        return d
    ts = _int(s.get("duration_ts"))
    tb = s.get("time_base")
    if ts is not None and isinstance(tb, str) and "/" in tb:
        num, den = tb.split("/", 1)
        try:
            return ts * int(num) / int(den)
        except (ValueError, ZeroDivisionError):
            return None
    return None


def parse_probe(data: dict, size: int = 0) -> Probe:
    """Turn ffprobe's JSON (-show_format -show_streams) into a Probe."""
    fmt = data.get("format") or {}
    streams = data.get("streams") or []
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if audio is None:
        raise ProbeError("no audio stream")
    duration = _float(fmt.get("duration"))
    if duration is None:
        duration = stream_duration(audio)
    if duration is None:
        raise ProbeError("ffprobe reported no duration")
    tags: Dict[str, str] = {}
    for source in (audio.get("tags") or {}, fmt.get("tags") or {}):
        for k, v in source.items():
            if isinstance(v, str) and v.strip():
                tags[k.lower()] = v.strip()
    pic = None
    for s in streams:
        if s.get("codec_type") == "video" and (s.get("disposition") or {}).get("attached_pic") == 1:
            pic = s.get("codec_name")
            break
    bit_rate = _int(fmt.get("bit_rate")) or _int(audio.get("bit_rate"))
    if not bit_rate and size and duration > 0:
        bit_rate = int(size * 8 / duration)
    return Probe(
        duration=duration,
        bit_rate=bit_rate,
        channels=_int(audio.get("channels")),
        sample_rate=_int(audio.get("sample_rate")),
        codec=audio.get("codec_name"),
        tags=tags,
        picture_codec=pic,
        size=size,
        audio_index=streams.index(audio),
        layout=tuple(str(s.get("codec_type")) for s in streams),
    )


# ---------------------------------------------------------------------------
# Planning (pure: from scan results and probes to what will be written)
# ---------------------------------------------------------------------------

@dataclass
class Chapter:
    title: str
    start_ms: int
    end_ms: int


@dataclass
class Plan:
    candidate: Candidate
    order: List[SourceFile]
    order_source: str
    probes: Dict[Path, Probe]
    chapters: List[Chapter]
    chapter_source: str
    tags: Dict[str, str]
    bitrate_k: int
    channels: int
    sample_rate: int
    cover: Optional[Path]
    cover_kind: Optional[str]          # "embedded" | "file" | None
    total_duration: float
    est_bytes: int
    join: str = "demuxer"              # "demuxer" (list file) | "filter" (one input per file)
    output_path: Optional[Path] = None
    status: str = "ok"                 # ok | ambiguous
    reasons: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    choices: List[str] = field(default_factory=list)


def _first_number(v: Optional[str]) -> Optional[int]:
    if not v:
        return None
    m = re.match(r"\s*0*(\d+)", v)
    return int(m.group(1)) if m else None


def _tag(tags: Dict[str, str], *keys: str) -> Optional[str]:
    for k in keys:
        v = tags.get(k)
        if v:
            return v
    return None


def normalize_album(album: str) -> str:
    s = unicodedata.normalize("NFKC", album).strip()
    s = DISC_SUFFIX_RE.sub("", s)
    s = re.sub(r"\s+", " ", s).strip(" -_.,:").casefold()
    return s


def strip_disc_suffix(album: str) -> str:
    return DISC_SUFFIX_RE.sub("", album).strip(" -_.,:") or album


def album_conflict(probes: Sequence[Probe]) -> Optional[str]:
    """A reason when the files carry clearly different album tags."""
    counts: Counter = Counter()
    raw: Dict[str, str] = {}
    for p in probes:
        a = p.tags.get("album")
        if a:
            n = normalize_album(a)
            if n:
                counts[n] += 1
                raw.setdefault(n, a)
    if len(counts) <= 1:
        return None
    parts = [f"'{raw[n]}' ({c} files)" for n, c in counts.most_common()]
    return "files carry different album tags: " + ", ".join(parts)


def looks_numbered(files: Sequence[SourceFile]) -> bool:
    """True when every file name carries a number and the numbers differ within each disc.

    That is what parts or chapters of ONE book look like ('01.mp3', 'Chapter 2',
    'Track 03'), unlike a folder of separate books ('Book A.mp3', 'Book B.mp3').
    """
    groups: Dict[int, List[int]] = {}
    for f in files:
        m = NUMBER_RE.search(f.path.stem)
        if not m:
            return False
        groups.setdefault(f.disc, []).append(int(m.group(1)))
    return all(len(set(v)) == len(v) for v in groups.values())


def same_book_problem(files: Sequence[SourceFile], probes: Dict[Path, Probe]) -> Optional[str]:
    """Why the files might NOT be one book (None = they look like one book)."""
    plist = [probes[f.path] for f in files]
    conflict = album_conflict(plist)
    if conflict:
        return conflict
    tagged = sum(1 for p in plist if p.tags.get("album"))
    if tagged == len(plist):
        return None
    if looks_numbered(files):
        return None
    if tagged == 0:
        return ("the files have no album tag and their names do not look like numbered parts of one book "
                "(it may be a folder of separate books)")
    return (f"only {tagged} of {len(plist)} files have an album tag and the names do not look like "
            "numbered parts of one book")


def choose_order(files: Sequence[SourceFile], probes: Dict[Path, Probe]) -> Tuple[List[SourceFile], str, List[str]]:
    """Tag order when every file has a unique track number, else file-name order.

    ``files`` is already in natural (disc folder, file name) order. Returns the
    order, its source ("tags" or "filenames") and warnings about gaps,
    duplicates or disagreements.
    """
    warnings: List[str] = []
    natural = list(files)
    tracks = [_first_number(_tag(probes[f.path].tags, "track", "tracknumber", "trck")) for f in natural]
    discs_tag = [_first_number(_tag(probes[f.path].tags, "disc", "discnumber", "disk", "tpos")) for f in natural]
    have_tracks = sum(t is not None for t in tracks)

    def gap_report(groups: Dict[int, List[int]], what: str) -> None:
        for disc, nums in sorted(groups.items()):
            label = f"disc {disc}: " if len(groups) > 1 else ""
            dup = sorted(n for n, c in Counter(nums).items() if c > 1)
            if dup:
                warnings.append(f"{label}duplicate {what} " + ", ".join(map(str, dup)))
            uniq = sorted(set(nums))
            if uniq:
                present = set(uniq)
                missing = [n for n in range(min(uniq[0], 1), uniq[-1] + 1) if n not in present]
                if missing:
                    shown = ", ".join(map(str, missing[:10])) + (" ..." if len(missing) > 10 else "")
                    warnings.append(f"{label}{what} missing: {shown}")

    if have_tracks == len(natural):
        use_disc_tags = all(d is not None for d in discs_tag)
        keys = [((discs_tag[i] if use_disc_tags else f.disc), tracks[i]) for i, f in enumerate(natural)]
        groups: Dict[int, List[int]] = {}
        for d, t in keys:
            groups.setdefault(d, []).append(t)
        gap_report(groups, "track numbers")
        if len(set(keys)) == len(keys):
            idx = sorted(range(len(natural)), key=lambda i: (keys[i], natural_key(natural[i].path.name)))
            ordered = [natural[i] for i in idx]
            if ordered != natural:
                first = next(i for i in range(len(natural)) if ordered[i] != natural[i])
                warnings.append(
                    "track-number order differs from file-name order; using track numbers "
                    f"(position {first + 1}: '{ordered[first].path.name}' by tag, "
                    f"'{natural[first].path.name}' by name)")
            return ordered, "tags", warnings
        warnings.append("track numbers are not unique; using file-name order")
    elif have_tracks:
        warnings.append(f"only {have_tracks} of {len(natural)} files have track numbers; using file-name order")

    # File-name numbering checks (first number in each name, per disc).
    nums = [_first_number(NUMBER_RE.search(f.path.stem).group(1)) if NUMBER_RE.search(f.path.stem) else None
            for f in natural]
    if have_tracks != len(natural) and all(n is not None for n in nums):
        groups = {}
        for f, n in zip(natural, nums):
            groups.setdefault(f.disc, []).append(n)
        gap_report(groups, "file numbers")
    return natural, "filenames", warnings


def clean_file_title(stem: str, index: int, width: int, book_title: Optional[str] = None) -> str:
    """A chapter title from a file name: '03 - The Storm' -> 'The Storm', '03' -> 'Chapter 03'."""
    s = unicodedata.normalize("NFC", stem).replace("_", " ")
    s = re.sub(r"\s+", " ", s).strip()
    if book_title:
        bt = book_title.strip().casefold()
        if bt and s.casefold().startswith(bt) and len(s) > len(bt):
            rest = s[len(bt):].lstrip(" -_.:,")
            if rest:
                s = rest
    m = LEAD_NUMBER_RE.match(s)
    if m and s[m.end():].strip():
        s = s[m.end():].strip()
    if not s or ONLY_NUMBERS_RE.fullmatch(s):
        return f"Chapter {index:0{width}d}"
    return s


def tag_titles_problem(titles: Sequence[Optional[str]], album: Optional[str]) -> Optional[str]:
    """Why the files' title tags are not usable as chapter names (None = usable)."""
    if any(not t for t in titles):
        return "some files have no title tag"
    norm = [unicodedata.normalize("NFKC", t).strip().casefold() for t in titles]  # type: ignore[arg-type]
    if len(norm) > 1 and len(set(norm)) == 1:
        return "every file has the same title tag"
    if len(set(norm)) < len(norm):
        return "some title tags repeat"
    if album and any(n == album.strip().casefold() for n in norm):
        return "a title tag is just the book title"
    if any(ONLY_NUMBERS_RE.fullmatch(t) for t in titles):  # type: ignore[arg-type]
        return "some title tags are only numbers"
    if any(len(t) > 200 for t in titles):  # type: ignore[arg-type]
        return "some title tags are very long"
    return None


def make_chapters(order: Sequence[SourceFile], probes: Dict[Path, Probe], mode: str,
                  book_title: Optional[str]) -> Tuple[List[Chapter], str]:
    """One chapter per file, timed by the probed durations."""
    n = len(order)
    width = max(2, len(str(n)))
    source = mode
    titles: List[str]
    if mode == "numbered":
        titles = [f"Chapter {i:0{width}d}" for i in range(1, n + 1)]
    else:
        tag_titles = [probes[f.path].tags.get("title") for f in order]
        problem = tag_titles_problem(tag_titles, book_title) if mode == "tags" else "file names asked for"
        if mode == "tags" and problem is None:
            titles = [t.strip() for t in tag_titles]  # type: ignore[union-attr]
        else:
            if mode == "tags":
                source = f"files (title tags not used: {problem})"
            titles = [clean_file_title(f.path.stem, i, width, book_title) for i, f in enumerate(order, start=1)]
            if len(set(t.casefold() for t in titles)) < n:
                # Disc books often repeat "Track 01" on every disc: name the disc too.
                with_disc = [f"{f.disc_folder} - {t}" if f.disc_folder else t for f, t in zip(order, titles)]
                if len(set(t.casefold() for t in with_disc)) == n:
                    titles = with_disc
                    source += " with disc folder names"
                else:
                    titles = [f"Chapter {i:0{width}d}" for i in range(1, n + 1)]
                    source += ", numbered because the file names repeat"
    chapters = []
    pos = 0.0
    for f, title in zip(order, titles):
        d = probes[f.path].duration
        chapters.append(Chapter(title, int(round(pos * 1000)), int(round((pos + d) * 1000))))
        pos += d
    return chapters, source


def choose_bitrate(probes: Sequence[Probe], override_k: Optional[int] = None) -> int:
    """Duration-weighted average source bitrate in kbit/s, clamped to 48..128."""
    if override_k:
        return int(override_k)
    total = sum(p.duration for p in probes if p.bit_rate and p.duration > 0)
    if total <= 0:
        return 64
    avg = sum(p.bit_rate * p.duration for p in probes if p.bit_rate and p.duration > 0) / total
    k = int(round(avg / 1000.0))
    return max(MIN_BITRATE_K, min(MAX_BITRATE_K, k))


def choose_channels(probes: Sequence[Probe]) -> int:
    chans = [p.channels for p in probes if p.channels]
    if chans and max(chans) == 1:
        return 1
    return 2


def choose_sample_rate(probes: Sequence[Probe]) -> int:
    rates = [p.sample_rate for p in probes if p.sample_rate]
    if not rates:
        return MAX_SAMPLE_RATE
    return min(max(rates), MAX_SAMPLE_RATE)


def choose_join(probes: Sequence[Probe]) -> str:
    """'demuxer' when every file has the same stream layout (the concat demuxer
    matches streams by position), else 'filter' (each file's audio mapped explicitly)."""
    first = probes[0]
    if all(p.layout == first.layout and p.audio_index == first.audio_index for p in probes):
        return "demuxer"
    return "filter"


def parse_bitrate(text: str) -> int:
    m = re.fullmatch(r"\s*(\d+)\s*[kK]?\s*", text or "")
    if not m:
        raise argparse.ArgumentTypeError("bitrate must look like 64k or 64")
    k = int(m.group(1))
    if k > 1000:      # given in bit/s
        k = k // 1000
    if not 16 <= k <= 320:
        raise argparse.ArgumentTypeError("bitrate must be between 16k and 320k")
    return k


def estimate_bytes(duration: float, bitrate_k: int, cover_bytes: int = 0) -> int:
    return int(duration * bitrate_k * 1000 / 8 * 1.03) + cover_bytes + 256 * 1024


def make_plan(cand: Candidate, probes: Dict[Path, Probe], chapter_names: str = "tags",
              bitrate_k: Optional[int] = None) -> Plan:
    """Everything the conversion will do, decided from the scan and the probes."""
    warnings: List[str] = []
    choices: List[str] = []

    not_one_book = same_book_problem(cand.files, probes)
    order, order_source, ow = choose_order(cand.files, probes)
    warnings += ow
    oprobes = [probes[f.path] for f in order]
    tagged = sum(1 for p in oprobes if p.tags.get("album"))
    if 0 < tagged < len(oprobes) and not not_one_book:
        warnings.append(f"only {tagged} of {len(oprobes)} files have an album tag")

    # Title
    albums = [p.tags["album"] for p in oprobes if p.tags.get("album")]
    if albums:
        common = Counter(strip_disc_suffix(a) for a in albums).most_common(1)[0][0]
        title = common
        choices.append(f"title from album tag: {title}")
    else:
        title = cand.name
        choices.append(f"title from folder name: {title}")

    parts = [] if cand.rel == "." else cand.rel.split("/")
    author = None
    for keys, what in ((("album_artist", "albumartist", "album artist"), "album artist tag"), (("artist",), "artist tag")):
        vals = [_tag(p.tags, *keys) for p in oprobes]
        vals = [v for v in vals if v]
        if vals:
            author = Counter(vals).most_common(1)[0][0]
            choices.append(f"author from {what}: {author}")
            break
    if author is None and len(parts) >= 2:
        author = parts[0]
        choices.append(f"author from top folder name: {author}")
    narrator_vals = [_tag(p.tags, "composer", "narrator", "narratedby", "narrated_by") for p in oprobes]
    narrator_vals = [v for v in narrator_vals if v]
    narrator = Counter(narrator_vals).most_common(1)[0][0] if narrator_vals else None
    if narrator:
        choices.append(f"narrator (composer) from tags: {narrator}")
    date = next((v for v in (_tag(p.tags, "date", "year", "tdrc", "tyer") for p in oprobes) if v), None)
    comment = next((v for v in (_tag(p.tags, "comment", "description") for p in oprobes) if v), None)

    # Series
    series = next((v for v in (_tag(p.tags, "series", "mvnm") for p in oprobes) if v), None)
    series_src = "series tag" if series else None
    if not series:
        series = next((v for v in (p.tags.get("grouping") for p in oprobes) if v), None)
        series_src = "grouping tag" if series else None
    part = next((v for v in (_tag(p.tags, "series-part", "series_part", "mvin") for p in oprobes) if v), None)
    if not series and len(parts) >= 3:
        parent = parts[-2]
        if not author or parent.casefold() != author.casefold():
            series = parent
            series_src = "parent folder name"
    if series and not part:
        m = SERIES_PART_RE.match(cand.name)
        if m:
            part = m.group(1).lstrip("0") or "0"
            if part.startswith("."):
                part = "0" + part
    if series:
        choices.append(f"series from {series_src}: {series}" + (f", part {part}" if part else "")
                       + " (stored in grouping)")

    tags: Dict[str, str] = {"title": title, "album": title}
    if author:
        tags["artist"] = author
        tags["album_artist"] = author
    if narrator:
        tags["composer"] = narrator
    tags["genre"] = "Audiobook"
    if date:
        tags["date"] = date
    tags["comment"] = comment or f"Joined from {len(order)} files by to_m4b {VERSION}"
    if series:
        tags["grouping"] = f"{series} #{part}" if part else series
        tags["series"] = series
        if part:
            tags["series-part"] = part

    chapters, chapter_source = make_chapters(order, probes, chapter_names, title)
    choices.append(f"chapter names from {chapter_source}")

    # Cover
    cover = None
    cover_kind = None
    for f in order:
        pc = probes[f.path].picture_codec
        if pc:
            if pc in COVER_CODECS:
                cover, cover_kind = f.path, "embedded"
                choices.append(f"cover: embedded picture of {f.path.name}")
                break
            warnings.append(f"embedded picture in {f.path.name} is {pc}, not JPEG/PNG; not used")
    if cover is None and cand.covers:
        cover, cover_kind = cand.covers[0], "file"
        choices.append(f"cover: {cover.name}")
    if cover is None:
        choices.append("cover: none found")

    kbps = choose_bitrate(oprobes, bitrate_k)
    channels = choose_channels(oprobes)
    rate = choose_sample_rate(oprobes)
    join = choose_join(oprobes)
    choices.append(f"audio: AAC {kbps}k, {'mono' if channels == 1 else 'stereo'}, {rate} Hz")
    if len({p.sample_rate for p in oprobes if p.sample_rate}) > 1 or len({p.channels for p in oprobes if p.channels}) > 1:
        choices.append(f"files differ in sample rate or channels: all resampled to {rate} Hz "
                       f"{'mono' if channels == 1 else 'stereo'}")
    if join == "filter":
        choices.append("files have different stream layouts: each file's audio is taken explicitly")
    codecs = {p.codec for p in oprobes if p.codec}
    if len(codecs) > 1:
        not_one_book = not_one_book or ("files use different audio codecs: " + ", ".join(sorted(codecs)))

    total = sum(p.duration for p in oprobes)
    plan = Plan(
        candidate=cand, order=order, order_source=order_source, probes=probes, chapters=chapters,
        chapter_source=chapter_source, tags=tags, bitrate_k=kbps, channels=channels, sample_rate=rate,
        cover=cover, cover_kind=cover_kind, total_duration=total,
        est_bytes=estimate_bytes(total, kbps, file_size(cover) if cover_kind == "file" else 300 * 1024),
        join=join, warnings=warnings, choices=choices,
    )
    if not_one_book:
        plan.status = "ambiguous"
        plan.reasons.append(not_one_book)
    elif any(probes[f.path].duration <= 0 for f in order):
        plan.status = "ambiguous"
        plan.reasons.append("a file reports no length; chapters would be wrong")
    return plan


# ---------------------------------------------------------------------------
# Files handed to ffmpeg
# ---------------------------------------------------------------------------

def ffmeta_escape(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    out = []
    for ch in value:
        if ch in "=;#\\\n":
            out.append("\\" + ch)
        else:
            out.append(ch)
    return "".join(out)


def ffmetadata_text(tags: Dict[str, str], chapters: Sequence[Chapter]) -> str:
    """An ffmetadata file: global tags, then one [CHAPTER] block per chapter."""
    lines = [";FFMETADATA1"]
    for k, v in tags.items():
        lines.append(f"{k}={ffmeta_escape(v)}")
    for ch in chapters:
        lines += ["", "[CHAPTER]", "TIMEBASE=1/1000", f"START={ch.start_ms}", f"END={ch.end_ms}",
                  f"title={ffmeta_escape(ch.title)}"]
    return "\n".join(lines) + "\n"


def concat_list_text(paths: Sequence[Path]) -> str:
    """A concat demuxer list. Inside single quotes only the quote needs escaping.

    A line break in a name would start a new directive, so it is refused.
    """
    lines = ["ffconcat version 1.0"]
    for p in paths:
        s = plain(p)
        if "\n" in s or "\r" in s:
            raise ValueError(f"a file name contains a line break: {s!r}")
        lines.append("file '" + s.replace("'", "'\\''") + "'")
    return "\n".join(lines) + "\n"


@dataclass
class Tools:
    ffmpeg: List[str]
    ffprobe: List[str]


def audio_filter(plan: Plan) -> str:
    """Resample every file to one rate and channel layout, so files that differ still join cleanly."""
    layout = "mono" if plan.channels == 1 else "stereo"
    return (f"aresample={plan.sample_rate},"
            f"aformat=sample_rates={plan.sample_rate}:channel_layouts={layout}")


def build_ffmpeg_cmd(tools: Tools, plan: Plan, list_file: Path, meta_file: Path, part_file: Path) -> List[str]:
    """The ffmpeg command. Inputs: 0 = ffmetadata, 1 = cover (if any), then the audio."""
    cmd = list(tools.ffmpeg) + [
        "-hide_banner", "-nostdin", "-n", "-loglevel", "level+warning", "-nostats", "-progress", "pipe:1",
        "-f", "ffmetadata", "-i", plain(meta_file),
    ]
    first_audio = 1
    if plan.cover is not None:
        cmd += ["-i", plain(plan.cover)]
        first_audio = 2
    if plan.join == "demuxer":
        cmd += ["-f", "concat", "-safe", "0", "-i", plain(list_file)]
        cmd += ["-map", f"{first_audio}:a:0", "-af", audio_filter(plan)]
    else:
        for f in plan.order:
            cmd += ["-i", plain(f.path)]
        n = len(plan.order)
        pads = "".join(f"[{first_audio + i}:a:0]" for i in range(n))
        cmd += ["-filter_complex", f"{pads}concat=n={n}:v=0:a=1,{audio_filter(plan)}[aout]", "-map", "[aout]"]
    if plan.cover is not None:
        cmd += ["-map", "1:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic"]
    cmd += [
        "-map_metadata", "0", "-map_chapters", "0",
        "-c:a", "aac", "-b:a", f"{plan.bitrate_k}k", "-ac", str(plan.channels), "-ar", str(plan.sample_rate),
        "-movflags", "+faststart", "-f", "mp4", plain(part_file),
    ]
    return cmd


def verify_output(data: dict, expected_duration: float, expected_chapters: int, expect_cover: bool) -> List[str]:
    """Problems with a built file (empty list = it is good).

    The length is the AUDIO stream's own duration: the container duration also
    covers the chapter text track, which always runs to the last chapter end.
    """
    problems = []
    streams = data.get("streams") or []
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    tol = max(1.0, expected_duration * 0.001)
    adur = None
    if not audio:
        problems.append("no audio stream")
    else:
        adur = stream_duration(audio[0])
        if adur is None:
            problems.append("the audio stream reports no length")
        elif abs(adur - expected_duration) > tol:
            problems.append(f"audio length {adur:.2f} s, expected {expected_duration:.2f} s (+/- {tol:.2f} s)")
    chapters = data.get("chapters") or []
    if len(chapters) != expected_chapters:
        problems.append(f"{len(chapters)} chapters, expected {expected_chapters}")
    if chapters and adur is not None:
        last_end = _float(chapters[-1].get("end_time"))
        if last_end is not None and last_end > adur + tol:
            problems.append(f"the last chapter ends at {last_end:.2f} s, after the audio ({adur:.2f} s)")
    if expect_cover and not any(
            s.get("codec_type") == "video" and (s.get("disposition") or {}).get("attached_pic") == 1 for s in streams):
        problems.append("cover picture missing")
    return problems


def ffmpeg_error_lines(stderr_text: str) -> List[str]:
    """Lines ffmpeg logged at error level or worse (with -loglevel level+...)."""
    return [l.strip() for l in stderr_text.splitlines() if re.search(r"\[(error|fatal|panic)\]", l)]


# ---------------------------------------------------------------------------
# Running the external programs
# ---------------------------------------------------------------------------

def find_tool(name: str, override: Optional[str]) -> Optional[List[str]]:
    """Command prefix for ffmpeg/ffprobe. A '.py' override runs with this Python (used by the tests)."""
    if override:
        p = Path(override)
        if not exists(p):
            found = shutil.which(override)
            return [found] if found else None
        if p.suffix.lower() == ".py":
            return [sys.executable, str(p)]
        return [str(p)]
    found = shutil.which(name)
    return [found] if found else None


def _creationflags(low_priority: bool) -> int:
    if IS_WINDOWS and low_priority:
        return getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0x00004000)
    return 0


def lower_own_priority() -> None:
    """POSIX: nice this process so the ffmpeg children inherit it. Windows uses creation flags."""
    if not IS_WINDOWS and hasattr(os, "nice"):
        try:
            os.nice(10)
        except OSError:
            pass


def run_ffprobe(tools: Tools, path: Path, chapters: bool = False, low_priority: bool = True) -> dict:
    cmd = list(tools.ffprobe) + ["-v", "error", "-print_format", "json", "-show_format", "-show_streams"]
    if chapters:
        cmd.append("-show_chapters")
    cmd.append(plain(path))
    try:
        r = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=300, creationflags=_creationflags(low_priority))
    except subprocess.TimeoutExpired:
        raise ProbeError("ffprobe took too long")
    except OSError as e:
        raise ProbeError(f"could not start ffprobe: {e}")
    if r.returncode != 0:
        msg = r.stderr.decode("utf-8", "replace").strip().splitlines()
        raise ProbeError(msg[-1] if msg else f"ffprobe exit code {r.returncode}")
    try:
        return json.loads(r.stdout.decode("utf-8", "replace") or "{}")
    except json.JSONDecodeError:
        raise ProbeError("ffprobe gave no readable answer")


def probe_files(tools: Tools, files: Sequence[SourceFile], low_priority: bool = True) -> Dict[Path, Probe]:
    out = {}
    for f in files:
        try:
            out[f.path] = parse_probe(run_ffprobe(tools, f.path, low_priority=low_priority), file_size(f.path))
        except ProbeError as e:
            raise ProbeError(f"could not read {f.path.name}: {e}")
    return out


# ---------------------------------------------------------------------------
# Report file
# ---------------------------------------------------------------------------

def is_our_report(path: Path) -> bool:
    """True when an existing file is a JSON report this tool wrote earlier."""
    try:
        with open(ext(path), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError, UnicodeDecodeError):
        return False
    return isinstance(data, dict) and data.get("tool") == "to_m4b" and isinstance(data.get("books"), dict)


def is_our_csv(path: Path) -> bool:
    try:
        with open(ext(path), "r", encoding="utf-8-sig", newline="") as fh:
            first = next(csv.reader(fh), None)
    except (OSError, ValueError, UnicodeDecodeError):
        return False
    return first == CSV_COLUMNS


def report_problem(report: Path, root: Path) -> Optional[str]:
    """Why the report path must not be written (None = fine)."""
    if report.suffix.lower() != ".json":
        return f"the report file must end in .json (got '{report.name}'); a .csv is written next to it"
    if same_or_inside(report, root):
        return "the report file must be OUTSIDE the library folder"
    for p, ours, what in ((report, is_our_report, "an earlier to_m4b report"),
                          (report.with_suffix(".csv"), is_our_csv, "an earlier to_m4b CSV report")):
        if exists(p) and (not os.path.isfile(ext(p)) or not ours(p)):
            return f"'{p}' already exists and is not {what}; choose another report name"
    return None


# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------

class Stopped(Exception):
    pass


def fmt_dur(sec: float) -> str:
    sec = int(round(sec))
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}"


def fmt_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} TB"


def free_space(path: Path) -> Optional[int]:
    p = Path(_absolute(path))
    while not exists(p):
        if p.parent == p:
            return None
        p = p.parent
    try:
        return shutil.disk_usage(ext(p)).free
    except OSError:
        return None


class Run:
    def __init__(self, opts: argparse.Namespace, tools: Tools, out=None):
        self.opts = opts
        self.tools = tools
        self.out = out or sys.stdout
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.stop_reason: Optional[str] = None
        self.procs: set = set()
        self.records: Dict[str, dict] = {}
        self.reserved = 0
        self.planned = 0
        self.started = time.time()
        self.low_priority = not opts.normal_priority

    # -- output --------------------------------------------------------------
    def say(self, text: str = "") -> None:
        with self.lock:
            try:
                print(text, file=self.out, flush=True)
            except UnicodeEncodeError:
                enc = getattr(self.out, "encoding", None) or "ascii"
                print(text.encode(enc, "replace").decode(enc, "replace"), file=self.out, flush=True)

    def record(self, rel: str, rec: dict) -> None:
        with self.lock:
            self.records[rel] = rec
            if self.opts.apply:
                self.write_report(quiet=True)

    # -- report --------------------------------------------------------------
    def report_path(self) -> Optional[Path]:
        if self.opts.report:
            return Path(_absolute(self.opts.report))
        if self.opts.output_root:
            return Path(_absolute(self.opts.output_root)) / REPORT_NAME
        return None

    def write_report(self, quiet: bool = False) -> Optional[Path]:
        path = self.report_path()
        if path is None:
            return None
        csv_path = path.with_suffix(".csv")
        with self.lock:
            books: Dict[str, dict] = {}
            if exists(path):
                try:
                    with open(ext(path), "r", encoding="utf-8") as fh:
                        old = json.load(fh)
                    if old.get("source_root") == str(self.opts.root):
                        books.update(old["books"])
                except (OSError, ValueError, KeyError, AttributeError, TypeError):
                    pass
            books.update(self.records)
            data = {
                "tool": "to_m4b", "version": VERSION,
                "source_root": str(self.opts.root),
                "updated": _dt.datetime.now().isoformat(timespec="seconds"),
                "last_run": {
                    "mode": self.mode(), "started": _dt.datetime.fromtimestamp(self.started).isoformat(timespec="seconds"),
                    "argv": [a for a in sys.argv[1:]],
                },
                "books": dict(sorted(books.items(), key=lambda kv: natural_key(kv[0]))),
            }
            os.makedirs(ext(path.parent), exist_ok=True)
            tmp = path.with_name(path.name + ".tmp")
            with open(ext(tmp), "w", encoding="utf-8", newline="\n") as fh:
                json.dump(data, fh, indent=2, ensure_ascii=False)
            os.replace(ext(tmp), ext(path))
            tmp = csv_path.with_name(csv_path.name + ".tmp")
            with open(ext(tmp), "w", encoding="utf-8-sig", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(CSV_COLUMNS)
                for rel, r in data["books"].items():
                    w.writerow([
                        rel, r.get("action"), r.get("file_count"), r.get("layout"),
                        (r.get("plan") or {}).get("order_source"), r.get("source_duration_s"),
                        r.get("source_bytes"), r.get("output_bytes"), r.get("seconds"), r.get("output"),
                        "; ".join(r.get("reasons") or []), "; ".join(r.get("warnings") or []),
                    ])
            os.replace(ext(tmp), ext(csv_path))
        if not quiet:
            self.say(f"Report: {path}  (and {csv_path.name})")
        return path

    def mode(self) -> str:
        return "apply" if self.opts.apply else "dry-run"

    # -- processes -----------------------------------------------------------
    def kill_all(self) -> None:
        with self.lock:
            procs = list(self.procs)
        for p in procs:
            try:
                p.terminate()
            except OSError:
                pass


def base_record(c: Candidate) -> dict:
    return {
        "source_folder": c.rel,
        "layout": c.layout,
        "file_count": len(c.files),
        "action": None,
        "reasons": list(c.reasons),
        "warnings": list(c.notes),
        "source_bytes": sum(file_size(f.path) for f in c.files),
    }


def plan_record(plan: Plan) -> dict:
    c = plan.candidate
    return {
        "order_source": plan.order_source,
        "files": [(f.path.relative_to(c.folder)).as_posix() for f in plan.order],
        "chapters": [{"title": ch.title, "start_ms": ch.start_ms, "end_ms": ch.end_ms} for ch in plan.chapters],
        "chapter_source": plan.chapter_source,
        "tags": plan.tags,
        "bitrate_k": plan.bitrate_k,
        "channels": plan.channels,
        "sample_rate": plan.sample_rate,
        "join": plan.join,
        "cover": (plan.cover.relative_to(c.folder).as_posix() if plan.cover else None),
        "cover_kind": plan.cover_kind,
        "choices": plan.choices,
        "estimated_bytes": plan.est_bytes,
    }


def target_for(opts: argparse.Namespace, c: Candidate) -> Path:
    return mirror_path(Path(_absolute(opts.output_root)), c.rel, c.name)


def mark_collisions(cands: Sequence[Candidate], output_root: Path) -> None:
    """Books whose output names collide (after making names safe, ignoring case) are all skipped."""
    groups: Dict[str, List[Candidate]] = {}
    for c in cands:
        if c.status != "candidate":
            continue
        key = os.path.normcase(str(mirror_path(output_root, c.rel, c.name))).casefold()
        groups.setdefault(key, []).append(c)
    for group in groups.values():
        if len(group) > 1:
            for c in group:
                others = ", ".join(o.rel for o in group if o is not c)
                c.status = "ambiguous"
                c.reasons.append(f"would get the same output file name as {others}; rename one of the folders")


def log_dir(opts: argparse.Namespace) -> Path:
    return Path(_absolute(opts.output_root)) / "_logs"


def print_plan(run: Run, plan: Plan, index: int) -> None:
    c = plan.candidate
    discs = f", {len({f.disc_folder for f in c.files})} disc folders" if c.layout == "discs" else ""
    run.say(f"[{index}] {c.rel}  ({len(c.files)} files{discs}, {fmt_dur(plan.total_duration)})")
    if plan.output_path is not None:
        run.say(f"      -> {plan.output_path}")
    run.say(f"      order: {plan.order_source}; " + "; ".join(plan.choices))
    for w in plan.warnings + c.notes:
        run.say(f"      ! {w}")
    chs = plan.chapters
    show = chs if run.opts.verbose or len(chs) <= 6 else chs[:3] + [None] + chs[-2:]
    for ch in show:
        if ch is None:
            run.say(f"        ... {len(chs) - 5} more ...")
        else:
            run.say(f"        {fmt_dur(ch.start_ms / 1000)}  {ch.title}")


def check_existing(run: Run, plan: Plan, target: Path) -> Tuple[Optional[str], List[str]]:
    """'skipped' when a verified result is already there, 'failed' when something else has the name."""
    if not exists(target):
        return None, []
    try:
        data = run_ffprobe(run.tools, target, chapters=True, low_priority=run.low_priority)
        problems = verify_output(data, plan.total_duration, len(plan.order), plan.cover is not None)
    except ProbeError as e:
        problems = [str(e)]
    if not problems:
        return "skipped", ["already converted and verified"]
    return "failed", ["a file with the output name already exists and does not match this book ("
                      + "; ".join(problems) + "); it was left untouched. Move it away to rebuild."]


def convert_book(run: Run, plan: Plan, index: int) -> dict:
    """Build and verify one book in the output folder. Never touches the originals and
    never leaves a half file under the final name."""
    opts = run.opts
    c = plan.candidate
    rec = base_record(c)
    rec["plan"] = plan_record(plan)
    rec["warnings"] += plan.warnings
    rec["source_duration_s"] = round(plan.total_duration, 3)
    rec["output"] = str(plan.output_path)
    t0 = time.time()
    rec["started"] = _dt.datetime.now().isoformat(timespec="seconds")
    final = plan.output_path
    assert final is not None
    part = final.with_name(final.name + ".part")
    tmpdir = None
    stderr_text = ""
    success = False
    part_is_ours = False
    reserved = 0
    try:
        if run.stop.is_set():
            raise Stopped()
        # Free space (counting the books already being written). A book that does not fit is
        # skipped; the others keep going.
        with run.lock:
            free = free_space(final.parent)
            need = plan.est_bytes + 50 * 1024 * 1024
            if free is not None and free - run.reserved < need:
                raise RuntimeError(f"not enough free space on the output drive: needs about "
                                   f"{fmt_bytes(need)}, {fmt_bytes(max(0, free - run.reserved))} free")
            run.reserved += plan.est_bytes
            reserved = plan.est_bytes
        os.makedirs(ext(final.parent), exist_ok=True)
        if exists(part):
            age = time.time() - os.stat(ext(part)).st_mtime
            if age < STALE_PART_SECONDS:
                raise RuntimeError(f"'{part.name}' is less than a minute old: another run may be writing it; "
                                   "left alone")
            os.unlink(ext(part))
            rec["warnings"].append(f"removed '{part.name}', left over from an earlier interrupted run")
        tmpdir = Path(tempfile.mkdtemp(prefix="to_m4b-"))
        list_file = tmpdir / "files.ffconcat"
        meta_file = tmpdir / "chapters.ffmeta"
        err_file = tmpdir / "ffmpeg-stderr.txt"
        try:
            listing = concat_list_text([f.path for f in plan.order])
        except ValueError as e:
            raise RuntimeError(str(e))
        with open(ext(list_file), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(listing)
        with open(ext(meta_file), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(ffmetadata_text(plan.tags, plan.chapters))
        cmd = build_ffmpeg_cmd(run.tools, plan, list_file, meta_file, part)
        if IS_WINDOWS and len(subprocess.list2cmdline(cmd)) > WINDOWS_CMDLINE_LIMIT:
            raise RuntimeError("too many files with different stream layouts for one ffmpeg command on Windows")
        run.say(f"[{index}] {c.rel}: encoding {fmt_dur(plan.total_duration)} at {plan.bitrate_k}k ...")
        with open(ext(err_file), "wb") as errfh:
            try:
                proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=errfh,
                                        creationflags=_creationflags(run.low_priority))
            except OSError as e:
                raise RuntimeError(f"could not start ffmpeg: {e}")
            part_is_ours = True   # the name was free before ffmpeg started (and -n never overwrites)
            with run.lock:
                run.procs.add(proc)
            try:
                last_step = 0
                assert proc.stdout is not None
                for raw in proc.stdout:
                    if run.stop.is_set():
                        proc.terminate()
                        break
                    line = raw.decode("utf-8", "replace").strip()
                    if line.startswith(("out_time_us=", "out_time_ms=")) and plan.total_duration > 0:
                        try:
                            secs = int(line.split("=", 1)[1]) / 1_000_000
                        except ValueError:
                            continue
                        step = int(secs / plan.total_duration * 10)
                        if 0 < step < 10 and step > last_step:
                            last_step = step
                            run.say(f"[{index}] {c.rel}: {step * 10}%")
                rc = proc.wait()
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()
                if proc.stdout is not None:
                    proc.stdout.close()
                with run.lock:
                    run.procs.discard(proc)
        try:
            with open(ext(err_file), "r", encoding="utf-8", errors="replace") as fh:
                stderr_text = fh.read()
        except OSError:
            stderr_text = ""
        if run.stop.is_set():
            raise Stopped()
        if rc != 0:
            tail = [l for l in stderr_text.strip().splitlines() if l.strip()][-1:] or [f"exit code {rc}"]
            raise RuntimeError("ffmpeg failed: " + tail[0])
        errors = ffmpeg_error_lines(stderr_text)
        if errors:
            if opts.strict:
                raise RuntimeError(f"ffmpeg reported {len(errors)} errors (--strict): {errors[0]}")
            rec["warnings"].append(f"ffmpeg reported {len(errors)} errors while decoding, e.g. {errors[0]}")
        if not exists(part):
            raise RuntimeError("ffmpeg wrote no file")
        data = run_ffprobe(run.tools, part, chapters=True, low_priority=run.low_priority)
        problems = verify_output(data, plan.total_duration, len(plan.order), plan.cover is not None)
        if problems:
            raise RuntimeError("the new file did not pass the checks: " + "; ".join(problems))
        audio = next(s for s in data["streams"] if s.get("codec_type") == "audio")
        rec["output_duration_s"] = round(stream_duration(audio) or 0.0, 3)
        rec["output_bytes"] = file_size(part)
        if run.stop.is_set():
            raise Stopped()
        if exists(final):
            raise RuntimeError("a file with the output name appeared while converting; left untouched")
        os.replace(ext(part), ext(final))
        success = True
        rec["action"] = "converted"
        run.say(f"[{index}] {c.rel}: done, {fmt_bytes(rec['output_bytes'])} -> {final}")
    except Stopped:
        rec["action"] = "failed"
        rec["reasons"].append(run.stop_reason or "stopped by the user (Ctrl-C)")
    except (RuntimeError, ProbeError, OSError) as e:
        rec["action"] = "failed"
        rec["reasons"].append(str(e))
        run.say(f"[{index}] {c.rel}: FAILED: {e}")
    finally:
        if not success and part_is_ours:
            # Remove ONLY the temporary output this run created.
            try:
                if exists(part):
                    os.unlink(ext(part))
            except OSError:
                rec["reasons"].append(f"could not remove the temporary file {part}")
        if rec.get("action") == "failed":
            try:
                ld = log_dir(opts)
                os.makedirs(ext(ld), exist_ok=True)
                name = safe_name((c.rel if c.rel != "." else c.name).replace("/", " - "))[:150] + ".log"
                with open(ext(ld / name), "w", encoding="utf-8", newline="\n") as fh:
                    fh.write("Book: " + c.rel + "\n")
                    fh.write("Reasons:\n  " + "\n  ".join(rec["reasons"]) + "\n\n")
                    fh.write("ffmpeg messages:\n" + (stderr_text or "(none)\n"))
                rec["log"] = str(ld / name)
            except OSError:
                pass
        if tmpdir is not None:
            shutil.rmtree(str(tmpdir), ignore_errors=True)
        if reserved:
            with run.lock:
                run.reserved -= reserved
        rec["seconds"] = round(time.time() - t0, 1)
        rec["finished"] = _dt.datetime.now().isoformat(timespec="seconds")
    return rec


def matches(rel: str, patterns: Optional[Sequence[str]]) -> bool:
    r = rel.casefold()
    return any(fnmatch.fnmatchcase(r, p.replace("\\", "/").casefold()) for p in patterns or [])


def validate(opts: argparse.Namespace) -> Optional[str]:
    root = Path(opts.root)
    if not os.path.isdir(ext(root)):
        return f"The library folder does not exist: {root}"
    if opts.apply and not opts.output_root:
        return "--apply needs --output-root DIR (a separate folder for the new files)."
    if opts.output_root:
        o = Path(opts.output_root)
        if same_or_inside(o, root) or same_or_inside(root, o):
            return ("--output-root must be OUTSIDE the library folder (else Audiobookshelf/Plex would "
                    "show every book twice, and the scan would find its own output). This is checked on "
                    "the real location, so a mapped drive, link or junction to the library counts too.")
    report = Path(_absolute(opts.report)) if opts.report else (
        Path(_absolute(opts.output_root)) / REPORT_NAME if opts.output_root else None)
    if report is not None:
        problem = report_problem(report, root)
        if problem:
            return "--report: " + problem if opts.report else problem
    if opts.jobs < 1:
        return "--jobs must be 1 or more."
    if opts.min_files < 2:
        return "--min-files must be 2 or more."
    return None


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="to_m4b.py",
        description="Join audiobooks made of many audio files into one chaptered .m4b each, written into a "
                    "separate folder. Dry run by default: nothing is written until you add --apply. "
                    "The original files are never moved, renamed or deleted.")
    p.add_argument("root", help="the library folder to scan (e.g. D:\\Audiobooks or /volume1/audiobooks)")
    p.add_argument("--apply", action="store_true", help="really convert (default: dry run, only a plan)")
    p.add_argument("--output-root", metavar="DIR",
                   help="separate folder for the new .m4b files (same layout as the library; outside it)")
    p.add_argument("--report", metavar="FILE.json", help="report file (must end in .json; a .csv is written "
                   "next to it). Default: to_m4b-report.json in the output root")
    p.add_argument("--jobs", type=int, default=1, metavar="N", help="books converted at the same time (default 1)")
    p.add_argument("--bitrate", type=parse_bitrate, metavar="RATE",
                   help="AAC bitrate such as 64k (default: the source's average, kept between 48k and 128k)")
    p.add_argument("--chapter-names", choices=["tags", "files", "numbered"], default="tags",
                   help="tags: the files' title tags when they make sense, else file names (default); "
                        "files: cleaned file names; numbered: Chapter 01, 02, ...")
    p.add_argument("--min-files", type=int, default=2, metavar="N", help="smallest number of files that makes a book (default 2)")
    p.add_argument("--only", action="append", metavar="PATTERN",
                   help="only books whose path inside the library matches, e.g. \"Tolkien/*\" (can repeat)")
    p.add_argument("--exclude", action="append", metavar="PATTERN", help="skip books whose path matches (can repeat)")
    p.add_argument("--limit", type=int, metavar="N", help="stop after N books (for a first trial)")
    p.add_argument("--strict", action="store_true",
                   help="fail a book when ffmpeg reports any decoding error (default: keep it if the checks pass, "
                        "and note the errors in the report)")
    p.add_argument("--ffmpeg", metavar="PATH", help="path to ffmpeg if it is not on PATH")
    p.add_argument("--ffprobe", metavar="PATH", help="path to ffprobe if it is not on PATH")
    p.add_argument("--normal-priority", action="store_true", help="do not lower the priority of the conversion")
    p.add_argument("--verbose", "-v", action="store_true", help="list every chapter and every skipped folder")
    p.add_argument("--version", action="version", version=f"to_m4b {VERSION}")
    return p


def main(argv: Optional[Sequence[str]] = None, out=None) -> int:
    opts = build_parser().parse_args(argv)
    out = out or sys.stdout
    if out is sys.stdout and hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass
    opts.root = _absolute(opts.root)

    def err(msg: str) -> int:
        print("Error: " + msg, file=out, flush=True)
        return 2

    problem = validate(opts)
    if problem:
        return err(problem)
    ffprobe = find_tool("ffprobe", opts.ffprobe)
    if ffprobe is None:
        return err(INSTALL_HELP.format(name="ffprobe"))
    ffmpeg = find_tool("ffmpeg", opts.ffmpeg)
    if ffmpeg is None and opts.apply:
        return err(INSTALL_HELP.format(name="ffmpeg"))
    tools = Tools(ffmpeg or ["ffmpeg"], ffprobe)
    run = Run(opts, tools, out)
    if opts.apply and not opts.normal_priority:
        lower_own_priority()

    root = Path(opts.root)
    excl = [Path(_absolute(opts.output_root))] if opts.output_root else []
    run.say(f"to_m4b {VERSION} - {run.mode().upper()}" + ("" if opts.apply else " (nothing is changed; add --apply to convert)"))
    run.say("The original files are only read: never moved, renamed or deleted.")
    run.say(f"Scanning {root} ...")
    cands = scan_library(root, opts.min_files, excl)
    if opts.only:
        cands = [c for c in cands if matches(c.rel, opts.only)]
    if opts.exclude:
        cands = [c for c in cands if not matches(c.rel, opts.exclude)]
    if opts.output_root:
        mark_collisions(cands, Path(_absolute(opts.output_root)))
    n_cand = sum(c.status == "candidate" for c in cands)
    run.say(f"Found {n_cand} multi-file books, plus {len(cands) - n_cand} folders that are skipped or unclear.")
    run.say("")

    pool = ThreadPoolExecutor(max_workers=opts.jobs) if opts.apply else None
    futures: Dict = {}
    interrupted = False
    planned_bytes = 0
    index = 0

    def collect(done):
        for fut in done:
            rel = futures[fut]
            try:
                rec = fut.result()
            except Exception as e:  # a bug, not a book problem: report it and go on
                rec = {"source_folder": rel, "action": "failed", "reasons": [f"internal error: {e!r}"]}
            run.record(rel, rec)
            futures.pop(fut, None)

    try:
        for c in cands:
            if run.stop.is_set():
                break
            if c.status != "candidate":
                rec = base_record(c)
                rec["action"] = {"single": "skipped", "too-few": "skipped"}.get(c.status, "ambiguous")
                run.record(c.rel, rec)
                if c.status == "ambiguous":
                    run.say(f"[?] {c.rel}: UNSURE, skipped: " + "; ".join(c.reasons))
                elif opts.verbose:
                    run.say(f"[-] {c.rel}: skipped: " + "; ".join(c.reasons))
                continue
            if opts.limit is not None and run.planned >= opts.limit:
                break
            rec = base_record(c)
            try:
                probes = probe_files(tools, c.files, run.low_priority)
            except ProbeError as e:
                rec["action"] = "failed"
                rec["reasons"].append(str(e))
                run.say(f"[!] {c.rel}: FAILED: {e}")
                run.record(c.rel, rec)
                continue
            plan = make_plan(c, probes, opts.chapter_names, opts.bitrate)
            plan.output_path = target_for(opts, c) if opts.output_root else None
            rec["plan"] = plan_record(plan)
            rec["warnings"] += plan.warnings
            rec["source_duration_s"] = round(plan.total_duration, 3)
            if plan.status == "ambiguous":
                rec["action"] = "ambiguous"
                rec["reasons"] += plan.reasons
                run.say(f"[?] {c.rel}: UNSURE, skipped: " + "; ".join(plan.reasons))
                run.record(c.rel, rec)
                continue
            if plan.output_path is not None:
                rec["output"] = str(plan.output_path)
                state, why = check_existing(run, plan, plan.output_path)
                if state is not None:
                    rec["action"] = state
                    rec["reasons"] += why
                    run.say(f"[=] {c.rel}: " + ("already done" if state == "skipped" else "FAILED: " + why[0]))
                    run.record(c.rel, rec)
                    continue
            run.planned += 1
            index += 1
            planned_bytes += plan.est_bytes
            if not opts.apply:
                rec["action"] = "dry-run"
                print_plan(run, plan, index)
                run.record(c.rel, rec)
                continue
            fut = pool.submit(convert_book, run, plan, index)  # type: ignore[union-attr]
            futures[fut] = c.rel
            while len(futures) >= opts.jobs:
                done, _ = wait(list(futures), timeout=0.5, return_when=FIRST_COMPLETED)
                collect(done)
        while futures:
            done, _ = wait(list(futures), timeout=0.5, return_when=FIRST_COMPLETED)
            collect(done)
    except KeyboardInterrupt:
        interrupted = True
        run.stop_reason = "stopped by the user (Ctrl-C)"
        run.stop.set()
        run.say("\nStopping: waiting for running conversions to clean up their temporary files ...")
        run.kill_all()
        while futures:
            try:
                done, _ = wait(list(futures), timeout=0.5, return_when=FIRST_COMPLETED)
                collect(done)
            except KeyboardInterrupt:
                run.kill_all()
    finally:
        if pool is not None:
            pool.shutdown(wait=True)

    # Summary
    counts = Counter(r.get("action") for r in run.records.values())
    run.say("")
    run.say("Summary")
    run.say("-------")
    labels = [("dry-run", "would convert"), ("converted", "converted"), ("skipped", "skipped (single file, too few files or already done)"),
              ("ambiguous", "unsure, skipped (see reasons)"), ("failed", "failed")]
    for key, label in labels:
        if counts.get(key):
            run.say(f"  {counts[key]:5d}  {label}")
    if not opts.apply and planned_bytes:
        line = f"  Estimated size of the new files: {fmt_bytes(planned_bytes)}"
        if opts.output_root:
            fs = free_space(Path(opts.output_root))
            if fs is not None:
                line += f"; free on the output drive: {fmt_bytes(fs)}"
                if fs < planned_bytes:
                    line += "  (NOT ENOUGH)"
        run.say(line)
    bad = [(rel, r) for rel, r in run.records.items() if r.get("action") in ("failed", "ambiguous")]
    if bad and (opts.verbose or len(bad) <= 30):
        run.say("")
        for rel, r in bad:
            run.say(f"  {r['action'].upper():9s} {rel}: " + "; ".join(r.get("reasons") or []))
    elif bad:
        run.say(f"  ({len(bad)} unsure or failed books: see the report for the reasons)")
    if counts.get("converted"):
        run.say("")
        run.say("Your original files are unchanged. After checking the new books you can move the originals "
                "yourself.")
    run.say(f"Time: {fmt_dur(time.time() - run.started)}")
    try:
        run.write_report()
    except OSError as e:
        run.say(f"Could not write the report: {e}")
    if interrupted:
        return 130
    return 1 if counts.get("failed") else 0


if __name__ == "__main__":
    sys.exit(main())
