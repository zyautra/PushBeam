# Changelog

이 프로젝트의 주요 변경 사항을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를, 버전은 [Semantic Versioning](https://semver.org/lang/ko/)을 따른다.

## [Unreleased]

## [0.1.0] - 2026-10-03

첫 공개 버전.

### 추가

- **Server**: 허용 목록, 기기 등록, 채널·구독, 발송 API, 수신 규칙(필수 채널 `critical`, 음소거, 최소 중요도, 방해 금지 시간), FCM 전송과 재시도, App Distribution 테스터 그룹 동기화, 90일 기록 정리, 일일 백업
- **Android**: Google 로그인, 받은 알림 목록·상세·검색, 채널 구독·음소거·울릴 알림 설정, 방해 금지 시간, 진동·소리 설정, 중요도별 알림 채널
- **pushbeam-admin**: Member, 채널, Sender, 발송, 기록, 배포 그룹 관리
- **배포**: Docker 이미지, Docker Compose + Caddy, Kubernetes base
- **CI**: 테스트·빌드, `pushbeam-v*` 태그로 App Distribution 배포

[Unreleased]: https://github.com/zyautra/pushbeam/compare/pushbeam-v0.1.0...HEAD
[0.1.0]: https://github.com/zyautra/pushbeam/releases/tag/pushbeam-v0.1.0
