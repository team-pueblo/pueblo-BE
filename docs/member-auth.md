# 회원 인증 구현

회원 서비스에 이메일 인증 가입, 로그인, HS256 JWT 갱신, 로그아웃, 비밀번호 재설정과 본인 조회를 구현했다. 주문 서비스는 회원 본인 조회 API로 인증을 연동했다. 결제 서비스 인증과 운영자 초기 생성 명령은 후속 범위다.

## API와 프론트 연결

모든 경로의 prefix는 `/api/v1`이다. 성공은 `{data, requestId}`, 오류는 `{error:{code,message}, requestId}` 형식이다.

| 메서드·경로 | 요청 | 결과 |
| --- | --- | --- |
| GET `/auth/csrf` | 없음 | CSRF `token`, `headerName`과 HttpOnly 쿠키 |
| POST `/auth/signup` | email, password, nickname, consents | 202. 이메일 인증 전 PENDING 회원 |
| POST `/auth/email-verification-requests` | email | 미인증 계정은 재발송 후 202. 인증 완료 계정은 409 `EMAIL_ALREADY_VERIFIED`. 미등록 이메일은 발송 없이 202 |
| POST `/auth/email-verifications` | token | 일회용 링크 소비, ACTIVE 전환 |
| POST `/auth/login` | email, password | accessToken, tokenType, expiresIn, user 및 refresh 쿠키 |
| POST `/auth/refresh` | refresh 쿠키 | 새 accessToken과 교체된 refresh 쿠키 |
| POST `/auth/logout` | refresh 쿠키 | 해당 세션 폐기, refresh 쿠키 삭제 |
| POST `/auth/password-reset-requests` | email | 202. 존재·상태와 무관한 동일 안내 |
| POST `/auth/password-resets` | token, newPassword | 비밀번호 변경·모든 기기 세션 폐기 |
| GET `/me` | Authorization: Bearer accessToken | 본인의 id, email, nickname |

가입 동의는 `[{"policyType":"TERMS","version":"v1","agreed":true},{"policyType":"PRIVACY","version":"v1","agreed":true}]`다. 버전은 `pueblo.auth.terms-version`, `privacy-version` 설정으로 변경한다. 이메일은 앞뒤 공백 제거·소문자 정규화 후 고유하게 저장한다. 닉네임 중복은 허용한다. 비밀번호는 현재 10~128자이며 PBKDF2-HMAC-SHA256(310,000회)으로 저장한다.

프론트는 먼저 CSRF API를 `credentials: 'include'`로 호출하고 반환받은 토큰을 `X-XSRF-TOKEN` 헤더에 넣어 POST한다. 이후 POST에도 credentials를 유지한다. CSRF 쿠키는 HttpOnly이므로 JavaScript에서 읽지 않는다. 액세스 토큰은 메모리에만 보관하고 보호 API에 Bearer로 전달한다. 로그인·갱신·로그아웃 등 공개 인증 요청에는 오래된 Bearer를 보내지 않는다.

운영 refresh 쿠키는 `__Secure-pueblo_refresh`, HttpOnly·Secure·SameSite=Lax, Path=/api/v1/auth다. CSRF 쿠키는 `__Host-pueblo_csrf`, Path=/이다. 로컬 HTTP 프로필에서만 Secure=false이고 접두사를 제거한다. CORS는 지정 origin만 credentials와 함께 허용한다. 서로 다른 사이트를 사용한다면 HTTPS와 SameSite=None 설정 및 브라우저의 제3자 쿠키 제한을 함께 확인해야 한다.

## 세션과 동시성

액세스 5분, 세션·refresh는 최초 로그인부터 최대 14일이며 갱신으로 연장하지 않는다. 회원 API는 서명·issuer·audience·용도·만료뿐 아니라 회원 DB의 세션 상태와 Redis 키의 유효 TTL을 확인한다. 로그아웃·비밀번호 변경 이후 시작한 인증 검사에서 기존 액세스 토큰을 거부한다. 이미 인증 검사를 통과한 진행 중 요청을 소급 취소하지는 않는다.

갱신 시 회원→세션 순으로 DB 잠금을 획득하고 Redis Lua 스크립트에서 refresh 해시를 비교·교체한다. refresh 원문은 저장하지 않고 Redis에서 SHA-256 해시를 비교한다. 기존 V2의 auth_refresh_token 테이블은 호환 이력으로 남지만 신규 쓰기·검증에는 사용하지 않는다. Redis 상태가 없는 기존 로그인은 다시 로그인해야 한다. 사용된 refresh의 **3초 이내** 중복 요청은 409 `REFRESH_ALREADY_ROTATED`이며 세션을 폐기하거나 쿠키를 덮어쓰지 않는다. 그 이후 재사용은 세션 폐기를 커밋한 뒤 401 `REFRESH_REUSE_DETECTED`를 반환한다. 3초는 정상 동시 요청을 위한 구현 유예값이며 탈취를 구분하는 증거는 아니다. 프론트는 탭 간 갱신을 직렬화하고 성공한 액세스 토큰을 공유해야 한다. 갱신 응답이 유실되고 새 쿠키도 받지 못했다면 재로그인이 필요하다.

