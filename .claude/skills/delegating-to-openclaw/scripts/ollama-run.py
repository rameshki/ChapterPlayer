"""Optional path C: the same one-shot prompt sent straight to Ollama, skipping OpenClaw.
About 3x faster per call (OpenClaw's own overhead is roughly 18 s), but it bypasses OpenClaw and its skills:
the owner prefers OpenClaw, so ask before using it for real work. Handy for timing and model comparisons,
because OpenClaw ignores --model for models that are not listed in its Ollama provider config.
Usage: python -I scripts/ollama-run.py <prompt.txt> <answer.txt> [model, default qwen3-coder:latest] [num_ctx, default 8192]"""
import json
import sys
import time
import urllib.request

prompt_file, out_file = sys.argv[1], sys.argv[2]
model = sys.argv[3] if len(sys.argv) > 3 else "qwen3-coder:latest"
num_ctx = int(sys.argv[4]) if len(sys.argv) > 4 else 8192
prompt = open(prompt_file, encoding="utf-8").read()
body = json.dumps({"model": model, "messages": [{"role": "user", "content": prompt}], "stream": False,
                   "options": {"num_ctx": num_ctx}}).encode()
req = urllib.request.Request("http://localhost:11434/api/chat", body, {"Content-Type": "application/json"})
start = time.time()
reply = json.load(urllib.request.urlopen(req, timeout=900))
open(out_file, "w", encoding="utf-8", newline="\n").write(reply["message"]["content"])
print("%s: %.1f s, %s output tokens" % (model, time.time() - start, reply.get("eval_count")))
