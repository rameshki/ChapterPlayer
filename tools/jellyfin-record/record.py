#!/usr/bin/env python3
"""Records the SHAPES of a Jellyfin server's answers for ChapterPlayer's test fixtures (review R39, plan J3 task 1).

Use a throwaway Jellyfin user, never a real account. Credentials come from environment variables only:

    JF_URL   the server address, e.g. http://host:8096
    JF_USER  the throwaway user
    JF_PASS  its password

    python tools/jellyfin-record/record.py --out C:\\path\\outside\\the\\repo [--with-writes]

Every answer is anonymised before it is written: ids, names, titles, paths, addresses, tokens and dates are replaced by invented
values of the same shape (equal values stay equal, so references between objects still line up); numbers, booleans and
enum-like fields (Type, Container, Codec, ...) are kept. Before anything is written the script checks that none of the real
values (the server's host, the user name, the password, the token, the user id, any real id) is left in the text, and writes
nothing if one is. The download call records the status and a few headers only and never reads the body.

Calls that change state on the server (play sessions, user data, played flag) run only with --with-writes, on the throwaway
user. The output is for review: look at it, then copy what is needed into shared/src/androidHostTest/resources/fixtures/jellyfin/.
"""

import argparse
import json
import os
import re
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

LIST_FIELDS = "People,Overview,Genres,DateCreated,ProviderIds"
DETAIL_FIELDS = "Chapters,People,Overview,Genres,Studios,MediaSources"

# Fields whose value describes structure, not content: kept as they are.
KEEP_KEYS = {
    "Type", "MediaType", "Container", "Codec", "CollectionType", "Protocol", "VideoType", "LocationType", "ChannelLayout",
    "Role", "Language", "ProductVersion", "Version", "StartupWizardCompleted", "OperatingSystem", "ImageType", "PlayMethod",
    "RepeatMode", "PlaybackOrder", "AudioChannelLayout", "SampleRate", "Profile",
}
ID_KEYS_AS_TOKEN = {"AccessToken", "Token"}
PATH_KEYS = {"Path", "FileName"}
ADDRESS_KEYS = {"LocalAddress", "Address", "WanAddress", "ServerName", "HostName"}
HEX32 = re.compile(r"^[0-9a-fA-F]{32}$")
GUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")
FIXED_DATE = "2026-01-01T00:00:00.0000000Z"


class Anonymiser:
    """Stable replacement of values: the same real value always becomes the same invented one."""

    def __init__(self):
        self.ids = {}
        self.texts = {}
        self.real = set()  # real values seen, to check none survives

    def _fake_id(self, value):
        key = value.replace("-", "").lower()
        if key not in self.ids:
            self.ids[key] = f"{len(self.ids) + 1:032x}"
        return self.ids[key]

    def _fake_text(self, key, value):
        if (key, value) not in self.texts:
            self.texts[(key, value)] = f"Test {key} {len(self.texts) + 1}"
        return self.texts[(key, value)]

    def value(self, key, v):
        if isinstance(v, dict):
            return {k: self.value(k, x) for k, x in v.items()}
        if isinstance(v, list):
            return [self.value(key, x) for x in v]
        if not isinstance(v, str) or v == "":
            return v
        if key in ID_KEYS_AS_TOKEN:
            self.real.add(v)
            return "REDACTED-TOKEN"
        if HEX32.match(v) or GUID.match(v):
            self.real.add(v)
            self.real.add(v.replace("-", ""))
            fake = self._fake_id(v)
            return fake if HEX32.match(v) else f"{fake[:8]}-{fake[8:12]}-{fake[12:16]}-{fake[16:20]}-{fake[20:]}"
        if DATE.match(v):
            return FIXED_DATE
        if key in PATH_KEYS:
            self.real.add(v)
            return "/media/audiobooks/Test Author/Test Book.m4b" if "/" in v or "\\" in v else "Test Book.m4b"
        if key in ADDRESS_KEYS or "://" in v:
            self.real.add(v)
            return "http://test.invalid:8096" if "://" in v else "Test Server"
        if key in KEEP_KEYS:
            return v
        if re.fullmatch(r"[A-Za-z]{1,12}", v) and key.endswith("Type"):
            return v
        self.real.add(v)
        return self._fake_text(key or "Text", v)

    def run(self, data):
        return self.value("", data)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect is an answer, not something to follow: the token must not travel to another host."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