다른 서비스는 회원 DB를 직접 조회하면 안 된다. JWT 서명 검증만으로 즉시 폐기를 보장할 수 없으므로, 주문 서비스는 매 요청의 액세스 JWT를 회원 `/api/v1/me`에 전달하고 확인된 회원 ID를 사용한다. 배포 환경에서는 이 호출을 Istio mTLS로 보호한다. 주문에 JWT 서명키나 회원 DB 자격을 배포하지 않는다. 공개 키 조회 API는 제공하지 않는다. Istio mTLS는 서비스 신원을, JWT·세션 검사는 사용자 신원을 검증한다.

## 이메일과 제한

이메일 인증 링크는 30분, 비밀번호 재설정 링크는 15분이다. 재발급은 같은 목적의 이전 토큰을 무효화한다. 링크는 `/verify-email#token=...`, `/reset-password#token=...`로 생성한다. 프론트가 fragment를 읽어 POST 본문에 전달하고 브라우저 주소에서 제거해야 한다. 토큰·비밀번호를 로그에 출력하지 않는다.

회원/링크 생성과 메일 작업 등록은 같은 DB 트랜잭션이다. 링크 검증 원장에는 SHA-256 해시만 저장하고, 발송 대기 원문은 별도의 32바이트 키로 AES-256-GCM 암호화한다. 발송기는 메일 작업 행만 SKIP LOCKED로 선점하고 DB 트랜잭션 밖에서 SMTP를 호출한다. 재시도 간격은 30·60·120·240초, 최대 5회이며 실패 상태를 보존한다. 발송 완료 또는 사용·만료 확인 시 암호문을 삭제한다. 최종 실패 작업의 만료 데이터 정리·운영 재처리 도구는 후속 범위다. 발송 직후 프로세스 종료 시 메일이 중복 발송될 수 있지만 링크는 한 번만 사용할 수 있다.

가입·각 링크 요청은 이메일별 시간당 5회, 로그인은 이메일별 15분당 10회, 갱신은 세션별 분당 60회로 제한한다. 429에는 현재 제한 구간의 남은 초를 Retry-After로 반환한다. IP·전역 발송 한도와 DB 이력 보관/정리 정책은 실제 공개 전에 추가해야 한다.

## 실행과 비밀 설정

저장소 루트에서 `./scripts/generate-local-auth-keys.sh`로 `.local/auth`에 키를 생성한다. 이 디렉터리는 Git에서 제외된다. 기존 키를 자동 교체하지 않는다. 이후 `./gradlew :member-service:bootRun --args='--spring.profiles.active=local'`을 실행한다. JAR를 루트에서 직접 실행한다면 `AUTH_KEY_DIRECTORY`를 `.local/auth`의 절대 경로로 지정한다. bootstrap 검증 스크립트는 자동으로 지정한다.

로컬 메일은 SMTP로 발송한다. 모의 발송은 통합 테스트에서만 사용한다. 개발 이메일 흐름에는 다음 환경변수를 설정한다:

이메일 인증·비밀번호 재설정 메일은 `mail/auth.html` 공통 HTML 템플릿을 사용한다. 최대 560px 본문을 왼쪽에 정렬하고 PNG 로고, 제목, 설명, 회색 안내 표, 파란 실행 버튼, 대체 링크와 발신 전용 안내를 배치한다. 로고는 `mail/images/pueblo_logo.png`를 `cid:pueblo-logo`로 인라인 첨부하여 외부 이미지 서버 없이 표시한다. HTML과 일반 텍스트를 UTF-8 multipart/alternative로 함께 보내며 인증은 발급 후 30분, 재설정은 15분이다. 메일 버튼은 프론트의 `/verify-email#token=...` 또는 `/reset-password#token=...`으로 이동한다. 해당 화면에서 CSRF 초기화 후 인증·재설정 API를 호출해야 하며 메일을 받기만 해서는 계정이 활성화되지 않는다.

- `AUTH_FRONTEND_BASE_URL`, `AUTH_ALLOWED_ORIGINS`
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`
- 기본 `SMTP_AUTH=true`, `SMTP_STARTTLS=true`. 인증 없는 로컬 SMTP에서만 두 값을 false로 설정한다. 연결·읽기·쓰기 제한은 각각 5초다.

### .env 값 → application-local.yml 참조 → SMTP 설정

값은 저장소 루트의 `.env`에 넣고, 기존 [application-local.yml](../member-service/src/main/resources/application-local.yml)이 `${SMTP_HOST}`, `${SMTP_PASSWORD}` 같은 환경변수를 참조한다. 별도 properties 파일이나 import는 사용하지 않는다. 실제 계정 정보를 YAML에 넣지 않는다. Compose의 회원 서비스는 local 프로필을 사용하고 DB·Redis·Kafka·키 경로도 환경변수로 받아 호스트 주소와 컨테이너 주소를 구분한다. 공통 application.yml의 운영 환경변수 지원과 연결 제한 시간은 유지한다.

```sh
cp .env.example .env
chmod 600 .env
```

기존 `.env`가 있다면 덮어쓰지 말고 [.env.example](../.env.example)의 필요한 변수만 추가한다. `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`를 입력한다. 메일 모드는 YAML에서 `smtp`로 지정하며 발신 주소도 `SMTP_USERNAME`을 사용하므로 제공사가 발송을 허용한 이메일 주소를 입력한다. `.env`는 Git에서 제외된다. 비밀번호에 `$`, `#`, 공백이 포함되면 `.env`에서 작은따옴표로 감싸 Compose가 리터럴 값으로 읽도록 한다.

