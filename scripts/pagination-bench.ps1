# scripts/pagination-bench.ps1 — PLAN.md M6.
# Latency-vs-depth for OFFSET vs keyset pagination over the 5M-row
# transaction table. Each request is fired twice; the second (warm) timing is
# kept, per PLAN.md ground rule 5. Keyset "depth" is approximated by jumping
# straight to afterId = depth * size (PLAN.md's explicitly sanctioned
# shortcut — walking the cursor 10,000 times would just be HTTP-round-trip
# overhead, not a different query shape; `WHERE id > :afterId` costs the same
# whether you arrived at that id by walking or by jumping).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Size = 50,
    [int[]]$Depths = @(1, 10, 100, 1000, 10000)
)

function Get-WarmTimeMs([string]$Url) {
    curl.exe -s -o NUL -w "%{time_total}" $Url | Out-Null
    $warm = curl.exe -s -o NUL -w "%{time_total}" $Url
    return [math]::Round([double]$warm * 1000, 2)
}

Write-Output "depth,offset_ms,keyset_ms"
foreach ($depth in $Depths) {
    $offsetUrl = "$BaseUrl/api/transactions?page=$depth&size=$Size"
    $afterId = $depth * $Size
    $keysetUrl = "$BaseUrl/api/transactions?afterId=$afterId&size=$Size"

    $offsetMs = Get-WarmTimeMs $offsetUrl
    $keysetMs = Get-WarmTimeMs $keysetUrl

    Write-Output "$depth,$offsetMs,$keysetMs"
}
