# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-10-03

First public release.

### Added

- **Server**: allowlist, device registration, channels and subscriptions, sending API, delivery rules (required-channel `critical`, mute, minimum severity, quiet hours), FCM delivery with retries, App Distribution tester group sync, 90-day record cleanup, daily backups
- **Android**: Google sign-in, inbox with detail and search, channel subscription/mute/minimum severity, quiet hours, vibration and sound settings, per-severity notification channels
- **pushbeam-admin**: manage Members, channels, Senders, sending, records and the distribution group
- **Deployment**: Docker image, Docker Compose + Caddy, Kubernetes base
- **CI**: tests and builds, App Distribution releases on `v*` tags

[Unreleased]: https://github.com/zyautra/PushBeam/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zyautra/PushBeam/releases/tag/v0.1.0
