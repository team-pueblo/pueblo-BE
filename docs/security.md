# 보안 적용과 검증

2026-10-24 요청 반영. Stateless HTTP 인증, Redis refresh rotation, 입력 제한, ErrorCode/i18n, 보안 CI를 구현했다. 운영 배포·GitHub 원격 검사 실행은 별도다.

## HTTP·JWT

네 서비스 모두 Spring Security `STATELESS`로 HTTP 세션 인증을 사용하지 않는다. 이는 서버에 인증 상태가 없다는 뜻이 아니다. 회원 로그인 세션·폐기는 MySQL, refresh 해시·회전 이력은 Redis에 보존한다. 회원 API만 실제 JWT 인증을 제공하며 나머지 서비스는 기존처럼 업무 경로를 차단한다.

회원 CORS는 설정한 정확한 origin만 credentials와 함께 허용한다. 나머지 서비스는 브라우저 origin을 허용하지 않는다. 폼 로그인·HTTP Basic은 비활성화하고 CSRF를 유지한다. 기본 캐시 금지·nosniff·HTTPS HSTS, frame deny, CSP(default-src/frame-ancestors/base-uri none), Referrer-Policy를 적용한다. TLS 종료를 담당하는 ingress에서도 외부 HTTPS/HSTS를 설정해야 한다. 임의 X-Forwarded-*를 신뢰하도록 변경하지 않았다.

액세스 JWT는 15분에서 **5분**으로 줄였다. HS256 비밀키 서명, issuer, 정확한 audience, typ=JWT, exp·iat, 최대 수명, UUID sub/jti/sid, token_use, role을 검증한다. 허용 미래 발급 시각은 5초이고 만료 유예는 없다. refresh는 access로 사용할 수 없다. `/me`는 DB 세션 상태와 Redis 키 TTL까지 확인한다. Redis 장애는 503 `AUTH_STORE_UNAVAILABLE`, 키 유실·만료는 401이며 정상 인증으로 우회하지 않는다.

Istio mTLS는 서비스 통신 신원을 검증한다. 사용자 JWT·세션 검증을 대체하지 않는다. 주문 서비스는 회원 `/api/v1/me`에 액세스 JWT를 전달해 세션 상태까지 확인한다. 결제 서비스 인증 연결은 후속 범위다.

## Redis 원자 교체·namespace·TTL

키 형식은 `pueblo:<environment>:member:security:v1:refresh:{<session UUID>}`다. `AUTH_REDIS_NAMESPACE`에는 마지막 `:refresh:{...}`를 제외한 prefix를 지정한다. 환경은 영문 소문자·숫자·하이픈 1~32자로 제한하고 테스트·개발·운영을 분리한다. 회원 식별 이메일이나 원문 JWT는 키·값에 저장하지 않는다.

한 세션 hash에는 `current`(SHA-256), `rotations`, `used:<SHA-256>`(회전 시각)가 있다. 생성은 Lua에서 HSET과 PEXPIREAT을 함께 수행하며 만료는 최초 로그인 시각+14일이다. 회전 시 TTL은 연장하지 않는다. 단일 key에 대한 Lua 비교·교체로 Redis Cluster에서도 같은 슬롯을 사용한다. 사용된 토큰을 3초 이내 다시 보내면 409, 그 이후면 키를 지우고 DB 세션도 폐기한다. 원문을 반환하거나 재발급하지 않는다. 회전 이력 증가를 제한하기 위해 세션당 4,096회 이후 재로그인을 요구한다.

DB와 Redis는 분산 트랜잭션이 아니다. 기존 회원→세션 DB 잠금 순서를 유지하고 Redis 변경을 커밋 전에 수행한다. 중간 장애로 Redis가 먼저 변경되면 인증을 거부하거나 재로그인을 요구하는 방향으로 처리한다. Redis의 이전 키가 없어도 DB만 보고 허용하지 않는다. 초기 로그인 DB 롤백 시 남은 Redis 키는 원래 TTL까지 남을 수 있으나 DB 세션이 없어 인증되지 않는다. 로그아웃·비밀번호 변경은 해당 Redis 키를 삭제한다.

