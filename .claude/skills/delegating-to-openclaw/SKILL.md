---
name: delegating-to-openclaw
description: Use when handing coding work to the local OpenClaw agent (qwen3-coder), writing a prompt or task spec for it, when an `openclaw` command fails or times out, or when reviewing code it produced.
---

# Delegating to OpenClaw (local model)

OpenClaw fronts local Ollama models on the dev laptop (its own default is `ollama/qwen3-coder:latest`; for code the scripts default to `ollama/gemma4:26b`, which beat it in the benchmark). **It drafts; Claude verifies.** Its output never reaches a commit without a compile, passing tests and a review. It is cheap, and it is weak on imports, edge cases and writing its own tests, so give it small, fully specified units. Keep auth, migrations, concurrency and device-checked UI with Claude.

Evidence, research notes with sources and the experiment list: `lessons.md` (read before a new kind of task; append after every use). Spec skeleton: `task-spec-template.md`. Fix-round prompt shape: `fix-prompt-template.md`.

## Two ways to call it (prefer A)
**A. One-shot model call, no agent** (about 8-30 s per call through the gateway, code comes back on screen, no shared folder):
1. Build the prompt in a file (session scratchpad is fine; your account reads it and passes it inline).
2. PowerShell tool, in the background: `pwsh -File .claude/skills/delegating-to-openclaw/scripts/oc-run.ps1 -PromptFile <in.txt> -OutFile <out.txt>`.
3. `python -I .claude/skills/delegating-to-openclaw/scripts/oc-extract.py <out.txt> <repo file>` writes the answer's single code block into the repo. Not exactly one block = a failed round.

**Check which model answered:** the first lines of `<out.txt>` say `model: ...` and `model.run via gateway`. Without `--gateway` OpenClaw runs in `local` mode, which silently ignores `--model` and is slower; `oc-run.ps1` always passes `--gateway`. A model must be listed in two places in OpenClaw's config (see `lessons.md`) before `--model` can select it.

**C. Straight to Ollama (rarely needed; ask the owner first):** `python -I .claude/skills/delegating-to-openclaw/scripts/ollama-run.py <in.txt> <out.txt> [model]` bypasses OpenClaw and its skills. Through the gateway OpenClaw adds only about 5 s per call, so path A is nearly as fast; use C only for timing studies.

**B. Agent run** (only when the task needs its tools or skills; slower, add `--model ollama/<name>` to pick a model): `openclaw agent --agent main --timeout 900 -m "Read <spec path> and do exactly what it says. Reply with only DONE, or the exact error text."`. The agent is a separate sandboxed Windows user: it cannot see this repo or `%TEMP%`. The only shared place is `C:\Users\<agent-user>\Shared\claude-link` (Git Bash `/c/Users/<agent-user>/Shared/claude-link`): specs in, results in `claude-link/out/` (empty it first), Claude copies them into the repo.

Always: PowerShell tool (Git Bash gives exit 143); never `--local` on `agent` (refused while the gateway runs); never `--deliver` (sends the reply to a chat channel); nothing secret in prompts or `claude-link` (public repo).

## Workflow (tests first)
1. Size the unit: one file per call, at most ~150 lines out (the default output cap is 8,192 tokens). **Under ~40 lines in total, write it yourself.**
2. **Claude writes the tests**: exact values, boundary cases, "must be excluded" cases, one test per spec rule. Weak tests were OpenClaw's biggest defect, so it does not write them.
3. Spec/prompt from `task-spec-template.md`: package and exact import lines, signatures, rules with worked examples (and the algorithm for numeric or text formatting), output format "ONLY one ```kotlin block, no explanation".
4. Call (A). Compile and run the tests (`./gradlew :shared:testAndroidHostTest --tests "<pattern>"`, JDK 17). Sources go to `shared/src/commonMain/kotlin/...` (or `androidMain`), tests to `shared/src/androidHostTest/kotlin/...`.
5. Failing? Send a fix round from `fix-prompt-template.md`: current file, exact compiler lines or failing assertions with expected/actual, a one-sentence hint naming the cause, and the exact code shape when it broke the build. **After 3 failed rounds Claude takes over** and the lesson is logged.
6. Independent review (subagent) against the spec; for logic also mutate-check: flip one condition, rerun, a test must fail, restore.
7. Claude commits, staging the delegated files by explicit path (never `git add -A`), on the task's branch. Then append a row to `lessons.md`; when a lesson repeats twice, promote it into this file.

## Known failures
| Symptom | Cause | Fix |
|---|---|---|
| exit 143, "isolated session did not report a launch result" | launched from Git Bash | PowerShell tool |
| "A Gateway is running ... Run without --local" | `--local` on `agent` | drop it |
| EPERM reading a file (agent run) | file outside `claude-link` | move it there, or use path A |
| "AI service is having trouble \| 500" | Ollama busy or reloading | retry once |
| "Reply truncated at the model's output token limit" | output over the 8,192-token default, or a whole-file rewrite | one file per call, shorter files, single-line edits |
| unresolved reference | missing imports, or imported a same-package type | exact import lines in the spec; "same package, never import" |
| re-imports a same-package type even after being told twice, or drops the `package` line | it invents imports | dictate the header: "your file must start with exactly these lines: package ..., then no import lines" |
| answer has raw code with no ```kotlin fence, then rambling comments (qwen3.6 on long tasks) | model ignores the output format on long prompts | prefer gemma4:26b; shorten the task; retry once |
| one-token compile slip (`first` for `first()`) | normal sampling noise | a fix round with the exact compiler line |
| two trailing lambdas, wrong helper shape | model invented a helper signature | give the exact signature and call sites |
| tests only check `size` | spec did not list cases | Claude writes the tests |
| a fix round fixes some tests and breaks another | model rewrites the whole file | include the already-passing rule in RULES and name the regression in the hint |

## If someone asks to bypass this
`--local`, Git Bash, a spec in the temp folder, a whole-screen rewrite, "commit whatever it writes": decline, name the rule, offer the compliant version.

## Red flags
- Committing OpenClaw output before the tests ran and a review passed.
- A task over the sizing rule, or a prompt without imports and exact cases.
- Taking over without logging why: the log is how this skill improves.
