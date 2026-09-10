try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/auth/login' -Method POST -ContentType 'application/json' -Body '{"email":"dawmya@gmail.com","password":"ucstgo@2026"}' -UseBasicParsing -TimeoutSec 15
    Write-Host "LOGIN OK: $($r.StatusCode)"
    $login = $r.Content | ConvertFrom-Json
    Write-Host "TOKEN: $($login.accessToken.Substring(0,20))..."
} catch {
    Write-Host "LOGIN FAILED: $($_.Exception.Message)"
    try {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $body = $sr.ReadToEnd()
        Write-Host "RESPONSE BODY: $body"
    } catch {}
}
