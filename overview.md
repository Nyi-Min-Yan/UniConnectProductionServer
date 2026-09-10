# UniConnect Server — Project Overview

## 1. System Overview

**UniConnect** is a comprehensive University Communication & Social Networking System. This repository contains the **Spring Boot REST API** backend that handles authentication, user management, academic records, attendance, and departmental operations.

The system uses a dual-database architecture:
- **Neon (Serverless PostgreSQL)** — Relational core data (users, grades, attendance, departments) managed by this Spring Boot API via JPA/Hibernate
- **Convex DB** — Real-time features (chat, messaging, posts, notifications) managed by the Next.js frontend

### Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Security | Spring Security + JWT (jjwt 0.12.6) |
| ORM | Spring Data JPA / Hibernate 6.4 |
| Database | Neon Serverless PostgreSQL |
| Build | Maven |
| Password Hashing | BCrypt |
| Remote Method Invocation | Java RMI (`java.rmi`, in-process on port 1099) |

---

## 2. Project Structure

```
src/main/java/com/unicconnect/
├── UniConnectApplication.java          # Application entry point
├── config/
│   ├── CorsConfig.java                 # CORS configuration (localhost:3000)
│   ├── CustomUserDetailsService.java   # Loads users for Spring Security auth
│   ├── JwtAuthenticationFilter.java    # Extracts JWT from Authorization header
│   ├── JwtUtil.java                    # Token generation, validation, hashing
│   └── SecurityConfig.java             # Security filter chain, endpoint rules
├── controller/
│   ├── AcademicController.java          # Grades retrieval (via RMI)
│   ├── AdminController.java            # Admin user creation
│   ├── AttendanceController.java       # Attendance queries (via RMI)
│   ├── AuthController.java             # Login, refresh, change password, logout
│   ├── DepartmentController.java       # Department CRUD
│   ├── HealthController.java           # Health check endpoint
│   ├── SetupController.java            # First admin bootstrap
│   └── UserController.java             # User queries (by role, department, etc.)
├── dto/
│   ├── ApiResponse.java                # Generic success/error response wrapper
│   ├── AuthResponse.java               # Login response (tokens + user info)
│   ├── ChangePasswordRequest.java      # Password change request body
│   ├── CreateAccountRequest.java       # Admin creates user request body
│   ├── CreatedAccountResponse.java     # Response with created user + temp password
│   ├── DepartmentRequest.java          # Department create/update body
│   ├── LoginRequest.java               # Login request body
│   ├── RegisterRequest.java            # Registration body (unused, kept for reference)
│   ├── SetupAdminRequest.java          # First admin setup body
│   └── UserResponse.java               # User response with optional student profile
├── exception/
│   └── GlobalExceptionHandler.java     # Catches auth, validation, runtime errors
├── model/
│   ├── AcademicRecord.java             # Student grades per subject
│   ├── AttendanceSummary.java          # Roll call per subject (computed %, below 75% flag)
│   ├── Department.java                 # Department (name + code)
│   ├── DepartmentHead.java             # HOD assignment per department
│   ├── DepartmentMeeting.java          # Meeting records per department
│   ├── RefreshToken.java               # Refresh token storage
│   ├── RegistrationStatus.java         # Enum: PENDING, APPROVED, REJECTED
│   ├── StudentProfile.java             # Student-specific data (ID number, batch, section)
│   ├── User.java                       # Core user entity
│   └── UserRole.java                   # Enum: STUDENT, TEACHER, MANAGE, STUDENT_AFFAIRS, RECTOR, PRO_RECTOR
├── repository/
│   ├── AcademicRecordRepository.java
│   ├── AttendanceSummaryRepository.java
│   ├── DepartmentHeadRepository.java
│   ├── DepartmentMeetingRepository.java
│   ├── DepartmentRepository.java
│   ├── RefreshTokenRepository.java
│   ├── StudentProfileRepository.java
│   └── UserRepository.java
├── service/
│   ├── AcademicService.java            # Delegates to AcademicRmiClient
│   ├── AttendanceService.java          # Delegates to AttendanceRmiClient
│   ├── AuthService.java                # Login, register, refresh, change password, logout
│   ├── DepartmentService.java          # Department CRUD logic
│   └── UserService.java                # User queries with student profile details
└── rmi/
    ├── client/
    │   ├── AcademicRmiClient.java      # RMI stub lookup + proxy for AcademicRemote
    │   └── AttendanceRmiClient.java    # RMI stub lookup + proxy for AttendanceRemote
    ├── config/
    │   └── RmiConfig.java              # Starts RMI registry (port 1099), binds services
    ├── dto/
    │   ├── AcademicRecordDto.java      # Serializable DTO for grades
    │   └── AttendanceSummaryDto.java   # Serializable DTO for attendance
    ├── remote/
    │   ├── AcademicRemote.java         # Remote interface: getGrades, getGradesByYear
    │   └── AttendanceRemote.java       # Remote interface: getAttendance, calculate, below75
    └── server/
        ├── AcademicRemoteServer.java   # UnicastRemoteObject impl, uses AcademicRecordRepository
        └── AttendanceRemoteServer.java # UnicastRemoteObject impl, uses AttendanceSummaryRepository
```

