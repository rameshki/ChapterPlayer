# Recorded Jellyfin shapes

These JSON files are answers of a real Jellyfin 12.4 server, recorded on 19 November 2026 with `tools/jellyfin-record/record.py`
(review R39, plan `docs/superpowers/plans/2026-11-19-jellyfin-j3.md`). Every value is invented: ids, titles, names, paths, dates,
addresses and tokens were replaced by values of the same shape, and the script refused to write a file while any real value was
left. The structure, the field names and the types are real, which the other Jellyfin fixtures (written from the API
description) are not. `JellyfinRecordedShapesTest` runs the app's own service and parsers over them.

What the recording showed about the server (headers are not in these files because they are not bodies):

- `GET /Items/{id}/Download` with `Range: bytes=0-0` answers 206 with `Accept-Ranges: bytes`, `Content-Range: bytes 0-0/<size>`,
  `Content-Type: audio/mp4` and a `Content-Disposition` that carries both `filename` and `filename*`. So resume by Range works.
- The same call without a token answers 401.
- An audiobook is one `m4a` file (`MediaSources[0].Container`, `Size` as an integer), with its chapters in `Chapters`
  (`StartPositionTicks`, `Name`) and its length in `RunTimeTicks`.
- `GET /QuickConnect/Enabled` answers a bare JSON boolean.

To record again, follow `tools/jellyfin-record/README.md`.
