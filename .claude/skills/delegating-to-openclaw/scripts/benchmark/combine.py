import os
import json, collections
B = os.environ.get("BENCH_DIR", "bench")
res = [json.loads(l) for l in open(B + "/results.jsonl", encoding="utf-8")]
mp = json.load(open(B + "/judge-map.json"))
# judge scores by label: (comments, code), copied from the four blind judge reports
J = {
 "offline": dict(A=(3,5), B=(2,4), C=(4,5), D=(3,5), E=(2,4), F=(3,4), G=(3,5)),
 "series": dict(A=(1,1), B=(3,2), C=(4,5), D=(1,1), E=(4,2), F=(3,2), G=(3,1)),
 "collections": dict(A=(3,5), B=(1,5), C=(1,5), D=(4,5), E=(4,5), F=(3,4), G=(3,5)),
 "groups": dict(A=(2,1), B=(1,1), C=(1,1), D=(2,1), E=(3,3), F=(2,3)),
}
judge = {(m, t): J[t][lab] for t in J for lab, m in mp[t].items()}
models = []
for r in res:
    if r["model"] not in models: models.append(r["model"])
print("PER TASK (tests passed/total | tok/s | comments/code judge score)")
for t in ["offline", "series", "collections", "groups"]:
    print("\n-- " + t)
    for m in models:
        r = [x for x in res if x["model"] == m and x["task"] == t][0]
        j = judge.get((m, t))
        tests = ("%d/%d" % (r["passed"], r["total"])) if r["compiled"] else ("no compile" if r.get("blocks") == 1 else "no code block")
        print("  %-20s tests %-13s %5s tok/s  gen %5ss  judge %s  kdoc %s/%s inline %s" % (m, tests, r.get("tok_per_s"), r.get("gen_s"), ("%d/%d" % j) if j else "-", r.get("kdoc_blocks"), r.get("public_decls"), r.get("inline_comments")))
print("\nSUMMARY")
print("%-20s %-10s %-9s %-10s %-10s %-9s" % ("model", "tasks ok", "tok/s", "comments", "code", "total gen s"))
for m in models:
    rs = [x for x in res if x["model"] == m]
    full = sum(1 for x in rs if x["compiled"] and x["passed"] == x["total"] and x["total"] > 0)
    tps = sum(x.get("tok_per_s") or 0 for x in rs) / len(rs)
    js = [judge[(m, x["task"])] for x in rs if (m, x["task"]) in judge]
    cm = sum(a for a, b in js) / len(js) if js else 0
    cd = sum(b for a, b in js) / len(js) if js else 0
    gs = sum(x.get("gen_s") or 0 for x in rs)
    print("%-20s %d/4        %-9.1f %-10.2f %-10.2f %-9.1f" % (m, full, tps, cm, cd, gs))
