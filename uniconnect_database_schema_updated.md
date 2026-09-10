# UniConnect System - Complete Database & Architecture Specification

## 1. System Overview & Technology Stack

**UniConnect** is a comprehensive University Communication & Social Networking System designed to streamline academic records, departmental hierarchies, real-time messaging, administrative workflows, and campus social feeds.

### Database Strategy

| Technology | Purpose | Core Features & Scope |
| :--- | :--- | :--- |
| **Neon (Serverless PostgreSQL)** | Relational Core Data | Strict ACID compliance, relational queries via Spring Boot (JPA/Hibernate), transactional data for users, grades, attendance, finance, and department structures. |
| **Convex DB** | Real-time & Social Engine | Reactive web sockets for Next.js, live direct/group messaging, communication permission workflows, posts, comments, likes, lost & found feeds, and instant notifications. |

---

## 2. Neon PostgreSQL Schema (Core Relational Database)

The following DDL creates all required enums and relational tables in Neon PostgreSQL.

```sql
-- =========================================================
-- 1. ENUMS
-- =========================================================

CREATE TYPE user_role AS ENUM (
    'STUDENT', 
    'TEACHER', 
    'FINANCE_ACCOUNTANT', 
    'SYSTEM_ADMIN', 
    'STUDENT_AFFAIRS_ADMIN', 
    'RECTOR_PRO_RECTOR'
);

CREATE TYPE registration_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED');
CREATE TYPE financial_type AS ENUM ('SALARY', 'SCHOLARSHIP', 'STIPEND', 'TUITION_FEE');
CREATE TYPE financial_status AS ENUM ('PENDING', 'APPROVED', 'PAID', 'REJECTED');

-- =========================================================
-- 2. DEPARTMENTS & USER MANAGEMENT
-- =========================================================

-- Departments Table
CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    code VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Core Users Table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role user_role NOT NULL,
    department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL,
    registration_status registration_status DEFAULT 'PENDING',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Head of Department (HOD) Assignment
-- Teachers are assigned as Head of Department through this table
CREATE TABLE department_heads (
    id BIGSERIAL PRIMARY KEY,
    department_id BIGINT NOT NULL UNIQUE REFERENCES departments(id) ON DELETE CASCADE,
    teacher_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

-- Student Profile Details
CREATE TABLE student_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    student_id_number VARCHAR(50) UNIQUE NOT NULL,
    batch_year INT NOT NULL,
    academic_year VARCHAR(50) NOT NULL, -- e.g. "CS Fourth Year"
    section VARCHAR(10)
);

-- =========================================================
-- 3. ACADEMIC & ATTENDANCE MODULE
-- =========================================================

-- Academic Records & Student Grades
CREATE TABLE academic_records (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_code VARCHAR(50) NOT NULL,
    subject_name VARCHAR(100) NOT NULL,
    academic_year VARCHAR(50) NOT NULL,
    grade_letter VARCHAR(10),
    marks NUMERIC(5, 2),
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Roll Call / Attendance Summary
CREATE TABLE attendance_summary (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_code VARCHAR(50) NOT NULL,
    total_classes INT DEFAULT 0,
    attended_classes INT DEFAULT 0,
    percentage NUMERIC(5,2) GENERATED ALWAYS AS (
        CASE WHEN total_classes > 0 THEN (attended_classes::numeric / total_classes::numeric) * 100 ELSE 0 END
    ) STORED,
    is_below_75 BOOLEAN GENERATED ALWAYS AS (
        CASE WHEN total_classes > 0 AND ((attended_classes::numeric / total_classes::numeric) * 100) < 75 THEN TRUE ELSE FALSE END
    ) STORED,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(student_id, subject_code)
);

-- =========================================================
-- 4. FINANCIAL & ADMINISTRATIVE MODULE
-- =========================================================

-- Financial Transactions (Salaries, Fees, Scholarships)
CREATE TABLE financial_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type financial_type NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status financial_status DEFAULT 'PENDING',
    description TEXT,
    processed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Department Meetings & Summaries (Managed by HOD)
CREATE TABLE department_meetings (
    id BIGSERIAL PRIMARY KEY,
    department_id BIGINT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    summary_notes TEXT,
    created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Project Supervision Assignments (Assigned by HOD)
CREATE TABLE project_supervisions (
    id BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_title VARCHAR(255) NOT NULL,
    department_id BIGINT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 3. Convex Schema (`convex/schema.ts`)

Convex manages real-time chat, direct messaging permission approvals, automated groups, social newsfeed posts, moderation/filtering, and push notifications.

```typescript
import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