개발 Redis는 localhost:16379, 비밀번호, AOF·appendfsync always, noeviction, 128MB 데이터 상한/192MB 컨테이너 제한을 사용한다. 용량 초과도 인증 저장 실패로 처리한다. 운영은 TLS(기본 true)·서비스 전용 ACL·동일 namespace·영속성·접근 통제가 필요하다. ACL 키 범위는 해당 namespace로 한정하고 실행 명령은 스크립트·hash·TTL·삭제·연결에 필요한 범위로 제한한다. 이 Redis는 재고 분산락에 사용하지 않는다.

Redis 유실·복구로 이전 snapshot을 되살릴 때는 DB 세션을 폐기하거나 namespace를 새 환경 값으로 변경해 이전 refresh가 재활성화되지 않게 한다. DB만 복구하거나 Redis만 이전 상태로 복구하는 방식으로 세션 연속성을 보장하지 않는다. 기존 MySQL refresh 기록은 새 Redis 키로 자동 이관하지 않으며 사용자는 재로그인해야 한다.

## 요청·URL·오류 다국어

`api-web`은 HTTP 계약·필터만 공유하며 서비스 도메인이나 DB 모델을 공유하지 않는다. JSON 요청 본문은 **65,536바이트**까지 받고 Content-Length가 없어도 읽기 상한을 적용한다. 초과는 413 `PAYLOAD_TOO_LARGE`다. 현재 유일한 목록 입력인 가입 약관은 정확히 2개, null 원소 금지다. 이메일·닉네임·비밀번호·일회용 토큰 길이도 제한한다. 향후 장바구니 등의 목록에는 해당 API별 원소 상한이 필요하다.

프론트 링크 URL과 CORS origin은 절대 HTTPS, host 존재, 사용자 정보·query·fragment 금지를 검사한다. origin에는 path를 허용하지 않는다. 로컬 Secure=false 설정에서만 localhost/loopback의 HTTP를 허용한다. 이 검증은 구성 URL에 적용된다. 아직 외부 상품 이미지 URL을 받거나 다운로드하는 업무 API는 없다. 서버가 사용자 URL을 fetch하게 되면 DNS/IP·리다이렉트·사설망 접근을 제한하는 별도 SSRF 정책이 필요하다.

`ErrorCode` enum이 HTTP 상태와 기계 판독 코드를 정의한다. 오류 메시지는 `api-web/src/main/resources/messages/errors*.properties`에서 관리한다. `Accept-Language`의 ko/en과 가중치를 지원하고, 미지원·잘못된 헤더·생략은 한국어로 응답한다. `Content-Language`와 `Vary: Accept-Language`를 함께 반환한다. 코드와 응답 구조는 언어에 따라 바뀌지 않는다. 컨트롤러·인증/권한·CSRF·CORS·본문 제한 오류에 동일 체계를 적용한다. 성공 응답의 안내 문구까지 다국어화한 것은 아니다.

예: `Accept-Language: en-US,en;q=0.9`로 미인증 `/api/v1/me` 요청 시 `INVALID_CREDENTIALS`, `Check your authentication credentials.`를 반환한다. 비밀번호·토큰·내부 예외 메시지는 응답/로그에 싣지 않는다.

## 자동 검사

`.github/workflows/security.yml`은 PR·main/dev push·매주·수동 실행을 지원한다.

| 검사 | 대상·실패 기준 |
| --- | --- |
| CodeQL | Java/Kotlin security-extended, 수동 Gradle 빌드, 결과를 Code Scanning에 업로드 |
| Secret | checkout 소스의 Trivy secret 검사, 발견 시 실패 |
| 의존성 | 빌드된 JAR를 포함한 rootfs 검사, HIGH/CRITICAL 발견 시 실패 |
| 컨테이너 | 4개 서비스 이미지를 로컬 빌드·tar로 내보내 검사, HIGH/CRITICAL 취약점·Secret 발견 시 실패 |

