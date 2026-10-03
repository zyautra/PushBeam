[English](../05_operations.md) | **한국어**

# Operations

## 1. 문서 목적

이 문서는 PushBeam의 설치, 일상 운영, 문제 확인, 릴리즈, 테스트를 정의한다.

---

## 2. 처음 설치

```text
1. 도메인을 Host로 연결하고 80, 443을 연다.
2. Firebase에서 pushbeam-server, pushbeam-ci 서비스 계정을 만들고 역할을 준다.
3. App Distribution에 테스터 그룹 pushbeam-members를 만든다.
4. pushbeam-admin token generate --output ~/.config/pushbeam/operator-token으로 Operator Token을 만든다.
5. Host의 secrets/에 서버 계정 키와 Operator Token을 둔다.
6. deploy/compose/.env에 도메인 등을 쓰고 docker compose up -d
7. pushbeam-admin status로 서버가 준비됐는지 확인한다.
8. pushbeam-admin members allow <내 이메일>
9. 첫 v* 태그로 앱을 배포하고, 설치해서 로그인한다.
10. 채널과 Sender를 만든다.
```

---

## 3. PushBeam Admin

`admin-cli/` 모듈이다. 설치:

```bash
./gradlew :admin-cli:installDist
ln -s "$PWD/admin-cli/build/install/pushbeam-admin/bin/pushbeam-admin" ~/.local/bin/
```

설정 파일 `~/.config/pushbeam/admin.properties` (권한 `0600`):

```properties
url=https://pushbeam.example.com
token-file=~/.config/pushbeam/operator-token
```

환경 변수 `PUSHBEAM_URL`, `PUSHBEAM_OPERATOR_TOKEN`이 설정 파일보다 우선한다. 주소가 `https://`가 아니면 요청하지 않는다.

| 명령                                                                 | 설명                         |
| -------------------------------------------------------------------- | ---------------------------- |
| `status`                                                             | 서버 상태                    |
| `members list [--status S]` / `show <email>`                         | Member 조회                  |
| `members allow <email> [--name N]`                                   | 허용 (취소된 사람은 재허용)  |
| `members revoke <email>`                                             | 허용 취소                    |
| `channels list`                                                      | 채널 목록                    |
| `channels create <slug> --name N [--required] [--auto]`              | 채널 만들기                  |
| `channels set <slug> [--name] [--required yes/no] [--auto yes/no]`   | 채널 변경                    |
| `channels archive <slug> [--undo]`                                   | 보관, 보관 해제              |
| `channels subscribe <slug> <email>`                                  | Member 구독시키기            |
| `senders list` / `create <name> --channels a,b`                      | Sender 조회, 발급 (Key 출력) |
| `senders set <id> --channels a,b` / `revoke <id>`                    | 채널 변경, 폐기              |
| `send (--channel C \| --users a,b \| --all) --title T --body B [--severity S] [--data k=v]` | 직접 발송 |
| `messages list [--limit N] [--cursor C]` / `show <id>`               | 보낸 알림과 결과 조회        |
| `distribution sync`                                                  | App Distribution 그룹 맞추기 |
| `token generate [--output FILE]`                                     | Operator Token 만들기        |

모든 명령에 `--json`을 붙이면 서버 응답 JSON을 그대로 출력한다.

---

## 4. 서버 상태 확인

`pushbeam-admin status`는 다음을 보여 준다.

| 항목                  | 정상             | 이상하면                                |
| --------------------- | ---------------- | --------------------------------------- |
| 상태                  | `READY`          | 로그 확인                               |
| 가장 오래된 대기 전송 | 없음 또는 1분 이내 | FCM 장애, 서비스 계정 키 확인          |
| 그룹 동기화 마지막 성공 | 6시간 이내     | 서비스 계정 역할 확인, `distribution sync` |
| 마지막 백업           | 24시간 이내      | 디스크 확인                             |

서버 로그는 한 줄에 JSON 하나다. 알림 제목·본문과 토큰은 남기지 않는다. Docker log는 50MB × 5개로 회전한다.

---

## 5. "알림이 안 왔어요"

`pushbeam-admin messages show <id>`로 그 Member의 결과를 본다.

