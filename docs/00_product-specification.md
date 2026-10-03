**English** | [한국어](ko/00_product-specification.md)

# PushBeam Product Specification

> This document defines what PushBeam does. Implementation and operational details live in the documents below and are not repeated here.
>
> All documents describe the target state of the product only. Version scope and change history are tracked with tags and commits; features not yet committed to go in [07 Backlog](./07_backlog.md).

| Topic                              | Document                                                       |
| ---------------------------------- | -------------------------------------------------------------- |
| Components, tech stack, flows      | [01 Architecture](./01_architecture.md)                        |
| Data and database                  | [02 Data Model](./02_data-model.md)                            |
| HTTP API, FCM messages             | [03 API Specification](./03_api-specification.md)              |
| Security and deployment            | [04 Security and Deployment](./04_security-and-deployment.md)  |
| Operations, testing, releases      | [05 Operations](./05_operations.md)                            |
| App screens                        | [06 Client UX](./06_client-ux.md)                              |
| Ideas for later                    | [07 Backlog](./07_backlog.md)                                  |

## 1. Overview

### 1.1 Name

**PushBeam**

The server is **PushBeam Server**, the Android app is **PushBeam for Android**, and the admin tool is **PushBeam Admin**.

### 1.2 One-line definition

> **PushBeam is a self-hosted push notification service that sends server alerts to people the operator has allowed, and lets each of them receive only the alerts they want.**

### 1.3 Goals

- Scripts, cron jobs and monitoring tools send an alert with a single HTTP request.
- Only people the operator allows can get the app and receive alerts.
- Each recipient chooses which alerts they receive.
- Critical alerts the operator marks as required always arrive.
- The server keeps a record of what was sent to whom.

---

## 2. Problem

Writing a single FCM token into a server config and sending to it works fine for one person.

Sharing alerts with several people causes these problems:

- Tokens must be managed by hand every time a person or device is added.
- FCM topics cannot restrict who subscribes.
- An unauthenticated send API can be called by anyone.
- App distribution and alert recipients must be managed separately.
- Everyone receives every alert, so people end up turning notifications off.

PushBeam **manages app distribution and alert delivery with a single allowlist, and the server decides who receives each alert.**

---

## 3. Principles

### 3.1 Allowed people only

Only people on the operator's allowlist get the app, sign in, and receive alerts. There is no public sign-up.

### 3.2 The server decides recipients

Only the server decides which devices receive an alert. Device-side subscriptions such as FCM topics are not used.

### 3.3 Recipients choose

Recipients subscribe to or leave channels, mute them, and set a minimum severity that rings. The server does not send alerts from channels a person has not subscribed to. Alerts that are muted or below the minimum severity do not ring but stay in the app's list.

### 3.4 Critical alerts always arrive

`critical` alerts in required channels are sent with sound regardless of mute, minimum severity and quiet hours.

### 3.5 Everything is recorded

FCM delivery is not guaranteed. Instead, the server records who an alert was sent to, and who it was not sent to and why.

---

## 4. Components

| Component             | Role                                                     |
| --------------------- | -------------------------------------------------------- |
| PushBeam Server       | Manages the allowlist, devices, channels and subscriptions. Accepts alerts, decides recipients, sends via FCM, keeps records |
| PushBeam for Android  | Google sign-in, device registration, notifications, inbox, receiving settings |
| PushBeam Admin        | Command-line tool for the operator. Allows users, manages channels and senders, views records |

External services:

| Service                   | Purpose                        |
| ------------------------- | ------------------------------ |
| Firebase Cloud Messaging  | Deliver alerts to devices      |
| Firebase Authentication   | Google account sign-in         |
| Firebase App Distribution | Distribute the app to allowed people |

There is no web admin UI.

---

## 5. Roles

| Role     | Who                              | Can do                                          |
| -------- | -------------------------------- | ----------------------------------------------- |
| Operator | The person running PushBeam      | Allow/revoke users, manage channels, issue senders, view records, send directly |
| Member   | A person the operator allowed    | Install the app, sign in, receive alerts, change receiving settings |
| Sender   | Scripts, cron jobs, other services | Send alerts to allowed channels               |

To receive alerts, the operator also allows themselves as a Member and signs in to the app.

---

## 6. Key scenarios

### 6.1 From invitation to first alert

The operator allows a friend's Google email. The friend receives an App Distribution invitation, installs the app and signs in. Signing in registers the device and subscribes them to required and auto-subscribe channels. From then on they receive alerts from those channels.

### 6.2 Sending from a script

The operator creates a Sender for a NAS monitoring script that may only send to the `server-alerts` channel. When the disk fills up, the script sends a `high` alert to `server-alerts`. The script does not know who receives it.

### 6.3 Receiving only what you want

