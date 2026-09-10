\$terms = Invoke-WebRequest -Uri 'http://localhost:8080/api/terms' -UseBasicParsing
Write-Host \$terms.Content