try {
    \$r = Invoke-WebRequest -Uri 'http://localhost:8080/api/auth/login' -Method POST -ContentType 'application/json' -Body '{"email":"dawmya@gmail.com","password":"ucstgo@2026"}' -UseBasicParsing -TimeoutSec 15
    \$login = \$r.Content | ConvertFrom-Json
    Write-Host "TOKEN: \$(\$login.accessToken.Substring(0,30))..."
} catch {
    Write-Host "LOGIN FAILED: \$_"
}