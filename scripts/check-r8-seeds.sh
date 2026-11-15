#!/usr/bin/env bash
# Fails when R8 stopped keeping a class the app builds by name: the Glance widget ActionCallbacks (constructor) and
# the manifest receivers. Run after :androidApp:assembleRelease; the seeds file lists what R8 kept.
set -euo pipefail
seeds="${1:-androidApp/build/outputs/mapping/release/seeds.txt}"
[ -s "$seeds" ] || { echo "No seeds file at $seeds"; exit 1; }
w=com.chapterplayer.ui.widgets
status=0
for c in ListenNextChapterAction ListenPlayPauseAction ListenPreviousChapterAction ListenSkipBackAction \
         ListenSkipForwardAction PlayPauseAction SkipBackAction SkipForwardAction WidgetPlayAction; do
  grep -qF "$w.$c: $c()" "$seeds" || { echo "R8 no longer keeps the constructor of $w.$c"; status=1; }
done
for c in $w.NowPlayingWidgetReceiver $w.ContinueListeningWidgetReceiver $w.ListenWidgetReceiver \
         com.chapterplayer.data.downloads.DownloadActionReceiver; do
  grep -qE "^${c//./\.}(:|$)" "$seeds" || { echo "R8 no longer keeps $c"; status=1; }
done
[ "$status" = 0 ] && echo "R8 seeds: all widget callbacks and receivers are kept"
exit $status