A Member browses channels in the app, subscribes to `deploy` and leaves `general`. They set `deploy` to ring only for `high` and above. The required channel `server-alerts` cannot be left, but it can be muted for a while.

### 6.4 Quiet at night

A Member sets quiet hours to 23:00–07:00. Alerts during that time arrive silently and stay in the list. `critical` alerts in required channels still make a sound.

### 6.5 Revoking access

The operator revokes a person. Their devices are immediately removed from delivery, and they no longer receive new app versions.

---

## 7. Features

### 7.1 Allowing users

Users are allowed by Google account email.

| State     | Meaning                         |
| --------- | ------------------------------- |
| `INVITED` | Allowed, has not signed in yet  |
| `ACTIVE`  | Signed in, receives alerts      |
| `REVOKED` | Access revoked                  |

Allowing a revoked person again returns them to `INVITED`, starting over.

### 7.2 App distribution

The allowlist and the App Distribution tester group are always kept in sync. Allowing adds a person to the group; revoking removes them. New app versions are distributed to the group.

### 7.3 Channels and subscriptions

A channel is an alert topic, e.g. `server-alerts`, `deploy`, `backup`.

Only the operator creates channels. A channel has these properties:

| Property       | Meaning                                                   |
| -------------- | --------------------------------------------------------- |
| Required       | Every Member is subscribed and cannot leave               |
| Auto-subscribe | New Members are subscribed initially and may leave later  |

For each channel, a Member sets:

- **Subscribe / leave**: required channels cannot be left.
- **Mute**: does not ring until a given time, or until turned off. Alerts still stay in the app's list.
- **Minimum severity**: alerts below this severity do not ring. They still stay in the app's list.

### 7.4 Alerts

An alert has a title, body, severity, target, and optional extra data.

| Severity   | Meaning                       | Presentation              |
| ---------- | ----------------------------- | ------------------------- |
| `critical` | Needs immediate action        | Sound and vibration       |
| `high`     | Should be checked soon        | Sound and vibration       |
| `normal`   | Regular alert                 | Sound                     |
| `low`      | For the record                | Silent                    |

| Target   | Recipients                 | Who can send              |
| -------- | -------------------------- | ------------------------- |
| Channel  | Subscribers of the channel | Operator, allowed Senders |
| Users    | The given Members          | Operator                  |
| All      | Every `ACTIVE` Member      | Operator                  |

### 7.5 Delivery rules

Whether a channel alert goes to a Member is decided in this order:

1. If the Member is not subscribed to the channel, do not send.
2. If it is a `critical` alert in a required channel, send with sound. Skip the rules below.
3. If muted, send to the app's list only, without a notification.
4. If below the minimum severity, send to the app's list only, without a notification.
5. If within quiet hours and not `critical`, send silently.
6. Otherwise, send.

User and all targets are sent regardless of subscriptions; only rule 5 applies.

### 7.6 Quiet hours

A Member sets a daily start and end time. The range may cross midnight (e.g. 23:00–07:00). It uses the Member's time zone and applies to all of their devices.

### 7.7 Records

The server keeps the following for 90 days:

- Sent alerts: sender, target, content, time
- Per-Member result: sent, sent silently, list only (muted), list only (below minimum), etc.
- Per-device delivery: pending, sent, failed, invalid token, cancelled

The app stores received alerts on the device and provides read state and filtering.

---

## 8. Security

- The server is exposed to the internet over HTTPS. No VPN is required.
- Every API is authenticated, except the health check.

| Role     | Authentication                             |
| -------- | ------------------------------------------ |
| Operator | Operator Token                             |
| Member   | Google sign-in (Firebase ID Token) + allowlist check |
| Sender   | Sender Key                                 |

- A Sender Key is shown only once when issued; the server stores only its hash.
- Firebase service account keys, app signing keys, Sender Keys and Operator Tokens never go into the repository or logs.
- Alert content passes through FCM (Google). Do not put secrets such as passwords in alerts.

---

## 9. Target users

> One operator sharing alerts from personal servers with up to a few dozen people they allow directly — family, friends, or a small team.

---

## 10. Non-goals

- Public sign-up, Play Store distribution
- iOS, web push, email, SMS or other delivery channels
- Replying to alerts
- Guaranteed delivery
- Server redundancy, bulk sending
- Monitoring itself (thresholds are decided by Senders)
- Deployments that require a VPN

---

## 11. Invariants

1. Alerts never go to anyone who is not `ACTIVE` on the allowlist.
2. Only the server decides recipients. FCM topics are not used.
3. Only authenticated Operators and Senders can send, and Senders only to allowed channels.
4. Alerts from channels a Member has not subscribed to are not sent, and muted or below-minimum alerts do not ring. `critical` alerts in required channels are the only exception.
5. Alerts the server accepted survive a server restart, with their records, and delivery continues.
6. Secrets never appear in the repository, logs or alert content.
