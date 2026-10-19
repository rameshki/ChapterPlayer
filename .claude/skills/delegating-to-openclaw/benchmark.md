# Local model benchmark (2026-10-18)

How to rerun: the scripts in `scripts/benchmark/` hard-code this session's scratchpad paths, so edit the `SP`/`B`/`REPO` constants first. Phases: `python -I bench.py gen [models]` (generation only, so Gradle never competes with Ollama for memory; one warm-up call per model), `python -I bench.py test` (each answer is copied into the repo, the matching test class is run through Gradle, then the file is restored with git), `python -I mkjudge.py` (anonymised per-task files with a hidden label-to-model map for blind judges), `python -I combine.py` (merge tests, speed, judge scores). Calls go straight to Ollama `/api/chat` (`num_ctx` 16384, `num_predict` 4096, `think` off for qwen3.6 and gemma4).

Tasks (all with tests written first by Claude, header and comment style dictated in the prompt): `offline` (2 small functions), `series` (regex parser), `collections` (kotlinx.serialization DTOs + mapper), `groups` (hard: grouping logic, 36 tests). Judges: four blind Claude Sonnet agents, one per task, scoring comments and code 1-5 without seeing tests or model names.

## Results (one sample per model and task; temperature at the model default, so treat small differences as noise)
| Model | Tasks fully passing | Mean tok/s | Comments (1-5) | Code (1-5) | Total generation time |
|---|---|---|---|---|---|
| gemma4:26b | 3/4 (groups 35/36) | 73.4 | 3.25 | 4.50 | 27 s |
| qwen3-coder:latest | 2/4 (series 7/8, groups 33/36) | 49.0 | 3.50 | 3.75 | 39 s |
| qwen3.6:35b-a3b | 2/4 (series no compile; groups gave no code block) | 62.0 | 3.33 | 3.67 | 54 s |
| qwen2.5-coder:14b | 2/4 (series 7/8; groups no compile) | 32.7 | 2.75 | 3.00 | 50 s |
| gemma3:27b | 2/4 (series 7/8; groups no compile) | 4.7 | 2.75 | 3.00 | 401 s |
| deepseek-coder:6.7b | 2/4 (series, groups no compile) | 60.6 | 1.25 | 2.75 | 37 s |
| codellama:13b | 2/4 (series, groups no compile) | 8.4 | 1.25 | 2.75 | 214 s |

## What it says
- Easy units (offline rules, DTOs) were solved by every model; the hard one separated them: only gemma4:26b (35/36) and qwen3-coder (33/36) produced code that compiled and nearly passed.
- Dense big models (gemma3:27b, codellama:13b) are far too slow here (5-10 tok/s); mixture-of-experts models (gemma4:26b, qwen3-coder, qwen3.6) run at 50-75 tok/s.
- Commenting: models given the KDoc instruction mostly wrote generic "Represents ..." KDoc; useful comments explained a rule or a why. deepseek-coder and codellama ignored the comment instruction on most tasks. Wrong comments were rare but real (one wrong `@param`, one wrong rule summary). qwen3-coder wrote the most inline comments, some restating code.
- Compile failures were mostly nullable misuse (`raw.trim()` on `String?`), wrong helper signatures, redefining existing declarations and returning the wrong deliverable (the test file).
- Current OpenClaw default is qwen3-coder; the evidence favours trying gemma4:26b, which needs an entry under `models.providers.ollama.models` in OpenClaw's config before `--model` works (ask the owner first), or the direct path (`scripts/ollama-run.py`).

## Same tasks through OpenClaw (gateway mode, 2026-10-18, after adding gemma4:26b to its config)
| Model | offline | series | collections | groups | Total time |
|---|---|---|---|---|---|
| gemma4:26b | 6/6 (8.1 s) | 8/8 (9.8 s) | 7/7 (11.2 s) | 35/36 (27.9 s) | 57 s |
| qwen3-coder | 6/6 (9.7 s) | 8/8 (11.9 s) | 7/7 (13.2 s) | no compile (41.2 s) | 76 s |
Both runs agree with the direct benchmark: gemma4:26b is the stronger and faster coding model here. Scripts: `scripts/benchmark/oc-bench.ps1` (generation) and `oc-test.py` (tests).
Correction: `openclaw infer model run` WITHOUT `--gateway` ignores `--model`, which is why the first comparison attempt compared a model with itself.

## Repeat samples (2026-10-18) and the other two models, through OpenClaw
| Model | offline | series | collections | groups (hard) |
|---|---|---|---|---|
| gemma4:26b run 1 / run 2 | 6/6, 6/6 | 8/8, 8/8 | 7/7, 7/7 | 35/36, no compile |
| qwen3-coder run 1 / run 2 | 6/6, 6/6 | 8/8, 8/8 | 7/7, 6/7 | no compile, 30/36 |
| qwen3.6:35b-a3b | 6/6 | 8/8 | 7/7 | no code fence |
| qwen3.5:4b | 6/6 | no compile | 7/7 | no compile |
Takeaways: the easy tasks are reliable for the three larger models; on the hard task even the two best models fail to compile about once in three attempts, so plan for a fix round; qwen3.5:4b is only suitable for the smallest units. Timing of the second run was noisy (the GPU swapped between four models), so the speed comparison above (first run) is the one to trust.
