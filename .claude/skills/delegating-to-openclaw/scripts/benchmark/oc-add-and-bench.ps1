param([string]$B)
function Add-Model($id, $idx) {
    $entry = '{"id":"' + $id + '","name":"' + $id + '","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":262144,"contextTokens":32768,"maxTokens":8192,"params":{"thinking":false},"compat":{"supportsUsageInStreaming":true,"supportsTools":true,"supportsJsonSchemaResponseFormat":true},"api":"ollama"}'
    "--- add $id as models[$idx]"
    openclaw config set "models.providers.ollama.models[$idx]" $entry --strict-json --dry-run 2>&1 | Select-Object -First 2
    openclaw config set "models.providers.ollama.models[$idx]" $entry --strict-json 2>&1 | Select-Object -First 2
    $v = '{"ollama/' + $id + '":{"agentRuntime":{"id":"openclaw"}}}'
    openclaw config set agents.entries.main.models $v --strict-json --merge 2>&1 | Select-Object -First 2
}
Add-Model "qwen3.6:35b-a3b" 2
Add-Model "qwen3.5:4b" 3
openclaw config validate 2>&1 | Select-Object -First 2
openclaw models list 2>&1 | Select-Object -First 8
$plan = @(@("qwen3.6:35b-a3b","r1"), @("qwen3.5:4b","r1"), @("gemma4:26b","r2"), @("qwen3-coder:latest","r2"))
$rows = @()
foreach ($pm in $plan) {
    $m = $pm[0]; $rep = $pm[1]; $tag = $m -replace "[:/]", "_"
    $null = openclaw infer model run --gateway --model "ollama/$m" --prompt "Reply with the single word ok." 2>&1
    foreach ($t in @("offline", "series", "collections", "groups")) {
        $p = Get-Content -Raw -Encoding utf8 "$B\oc\prompt-$t.txt"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        openclaw infer model run --gateway --model "ollama/$m" --prompt $p 2>&1 | Out-File -Encoding utf8 "$B\oc\$tag`__$t`__$rep.txt"
        $rows += ("{0}|{1}|{2}|{3:N1}" -f $m, $rep, $t, $sw.Elapsed.TotalSeconds)
        Write-Output $rows[-1]
    }
}
$rows | Out-File -Encoding utf8 "$B\oc\timing2.txt"
