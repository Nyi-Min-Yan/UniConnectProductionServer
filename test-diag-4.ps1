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

function Run-DiagTest($testName, $scopeBody, $timeoutSec = 180) {
    Write-Host "`n===== $testName ====="
    $genResp = Invoke-WebRequest -Uri "$baseUrl/api/generations" -Method POST -Headers $headers -ContentType "application/json" -Body (@{ termId = $termId } | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
    $genId = ($genResp.Content | ConvertFrom-Json).generationId

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $trigResp = Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/generate" -Method POST -Headers $headers -ContentType "application/json" -Body $scopeBody -UseBasicParsing -TimeoutSec 30
        Write-Host "  Trigger: $(($trigResp.Content | ConvertFrom-Json).status)"
    } catch {
        Write-Host "  Trigger error: $($_.Exception.Message)"
        return
    }

    $status = "TIMEOUT"
    for ($i = 0; $i -lt $timeoutSec; $i += 5) {
        Start-Sleep -Seconds 5
        try {
            $check = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId" -Headers $headers -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json
            if ($check.status -eq "COMPLETED" -or $check.status -eq "FAILED") {
                $status = $check.status
                break
            }
        } catch {}
    }
    $sw.Stop()
    $time = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    $schedules = @()
    if ($status -eq "COMPLETED") {
        $schedules = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/schedules" -Headers $headers -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
    }

    Write-Host "  Result: $status | Time: ${time}s | Schedules: $($schedules.Count)"
    if ($status -eq "FAILED" -and $check.failureReport) { Write-Host "  Failure: $($check.failureReport)" }
    if ($status -eq "COMPLETED") {
        $schedules | Group-Object { "$($_.semesterNo)/$($_.sectionName)" } | Sort-Object Name | ForEach-Object { Write-Host "    $($_.Name): $($_.Count) schedules" }
    }

    return @{ status=$status; time=$time; schedules=$schedules.Count }
}

# ===== TEST 4: REVERSED ORDER 7→5→3→1 =====
$test4Body = @{
    examTypeId = $midTermId
    semesters = @(
        @{ semesterId = $sem7; sectionIds = @($secA, $secB, $secCT) }
        @{ semesterId = $sem5; sectionIds = @($secA, $secB, $secCT) }
        @{ semesterId = $sem3; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem1; sectionIds = @($secA, $secB, $secC) }
    )
    autoBindCurriculum = $true
    semesterOrder = "reverse"
} | ConvertTo-Json -Depth 5
$r4 = Run-DiagTest "TEST 4: 7->5->3->1 REVERSED" $test4Body

Write-Host "`n===== FINAL DIAGNOSTIC SUMMARY ====="
Write-Host "TEST 1: Sem-7 ALONE       | FAILED | 67.6s | 0 schedules"
Write-Host "TEST 2: Sem-5+7 TOGETHER  | FAILED | 71.6s | 0 schedules"
Write-Host "TEST 3: 1-3-5-7 STANDARD  | FAILED | 77.7s | 0 schedules"
Write-Host "TEST 4: 7-5-3-1 REVERSED  | $($r4.status) | $($r4.time)s | $($r4.schedules) schedules"
