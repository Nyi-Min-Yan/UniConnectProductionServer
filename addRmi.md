# Add Java RMI to UniConnect Spring Boot

## Goal

Integrate Java RMI into the existing Spring Boot backend **without
removing the REST API**.

REST remains the public interface. Selected business logic is delegated
to remote Java services through RMI.

## Requirements

-   Keep all existing REST endpoints working.
-   Do not change JWT authentication.
-   Do not change database schema.
-   Use Java 17.
-   Use standard Java RMI (`java.rmi`).

## Architecture

``` text
Next.js
   |
REST + JWT
   |
Spring Boot Controllers
   |
Spring Services
   |
RMI Client
   |
RMI Registry
   |
RMI Server
   |
Repositories
   |
PostgreSQL
```

## Create Packages

    src/main/java/com/uniconnect/rmi
    ├── client
    ├── server
    ├── remote
    ├── dto
    └── config

## Remote Interfaces

Create:

-   AcademicRemote
-   AttendanceRemote
-   FinanceRemote

Each interface must:

-   extend `java.rmi.Remote`
-   throw `RemoteException`

Example methods:

``` java
List<AcademicRecordDto> getGrades(Long studentId);

AttendanceSummaryDto calculateAttendance(Long studentId);

FinancialResultDto processSalary(Long employeeId);
```

## Server Implementations

Implement each interface.

Extend `UnicastRemoteObject`.

Move business logic from services into these implementations where
appropriate.

Use Spring repositories.

## RMI Registry

Create configuration that starts an RMI registry on port 1099.

Register:

-   AcademicService
-   AttendanceService
-   FinanceService

## RMI Client

Create Spring-managed client classes that lookup remote objects using:

``` java
Naming.lookup("rmi://localhost:1099/AcademicService");
```

Cache the stub after startup.

## Service Changes

Replace direct business logic calls with RMI calls for:

-   grade retrieval
-   attendance calculation
-   salary processing

Authentication, JWT, login, logout and password management must remain
local.

## Controllers

Controllers should continue exposing the exact same REST endpoints.

No API contract changes.

Controllers call Services.

Services call RMI Clients.

RMI Clients call Remote Servers.

## Error Handling

Convert RemoteException into appropriate Spring exceptions.

Return existing ApiResponse format.

## Security

Keep JWT only in REST.

Do not expose RMI directly to browsers.

RMI is internal backend communication.

## Deliverables

-   Working REST API
-   Working RMI registry
-   Three remote interfaces
-   Three remote implementations
-   Three RMI clients
-   Spring configuration
-   Documentation explaining architecture

## Constraints

-   Do not break existing endpoints.
-   Preserve DTOs and response format.
-   Follow clean architecture.
-   Produce production-quality code with comments.
