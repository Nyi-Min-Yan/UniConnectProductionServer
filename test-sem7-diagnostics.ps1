#!/usr/bin/env pwsh
# Diagnostic experiment: determine if Sem-7 failure is global infeasibility vs greedy-freeze infeasibility

$baseUrl = "http://localhost:8080"
$termId = "efc68a91-5818-41aa-b4fb-893e240ea0ed"
$midTermId = "4f885c79-0eb3-a8b3-d35d-dee741a88907"
$sem1 = "ca7bb336-9530-4254-bc8d-3e92d3278ab3"
$sem3 = "2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb"
$sem5 = "45088805-eefe-47b3-a7d4-eed0c8ec7f0c"
$sem7 = "3890917f-e9c5-4ddc-8622-c981820a589f"
$secA = "81004274-cf05-491f-b1a3-7c8e3d5c77e7"
$secB = "f19a0bb5-347a-4b40-aa0a-dd7062a0d64e"
$secC = "adc0d7f4-3075-41d0-9366-c6e8b80f0a27"
$secCT = "029878c5-d51d-4a0a-8fb1-99642ab4dee1"

$loginResp = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" -Method POST -ContentType "application/json" -Body '{"email":"dawmya@gmail.com","password":"ucstgo@2026"}' -UseBasicParsing -TimeoutSec 10
$loginData = $loginResp.Content | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($loginData.accessToken)" }

$results = @()

function Run-Test($testName, $semesters, $timeoutSec = 180) {
    Write-Host "`n===== $testName ====="
    $body = @{ examTypeId = $midTermId; semesters = $semesters; autoBindCurriculum = $true } | ConvertTo-Json -Depth 5
    $createResp = Invoke-WebRequest -Uri "$baseUrl/api/generations" -Method POST -Headers $headers -ContentType "application/json" -Body (@{ termId = $termId } | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
    $genId = ($createResp.Content | ConvertFrom-Json).generationId
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try { Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/generate" -Method POST -Headers $headers -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 30 | Out-Null } catch {}
    $status = "TIMEOUT"; $elapsed = 0
    while ($elapsed -lt $timeoutSec) {
        Start-Sleep -Seconds 5; $elapsed += 5
        try {
            $check = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId" -Headers $headers -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json
            if ($check.status -eq "COMPLETED" -or $check.status -eq "FAILED") { $status = $check.status; break }
        } catch {}
    }
    $sw.Stop()
    $schedules = @()
    if ($status -eq "COMPLETED") {
        $schedules = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/schedules" -Headers $headers -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
    }
    Write-Host "  Status: $status | Time: $([math]::Round($sw.Elapsed.TotalSeconds, 1))s | Schedules: $($schedules.Count)"
    if ($status -eq "FAILED" -and $check.failureReport) { Write-Host "  Report: $($check.failureReport)" }
    $script:results += [PSCustomObject]@{ Test=$testName; Status=$status; TimeSec=[math]::Round($sw.Elapsed.TotalSeconds,1); Schedules=$schedules.Count }
    return $status
}

# TEST 1: Sem-7 ALONE (no frozen semesters)
Run-Test "TEST1: Sem-7 ALONE" @(
    @{ semesterId=$sem7; sectionIds=@($secA,$secB,$secCT) }
)

Start-Sleep -Seconds 20

# TEST 2: Sem-5 + Sem-7 TOGETHER (sequential, only 2 semesters)
Run-Test "TEST2: Sem-5+7 TOGETHER" @(
    @{ semesterId=$sem5; sectionIds=@($secA,$secB,$secCT) }
    @{ semesterId=$sem7; sectionIds=@($secA,$secB,$secCT) }
)

Start-Sleep -Seconds 20

# TEST 3: Sem-1/3/5/7 in standard order (confirm current behavior)
Run-Test "TEST3: 1-3-5-7 STANDARD" @(
    @{ semesterId=$sem1; sectionIds=@($secA,$secB,$secC) }
    @{ semesterId=$sem3; sectionIds=@($secA,$secB,$secC) }
    @{ semesterId=$sem5; sectionIds=@($secA,$secB,$secCT) }
    @{ semesterId=$sem7; sectionIds=@($secA,$secB,$secCT) }
)

Write-Host "`n===== RESULTS ====="
$results | Format-Table -AutoSize
