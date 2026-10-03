# Security Policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please do not open a public issue. Report it through GitHub's [private vulnerability reporting](https://github.com/zyautra/PushBeam/security/advisories/new).

Including the following helps us respond quickly:

- The affected component (Server, Android, pushbeam-admin) and version
- Steps to reproduce
- The expected impact

## Notes

- `android/google-services.example.json` contains placeholder values. Firebase Android API keys ship inside the app and are not secret, but restricting your key to your Android app and the APIs it needs is recommended.
- For handling secrets in operation, see [docs/04_security-and-deployment.md](docs/04_security-and-deployment.md).
