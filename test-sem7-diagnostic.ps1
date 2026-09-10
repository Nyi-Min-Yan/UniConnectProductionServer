#!/usr/bin/env pwsh
# Diagnostic test script for Sem-7 root cause analysis
# Tests: (A) Sem-1/3/5 only, (B) Full Mid-Term with Sem-7

$baseUrl = "http://localhost:8080"
$logFile = "D:\UniConnect\UniConnectServer-V2\UniConnectServer\gen-test.log"

# ---- Login ----
$loginResp = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" -Method POST -ContentType "application/json" -Body '{"email":"dawmya@gmail.com","password":"ucstgo@2026"}' -UseBasicParsing -TimeoutSec 10
$loginData = $loginResp.Content | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($loginData.accessToken)" }

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

function Run-Generation($testName, $genBody) {
    "" | Out-File $logFile -Encoding utf8
    Write-Host "`n========================================"
    Write-Host "  TEST: $testName"
    Write-Host "========================================"

    $createResp = Invoke-WebRequest -Uri "$baseUrl/api/generations" -Method POST -Headers $headers -ContentType "application/json" -Body (@{ termId = $termId } | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
    $genId = ($createResp.Content | ConvertFrom-Json).generationId

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $genResp = Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/generate" -Method POST -Headers $headers -ContentType "application/json" -Body $genBody -UseBasicParsing -TimeoutSec 180
        Write-Host "  Trigger response: $(($genResp.Content | ConvertFrom-Json).status)"
    } catch {
        Write-Host "  Trigger error: $($_.Exception.Message)"
    }

    $maxWait = 180; $elapsed = 0
    while ($elapsed -lt $maxWait) {
        Start-Sleep -Seconds 5; $elapsed += 5
        try {
            $check = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId" -Headers $headers -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json
            if ($check.status -eq "COMPLETED" -or $check.status -eq "FAILED") {
                $sw.Stop()
                Write-Host "  Result: $($check.status) in $($sw.Elapsed.TotalSeconds.ToString('F1'))s"
                if ($check.failureReport) { Write-Host "  Report: $($check.failureReport)" }
                if ($check.status -eq "COMPLETED") {
                    $schedules = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/schedules" -Headers $headers -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
                    Write-Host "  Total schedules: $($schedules.Count)"
                    $schedules | Group-Object { "$($_.semesterNo)/$($_.sectionName)" } | Sort-Object Name | ForEach-Object { Write-Host "    $($_.Name): $($_.Count)" }
                }
                return @{ status = $check.status; time = $sw.Elapsed.TotalSeconds; schedules = $schedules }
            }
        } catch { Write-Host "  [$elapsed s] polling..." }
    }
    $sw.Stop()
    Write-Host "  TIMEOUT after $($sw.Elapsed.TotalSeconds.ToString('F1'))s"
    return @{ status = "TIMEOUT"; time = $sw.Elapsed.TotalSeconds }
}

# ============================================
#  TEST A: Sem-1/3/5 ONLY (no Sem-7)
# ============================================
$testA = @{
    examTypeId = $midTermId
    semesters = @(
        @{ semesterId = $sem1; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem3; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem5; sectionIds = @($secA, $secB, $secCT) }
    )
    autoBindCurriculum = $true
} | ConvertTo-Json -Depth 5

$resultA = Run-Generation "Sem-1/3/5 ONLY" $testA

# Wait for DB connection pool to recover
Write-Host "`n  Waiting 30s for DB pool recovery..."
Start-Sleep -Seconds 30

# ============================================
#  TEST B: FULL Mid-Term (Sem-1/3/5/7)
# ============================================
$testB = @{
    examTypeId = $midTermId
    semesters = @(
        @{ semesterId = $sem1; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem3; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem5; sectionIds = @($secA, $secB, $secCT) }
        @{ semesterId = $sem7; sectionIds = @($secA, $secB, $secCT) }
    )
    autoBindCurriculum = $true
} | ConvertTo-Json -Depth 5

$resultB = Run-Generation "FULL Mid-Term (Sem-1/3/5/7)" $testB

# ============================================
#  LOG ANALYSIS
# ============================================
Write-Host "`n========================================"
Write-Host "  SERVER LOG ANALYSIS (full test)"
Write-Host "========================================"

Write-Host "`n--- Semester Summary ---"
Get-Content $logFile | Select-String -Pattern "SEMESTER.*SOLVED|SEMESTER.*FAILED" -CaseSensitive:$false | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host "`n--- Solver Context ---"
Get-Content $logFile | Select-String -Pattern "SolverContext" -CaseSensitive:$false | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host "`n--- Elective Groups ---"
Get-Content $logFile | Select-String -Pattern "ElectiveGroupPrecheck|elective groups" -CaseSensitive:$false | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host "`n--- Error / Failure ---"
Get-Content $logFile | Select-String -Pattern "Exhausted|time limit|FAILED|giving up" -CaseSensitive:$false | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host "`n--- Sem-7 Candidate Filtering ---"
Get-Content $logFile | Select-String -Pattern "depth=.*pattern.*pruned|depth=.*pattern.*skipped|prefilterSkipped|forwardPrunes" -CaseSensitive:$false | Measure-Object | ForEach-Object { Write-Host "  Total prune/skip log lines: $($_.Count)" }
Get-Content $logFile | Select-String -Pattern "depth=.*pruned by forward" -CaseSensitive:$false | Measure-Object | ForEach-Object { Write-Host "  Forward check prunes: $($_.Count)" }
Get-Content $logFile | Select-String -Pattern "skipped \(cross-section" -CaseSensitive:$false | Measure-Object | ForEach-Object { Write-Host "  Cross-section staff conflict skips: $($_.Count)" }

Write-Host "`n--- Timing ---"
Get-Content $logFile | Select-String -Pattern "SEMESTER.*in \d+ms" -CaseSensitive:$false | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

# ============================================
#  FINAL COMPARISON
# ============================================
Write-Host "`n========================================"
Write-Host "  COMPARISON SUMMARY"
Write-Host "========================================"
Write-Host "  Test A (Sem-1/3/5): $($resultA.status) in $($resultA.time)s"
Write-Host "  Test B (Sem-1/3/5/7): $($resultB.status) in $($resultB.time)s"
