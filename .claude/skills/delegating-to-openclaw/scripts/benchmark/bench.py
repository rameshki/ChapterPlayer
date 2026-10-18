import json, os, re, subprocess, sys, time, urllib.request, urllib.error, glob

SP = os.environ.get("SCRATCH_DIR", ".")
BENCH = SP + "/bench"
REPO = os.environ.get("REPO_DIR", ".")
BASH = "C:/Program Files/Git/bin/bash.exe"
BROWSE = "shared/src/commonMain/kotlin/com/chapterplayer/domain/browse/"

COMMENT_STYLE = ("COMMENT STYLE: put a KDoc comment (/** ... */) of one or two sentences on every public type and function saying what it "
                 "does, and add a short // comment only where the logic is not obvious. Do not comment obvious lines.\n\n")


def read(p):
    return open(p, encoding="utf-8").read().replace("\r\n", "\n")


def build_prompts():
    prompts = {}
    off = read(SP + "/oc-prompt-offline.txt")
    off = off.replace("WRITE exactly:", COMMENT_STYLE + "YOUR FILE MUST START WITH EXACTLY THESE LINES:\npackage com.chapterplayer.domain.browse\n\nimport com.chapterplayer.data.model.DownloadState\n\nWRITE exactly:", 1)
    prompts["offline"] = off
    ser = read(SP + "/oc-prompt-series.txt")
    ser = ser.replace("WRITE exactly:", COMMENT_STYLE + "YOUR FILE MUST START WITH EXACTLY THESE LINES (no import lines):\npackage com.chapterplayer.domain.browse\n\nWRITE exactly:", 1)
    prompts["series"] = ser
    col = read(SP + "/oc-prompt-collections.txt")
    col = col.replace("WRITE exactly these declarations", COMMENT_STYLE + "WRITE exactly these declarations", 1)
    prompts["collections"] = col
    g1 = read(SP + "/oc-prompt-groups.txt")
    rules = g1[g1.index("RULES."):g1.index("CURRENT FILE BrowseGroups.kt")]
    tests = read(REPO + "/shared/src/androidHostTest/kotlin/com/chapterplayer/domain/browse/BrowseLogicTest.kt")
    prompts["groups"] = (
        "Write ONE new Kotlin file, BrowseGroups.kt, for a Kotlin Multiplatform app (common Kotlin: no java.*). Package com.chapterplayer.domain.browse. "
        "BrowseItem (a data class with `book: AudioBook`, `progress: ReadingProgress?`, `downloadState: DownloadState`) is defined in this same package: never import it. "
        "`internal fun titleSortKey(title: String): String` already exists in BrowseSort.kt in this package (lowercase, trimmed, ONE leading \"the \"/\"a \"/\"an \" removed): reuse it where the rules ask for a title key.\n\n"
        + COMMENT_STYLE
        + "YOUR FILE MUST START WITH EXACTLY THESE LINES:\npackage com.chapterplayer.domain.browse\n\nimport com.chapterplayer.data.model.BookCollection\n\n"
        + rules
        + "WRITE exactly these public declarations: `data class BookGroup(val key: String, val title: String, val items: List<BrowseItem>)`, "
          "`fun groupByAuthor(items: List<BrowseItem>): List<BookGroup>`, `fun groupByNarrator(items: List<BrowseItem>): List<BookGroup>`, "
          "`fun groupBySeries(items: List<BrowseItem>): List<BookGroup>`, `fun groupByCollection(items: List<BrowseItem>, collections: List<BookCollection>): List<BookGroup>` "
          "(plus private helpers as you need).\n"
          "The AudioBook fields you may use: id, title, author, narrator, serverId, seriesName: String?, seriesSequence: String?.\n\n"
          "These tests are the specification; ALL tests of the class must pass (the helper functions book(...), item(...), progress(...) build the inputs):\n```kotlin\n"
        + tests + "```\n\n"
          "OUTPUT FORMAT: reply with ONLY the complete BrowseGroups.kt in a single ```kotlin code block. No explanation before or after.")
    return prompts


TASKS = {
    "offline": dict(target=BROWSE + "OfflineRules.kt", test="OfflineRulesTest"),
    "series": dict(target=BROWSE + "SeriesName.kt", test="SeriesNameTest"),
    "collections": dict(target="shared/src/androidMain/kotlin/com/chapterplayer/data/remote/abs/dto/AbsCollectionsDto.kt", test="AbsCollectionsParsingTest"),
    "groups": dict(target=BROWSE + "BrowseGroups.kt", test="BrowseLogicTest"),
}
MODELS = ["qwen3-coder:latest", "qwen2.5-coder:14b", "deepseek-coder:6.7b", "codellama:13b", "gemma3:27b", "gemma4:26b", "qwen3.6:35b-a3b"]
NO_THINK = ("qwen3.6", "gemma4")


def chat(model, prompt):
    opts = {"num_ctx": 16384, "num_predict": 4096}
    body = {"model": model, "messages": [{"role": "user", "content": prompt}], "stream": False, "options": opts}
    if model.startswith(NO_THINK):
        body["think"] = False
    for attempt in (1, 2):
        req = urllib.request.Request("http://localhost:11434/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"})
        t = time.time()
        try:
            r = json.load(urllib.request.urlopen(req, timeout=900))
            return time.time() - t, r, None
        except urllib.error.HTTPError as e:
            msg = e.read().decode("utf-8", "replace")[:200]
            if attempt == 1 and "think" in body:
                body.pop("think")
                continue
            return time.time() - t, None, "HTTP %s %s" % (e.code, msg)
        except Exception as e:
            return time.time() - t, None, repr(e)[:200]


