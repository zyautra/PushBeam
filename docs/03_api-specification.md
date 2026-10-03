**English** | [한국어](ko/03_api-specification.md)

# API Specification

## 1. Purpose

This document defines PushBeam Server's HTTP API and the FCM message format. Request and response types are implemented in Kotlin in the `shared` module.

---

## 2. Basics

- Base URL: `https://<domain>/api/v1/...`
- Format: JSON, UTF-8, camelCase field names
- Time: ISO 8601 UTC (`2026-10-02T12:00:03.120Z`)
- Severity: lower case (`critical`, `high`, `normal`, `low`)
- Authentication: `Authorization: Bearer <credential>`

| API          | Credential                               |
| ------------ | ---------------------------------------- |
| Sending      | Sender Key (`pbs_…`) or Operator Token   |
| Member       | Firebase ID Token                        |
| Admin        | Operator Token                           |
| Health       | None                                     |

Senders send a `User-Agent` that names them (e.g. `nas-monitor/1.0`). A proxy in front of the server (Cloudflare, etc.) may treat library defaults such as `Python-urllib` as bots and block them.

Clients ignore unknown response fields. Removing a field or changing its meaning requires `/api/v2`.

### 2.1 Errors

```json
{ "error": { "code": "CHANNEL_REQUIRED", "message": "Required channels cannot be unsubscribed." } }
```

| code                | HTTP | Meaning                                    |
| ------------------- | ---- | ------------------------------------------ |
| `INVALID_REQUEST`   | 400  | Malformed request or invalid value         |
| `UNAUTHORIZED`      | 401  | Authentication failed                      |
| `NOT_ALLOWLISTED`   | 403  | Not on the allowlist                       |
| `MEMBER_REVOKED`    | 403  | Access has been revoked                    |
| `FORBIDDEN`         | 403  | Not permitted (e.g. a Sender sending to a channel it is not allowed to) |
| `NOT_FOUND`         | 404  | Target not found                           |
| `ALREADY_EXISTS`    | 409  | Already exists                             |
| `CHANNEL_REQUIRED`  | 409  | Required channels cannot be left          |
| `PAYLOAD_TOO_LARGE` | 413  | Alert content is too large                 |
| `UNKNOWN_RECIPIENT` | 422  | Sending to an email not on the allowlist   |
| `SERVER_NOT_READY`  | 503  | Server is starting or stopping             |

---

## 3. Sending API

### 3.1 Send an alert

```text
POST /api/v1/messages
```

```json
{
  "target": { "channel": "server-alerts" },
  "title": "Disk warning",
  "body": "nas-01 /var at 92%",
  "severity": "high",
  "data": { "host": "nas-01" }
}
```

`target` is one of the following. `users` and `all` require an Operator Token.

```json
{ "channel": "server-alerts" }
{ "users": ["alice@gmail.com"] }
{ "all": true }
```

| Field      | Limit                                                  |
| ---------- | ------------------------------------------------------ |
| `title`    | 1–100 characters                                       |
| `body`     | 1–1000 characters                                      |
| `severity` | Defaults to `normal`                                   |
| `data`     | Optional. String key-values, up to 16, 1 KB in total   |

If the whole FCM message exceeds 4 KB, the request fails with `PAYLOAD_TOO_LARGE`.

Response `202 Accepted`:

```json
{
  "messageId": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
  "recipients": { "deliver": 3, "quiet": 1, "inboxOnly": 1, "skipped": 1 },
  "deliveries": 5
}
```

`inboxOnly` counts `MUTED` and `BELOW_MIN` (list only, no notification); `skipped` counts `NOT_ACTIVE` and `NO_DEVICE` (not sent).

### 3.2 Alert status

```text
GET /api/v1/messages/{messageId}
```

A Sender can only see alerts it sent.

```json
{
  "messageId": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
  "createdAt": "2026-10-02T12:00:03.120Z",
  "deliveries": { "pending": 0, "sent": 4, "failed": 0, "invalidToken": 1, "cancelled": 0 }
}
```

---

## 4. Member API

Authenticated with a Firebase ID Token. The server checks that the token's email is on the allowlist.

| Method | Path                                         | Description                       |
| ------ | -------------------------------------------- | --------------------------------- |
| GET    | `/api/v1/me`                                 | My status and quiet hours         |
| PUT    | `/api/v1/me/devices/{installationId}`        | Register or update a device       |
| DELETE | `/api/v1/me/devices/{installationId}`        | Unregister a device (sign-out)    |
| GET    | `/api/v1/me/channels`                        | Channels and my subscription settings |
| PUT    | `/api/v1/me/channels/{slug}`                 | Subscribe                         |
| DELETE | `/api/v1/me/channels/{slug}`                 | Leave                             |
| PATCH  | `/api/v1/me/channels/{slug}`                 | Change mute or minimum severity   |
| PUT    | `/api/v1/me/quiet-hours`                     | Set quiet hours                   |

