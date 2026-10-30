# API v1

Base URL: `http://localhost:8080/api/v1` for local development. Every endpoint except login and `/health` requires `Authorization: Bearer <token>`. Unauthorized requests return 401; inaccessible or nonexistent owned resources return 404. Malformed/invalid requests return `{ "message": "..." }` with 400. Server internals are never returned in error responses.

| Method | Path | Result |
|---|---|---|
| GET | `/health` | Database-backed health status, outside `/api/v1` |
| POST | `/auth/login` | `{email,password}` → session, technician, token and expiry |
| GET | `/auth/me` | Current technician |
| POST | `/auth/logout` | Revokes the current token; 204 |
| GET | `/work-orders` | Assigned work, maximum 1,000 |
| GET | `/work-orders/{id}` | Assigned work detail |
| GET | `/assets` | Assets referenced by assigned work |
| GET | `/assets/{id}` | Authorized asset detail |
| GET | `/inspection-templates` | At most 100 data-driven templates |
| GET | `/inspections` | Owned inspections, maximum 1,000 |
| GET | `/inspections/{id}` | Owned inspection |
| GET | `/audit-events` | Latest 250 owned audit events |
| GET | `/sync/changes` | Bounded snapshot with server timestamp |
| POST | `/sync/operations` | Idempotent inspection mutation; 200 or structured 409 |
| POST | `/attachments` | Idempotent JPEG upload, maximum 5 MB decoded |
| GET | `/attachments/{id}` | Authorized JPEG bytes |

## Operation contract

The Kotlin serialization definitions in `shared/.../core/Models.kt` are the authoritative contract. Operations have `id`, `inspection`, `expectedVersion`, and `createdAt`. Inspection carries IDs for work order, asset, technician and template; answer/defect/photo arrays; optional coordinates; status; timestamps and version. Question IDs are stable within their template.

Replies have `status` (`SYNCED` or `CONFLICT`), `version`, `message`, and optional `server` inspection. Conflict replies use HTTP 409. Changing a payload under an existing operation ID is a 400 error. A retry must preserve the original ID **and the exact logical payload**. A resolved/new logical submission uses a new ID.

Attachment request: `{id,inspectionId,workOrderId,base64}`. Only JPEG is accepted, and ownership of the assigned work order is checked. Replays must match the content and references. Inspection submission verifies that every referenced photo already exists for that technician/inspection/work order.

Login requests are bounded to 4 KB, operations to 1 MB, attachments to 7.1 MB encoded (5 MB decoded). Inspection lists are bounded to 100 answers, 100 defects and 30 photos. Login is rate limited per remote address to 20 requests/minute in the current process; use an infrastructure-level limiter for multiple replicas.

## Example

```sh
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alex@fieldflow.example","password":"FieldFlow-Demo-2026!"}'
curl http://localhost:8080/api/v1/work-orders -H "Authorization: Bearer $TOKEN"
```

The password above is only the fictional development value from `.env.example`. The server does not embed it; account creation requires `SEED_DEMO=true` and an explicit `DEMO_PASSWORD`.
