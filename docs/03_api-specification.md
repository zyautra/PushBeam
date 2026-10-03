# API Specification

## 1. 문서 목적

이 문서는 PushBeam Server의 HTTP API와 FCM 메시지 형식을 정의한다. 요청·응답 타입은 `shared` 모듈에 Kotlin으로 구현한다.

---

## 2. 기본 규칙

- 주소: `https://<도메인>/api/v1/...`
- 형식: JSON, UTF-8, 필드 이름은 camelCase
- 시각: ISO 8601 UTC (`2026-10-02T12:00:03.120Z`)
- 중요도: 소문자 (`critical`, `high`, `normal`, `low`)
- 인증: `Authorization: Bearer <credential>`

| API          | credential                         |
| ------------ | ---------------------------------- |
| 발송         | Sender Key (`pbs_…`) 또는 Operator Token |
| Member       | Firebase ID Token                  |
| Admin        | Operator Token                     |
| Health       | 없음                               |

Sender는 자기 이름이 들어간 `User-Agent`를 보낸다 (예: `nas-monitor/1.0`). 서버 앞의 프록시(Cloudflare 등)가 라이브러리 기본값(`Python-urllib` 등)을 봇으로 보고 막을 수 있다.

모르는 응답 필드는 무시한다. 필드를 지우거나 의미를 바꾸면 `/api/v2`로 올린다.

### 2.1 오류

```json
{ "error": { "code": "CHANNEL_REQUIRED", "message": "Required channels cannot be unsubscribed." } }
```

| code                | HTTP | 의미                                   |
| ------------------- | ---- | -------------------------------------- |
| `INVALID_REQUEST`   | 400  | 형식이나 값이 잘못됨                   |
| `UNAUTHORIZED`      | 401  | 인증 실패                              |
| `NOT_ALLOWLISTED`   | 403  | 허용 목록에 없음                       |
| `MEMBER_REVOKED`    | 403  | 허용이 취소됨                          |
| `FORBIDDEN`         | 403  | 권한 없음 (예: Sender가 허용되지 않은 채널로 발송) |
| `NOT_FOUND`         | 404  | 대상이 없음                            |
| `ALREADY_EXISTS`    | 409  | 이미 있음                              |
| `CHANNEL_REQUIRED`  | 409  | 필수 채널은 해제할 수 없음             |
| `PAYLOAD_TOO_LARGE` | 413  | 알림 내용이 너무 큼                    |
| `UNKNOWN_RECIPIENT` | 422  | 허용 목록에 없는 이메일로 발송         |
| `SERVER_NOT_READY`  | 503  | 서버 시작·종료 중                      |

---

## 3. 발송 API

### 3.1 알림 보내기

```text
POST /api/v1/messages
```

```json
{
  "target": { "channel": "server-alerts" },
  "title": "디스크 경고",
  "body": "nas-01 /var 사용량 92%",
  "severity": "high",
  "data": { "host": "nas-01" }
}
```

`target`은 다음 중 하나다. `users`와 `all`은 Operator Token으로만 쓸 수 있다.

```json
{ "channel": "server-alerts" }
{ "users": ["alice@gmail.com"] }
{ "all": true }
```

| 필드       | 제한                                              |
| ---------- | ------------------------------------------------- |
| `title`    | 1~100자                                           |
| `body`     | 1~1000자                                          |
| `severity` | 생략하면 `normal`                                 |
| `data`     | 선택. 문자열 key-value, 최대 16개, 합쳐서 1KB 이하 |

FCM 메시지 전체가 4KB를 넘으면 `PAYLOAD_TOO_LARGE`다.

응답 `202 Accepted`:

```json
{
  "messageId": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
  "recipients": { "deliver": 3, "quiet": 1, "inboxOnly": 1, "skipped": 1 },
  "deliveries": 5
}
```

`inboxOnly`는 `MUTED`, `BELOW_MIN`(알림 없이 목록만), `skipped`는 `NOT_ACTIVE`, `NO_DEVICE`(보내지 않음)의 합이다.

### 3.2 보낸 알림 상태

```text
GET /api/v1/messages/{messageId}
```

Sender는 자기가 보낸 알림만 볼 수 있다.

```json
{
  "messageId": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
  "createdAt": "2026-10-02T12:00:03.120Z",
  "deliveries": { "pending": 0, "sent": 4, "failed": 0, "invalidToken": 1, "cancelled": 0 }
}
```

---

## 4. Member API

Firebase ID Token으로 인증한다. 서버는 토큰의 이메일이 허용 목록에 있는지 확인한다.

