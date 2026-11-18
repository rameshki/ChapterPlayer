"""Tests for record.py: the anonymiser, and the whole script against a fake Jellyfin server run in this process.
Run: python -m unittest discover -s tools/jellyfin-record
No network beyond 127.0.0.1; every value below is invented."""

import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import record  # noqa: E402

USER_ID = "0123456789abcdef0123456789abcdef"
ITEM_ID = "fedcba9876543210fedcba9876543210"
VIEW_ID = "aaaabbbbccccddddeeeeffff00001111"
TOKEN = "tok3n-SECRET-value-1234567890abcdef"
PASSWORD = "dev-test-pass"
USER = "devtest"
BOOK_TITLE = "The Real Secret Book Title"
AUTHOR = "Real Author Name"


class FakeJellyfin(http.server.BaseHTTPRequestHandler):
    seen = []  # (method, path, authorization header)

    def log_message(self, *args):
        pass

    def _send(self, status, body=None, headers=None):
        raw = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def _handle(self):
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        FakeJellyfin.seen.append((self.command, self.path, self.headers.get("Authorization", "")))
        path = self.path.split("?")[0]
        authed = f'Token="{TOKEN}"' in self.headers.get("Authorization", "")
        if path == "/System/Info/Public":
            return self._send(200, {"ServerName": "Home Jelly", "Version": "10.10.7", "Id": USER_ID, "LocalAddress": "http://192.168.7.7:8096"})
        if path == "/Users/AuthenticateByName":
            return self._send(200, {"AccessToken": TOKEN, "User": {"Id": USER_ID, "Name": USER}, "ServerId": USER_ID})
        if not authed and not path.endswith("/Download"):
            return self._send(401, None)
        if path == "/UserViews":
            return self._send(200, {"Items": [{"Id": VIEW_ID, "Name": "My Books", "CollectionType": "books"}]})
        if path == "/Items":
            return self._send(200, {"TotalRecordCount": 1, "Items": [{
                "Id": ITEM_ID, "Name": BOOK_TITLE, "Type": "AudioBook", "RunTimeTicks": 36000000000, "DateCreated": "2025-05-05T10:11:12.0000000Z",
                "People": [{"Name": AUTHOR, "Id": USER_ID, "Type": "Author"}], "ImageTags": {"Primary": "f" * 32}}]})
        if path == "/System/Configuration":
            return self._send(200, {"ServerName": "Home Jelly", "EnableCaseSensitiveItemIds": True})
        if path == f"/Items/{ITEM_ID}":
            return self._send(200, {"Id": ITEM_ID, "Name": BOOK_TITLE, "Path": "/srv/media/Real Author Name/The Real Secret Book Title.m4b",
                                    "Chapters": [{"Name": "Chapter One", "StartPositionTicks": 0}],
                                    "MediaSources": [{"Container": "m4b", "Size": 123456789, "Path": "/srv/media/x.m4b"}]})
        if path == f"/UserItems/{ITEM_ID}/UserData":
            if self.command == "POST":
                return self._send(200, None)
            return self._send(200, {"PlaybackPositionTicks": 0, "Played": False, "ItemId": ITEM_ID, "Key": "real-key-" + BOOK_TITLE})
        if path == f"/Items/{ITEM_ID}/Download":
            if not authed:
                return self._send(401, None)
            return self._send(206, None, {"Content-Range": "bytes 0-0/123456789", "Accept-Ranges": "bytes",
                                          "Content-Disposition": f'attachment; filename="{BOOK_TITLE}.m4b"', "Content-Type": "audio/mp4"})
        if path == "/QuickConnect/Enabled":
            return self._send(200, True)
        if path.startswith("/Sessions/Playing") or path.startswith("/UserPlayedItems/"):
            return self._send(204, None)
        return self._send(404, None)

    do_GET = do_POST = do_DELETE = _handle


