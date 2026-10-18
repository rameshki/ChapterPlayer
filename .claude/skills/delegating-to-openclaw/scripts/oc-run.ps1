# One-shot call to a local model through OpenClaw's gateway (no agent loop, no tools, no sandbox folder needed).
# Usage (PowerShell tool, run in the background): pwsh -File scripts/oc-run.ps1 -PromptFile <in.txt> -OutFile <out.txt> [-Model ollama/gemma4:26b]
# The prompt is read by YOUR account and passed inline, so it can live anywhere (for example the session scratchpad).
# --gateway matters: the default "local" mode ignores --model (it always answers with the agent's default model) and is slower.
# The model must be listed in OpenClaw's config under models.providers.ollama.models AND agents.entries.main.models (see lessons.md).
# Check the "model:" line at the top of the answer file.
param(
    [Parameter(Mandatory = $true)][string]$PromptFile,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [string]$Model = "ollama/gemma4:26b"
)
$p = Get-Content -Raw -Encoding utf8 $PromptFile
openclaw infer model run --gateway --model $Model --prompt $p 2>&1 | Out-File -Encoding utf8 $OutFile
