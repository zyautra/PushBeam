# 보안 정책

## 지원 버전

가장 최근 릴리즈만 보안 수정을 받는다.

## 취약점 알리기

보안 문제는 공개 이슈로 올리지 말고, GitHub의 [Private vulnerability reporting](https://github.com/zyautra/pushbeam/security/advisories/new)으로 알려 준다.

다음을 함께 적어 주면 빨리 확인할 수 있다.

- 영향 받는 구성요소(Server, Android, pushbeam-admin)와 버전
- 재현 방법
- 예상되는 영향

## 참고

- `android/google-services.example.json`은 자리표시 값이다. Firebase Android API 키는 앱에 들어가는 공개 값이지만, 사용하는 키는 Android 앱과 필요한 API로 제한하는 것을 권한다.
- 운영 시 비밀 값 관리는 [docs/04_security-and-deployment.md](docs/04_security-and-deployment.md)를 따른다.
