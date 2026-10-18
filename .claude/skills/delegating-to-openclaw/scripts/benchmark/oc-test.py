import json, os, re, subprocess, sys

B = os.environ.get("BENCH_DIR", "bench")
src = open(B + "/bench.py", encoding="utf-8").read()
src = src[:src.index('if sys.argv[1] == "gen":')]
ns = {}
exec(compile(src, "bench", "exec"), ns)
REPO, TASKS = ns["REPO"], ns["TASKS"]
timing = {}
for line in open(B + "/oc/timing.txt", encoding="utf-8-sig"):
    m = re.match(r"(\S+) (\w+): ([\d.]+) s", line.strip())
    if m:
        timing[(m[1], m[2])] = float(m[3])
rows = []
try:
    for model in ["gemma4:26b", "qwen3-coder:latest"]:
        for task in ["offline", "series", "collections", "groups"]:
            tag = model.replace(":", "_").replace("/", "_") + "__" + task
            txt = open(B + "/oc/" + tag + ".txt", encoding="utf-8-sig", errors="replace").read()
            header = txt.split("\n", 4)[:4]
            actual = next((h.split(":", 1)[1].strip() for h in header if h.startswith("model:")), "?")
            blocks = re.findall(r"```kotlin\n(.*?)```", txt, flags=re.S)
            rec = dict(model=model, answered_by=actual, task=task, wall_s=timing.get((model, task)), blocks=len(blocks))
            if len(blocks) == 1:
                code = blocks[0]
                rec.update(ns["comment_metrics"](code))
                open(B + "/oc/" + tag + ".kt", "w", encoding="utf-8", newline="\n").write(code)
                target = REPO + "/" + TASKS[task]["target"]
                open(target, "w", encoding="utf-8", newline="\n").write(code if code.endswith("\n") else code + "\n")
                rec.update(ns["run_tests"](task))
                subprocess.run(["git", "checkout", "--", TASKS[task]["target"]], cwd=REPO, capture_output=True)
            else:
                rec.update(compiled=False, passed=0, total=0, error="no single kotlin block")
            rows.append(rec)
            print("%-20s %-12s answered_by=%-20s wall %6s s  %s  kdoc %s/%s inline %s  %s" % (
                model, task, actual, rec["wall_s"],
                ("tests %d/%d" % (rec["passed"], rec["total"])) if rec["compiled"] else "NO COMPILE/NO BLOCK",
                rec.get("kdoc_blocks"), rec.get("public_decls"), rec.get("inline_comments"), (rec.get("error") or "")[:90]), flush=True)
finally:
    subprocess.run(["git", "checkout", "--", "shared"], cwd=REPO, capture_output=True)
json.dump(rows, open(B + "/oc/results.json", "w"), indent=1)