---

## 3. Database Schema (PostgreSQL — Neon)

### 3.1 Enums

| Enum | Values |
|------|--------|
| `user_role` | STUDENT, TEACHER, MANAGE, STUDENT_AFFAIRS, RECTOR, PRO_RECTOR |
| `registration_status` | PENDING, APPROVED, REJECTED |

### 3.2 Tables

#### `users`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | Auto-increment |
| email | VARCHAR(255) | Unique, not null |
| password_hash | VARCHAR(255) | BCrypt hash, not null |
| full_name | VARCHAR(255) | Not null |
| role | user_role | Not null |
| department_id | BIGINT FK | References departments(id), nullable |
| registration_status | registration_status | Default PENDING |
| is_active | BOOLEAN | Default TRUE |
| must_change_password | BOOLEAN | Default TRUE |
| last_login | TIMESTAMP | Updated on login |
| failed_login_attempts | INT | Default 0, resets on success |
| account_locked_until | TIMESTAMP | Set after 5 failed attempts |
| password_changed_at | TIMESTAMP | Set on password change |
| last_password_reset | TIMESTAMP | Set on admin reset |
| created_at | TIMESTAMP | Auto |
| updated_at | TIMESTAMP | Auto-updated |

#### `departments`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| name | VARCHAR(100) | Unique, not null |
| code | VARCHAR(20) | Unique, not null |
| created_at | TIMESTAMP | |

#### `department_heads`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| department_id | BIGINT FK | Unique (one HOD per department) |
| teacher_id | BIGINT FK | References users(id) |
| assigned_at | TIMESTAMP | |
| is_active | BOOLEAN | |

#### `student_profiles`
| Column | Type | Notes |
|--------|------|-------|
| user_id | BIGINT PK/FK | References users(id) |
| student_id_number | VARCHAR(50) | Unique, not null |
| batch_year | INT | Not null |
| academic_year | VARCHAR(50) | Not null |
| section | VARCHAR(10) | |

#### `academic_records`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| student_id | BIGINT FK | References users(id) |
| subject_code | VARCHAR(50) | |
| subject_name | VARCHAR(100) | |
| academic_year | VARCHAR(50) | |
| grade_letter | VARCHAR(10) | |
| marks | NUMERIC(5,2) | |
| published_at | TIMESTAMP | |

#### `attendance_summary`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| student_id | BIGINT FK | References users(id) |
| subject_code | VARCHAR(50) | |
| total_classes | INT | Default 0 |
| attended_classes | INT | Default 0 |
| percentage | NUMERIC(5,2) | Computed (attended/total * 100) |
| is_below_75 | BOOLEAN | Computed (TRUE if percentage < 75) |
| updated_at | TIMESTAMP | |

#### `department_meetings`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| department_id | BIGINT FK | References departments(id) |
| title | VARCHAR(255) | |
| scheduled_at | TIMESTAMP | |
| summary_notes | TEXT | |
| created_by | BIGINT FK | References users(id) |
| created_at | TIMESTAMP | |

#### `refresh_tokens`
| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL PK | |
| user_id | BIGINT FK | References users(id) |
| token_hash | VARCHAR(255) | SHA-256 hash of the token |
| expires_at | TIMESTAMP | |
| revoked | BOOLEAN | Default FALSE |
| created_at | TIMESTAMP | |
| device_name | VARCHAR(255) | |
| ip_address | VARCHAR(100) | |

---

## 4. User Roles

