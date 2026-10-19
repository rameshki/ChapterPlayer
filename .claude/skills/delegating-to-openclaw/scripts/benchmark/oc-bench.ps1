param([string]$B)
$rows = @()
foreach ($m in @("gemma4:26b", "qwen3-coder:latest")) {
    $tag = $m -replace "[:/]", "_"
    # warm-up so the first measured task does not pay for loading the model
    $null = openclaw infer model run --gateway --model "ollama/$m" --prompt "Reply with the single word ok." 2>&1
    foreach ($t in @("offline", "series", "collections", "groups")) {
        $p = Get-Content -Raw -Encoding utf8 "$B\oc\prompt-$t.txt"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        openclaw infer model run --gateway --model "ollama/$m" --prompt $p 2>&1 | Out-File -Encoding utf8 "$B\oc\$tag`__$t.txt"
        $rows += ("{0} {1}: {2:N1} s" -f $m, $t, $sw.Elapsed.TotalSeconds)
        Write-Output $rows[-1]
    }
}
$rows | Out-File -Encoding utf8 "$B\oc\timing.txt"