export default defineSchema({
  // 1. Direct Messaging Permission Requests
  communicationPermissions: defineTable({
    requesterId: v.string(), // Neon user_id
    recipientId: v.string(), // Neon user_id
    status: v.string(),      // "PENDING", "APPROVED", "REJECTED"
    requestedAt: v.number(),
  })
    .index("by_requester", ["requesterId"])
    .index("by_recipient", ["recipientId"]),

  // 2. Chat Conversations & Group Channels
  conversations: defineTable({
    participants: v.array(v.string()), // Array of Neon user_ids
    type: v.string(),                  // "DIRECT", "LECTURE_GROUP", "DEPARTMENT_GROUP"
    title: v.optional(v.string()),       // Group name (e.g. "CS Teachers Group")
    departmentId: v.optional(v.string()),
    createdBy: v.string(),             // Neon user_id
    createdAt: v.number(),
  }).index("by_department", ["departmentId"]),

  // 3. Real-Time Chat Messages
  messages: defineTable({
    conversationId: v.id("conversations"),
    senderId: v.string(),               // Neon user_id
    content: v.string(),
    attachments: v.optional(v.array(v.string())),
    sentAt: v.number(),
  }).index("by_conversation", ["conversationId"]),

  // 4. Newsfeed, Lost & Found, Campus Events & Notices
  posts: defineTable({
    authorId: v.string(),               // Neon user_id
    authorRole: v.string(),             // e.g. "STUDENT", "TEACHER", "RECTOR"
    category: v.string(),               // "NEWSFEED", "LOST_AND_FOUND", "ANNOUNCEMENT", "EVENT"
    priority: v.string(),               // "NORMAL", "HIGH_PRIORITY"
    title: v.string(),
    content: v.string(),
    mediaUrls: v.optional(v.array(v.string())),
    isApproved: v.boolean(),            // Status after content filtering
    flaggedByFilter: v.boolean(),       // Flag for non-academic/spam filtering
    targetDepartmentId: v.optional(v.string()),
    createdAt: v.number(),
  })
    .index("by_category", ["category"])
    .index("by_approval", ["isApproved"])
    .index("by_priority", ["priority"]),

  // 5. Post Interactions (Likes & Comments)
  postInteractions: defineTable({
    postId: v.id("posts"),
    userId: v.string(),                 // Neon user_id
    type: v.string(),                   // "LIKE", "COMMENT"
    commentText: v.optional(v.string()),
    createdAt: v.number(),
  }).index("by_post", ["postId"]),

  // 6. Real-Time Notification Center
  notifications: defineTable({
    recipientId: v.string(),            // Neon user_id
    type: v.string(),                   // "PERMISSION_REQUEST", "ATTENDANCE_WARNING", "ANNOUNCEMENT"
    title: v.string(),
    message: v.string(),
    isRead: v.boolean(),
    createdAt: v.number(),
  }).index("by_recipient", ["recipientId", "isRead"]),
});
```

---

## 4. Operational & Integration Workflow

1. **Authentication & Authorization**:
   - Spring Boot issues JWT tokens upon user login against the `users` table in Neon.
   - Next.js uses the JWT token to authenticate requests to Spring Boot APIs and passed to Convex client context for live subscriptions.

2. **Head of Department (HOD) Authorization**:
   - To check if a teacher has HOD privileges, Spring Boot queries the `department_heads` table where `is_active = true`.
   - HODs gain access to departmental meeting scheduling, project supervisor assignments, and broadcast notices.

3. **Real-time Messaging & Permissions**:
   - Students initiating direct contact with Teachers/Rectors create a record in Convex `communicationPermissions`.
   - Upon acceptance (`status = APPROVED`), Convex creates a conversation record in `conversations`.

4. **Roll Call & Attendance Alerts**:
   - Spring Boot updates `attendance_summary`.
   - When `is_below_75` evaluates to `true`, a trigger or Spring service automatically pushes an alert notification to Convex `notifications`.



### Authentication Enhancements

#### Updated `users` table additions

```sql
ALTER TABLE users
ADD COLUMN must_change_password BOOLEAN DEFAULT TRUE,
ADD COLUMN last_login TIMESTAMP,
ADD COLUMN failed_login_attempts INT DEFAULT 0,
ADD COLUMN account_locked_until TIMESTAMP,
ADD COLUMN password_changed_at TIMESTAMP,
ADD COLUMN last_password_reset TIMESTAMP;
```

#### Refresh Tokens

```sql
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    device_name VARCHAR(255),
    ip_address VARCHAR(100)
);
```

### Authentication Flow

- Accounts are created only by administrators.
- Users authenticate with **email + password**.
- Passwords are hashed using BCrypt (or Argon2).
- Spring Boot + Spring Security issue JWT Access Tokens and Refresh Tokens.
- Refresh Token is stored as an HttpOnly cookie.
- Users with `must_change_password = TRUE` are redirected to change their password after first login.
- Convex trusts the authenticated Neon user ID from the JWT and does not perform authentication itself.
