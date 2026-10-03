# 기여 안내

PushBeam에 관심을 가져 주셔서 고맙습니다.

## 시작하기

```bash
git clone https://github.com/zyautra/pushbeam.git
cd pushbeam
cp android/google-services.example.json android/google-services.json
./gradlew :shared:test :server:test :admin-cli:test :android:assembleDebug
```

- Android 앱을 빌드하지 않을 때는 `-Ppushbeam.serverOnly=true`로 Android SDK 없이 서버만 빌드할 수 있다.
- 실제 기기에서 확인하려면 자신의 Firebase 프로젝트와 `google-services.json`이 필요하다.

## 변경하기

1. 이슈로 무엇을 바꿀지 먼저 이야기한다. 작은 수정은 바로 PR을 보내도 된다.
2. 동작을 바꾸면 [docs/](docs/)의 해당 문서도 함께 고친다. 문서는 제품의 목표 상태만 적고, 아직 정하지 않은 기능은 [07 Backlog](docs/07_backlog.md)에 둔다.
3. 테스트를 추가하거나 고친다. 수신 규칙은 `DeliveryPlanner` 단위 테스트, API는 `ApiFlowTest` 통합 테스트에 둔다.
4. `CHANGELOG.md`의 `Unreleased`에 한 줄 남긴다.

## 커밋 메시지

[Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/) 형식을 쓴다.

```
feat: 채널별 최소 중요도 설정
fix: 재시작 후 대기 전송이 남는 문제
docs: 배포 문서에 Kubernetes overlay 예시 추가
```

## 비밀 값

Firebase 서비스 계정 키, 앱 서명 키, Operator Token, Sender Key, 개인 도메인은 커밋하지 않는다. 개인 설정은 git이 무시하는 파일(`deploy/compose/.env`, `deploy/kubernetes/overlays/*-local/`, `android/google-services.json`)에 둔다.