| Role | Value | Description |
|------|-------|-------------|
| Student | `STUDENT` | Views grades, attendance, posts |
| Teacher | `TEACHER` | Manages grades, attendance, supervision |
| Admin (Manager) | `MANAGE` | Full system access, creates all accounts |
| Student Affairs | `STUDENT_AFFAIRS` | Student welfare and affairs |
| Rector | `RECTOR` | Head of university |
| Pro Rector | `PRO_RECTOR` | Deputy head of university |

**Head of Teacher** — Determined by the `department_heads` table, not a separate role. When a teacher is assigned as HOD, they gain access to departmental meeting scheduling and broadcast notices.

---

## 5. Authentication & Security

### 5.1 Authentication Flow

```
1. POST /api/setup/admin     → Create first admin (only when DB is empty)
2. POST /api/auth/login      → Login with email + password → Returns JWT + refresh token
3. POST /api/auth/refresh    → Exchange refresh token for new access token
4. POST /api/auth/change-password → Force password change (must_change_password flow)
5. POST /api/auth/logout     → Revoke all refresh tokens
```

### 5.2 Token Details

| Token | Lifetime | Storage |
|-------|----------|---------|
| Access Token | 15 minutes | Client-side (memory/localStorage) |
| Refresh Token | 7 days | Hashed in DB (SHA-256), rotated on each refresh |

### 5.3 Login Flow Details

```
POST /api/auth/login
  │
  ├── 1. Find user by email → 401 if not found
  ├── 2. Auto-unlock if `account_locked_until` has passed (reset attempts to 0)
  ├── 3. Check `is_active` → 403 FORBIDDEN (DisabledException) if false
  ├── 4. Check `account_locked_until` future → 423 LOCKED (LockedException) if locked
  ├── 5. Verify password via BCrypt
  │     ├── Invalid → increment `failed_login_attempts`
  │     │            ├── ≥ 5 → lock for 15 min → 423 LOCKED
  │     │            └── < 5 → 401 with "N attempt(s) remaining" message
  │     └── Valid → reset attempts, clear lock, set `last_login` = NOW()
  │                 → return access token + refresh token + `mustChangePassword` flag
  └──
```

### 5.4 Security Features

- **BCrypt** password hashing
- **Account lockout** after 5 failed login attempts (15-minute lock)
- **Auto-unlock** once the lock duration has passed (on next login attempt)
- **Disabled account rejection** — inactive accounts (`is_active = false`) receive HTTP 403
- **Remaining attempts feedback** — each failed attempt returns the count left before lockout
- **Must change password** flag on first login
- **Token rotation** on refresh (old refresh token revoked)
- **Stateless sessions** (no HTTP session, JWT only)
- **CORS** configured for `http://localhost:3000` (Next.js frontend)

### 5.5 Password Policy

### 5.5 Password Policy

- Minimum 8 characters
- Must be changed on first login (`must_change_password = true`)

---

## 6. API Endpoints

### 6.1 Public Endpoints (No Auth Required)

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `GET` | `/api/health` | — | Health check |
| `POST` | `/api/setup/admin` | `SetupAdminRequest` | Create first admin (only works on empty DB) |
| `POST` | `/api/auth/login` | `LoginRequest` | Login, returns JWT + refresh token |
| `POST` | `/api/auth/refresh` | `{ "refreshToken": "..." }` | Rotate refresh token |

### 6.2 Authenticated Endpoints (JWT Required)

| Method | Path | Role | Body | Description |
|--------|------|------|------|-------------|
| `POST` | `/api/auth/change-password` | Any | `ChangePasswordRequest` | Change password (resets all refresh tokens) |
| `POST` | `/api/auth/logout` | Any | — | Revoke all refresh tokens |

### 6.3 Admin Endpoints (`ROLE_MANAGE` Required)

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/api/admin/users/create` | `CreateAccountRequest` | Create any user account (returns temp password) |

### 6.4 Department Endpoints (Any Authenticated User)

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `GET` | `/api/departments` | — | List all departments |
| `GET` | `/api/departments/{id}` | — | Get department by ID |
| `POST` | `/api/departments` | `DepartmentRequest` | Create department |
| `PUT` | `/api/departments/{id}` | `DepartmentRequest` | Update department |
| `DELETE` | `/api/departments/{id}` | — | Delete department |

### 6.5 User Endpoints (Any Authenticated User)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/users` | All users (basic info) |
| `GET` | `/api/users/{id}` | Single user (detailed, includes student profile) |
| `GET` | `/api/users/role/{role}` | Users by role (basic) |
| `GET` | `/api/users/role/{role}/details` | Users by role (detailed, includes student profile) |
| `GET` | `/api/users/department/{id}` | Users by department (basic) |
| `GET` | `/api/users/department/{id}/details` | Users by department (detailed, includes student profile) |