def run_tests(task):
    cls = TASKS[task]["test"]
    for x in glob.glob(REPO + "/shared/build/test-results/testAndroidHostTest/*%s*.xml" % cls):
        os.remove(x)
    p = subprocess.run([BASH, "-c", "./gradlew :shared:testAndroidHostTest --tests '*%s'" % cls],
                       cwd=REPO, capture_output=True, text=True, timeout=590)
    log = p.stdout + p.stderr
    xs = glob.glob(REPO + "/shared/build/test-results/testAndroidHostTest/*%s*.xml" % cls)
    if not xs:
        errs = [l for l in log.splitlines() if l.startswith("e: ")][:2]
        return dict(compiled=False, passed=0, total=0, error=" | ".join(e[-170:] for e in errs))
    h = open(xs[0], encoding="utf-8").read()
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', h)
    total, fails, errs = int(m[1]), int(m[2]), int(m[3])
    return dict(compiled=True, passed=total - fails - errs, total=total, error="")


def comment_metrics(code):
    lines = code.splitlines()
    kdoc = len(re.findall(r"/\*\*", code))
    pub = len(re.findall(r"^(?:data class|class|object|enum class|fun|interface)\b", code, flags=re.M))
    inline = len([l for l in lines if l.strip().startswith("//")])
    block_lines = 0
    inb = False
    for l in lines:
        s = l.strip()
        if s.startswith("/*"):
            inb = True
        if inb:
            block_lines += 1
        if "*/" in s:
            inb = False
    code_lines = len([l for l in lines if l.strip() and not l.strip().startswith(("//", "*", "/*"))])
    return dict(code_lines=code_lines, kdoc_blocks=kdoc, public_decls=pub, inline_comments=inline, comment_lines=block_lines + inline)


def tag_of(model, task):
    return model.replace(":", "_").replace("/", "_") + "__" + task


def gen(models):
    prompts = build_prompts()
    os.makedirs(BENCH + "/out", exist_ok=True)
    f = open(BENCH + "/gen.jsonl", "a", encoding="utf-8")
    for model in models:
        # one warm-up call so the first measured task does not pay for loading the model
        chat(model, "Reply with the single word ok.")
        for task in ["offline", "series", "collections", "groups"]:
            wall, r, err = chat(model, prompts[task])
            rec = dict(model=model, task=task, wall_s=round(wall, 1), gen_error=err)
            if r:
                txt = r["message"]["content"]
                tag = tag_of(model, task)
                open(BENCH + "/out/" + tag + ".txt", "w", encoding="utf-8", newline="\n").write(txt)
                ev = r.get("eval_duration", 0) / 1e9
                rec.update(prompt_tokens=r.get("prompt_eval_count"), out_tokens=r.get("eval_count"),
                           tok_per_s=round(r.get("eval_count", 0) / ev, 1) if ev else 0,
                           gen_s=round(ev, 1), load_s=round(r.get("load_duration", 0) / 1e9, 1))
                blocks = re.findall(r"```kotlin\n(.*?)```", txt, flags=re.S)
                rec["blocks"] = len(blocks)
                if len(blocks) == 1:
                    open(BENCH + "/out/" + tag + ".kt", "w", encoding="utf-8", newline="\n").write(blocks[0])
                    rec.update(comment_metrics(blocks[0]))
            f.write(json.dumps(rec) + "\n")
            f.flush()
            print("gen", model, task, rec.get("wall_s"), "s", rec.get("tok_per_s"), "tok/s blocks", rec.get("blocks"), flush=True)
    print("generation done", flush=True)


def test():
    out = open(BENCH + "/results.jsonl", "w", encoding="utf-8")
    try:
        for line in open(BENCH + "/gen.jsonl", encoding="utf-8"):
            rec = json.loads(line)
            model, task = rec["model"], rec["task"]
            kt = BENCH + "/out/" + tag_of(model, task) + ".kt"
            if rec.get("blocks") == 1 and os.path.exists(kt):
                code = open(kt, encoding="utf-8").read()
                target = REPO + "/" + TASKS[task]["target"]
                open(target, "w", encoding="utf-8", newline="\n").write(code if code.endswith("\n") else code + "\n")
                rec.update(run_tests(task))
                subprocess.run(["git", "checkout", "--", TASKS[task]["target"]], cwd=REPO, capture_output=True)
            else:
                rec.update(compiled=False, passed=0, total=0,
                           error=rec.get("gen_error") or "answer did not contain exactly one kotlin block (%s)" % rec.get("blocks"))
            out.write(json.dumps(rec) + "\n")
            out.flush()
            print("test", model, task, rec["compiled"], "%s/%s" % (rec["passed"], rec["total"]), flush=True)
    finally:
        subprocess.run(["git", "checkout", "--", "shared"], cwd=REPO, capture_output=True)
        print("tests done; restored", flush=True)


if sys.argv[1] == "gen":
    gen(sys.argv[2].split(",") if len(sys.argv) > 2 else MODELS)
else:
    test()