class Server:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.user = user
        self.password = password
        self.device_id = "recorder-" + uuid.uuid4().hex[:12]
        self.token = None
        self.user_id = None

    def header(self, with_token=True):
        parts = [("Client", "ChapterPlayer recorder"), ("Device", "recorder"), ("DeviceId", self.device_id), ("Version", "0")]
        if with_token and self.token:
            parts.append(("Token", self.token))
        return "MediaBrowser " + ", ".join(f'{n}="{v}"' for n, v in parts)

    def call(self, method, path, params=None, body=None, token=True, extra=None, read_body=True):
        url = self.base + "/" + path.lstrip("/")
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", self.header(token))
        req.add_header("Accept", "application/json")
        if body is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (extra or {}).items():
            req.add_header(k, v)
        try:
            resp = _OPENER.open(req, timeout=30)
            status, headers = resp.status, dict(resp.headers.items())
            raw = resp.read() if read_body else b""
            resp.close()
        except urllib.error.HTTPError as e:
            status, headers = e.code, dict(e.headers.items())
            raw = e.read() if read_body else b""
        text = raw.decode("utf-8", "replace")
        try:
            parsed = json.loads(text) if text.strip() else None
        except ValueError:
            parsed = None
        return status, headers, parsed


SAFE_HEADERS = ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Content-Disposition"]
FILENAME = re.compile(r'(filename\*?=)(?:UTF-8\'\')?"?([^";]*)"?', re.IGNORECASE)


