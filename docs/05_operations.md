**English** | [한국어](ko/05_operations.md)

# Operations

## 1. Purpose

This document covers installing PushBeam, day-to-day operation, troubleshooting, releases and testing.

---

## 2. First installation

```text
1. Point the domain at the host and open ports 80 and 443.
2. In Firebase, create the pushbeam-server and pushbeam-ci service accounts and grant their roles.
3. Create the tester group pushbeam-members in App Distribution (the server also creates it if missing).
4. Create an Operator Token: pushbeam-admin token generate --output ~/.config/pushbeam/operator-token
5. Put the server account key and the Operator Token in the host's secrets/ directory.
6. Fill in deploy/compose/.env (domain, etc.) and run docker compose up -d
7. Check that the server is ready with pushbeam-admin status.
8. pushbeam-admin members allow <your email>
9. Release the app with the first v* tag, install it and sign in.
10. Create channels and Senders.
```

---

## 3. PushBeam Admin

The `admin-cli/` module. Install:

```bash
./gradlew :admin-cli:installDist
ln -s "$PWD/admin-cli/build/install/pushbeam-admin/bin/pushbeam-admin" ~/.local/bin/
```

Config file `~/.config/pushbeam/admin.properties` (mode `0600`):

```properties
url=https://pushbeam.example.com
token-file=~/.config/pushbeam/operator-token
```

The environment variables `PUSHBEAM_URL` and `PUSHBEAM_OPERATOR_TOKEN` take precedence over the file. Requests are refused unless the URL is `https://`.

| Command                                                              | Description                  |
| -------------------------------------------------------------------- | ---------------------------- |
| `status`                                                             | Server status                |
| `members list [--status S]` / `show <email>`                         | View Members                 |
| `members allow <email> [--name N]`                                   | Allow (re-allows revoked people) |
| `members revoke <email>`                                             | Revoke                       |
| `channels list`                                                      | List channels                |
| `channels create <slug> --name N [--required] [--auto]`              | Create a channel             |
| `channels set <slug> [--name] [--required yes/no] [--auto yes/no]`   | Change a channel             |
| `channels archive <slug> [--undo]`                                   | Archive, unarchive           |
| `channels subscribe <slug> <email>`                                  | Subscribe a Member           |
| `senders list` / `create <name> --channels a,b`                      | View, issue (prints the key) |
| `senders set <id> --channels a,b` / `revoke <id>`                    | Change channels, revoke      |
| `send (--channel C \| --users a,b \| --all) --title T --body B [--severity S] [--data k=v]` | Send directly |
| `messages list [--limit N] [--cursor C]` / `show <id>`               | Sent alerts and results      |
| `distribution sync`                                                  | Sync the App Distribution group |
| `token generate [--output FILE]`                                     | Create an Operator Token     |

Add `--json` to any command to print the server's JSON response as is.

---

## 4. Checking server health

`pushbeam-admin status` shows:

| Item                      | Healthy             | Otherwise                               |
| ------------------------- | ------------------- | --------------------------------------- |
| State                     | `READY`             | Check the logs                          |
| Oldest pending delivery   | None or < 1 minute  | FCM outage, check the service account key |
| Last group sync           | Within 6 hours      | Check service account roles, run `distribution sync` |
| Last backup               | Within 24 hours     | Check the disk                          |

Server logs are one JSON object per line. Alert titles, bodies and tokens are never logged. Docker logs rotate at 50 MB × 5 files.

---

## 5. "I didn't get the alert"

Look at that Member's result with `pushbeam-admin messages show <id>`.

| Result                      | Meaning                          | What to do                            |
| --------------------------- | -------------------------------- | ------------------------------------- |
| No record (channel alert)   | Not subscribed to the channel    | Ask them to subscribe                 |
| `MUTED`                     | Channel muted (list only)        | Member setting                        |
| `BELOW_MIN`                 | Below minimum severity (list only) | Member setting, or check the severity sent |
| `NOT_ACTIVE`                | Has not signed in                | Ask them to sign in                   |
| `NO_DEVICE`                 | No active device                 | Sign in to the app again              |
| `QUIET`                     | Sent silently (quiet hours)      | Check the app's list                  |
| Delivery `PENDING`          | Still sending                    | Check server status                   |
| Delivery `FAILED`           | FCM failure                      | Check `last_error`                    |
| Delivery `INVALID_TOKEN`    | Token invalid (app uninstalled, etc.) | Reinstall and sign in            |
| Delivery `SENT`             | Reached FCM                      | Check notification permission, channels, battery optimization on the device |

In the app, Settings → Diagnostics shows the app version, account, last device registration and notification permission.

---

## 6. Backup and restore

- The server backs up daily to `data/backup/pushbeam-YYYYMMDD.db` and keeps 14 copies.
- Copying backups off the host is up to the operator.
- Restore: stop the server → replace `data/pushbeam.db` with the backup → start. Afterwards, check the allowlist and run `distribution sync`.

---

## 7. Updates

### 7.1 Server

```text
Change the image tag → docker compose pull (or build) → docker compose up -d
```

On startup the server backs up the database and applies migrations. If a migration fails, it does not start.

The API is backward compatible within a version, so update the server first and release the app afterwards.

On Kubernetes with locally imported images, make sure the new image is present on the node before applying the manifest.

### 7.2 App

```text
git tag v<version> && git push origin v<version>
```

GitHub Actions builds, signs and distributes to the `pushbeam-members` group. Use 0.x versions during development.

### 7.3 Rotating keys

| Key                 | How                                                    |
| ------------------- | ------------------------------------------------------ |
| Sender Key          | Issue a new Sender → update the script → revoke the old Sender |
| Operator Token      | Create a new one → replace it in `secrets/` → restart the server → update the admin config |
| Server account key  | Issue a new key → replace it in `secrets/` → restart the server → delete the old key |
| App signing key     | Never rotated                                          |

---

## 8. Testing

### 8.1 Automated tests (every push)

- **Delivery rules**: results for each combination of required, severity, mute, minimum severity and quiet hours, including ranges across midnight and different time zones.
- **Server integration tests** with real SQLite and fake FCM/App Distribution:
  - allow → sign in → auto-subscribe
  - send to a channel → records and deliveries created
  - FCM failure → retry → success or `FAILED`
  - invalid token → device deactivated
  - revoke → pending deliveries cancelled, no longer a recipient
  - server restart → pending deliveries resume
  - permissions: Senders cannot send to other channels; Member tokens cannot send or use the Admin API
- **App tests**: the same alert received twice is shown once, per-severity channels, registration retries
- **API format**: `shared` types match the example JSON in document 03.

### 8.2 Device checks (before an app or server release)

```text
1. Allow a new email → invitation → install → sign in → required channels subscribed
2. Receive an alert with the app closed → stored in the list
3. A critical alert arrives immediately with the screen off
4. Mute, minimum severity and quiet hours behave as intended
5. Revoke → that device no longer receives alerts
6. Update from the previous version → still signed in, alerts keep arriving
```
