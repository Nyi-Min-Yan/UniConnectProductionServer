$baseUrl = "http://localhost:8080"
$termId = "efc68a91-5818-41aa-b4fb-893e240ea0ed"
$midTermId = "4f885c79-0eb3-a8b3-d35d-dee741a88907"
$sem5 = "45088805-eefe-47b3-a7d4-eed0c8ec7f0c"
$sem7 = "3890917f-e9c5-4ddc-8622-c981820a589f"
$secA = "81004274-cf05-491f-b1a3-7c8e3d5c77e7"
$secB = "f19a0bb5-347a-4b40-aa0a-dd7062a0d64e"
$secCT = "029878c5-d51d-4a0a-8fb1-99642ab4dee1"

$loginResp = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" -Method POST -ContentType "application/json" -Body '{"email":"dawmya@gmail.com","password":"ucstgo@2026"}' -UseBasicParsing -TimeoutSec 10
$loginData = $loginResp.Content | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($loginData.accessToken)" }

Write-Host "===== TEST 5a: 5->7->3->1 ====="
$body = @{
    examTypeId = $midTermId
    semesters = @(
        @{ semesterId = $sem5; sectionIds = @($secA, $secB, $secCT) }
        @{ semesterId = $sem7; sectionIds = @($secA, $secB, $secCT) }
    )
    autoBindCurriculum = $true
    semesterOrder = "reverse"
} | ConvertTo-Json -Depth 5
$genResp = Invoke-WebRequest -Uri "$baseUrl/api/generations" -Method POST -Headers $headers -ContentType "application/json" -Body (@{ termId = $termId } | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
$genId = ($genResp.Content | ConvertFrom-Json).generationId
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId/generate" -Method POST -Headers $headers -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 30 | Out-Null } catch {}
$status = "TIMEOUT"
for ($i = 0; $i -lt 180; $i += 5) {
    Start-Sleep -Seconds 5
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
Write-Host "  Result: $status | Time: $([math]::Round($sw.Elapsed.TotalSeconds, 1))s | Schedules: $($schedules.Count)"
if ($status -eq "FAILED" -and $check.failureReport) { Write-Host "  Failure: $($check.failureReport)" }

Start-Sleep -Seconds 25

Write-Host "`n===== TEST 5b: 7->1->3->5 (reverse+standard mix) ====="
$sem1 = "ca7bb336-9530-4254-bc8d-3e92d3278ab3"
$sem3 = "2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb"
$secC = "adc0d7f4-3075-41d0-9366-c6e8b80f0a27"
$body2 = @{
    examTypeId = $midTermId
    semesters = @(
        @{ semesterId = $sem7; sectionIds = @($secA, $secB, $secCT) }
        @{ semesterId = $sem1; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem3; sectionIds = @($secA, $secB, $secC) }
        @{ semesterId = $sem5; sectionIds = @($secA, $secB, $secCT) }
    )
    autoBindCurriculum = $true
    semesterOrder = "reverse"
} | ConvertTo-Json -Depth 5
$genResp2 = Invoke-WebRequest -Uri "$baseUrl/api/generations" -Method POST -Headers $headers -ContentType "application/json" -Body (@{ termId = $termId } | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
$genId2 = ($genResp2.Content | ConvertFrom-Json).generationId
$sw2 = [System.Diagnostics.Stopwatch]::StartNew()
try { Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId2/generate" -Method POST -Headers $headers -ContentType "application/json" -Body $body2 -UseBasicParsing -TimeoutSec 30 | Out-Null } catch {}
$status2 = "TIMEOUT"
for ($i = 0; $i -lt 180; $i += 5) {
    Start-Sleep -Seconds 5
    try {
        $check2 = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId2" -Headers $headers -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json
        if ($check2.status -eq "COMPLETED" -or $check2.status -eq "FAILED") { $status2 = $check2.status; break }
    } catch {}
}
$sw2.Stop()
$schedules2 = @()
if ($status2 -eq "COMPLETED") {
    $schedules2 = (Invoke-WebRequest -Uri "$baseUrl/api/generations/$genId2/schedules" -Headers $headers -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
}
Write-Host "  Result: $status2 | Time: $([math]::Round($sw2.Elapsed.TotalSeconds, 1))s | Schedules: $($schedules2.Count)"
if ($status2 -eq "FAILED" -and $check2.failureReport) { Write-Host "  Failure: $($check2.failureReport)" }