class AnonymiserTest(unittest.TestCase):
    def test_ids_text_dates_paths_and_addresses_are_replaced_and_stay_consistent(self):
        a = record.Anonymiser()
        out = a.run({"Id": ITEM_ID, "ParentId": ITEM_ID, "Name": BOOK_TITLE, "Album": BOOK_TITLE, "Path": "/srv/x/y.m4b",
                     "DateCreated": "2025-05-05T10:11:12.0000000Z", "LocalAddress": "http://192.168.7.7:8096",
                     "Type": "AudioBook", "RunTimeTicks": 36000000000, "IsFolder": False, "AccessToken": TOKEN})
        text = json.dumps(out)
        for secret in (ITEM_ID, BOOK_TITLE, "/srv/x", "2025-05-05", "192.168.7.7", TOKEN):
            self.assertNotIn(secret, text)
        self.assertEqual(out["Id"], out["ParentId"])
        self.assertRegex(out["Id"], r"^[0-9a-f]{32}$")
        self.assertEqual(out["Type"], "AudioBook")
        self.assertEqual(out["RunTimeTicks"], 36000000000)
        self.assertIs(out["IsFolder"], False)
        self.assertEqual(out["AccessToken"], "REDACTED-TOKEN")

    def test_guid_form_is_kept_as_a_guid_and_lists_are_walked(self):
        out = record.Anonymiser().run({"Items": [{"Id": "01234567-89ab-cdef-0123-456789abcdef"}]})
        self.assertRegex(out["Items"][0]["Id"], r"^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")


class WholeScriptTest(unittest.TestCase):
    def run_script(self, *extra, url=None):
        FakeJellyfin.seen = []
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeJellyfin)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            out = tempfile.mkdtemp(prefix="jf-record-")
            env = dict(os.environ, JF_URL=url or f"http://127.0.0.1:{server.server_port}", JF_USER=USER, JF_PASS=PASSWORD)
            proc = subprocess.run([sys.executable, "-I", str(HERE / "record.py"), "--out", out, *extra], env=env, capture_output=True, text=True, timeout=60)
            return proc, Path(out)
        finally:
            server.shutdown()

    def test_records_the_reads_and_leaves_nothing_real_in_the_files_or_the_output(self):
        proc, out = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        names = sorted(p.stem for p in out.glob("*.json"))
        for expected in ("system-info-public", "authenticate-by-name", "user-views", "items-audiobooks-page", "item-detail",
                         "user-data", "download-range-0-0", "download-no-token", "quick-connect-enabled", "system-configuration"):
            self.assertIn(expected, names)
        self.assertNotIn("session-playing", names)  # writes only with --with-writes
        everything = proc.stdout + proc.stderr + "".join(p.read_text(encoding="utf-8") for p in out.glob("*.json"))
        for secret in (TOKEN, PASSWORD, USER_ID, ITEM_ID, VIEW_ID, BOOK_TITLE, AUTHOR, "Home Jelly", "192.168.7.7", "127.0.0.1", "/srv/media", USER):
            self.assertNotIn(secret, everything, secret)
        dl = json.loads((out / "download-range-0-0.json").read_text(encoding="utf-8"))
        self.assertEqual(dl["status"], 206)
        self.assertEqual(dl["headers"]["Accept-Ranges"], "bytes")
        self.assertNotIn("body", dl)
        self.assertEqual(json.loads((out / "download-no-token.json").read_text(encoding="utf-8"))["status"], 401)
        self.assertNotIn(BOOK_TITLE, dl["headers"].get("Content-Disposition", ""))

    def test_the_token_goes_in_the_header_never_in_a_url(self):
        self.run_script("--with-writes")
        self.assertTrue(FakeJellyfin.seen)
        for method, path, auth in FakeJellyfin.seen:
            self.assertNotIn(TOKEN, path)
            self.assertNotIn("api_key", path.lower())
        self.assertTrue(any(m == "POST" and p.startswith("/Sessions/Playing") for m, p, _ in FakeJellyfin.seen))

    def test_writes_are_recorded_only_when_asked_for(self):
        proc, out = self.run_script("--with-writes")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        names = {p.stem for p in out.glob("*.json")}
        for expected in ("session-playing", "session-progress", "session-stopped", "mark-played", "mark-unplayed", "write-user-data"):
            self.assertIn(expected, names)

    def test_refuses_to_write_inside_the_repository(self):
        env = dict(os.environ, JF_URL="http://127.0.0.1:9", JF_USER=USER, JF_PASS=PASSWORD)
        proc = subprocess.run([sys.executable, "-I", str(HERE / "record.py"), "--out", str(HERE / "out")], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(proc.returncode, 2)
        self.assertFalse((HERE / "out").exists())

    def test_without_credentials_it_stops(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("JF_")}
        proc = subprocess.run([sys.executable, "-I", str(HERE / "record.py"), "--out", tempfile.mkdtemp()], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(proc.returncode, 2)


if __name__ == "__main__":
    unittest.main()
