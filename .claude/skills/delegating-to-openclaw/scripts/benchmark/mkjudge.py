import json, os, random, sys
sys.argv = ["x", "none"]
B = os.environ.get("BENCH_DIR", "bench")
src = open(B + "/bench.py", encoding="utf-8").read()
src = src[:src.index('if sys.argv[1] == "gen":')]
ns = {}
exec(compile(src, "bench", "exec"), ns)
prompts = ns["build_prompts"]()
rows = [json.loads(l) for l in open(B + "/gen.jsonl", encoding="utf-8")]
os.makedirs(B + "/judge", exist_ok=True)
random.seed(20261018)
mapping = {}
for task in ["offline", "series", "collections", "groups"]:
    samples = [r for r in rows if r["task"] == task and r.get("blocks") == 1]
    random.shuffle(samples)
    labels = "ABCDEFG"
    mapping[task] = {}
    out = ["# Task: " + task, "", "## The instructions every author received (with the tests that define correct behaviour)", "", prompts[task] if task != "groups" else prompts[task][:prompts[task].index("These tests are the specification")] + "(the author also received the full test file for the class; its expectations are the rules above)", "", "## Samples", ""]
    for lab, r in zip(labels, samples):
        mapping[task][lab] = r["model"]
        code = open(B + "/out/" + ns["tag_of"](r["model"], task) + ".kt", encoding="utf-8").read()
        out += ["### Sample " + lab, "```kotlin", code.rstrip("\n"), "```", ""]
    open(B + "/judge/task-" + task + ".md", "w", encoding="utf-8", newline="\n").write("\n".join(out))
json.dump(mapping, open(B + "/judge-map.json", "w"), indent=1)
print({t: len(m) for t, m in mapping.items()})
