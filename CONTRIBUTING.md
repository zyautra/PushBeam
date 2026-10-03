# Contributing

Thanks for your interest in PushBeam.

## Getting started

```bash
git clone https://github.com/zyautra/PushBeam.git
cd PushBeam
cp android/google-services.example.json android/google-services.json
./gradlew :shared:test :server:test :admin-cli:test :android:assembleDebug
```

- To build only the server without the Android SDK, pass `-Ppushbeam.serverOnly=true`.
- Testing on a real device requires your own Firebase project and `google-services.json`.

## Making changes

1. Open an issue first to discuss what you want to change. Small fixes can go straight to a pull request.
2. If behavior changes, update the matching document in [docs/](docs/) (and [docs/ko/](docs/ko/) if you can). Documents describe only the target state; ideas not yet committed to go in [07 Backlog](docs/07_backlog.md).
3. Add or update tests. Delivery rules belong in the `DeliveryPlanner` unit tests; API behavior in the `ApiFlowTest` integration tests.
4. Add a line under `Unreleased` in `CHANGELOG.md`.

All pull requests to `main` need an approving review from a code owner and a passing CI.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
feat: per-channel minimum severity
fix: pending deliveries left after restart
docs: add a Kubernetes overlay example
```

## Secrets

Never commit Firebase service account keys, app signing keys, Operator Tokens, Sender Keys or personal domains. Keep personal settings in git-ignored files (`deploy/compose/.env`, `deploy/kubernetes/overlays/*-local/`, `android/google-services.json`).