def safe_headers(headers, anon):
    """The few headers worth recording. Their values describe the transfer (type, size, ranges), so they are kept; the file
    name in Content-Disposition is real content and is replaced, keeping its extension."""
    kept = {}
    for name, value in headers.items():
        title = name.title()
        if title not in SAFE_HEADERS:
            continue
        if title == "Content-Disposition":
            ext = re.search(r"\.([A-Za-z0-9]{1,5})[\";\s]*$", value.strip())
            suffix = ext.group(1) if ext else "m4b"
            # The real file name is content: remember it so the final check can prove it is gone, then replace it.
            for found in FILENAME.finditer(value):
                anon.real.add(found.group(2))
            value = FILENAME.sub(lambda m: f'{m.group(1)}"Test Book.{suffix}"', value)
        kept[title] = value
    return kept


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="folder for the anonymised answers (outside the repository)")
    ap.add_argument("--with-writes", action="store_true", help="also run calls that change state (throwaway user only)")
    args = ap.parse_args(argv)
    base, user, password = (os.environ.get(n) for n in ("JF_URL", "JF_USER", "JF_PASS"))
    if not (base and user and password):
        print("Set JF_URL, JF_USER and JF_PASS (a throwaway user).", file=sys.stderr)
        return 2
    out = Path(args.out)
    if out.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        print("Write the output outside the repository; copy reviewed files in afterwards.", file=sys.stderr)
        return 2

    jf = Server(base, user, password)
    anon = Anonymiser()
    results = {}  # name -> (status, shape)
    real_extra = {urllib.parse.urlparse(base).hostname or "", user, password}

    def record(name, status, headers, parsed, keep_headers=False):
        entry = {"status": status}
        if keep_headers:
            entry["headers"] = safe_headers(headers, anon)
        if parsed is not None:
            entry["body"] = anon.run(parsed)
        results[name] = entry
        print(f"{name:34} {status}  {'(body)' if parsed is not None else ''}")

    s, h, b = jf.call("GET", "System/Info/Public", token=False)
    record("system-info-public", s, h, b)

    s, h, b = jf.call("POST", "Users/AuthenticateByName", body={"Username": user, "Pw": password}, token=False)
    if s != 200 or not isinstance(b, dict) or "AccessToken" not in b:
        print(f"Sign-in failed ({s}).", file=sys.stderr)
        return 1
    jf.token = b["AccessToken"]
    jf.user_id = (b.get("User") or {}).get("Id")
    anon.real.update({jf.token, jf.user_id or ""})
    record("authenticate-by-name", s, h, b)

    s, h, b = jf.call("GET", "UserViews")
    record("user-views", s, h, b)
    views = [v for v in (b or {}).get("Items", []) if (v.get("CollectionType") or "").lower() in ("books", "audiobooks")]
    if not views:
        print("The user has no books library; stopping after the calls that need none.", file=sys.stderr)
    first_item = None
    if views:
        s, h, b = jf.call("GET", "Items", {
            "ParentId": views[0]["Id"], "IncludeItemTypes": "AudioBook", "Recursive": "true", "Fields": LIST_FIELDS,
            "EnableUserData": "true", "EnableImageTypes": "Primary", "StartIndex": 0, "Limit": 5, "EnableTotalRecordCount": "true"})
        record("items-audiobooks-page", s, h, b)
        items = (b or {}).get("Items", [])
        first_item = items[0]["Id"] if items else None

    s, h, b = jf.call("GET", "System/Configuration")
    record("system-configuration", s, h, b)

    if first_item:
        s, h, b = jf.call("GET", f"Items/{first_item}", {"Fields": DETAIL_FIELDS})
        record("item-detail", s, h, b)
        s, h, b = jf.call("GET", f"UserItems/{first_item}/UserData")
        record("user-data", s, h, b)
        # The download: status and headers only. A Range of one byte, so no body is read or saved.
        s, h, _ = jf.call("GET", f"Items/{first_item}/Download", extra={"Range": "bytes=0-0"}, read_body=False)
        record("download-range-0-0", s, h, None, keep_headers=True)
        s, h, _ = jf.call("GET", f"Items/{first_item}/Download", token=False, extra={"Range": "bytes=0-0"}, read_body=False)
        record("download-no-token", s, h, None, keep_headers=True)

    s, h, b = jf.call("GET", "QuickConnect/Enabled")
    record("quick-connect-enabled", s, h, b)

    if args.with_writes and first_item:
        for name, path, body in (
            ("session-playing", "Sessions/Playing", {"ItemId": first_item, "PositionTicks": 0, "PlayMethod": "DirectPlay", "CanSeek": True}),
            ("session-progress", "Sessions/Playing/Progress", {"ItemId": first_item, "PositionTicks": 600000000, "PlayMethod": "DirectPlay", "IsPaused": False}),
            ("session-stopped", "Sessions/Playing/Stopped", {"ItemId": first_item, "PositionTicks": 900000000}),
        ):
            s, h, b = jf.call("POST", path, body=body)
            record(name, s, h, b)
        s, h, b = jf.call("GET", f"UserItems/{first_item}/UserData")
        record("user-data-after-stop", s, h, b)
        s, h, b = jf.call("POST", f"UserPlayedItems/{first_item}")
        record("mark-played", s, h, b)
        s, h, b = jf.call("GET", f"UserItems/{first_item}/UserData")
        record("user-data-after-played", s, h, b)
        s, h, b = jf.call("DELETE", f"UserPlayedItems/{first_item}")
        record("mark-unplayed", s, h, b)
        s, h, b = jf.call("POST", f"UserItems/{first_item}/UserData", body={"PlaybackPositionTicks": 300000000, "Played": False})
        record("write-user-data", s, h, b)
        s, h, b = jf.call("GET", f"UserItems/{first_item}/UserData")
        record("user-data-after-write", s, h, b)

    # Nothing real may remain in what is about to be written.
    rendered = {name: json.dumps(shape, indent=2, sort_keys=True) for name, shape in results.items()}
    blob = "\n".join(rendered.values())
    leaks = [v for v in (anon.real | real_extra) if v and len(v) >= 4 and v in blob]
    if leaks:
        print(f"Aborted: {len(leaks)} real value(s) would remain in the output; nothing written.", file=sys.stderr)
        return 1

    out.mkdir(parents=True, exist_ok=True)
    for name, text in rendered.items():
        (out / f"{name}.json").write_text(text + "\n", encoding="utf-8")
    print(f"Wrote {len(rendered)} anonymised answers to {out}. Review them before copying into the fixtures.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
