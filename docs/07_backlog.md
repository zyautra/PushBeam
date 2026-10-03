**English** | [한국어](ko/07_backlog.md)

# Backlog

> Features not yet committed to. Once committed, they move into the relevant document and are removed from here.

## Sending

- **Idempotency key**: an `idempotencyKey` so a Sender's retried request is delivered once.
- **Sender rate limits**: a per-Sender limit on alerts per minute.
- **Sender user/all targets**: currently only the Operator can use them.

## Channels and receiving settings

- **Private channels**: channels only invited Members can see and subscribe to.
- **Quiet hours per weekday**: different quiet hours for each day of the week.
- **Critical alerts through Do Not Disturb**: request Android's DND access so `critical` rings even in the device's Do Not Disturb mode.

## Presentation

- **Grouping**: collapse repeated alerts from the same server.
- **Open link**: open a given URL when an alert is tapped.

## Operations

- **In-app admin screens**: allow users and manage channels and Senders from the app.
- **Cross-device history**: see alerts received on other devices.
- **Account pinning**: bind a Member to the Firebase UID of their first sign-in, in case an email address changes hands.
