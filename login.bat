curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d {"email":"dawmya@gmail.com","password":"ucstgo@2026"} > response.txt 2>&1
findstr /i "accessToken" response.txt > result.txt
type result.txt