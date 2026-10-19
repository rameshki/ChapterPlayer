"""Pull the single fenced code block out of a model answer and write it to a file.
Usage: python -I scripts/oc-extract.py <answer.txt> <target-file> [fence-language, default kotlin]
Exits non-zero (and writes nothing) unless there is exactly one block: a reply with prose plus code,
or two blocks, is a failed round, not something to repair by hand."""
import re
import sys

src, dst = sys.argv[1], sys.argv[2]
lang = sys.argv[3] if len(sys.argv) > 3 else "kotlin"
text = open(src, encoding="utf-8-sig", errors="replace").read()
blocks = re.findall(r"```" + re.escape(lang) + r"\n(.*?)```", text, flags=re.S)
if len(blocks) != 1:
    sys.exit("expected exactly one %s block, found %d" % (lang, len(blocks)))
body = blocks[0] if blocks[0].endswith("\n") else blocks[0] + "\n"
open(dst, "w", encoding="utf-8", newline="\n").write(body)
print("wrote", dst, len(body.splitlines()), "lines")