| 결과                    | 의미                         | 할 일                                 |
| ----------------------- | ---------------------------- | ------------------------------------- |
| 기록 없음 (채널 알림)   | 그 채널을 구독하지 않음      | 구독 안내                             |
| `MUTED`                 | 음소거 중                    | Member 설정                           |
| `BELOW_MIN`             | 최소 중요도보다 낮음         | Member 설정 또는 보낸 중요도 확인     |
| `NOT_ACTIVE`            | 로그인 안 함                 | 로그인 안내                           |
| `NO_DEVICE`             | 활성 기기 없음               | 앱 다시 로그인                        |
| `QUIET`                 | 방해 금지라 무음으로 감      | 앱 목록 확인                          |
| 전송 `PENDING`          | 아직 보내는 중               | 서버 상태 확인                        |
| 전송 `FAILED`           | FCM 실패                     | `last_error` 확인                     |
| 전송 `INVALID_TOKEN`    | 앱 삭제 등으로 토큰 무효     | 앱 다시 설치·로그인                   |
| 전송 `SENT`             | FCM까지 감                   | 기기의 알림 권한, 알림 채널, 배터리 최적화 확인 |

앱의 설정 → 진단 정보에서 앱 버전, 로그인 상태, 마지막 기기 등록 시각, 알림 권한을 볼 수 있다.

---

## 6. 백업과 복원

- 서버가 하루 한 번 `data/backup/pushbeam-YYYYMMDD.db`로 백업하고 14개를 남긴다.
- 백업을 Host 밖으로 복사하는 것은 운영자가 한다.
- 복원: 서버를 멈추고 → 백업 파일을 `data/pushbeam.db`로 바꾸고 → 시작한다. 복원한 뒤 허용 목록을 확인하고 `distribution sync`를 한다.

---

## 7. 업데이트

### 7.1 서버

```text
이미지 태그를 바꾼다 → docker compose pull (또는 build) → docker compose up -d
```

서버는 시작할 때 DB를 백업하고 Migration을 적용한다. Migration이 실패하면 시작하지 않는다.

API는 같은 버전 안에서 하위 호환이므로 서버를 먼저 올리고 앱을 나중에 배포한다.

### 7.2 앱

```text
git tag v<버전> && git push origin v<버전>
```

GitHub Actions가 빌드·서명하고 `pushbeam-members` 그룹에 배포한다. 개발 중에는 0.x 버전을 쓴다.

### 7.3 키 교체

| 키                  | 방법                                                   |
| ------------------- | ------------------------------------------------------ |
| Sender Key          | 새 Sender 발급 → 스크립트 교체 → 이전 Sender 폐기      |
| Operator Token      | 새로 만들기 → `secrets/` 교체 → 서버 재시작 → Admin 설정 교체 |
| 서버 계정 키        | 새 키 발급 → `secrets/` 교체 → 서버 재시작 → 이전 키 삭제 |
| 앱 서명 키          | 바꾸지 않는다                                          |

---

## 8. 테스트

### 8.1 자동 테스트 (모든 push)

- **수신 규칙**: 필수 여부, 중요도, 음소거, 최소 중요도, 방해 금지 시간의 조합별로 결과가 맞는지 확인한다. 방해 금지 시간은 자정을 넘는 구간과 시간대 차이를 포함한다.
- **서버 통합 테스트**: 실제 SQLite와 가짜 FCM·App Distribution으로 확인한다.
  - 허용 → 로그인 → 자동 구독
  - 채널 발송 → 기록과 전송 생성
  - FCM 실패 → 재시도 → 성공 또는 `FAILED`
  - 토큰 무효 → 기기 비활성화
  - 허용 취소 → 대기 전송 취소, 이후 발송 대상에서 빠짐
  - 서버 재시작 → 대기 전송 이어서 처리
  - 권한: Sender가 허용되지 않은 채널로 보내면 거부, Member 토큰으로 발송·관리 API 거부
- **앱 테스트**: 같은 알림 두 번 수신 → 한 번만 표시, 중요도별 알림 채널, 등록 실패 재시도
- **API 형식**: `shared`의 타입이 03 문서의 예시 JSON과 맞는지 확인한다.

### 8.2 실제 기기 확인 (앱·서버 릴리즈 전)

```text
1. 새 이메일 허용 → 초대 메일 → 설치 → 로그인 → 필수 채널 구독 확인
2. 앱을 종료한 상태에서 알림 수신 → 목록에 저장됨
3. 화면이 꺼진 상태에서 critical 즉시 수신
4. 음소거, 최소 중요도, 방해 금지 시간이 의도대로 동작
5. 허용 취소 → 그 기기로 알림이 오지 않음
6. 이전 버전에서 업데이트 → 로그인 유지, 정상 수신
```