| Method | Path                                         | 설명                         |
| ------ | -------------------------------------------- | ---------------------------- |
| GET    | `/api/v1/me`                                 | 내 상태와 방해 금지 시간     |
| PUT    | `/api/v1/me/devices/{installationId}`        | 기기 등록·갱신               |
| DELETE | `/api/v1/me/devices/{installationId}`        | 기기 해제 (로그아웃)         |
| GET    | `/api/v1/me/channels`                        | 채널 목록과 내 구독 설정     |
| PUT    | `/api/v1/me/channels/{slug}`                 | 구독                         |
| DELETE | `/api/v1/me/channels/{slug}`                 | 구독 해제                    |
| PATCH  | `/api/v1/me/channels/{slug}`                 | 음소거, 최소 중요도 변경     |
| PUT    | `/api/v1/me/quiet-hours`                     | 방해 금지 시간 설정          |

### 4.1 내 상태

```json
{
  "email": "alice@gmail.com",
  "displayName": "Alice",
  "status": "ACTIVE",
  "quietHours": { "enabled": true, "start": "23:00", "end": "07:00" }
}
```

### 4.2 기기 등록

```json
{ "fcmToken": "eurzX6KX…", "model": "Pixel 9", "appVersion": 12, "timeZone": "Asia/Seoul" }
```

### 4.3 채널 목록

```json
{
  "channels": [
    {
      "slug": "server-alerts",
      "name": "서버 경고",
      "description": "홈서버와 NAS 장애",
      "required": true,
      "subscribed": true,
      "minSeverity": "low",
      "muted": false,
      "mutedUntil": null
    }
  ]
}
```

### 4.4 구독 설정 변경

바꿀 필드만 보낸다. 응답은 변경된 채널 항목 하나다.

```json
{ "minSeverity": "high" }
{ "muted": true, "mutedUntil": "2026-10-02T20:00:00.000Z" }
{ "muted": true, "mutedUntil": null }
{ "muted": false }
```

---

## 5. Admin API

Operator Token으로 인증한다. PushBeam Admin이 사용한다.

| Method | Path                                           | 설명                          |
| ------ | ---------------------------------------------- | ----------------------------- |
| GET    | `/api/v1/admin/status`                         | 서버 상태                     |
| GET    | `/api/v1/admin/members`                        | Member 목록                   |
| POST   | `/api/v1/admin/members`                        | 허용 `{email, displayName}`   |
| GET    | `/api/v1/admin/members/{email}`                | 상세 (기기, 구독)             |
| POST   | `/api/v1/admin/members/{email}/revoke`         | 허용 취소                     |
| GET    | `/api/v1/admin/channels`                       | 채널 목록                     |
| POST   | `/api/v1/admin/channels`                       | 채널 만들기                   |
| PATCH  | `/api/v1/admin/channels/{slug}`                | 이름, 설명, 필수, 자동 구독 변경 |
| POST   | `/api/v1/admin/channels/{slug}/archive`        | 보관                          |
| PUT    | `/api/v1/admin/channels/{slug}/members/{email}`| Member를 구독시킴             |
| GET    | `/api/v1/admin/senders`                        | Sender 목록                   |
| POST   | `/api/v1/admin/senders`                        | 발급 `{name, allowedChannels}` → Key는 이 응답에만 |
| PATCH  | `/api/v1/admin/senders/{id}`                   | 허용 채널 변경                |
| POST   | `/api/v1/admin/senders/{id}/revoke`            | 폐기                          |
| GET    | `/api/v1/admin/messages`                       | 보낸 알림 목록                |
| GET    | `/api/v1/admin/messages/{id}`                  | 알림 상세 (Member별 결과, 기기별 전송 결과) |
| POST   | `/api/v1/admin/distribution/sync`              | App Distribution 그룹 즉시 맞추기 |

목록은 `?limit=50&cursor=…` 방식으로 나눠 받는다.

---

## 6. Health

| Path            | 응답                                          |
| --------------- | --------------------------------------------- |
| `/health/live`  | 프로세스가 살아 있으면 `200`                  |
| `/health/ready` | 요청을 받을 수 있으면 `200`, 아니면 `503`     |

---

## 7. FCM 메시지

Server는 기기마다 data-only 메시지를 보낸다. `notification` 필드는 쓰지 않는다. 앱이 꺼져 있어도 `onMessageReceived`가 불려 Inbox에 저장할 수 있게 하기 위해서다.

```json
{
  "message": {
    "token": "<기기 FCM 토큰>",
    "android": { "priority": "HIGH" },
    "data": {
      "pb.v": "1",
      "pb.id": "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
      "pb.title": "디스크 경고",
      "pb.body": "nas-01 /var 사용량 92%",
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

- `pb.display`: `normal`(알림), `quiet`(방해 금지: 소리 없는 알림), `inbox`(음소거·최소 중요도 미만: 알림 없이 목록만). `inbox`이면 `pb.reason`에 `muted` 또는 `below_min`이 온다.
- `pb.quiet`는 `pb.display`가 생기기 전 형식이다. 이전 앱을 위해 계속 보낸다.
- `critical`, `high`는 `priority: HIGH`, 나머지와 `inbox`는 `NORMAL`이다.
- 대상은 항상 기기 토큰 하나다. Topic은 쓰지 않는다.
- `pb.v`가 모르는 값이면 앱은 제목과 본문만 보통 알림으로 띄운다.