---

## 7. Request/Response Bodies

### SetupAdminRequest
```json
{
  "email": "admin@university.edu",
  "password": "securePass123",
  "fullName": "Super Admin"
}
```

### LoginRequest
```json
{
  "email": "admin@university.edu",
  "password": "securePass123"
}
```

### AuthResponse
```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userId": 1,
  "email": "admin@university.edu",
  "fullName": "Super Admin",
  "role": "MANAGE",
  "mustChangePassword": true
}
```

### CreateAccountRequest
```json
{
  "email": "student@university.edu",
  "password": "student123",
  "fullName": "John Doe",
  "role": "STUDENT",
  "departmentId": 1,
  "studentIdNumber": "STU001",
  "batchYear": "2023",
  "academicYear": "CS Third Year",
  "section": "A"
}
```

### CreatedAccountResponse
```json
{
  "id": 2,
  "email": "student@university.edu",
  "fullName": "John Doe",
  "role": "STUDENT",
  "departmentId": 1,
  "departmentName": "Computer Science",
  "tempPassword": "student123",
  "mustChangePassword": true,
  "createdAt": "2026-07-26T12:00:00"
}
```

### UserResponse (Basic)
```json
{
  "id": 2,
  "email": "student@university.edu",
  "fullName": "John Doe",
  "role": "STUDENT",
  "departmentId": 1,
  "departmentName": "Computer Science",
  "registrationStatus": "APPROVED",
  "isActive": true,
  "mustChangePassword": true,
  "lastLogin": "2026-07-26T12:00:00",
  "createdAt": "2026-07-26T10:00:00"
}
```

### UserResponse (Detailed — for Students)
```json
{
  "id": 2,
  "email": "student@university.edu",
  "fullName": "John Doe",
  "role": "STUDENT",
  "departmentId": 1,
  "departmentName": "Computer Science",
  "registrationStatus": "APPROVED",
  "isActive": true,
  "mustChangePassword": true,
  "lastLogin": "2026-07-26T12:00:00",
  "createdAt": "2026-07-26T10:00:00",
  "studentIdNumber": "STU001",
  "batchYear": 2023,
  "academicYear": "CS Third Year",
  "section": "A"
}
```

### ChangePasswordRequest
```json
{
  "currentPassword": "oldPassword123",
  "newPassword": "newSecurePass456"
}
```

### DepartmentRequest
```json
{
  "name": "Computer Science",
  "code": "CS"
}
```

### ApiResponse (Generic)
```json
{
  "success": true,
  "message": "Operation completed",
  "data": null
}
```

---

## 8. Bootstrap Sequence

For a fresh database, follow this order:

```
Step 1:  POST /api/setup/admin
         → Creates the first MANAGE (admin) account
         → Only works when the users table is empty

Step 2:  POST /api/auth/login (as admin)
         → Get JWT access token

Step 3:  POST /api/departments (as admin)
         → Create departments (CS, EE, ME, etc.)

Step 4:  POST /api/admin/users/create (as admin)
         → Create teachers, students, etc.
         → Response includes temp password for each user

Step 5:  Each user logs in and changes password
         → POST /api/auth/change-password
```

---

## 9. Configuration

### application.yml
```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://<neon-host>/neondb?sslmode=require
    username: neondb_owner
    password: <neon-password>
  jpa:
    hibernate:
      ddl-auto: update          # Auto-creates/updates tables on startup
    show-sql: true

jwt:
  secret: <256-bit-secret>       # Change in production!
  access-token-expiration-ms: 900000       # 15 minutes
  refresh-token-expiration-ms: 604800000   # 7 days
```

### CORS
- Allowed origins: All origins (`Access-Control-Allow-Origin: *` via origin patterns)
- Allowed methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
- Allowed headers: `*`
- Credentials: true
- Max age: 3600s

---

## 10. Error Handling

All errors return a consistent `ApiResponse` format:

```json
{
  "success": false,
  "message": "Error description",
  "data": null
}
```

Validation errors include field-level details:

```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "email": "Email must be valid",
    "password": "Password must be at least 8 characters"
  }
}
```

| HTTP Status | When |
|-------------|------|
| 400 | Validation error, bad request |
| 401 | Invalid credentials, expired/missing JWT |
| 403 | Account disabled; authenticated but insufficient role |
| 409 | Duplicate email |
| 423 | Account locked (too many failed attempts) |
| 500 | Internal server error |

---

## 11. RMI (Remote Method Invocation) Architecture

The Grading, and Attendance modules use Java RMI for internal backend communication. REST controllers remain the public interface; they delegate to Spring services, which delegate to RMI clients, which invoke remote server implementations.

### Architecture Flow

```
Next.js (REST + JWT)
  ↓
Spring Boot Controllers    ←── local JWT auth
  ↓
Spring Services            ←── delegates to RMI clients
  ↓
RMI Clients                ←── Naming.lookup(), cached stubs
  ↓
RMI Registry (port 1099)
  ↓
RMI Server Implementations ←── extend UnicastRemoteObject
  ↓
Spring Data Repositories
  ↓
PostgreSQL (Neon)
```

### Remote Interfaces

| Interface | Methods | Description |
|-----------|---------|-------------|
| `AcademicRemote` | `getGrades(studentId)`, `getGradesByYear(studentId, year)` | Grade retrieval |
| `AttendanceRemote` | `getAttendance(studentId)`, `calculateAttendance(studentId, subjectCode)`, `getStudentsBelow75()` | Attendance queries |

### Lifecycle

- **Startup**: `RmiConfig` creates an RMI registry on port 1099 and binds `AcademicService` and `AttendanceService` via `Naming.rebind()`.
- **Runtime**: RMI clients (`AcademicRmiClient`, etc.) look up stubs via `Naming.lookup()` on `@PostConstruct` and cache them. Spring Services delegate to these clients. `RemoteException` is caught and rethrown as `RuntimeException`.
- **Shutdown**: Registry is torn down gracefully via `@PreDestroy`.

### Configuration (application.yml)

```yaml
rmi:
  host: localhost
  port: 1099
```

### Endpoints that use RMI

| Method | Path | RMI Service |
|--------|------|-------------|
| `GET` | `/api/academic/grades/{studentId}` | AcademicRemote |
| `GET` | `/api/academic/grades/{studentId}/{academicYear}` | AcademicRemote |
| `GET` | `/api/attendance/{studentId}` | AttendanceRemote |
| `GET` | `/api/attendance/calculate/{studentId}/{subjectCode}` | AttendanceRemote |
| `GET` | `/api/attendance/below75` | AttendanceRemote |

### Constraints

- JWT authentication stays in REST only — RMI is purely internal backend communication.
- RMI is **not** exposed to browsers.
- Existing REST endpoints, DTOs, and response formats are preserved.

---

## 12. Running the Project

### Local Development
```bash
# Build
./mvnw clean compile

# Run
./mvnw spring-boot:run

# Access
# API: http://localhost:8080
# Health: http://localhost:8080/api/health
```

### Docker
```bash
# Build image
docker build -t unicconnect-api .

# Run container
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://<host>/<db>?sslmode=require \
  -e DB_USERNAME=<user> \
  -e DB_PASSWORD=<pass> \
  -e JWT_SECRET=<256-bit-secret> \
  unicconnect-api
```

### Deploy to Render
1. Push the repository to GitHub.
2. In Render Dashboard → **New +** → **Blueprint** (or **Web Service**).
3. Connect your repository. Render auto-detects the `Dockerfile`.
4. Set the following **Environment Variables**:
   - `DB_URL` — Your Neon PostgreSQL connection string
   - `DB_USERNAME` — Database username
   - `DB_PASSWORD` — Database password
   - `JWT_SECRET` — A 256-bit secret for JWT signing
5. Deploy. Render builds the Docker image and starts the container.

---

## 13. Future Modules (Planned — Convex DB Side)

The following features are designed in the schema spec but managed by Convex on the frontend:
- Real-time direct messaging (with permission requests)
- Group chats (lecture groups, department groups)
- Newsfeed posts (announcements, events, lost & found)
- Post interactions (likes, comments)
- Real-time notifications
- Content moderation / spam filtering