이미지를 레지스트리에 게시하지 않는다. 스캐너에는 Docker socket이나 저장소 쓰기 토큰을 전달하지 않는다. 공식 Actions는 확인한 commit SHA, Trivy 0.76.1 이미지는 확인한 digest로 고정했다. Dependabot은 Gradle·Actions·Docker 업데이트를 생성한다. HIGH/CRITICAL 미수정 취약점을 자동으로 무시하거나 검사 실패를 성공으로 바꾸지 않는다.

CodeQL 업로드에는 GitHub Code Scanning 사용 가능 여부와 권한이 필요하다. 워크플로 추가만으로 branch protection이나 필수 체크가 활성화되지는 않는다. Secret 검사는 현재 checkout을 대상으로 하며 전체 Git 이력이나 GitHub push protection 설정을 대신하지 않는다.

로컬 검증은 `./gradlew build :member-service:integrationTest --no-daemon`, `python3 scripts/validate-kubernetes.py`, `./scripts/verify-bootstrap.sh`로 실행한다. 후자는 Compose 인프라·JDK21이 필요하다. 검사 결과는 실제 실행 결과와 구분해서 기록한다.

공식 근거: [Redis Lua 원자 실행](https://redis.io/docs/latest/develop/programmability/), [Trivy 분석 대상별 Java 지원](https://trivy.dev/docs/latest/coverage/language/), [GitHub CodeQL Action](https://github.com/github/codeql-action).

## 의존성 보안 패치와 남은 탐지

초기 회원 JAR 검사에서 HIGH/CRITICAL 13건을 확인했다. Jackson BOM 2.21.7, Netty BOM 4.1.137.Final, lz4-java 1.11.4, Tomcat 10.1.61을 같은 계열 패치로 적용한다. Tomcat advisory에 표시된 10.1.58은 Maven Central에 없으므로 실제 게시된 10.1.61을 사용한다. 이 override들은 Boot BOM 업데이트 시 다시 검토한다.

Spring MVC `CVE-2026-47884`, `CVE-2026-47890`는 별도 남은 항목이다. Trivy/GHSA는 CRITICAL로 분류하지만 Spring 공식 심각도는 각각 MEDIUM/LOW이며, 전제는 XsltView의 암시적 뷰 렌더링 또는 SSE view fragment다. 현재 REST API에는 이 기능이 없다. 해당 패키지가 포함되어 있다는 탐지를 숨기거나 무조건 안전하다고 단정하지 않는다. Spring 6.2.20 패치는 Enterprise Support Only이고 공개 수정 계열은 Spring 7.0.9 이상이다. 상용 패치 도입 또는 Boot 4/Spring 7 호환성 전환은 별도 작업이다. 보안 CI의 취약점 실패 기준은 유지한다.

근거: [Spring XsltView 공지](https://spring.io/security/cve-2026-47884/), [Spring SSE 공지](https://spring.io/security/cve-2026-47890/).

## 2026-10-24 로컬 실행 결과

- 전체 Gradle build 및 테스트 34개 통과(회원 통합 17개, 메시징 통합 12개, 단위 검사 5개). MySQL·Redis·Kafka는 격리된 Testcontainers를 사용했다.
- 소스 Secret 검사 통과. 개발용 `.local` 키·빌드 출력은 소스 검사에서 제외했고 실제 키를 커밋하지 않았다.
- 회원 실행 JAR의 라이브러리 92개를 분석해 HIGH/CRITICAL 13건에서 위 Spring MVC 2건으로 감소했다. 최종 검사 exit code는 1이며 통과로 보고하지 않는다.
- 네 서비스 JAR 기동, DB/회원 Redis readiness, 미인증 경로 차단, Flyway 및 교차 DB 접근 금지 검증 통과.
- Kubernetes 18개 리소스 정적 검증 통과. 실제 클러스터 배포·mTLS 핸드셰이크는 미수행이다.
- CodeQL·4개 이미지 검사는 워크플로 구성 단계이며 GitHub에서 실행하지 않았다. 로컬 검사는 설치된 Trivy 0.67.2와 갱신된 취약점 DB를 사용했고 CI는 digest 고정 0.76.1을 사용한다.