### 4.1 My status

```json
{
  "email": "alice@gmail.com",
  "displayName": "Alice",
  "status": "ACTIVE",
  "quietHours": { "enabled": true, "start": "23:00", "end": "07:00" }
}
```

### 4.2 Register a device

```json
{ "fcmToken": "eurzX6KX…", "model": "Pixel 9", "appVersion": 12, "timeZone": "Asia/Seoul" }
```

### 4.3 Channels

```json
{
  "channels": [
    {
      "slug": "server-alerts",
      "name": "Server alerts",
      "description": "Home server and NAS failures",
      "required": true,
      "subscribed": true,
      "minSeverity": "low",
      "muted": false,
      "mutedUntil": null,
      "subscribers": 4
    }
  ]
}
```

### 4.4 Change subscription settings

Send only the fields to change. The response is the updated channel item.

```json
{ "minSeverity": "high" }
{ "muted": true, "mutedUntil": "2026-10-02T20:00:00.000Z" }
{ "muted": true, "mutedUntil": null }
{ "muted": false }
```

---

## 5. Admin API

Authenticated with the Operator Token. Used by PushBeam Admin.

| Method | Path                                           | Description                     |
| ------ | ---------------------------------------------- | ------------------------------- |
| GET    | `/api/v1/admin/status`                         | Server status                   |
| GET    | `/api/v1/admin/members`                        | List Members                    |
| POST   | `/api/v1/admin/members`                        | Allow `{email, displayName}`    |
| GET    | `/api/v1/admin/members/{email}`                | Details (devices, subscriptions) |
| POST   | `/api/v1/admin/members/{email}/revoke`         | Revoke                          |
| GET    | `/api/v1/admin/channels`                       | List channels                   |
| POST   | `/api/v1/admin/channels`                       | Create a channel                |
| PATCH  | `/api/v1/admin/channels/{slug}`                | Change name, description, required, auto-subscribe |
| POST   | `/api/v1/admin/channels/{slug}/archive`        | Archive                         |
| PUT    | `/api/v1/admin/channels/{slug}/members/{email}`| Subscribe a Member              |
| GET    | `/api/v1/admin/senders`                        | List Senders                    |
| POST   | `/api/v1/admin/senders`                        | Issue `{name, allowedChannels}` → key appears only in this response |
| PATCH  | `/api/v1/admin/senders/{id}`                   | Change allowed channels         |
| POST   | `/api/v1/admin/senders/{id}/revoke`            | Revoke                          |
| GET    | `/api/v1/admin/messages`                       | List sent alerts                |
| GET    | `/api/v1/admin/messages/{id}`                  | Alert details (per-Member results, per-device deliveries) |
| POST   | `/api/v1/admin/distribution/sync`              | Sync the App Distribution group now |

Lists are paged with `?limit=50&cursor=…`.

---

## 6. Health

| Path            | Response                                       |
| --------------- | ---------------------------------------------- |
| `/health/live`  | `200` while the process is alive               |
| `/health/ready` | `200` when accepting requests, otherwise `503` |

---

## 7. FCM message

The server sends one data-only message per device. The `notification` field is not used, so `onMessageReceived` runs even when the app is closed and the alert can be stored in the inbox.

```json
{
  "message": {
    "token": "<device FCM token>",
    "android": { "priority": "HIGH" },
    "data": {
      "pb.v": "1",
      "pb.id": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
      "pb.title": "Disk warning",
      "pb.body": "nas-01 /var at 92%",
      "pb.severity": "high",
      "pb.channel": "server-alerts",
      "pb.display": "normal",
      "pb.quiet": "false",
      "pb.sentAt": "2026-10-02T12:00:03.120Z",
      "pb.data": "{\"host\":\"nas-01\"}"
    }
  }
}
```

- `pb.display`: `normal` (notify), `quiet` (quiet hours: silent notification), `inbox` (muted or below minimum: list only, no notification). For `inbox`, `pb.reason` is `muted` or `below_min`.
- `pb.quiet` is the format used before `pb.display` existed. It is still sent for older apps.
- `critical` and `high` use `priority: HIGH`; everything else, including `inbox`, uses `NORMAL`.
- The target is always a single device token. Topics are never used.
- If `pb.v` is unknown, the app shows only the title and body as a normal notification.