기본 예시는 587/STARTTLS다. 제공사가 465/implicit TLS를 요구하면 `SMTP_PORT=465`, `SMTP_STARTTLS=false`, `SMTP_SSL=true`로 설정한다. 발신 주소는 해당 SMTP 계정에서 발송이 허용된 주소여야 한다.

Docker Compose가 `.env`를 읽어 필요한 변수만 회원 컨테이너에 전달한다. Spring 자체가 `.env` 파일을 자동으로 읽는 것은 아니다. 호스트 bootRun 실행에서는 IDE 실행 환경이나 셸 환경변수로 같은 값을 제공한다.

설정 변경 후 회원 컨테이너를 다시 생성한다. 단순 restart는 변경한 환경변수를 반영하지 않는다.

```sh
docker compose up -d --no-deps --force-recreate --wait member-service
```

이번 YAML 코드 변경을 처음 적용하거나 이후 코드를 수정했다면 `./scripts/start-local.sh`로 JAR·이미지를 먼저 갱신한다. 그 이후 `.env` 값 변경에는 재빌드가 필요 없다. 이전 `.local/auth/application-smtp.properties`는 더 이상 로드하지 않으며, 호스트 파일은 자동 삭제하지 않는다. 인증 키 초기화 작업도 SMTP 설정에 사용하지 않는다.

`AUTH_FRONTEND_BASE_URL`은 실제 프론트 주소여야 한다. 메일 발송만으로 회원이 활성화되지는 않으며, 프론트가 링크의 `#token`을 읽어 이메일 인증 API를 호출해야 한다. 기존 mock으로 SENT 처리된 메일은 SMTP 설정 후 자동 재전송되지 않으므로 `/api/v1/auth/email-verification-requests`로 새 링크를 요청한다.

JWT는 환경변수 `JWT_TOKEN`의 UTF-8 문자열을 비밀키로 사용하는 HS256 방식이다. 최소 32바이트가 필요하며 `openssl rand -hex 32`로 생성한 임의 값을 사용할 수 있다. Base64 디코딩은 하지 않는다. Compose는 `.env` 값을 전달하고 호스트 실행에서는 환경변수를 직접 설정한다. RSA 키 파일과 공개 키 조회 API는 사용하지 않는다. 이전 RSA 토큰은 무효가 되므로 다시 로그인해야 한다. 검증 서비스에 같은 비밀키를 전달하면 토큰 발급 권한도 갖게 되므로 신뢰하는 서비스에만 배포한다.

`AUTH_MAIL_KEY_LOCATION`은 메일 대기 데이터 암호화용 파일 Resource를 가리킨다. mail.key는 32바이트 키를 Base64 인코딩한 파일이며 JWT 비밀키와 별개다. 키를 이미지·소스·공개 ConfigMap에 넣지 않는다. 모든 회원 서비스 인스턴스는 같은 키와 Redis namespace를 사용한다. Redis는 REDIS_HOST, REDIS_PORT, REDIS_USERNAME, REDIS_PASSWORD, REDIS_SSL(기본 true), AUTH_REDIS_NAMESPACE 설정이 필요하다.

Kubernetes member Deployment는 `member-auth-keys` Secret의 `mail.key`를 `/var/run/pueblo/auth`에 읽기 전용 마운트한다. `member-service-runtime` Secret에는 `JWT_TOKEN`과 DB/Kafka·SMTP·origin·프론트 URL 설정을 넣는다. 실제 비밀 값은 저장소에 포함하지 않는다. 키 변경은 롤아웃과 기존 토큰/미발송 메일 처리 계획이 필요하다. 실제 클러스터에 적용하지 않았다.

## 검증

`./gradlew build :member-service:integrationTest --no-daemon`으로 단위 검사와 Docker MySQL 8.4.7·Redis 8.10.2 통합 테스트를 실행한다. 통합 테스트는 격리된 임시 DB와 임시 키·모의 메일만 사용한다. 가입부터 로그아웃, 실쿠키 CSRF·CORS, 동시 갱신, 재사용 폐기, 모든 기기 비밀번호 재설정, 고정 만료, 재발급 무효화, 중복 가입, 요청 제한, Swagger API 목록, 메일 재시도·트랜잭션 롤백을 검사한다. 실제 외부 SMTP 수신과 Kubernetes mTLS 핸드셰이크는 별도 환경 검증 대상이다.

추가 보안 정책·오류 다국어·보안 검사 구성은 [보안 적용 문서](security.md)를 참고한다.
