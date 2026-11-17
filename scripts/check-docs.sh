#!/usr/bin/env bash
# Checks docs/PROJECT_STATUS.md for the ways it went stale (review R37). Run in CI (docs-check.yml) and by hand.
#   1. it stays under 4 KB;
#   2. it never says "not pushed";
#   3. every branch it names (stage-*/..., fix/...) exists on the remote;
#   4. no finding id (R12) is both on a line that says done or merged and in the Open work or Next steps sections.
# Needs git with an origin remote. Set STATUS_FILE to check another file (used by the self-test).
set -u
file="${STATUS_FILE:-docs/PROJECT_STATUS.md}"
fail=0
say() { echo "check-docs: $1"; fail=1; }

[ -f "$file" ] || { echo "check-docs: $file not found"; exit 2; }

size=$(wc -c < "$file" | tr -d ' ')
[ "$size" -le 4096 ] || say "$file is $size bytes; keep it under 4096 (the list of merged work is git log)"

grep -inq 'not pushed' "$file" && say "$file says 'not pushed'; push the branch or remove the line"

branches=$(grep -oE '\b(stage-[0-9a-z]+|fix)/[A-Za-z0-9][A-Za-z0-9._-]*' "$file" | sed -E 's/[._-]+$//' | sort -u)
if [ -n "$branches" ]; then
  if remote=$(git ls-remote --heads origin 2>/dev/null) && [ -n "$remote" ]; then
    for b in $branches; do
      echo "$remote" | grep -q "refs/heads/$b\$" || say "$file names branch '$b', which is not on the remote"
    done
  else
    echo "check-docs: cannot read the remote; branch names not checked"
  fi
fi

done_ids=$(grep -iE '\b(done|merged)\b' "$file" | grep -oE '\bR[0-9]+\b' | sort -u)
open_ids=$(awk '/^## /{open = ($0 ~ /^## (Open work|Next steps)/)} open' "$file" | grep -oE '\bR[0-9]+\b' | sort -u)
both=$(comm -12 <(echo "$done_ids") <(echo "$open_ids"))
[ -z "$both" ] || say "finding(s) listed as both done and open: $(echo $both)"

[ "$fail" -eq 0 ] && echo "check-docs: ok ($size bytes)"
exit "$fail"
