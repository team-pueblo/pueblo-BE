# 로컬 개발 환경

## 범위와 버전

프로젝트 골격은 Java 21, Spring Boot 3.5.16, Gradle 8.14.3을 사용한다. MySQL 8.4.7과 Kafka 4.1.2는 로컬 Compose 이미지로 고정한다. Spring Boot BOM으로 JDBC·Kafka·Flyway 의존성 버전을 맞춘다. Java 21은 LTS 기반으로 선택했고, Spring Boot 3.5 계열의 Java·Gradle 호환 범위 안에서 구성했다.

- [Spring Boot 3.5 시스템 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Gradle 8.14.3 릴리스](https://docs.gradle.org/8.14.3/release-notes.html)
- [Kafka 4.1 공식 Docker 안내](https://kafka.apache.org/41/getting-started/docker/)

서비스 시작점·DB/Kafka 설정·Outbox 발행·소비 중복 방지, 회원 인증 API, 상품 공개 조회·SKU, 재고 상태 전이·내부 Kafka 수신을 구현했다. 주문·결제 업무 API와 전체 흐름 연결·대사 작업은 후속 단계다. [회원 인증 설정](member-auth.md), [카탈로그 데이터 준비](catalog.md), [재고 활성화 설정](inventory.md), [메시징 구현 문서](messaging.md)를 참고한다.

## 컨테이너 실행 — 기본

JDK 21과 Docker Compose v2를 준비하고 저장소 루트에서 실행한다. 서비스는 모두 컨테이너로 구동한다. JDK는 이미지에 복사할 JAR 빌드에만 사용한다.

```sh
./scripts/start-local.sh
docker compose ps
docker compose logs -f member-service
```

스크립트는 기존 인증 키를 보존하고 JAR를 빌드한 뒤 이미지 빌드·기동·readiness 확인을 수행한다. 이미 호스트 Java 프로세스가 8081~8084를 사용 중이면 먼저 해당 프로세스를 종료해야 한다. 무관한 프로세스를 자동 종료하지 않는다.

MySQL·Redis·Kafka와 서비스 4개, 일회성 `auth-keys-init`으로 구성한다. 초기화 컨테이너는 호스트의 개발용 인증 키를 전용 볼륨에 복사하고 UID 10001 소유/파일 0400으로 설정한 뒤 종료한다. 회원 서비스는 그 볼륨을 읽기 전용으로 사용한다. 호스트 키 권한을 완화하거나 이미지에 키를 포함하지 않는다. 키를 교체했다면 `docker compose run --rm auth-keys-init` 후 `docker compose restart member-service`로 적용한다.

서비스는 컨테이너 내부 HTTP 8080을 사용하고 호스트의 8081~8084로 매핑한다. 컨테이너에서는 `mysql:3306`, `redis:6379`, `kafka:19092`로 연결한다. 호스트 디버깅용 인프라 포트는 localhost의 13306·16379·9092다. 모든 공개 포트는 127.0.0.1에만 바인딩한다.

Compose는 로컬 개발 전용이며 공개된 개발 비밀번호·HTTP 쿠키를 사용한다. 메일은 `.env`에 설정한 SMTP로 발송한다. Kubernetes의 Istio mTLS를 로컬 Compose에서 실행하는 구성은 아니다. 실제 운영 Secret·TLS 설정으로 재사용하면 안 된다.

Swagger는 각 서비스의 `/swagger-ui/index.html`에서 확인한다. [접속 및 JWT·CSRF 사용법](swagger.md)을 참고한다.

## 호스트 디버깅 — 선택

호스트에서 특정 서비스를 디버깅하려면 먼저 같은 서비스를 차지한 컨테이너를 중지한다. 모든 서비스를 호스트로 실행할 때는 다음과 같이 인프라만 시작한다.

```sh
docker compose stop member-service catalog-service order-service payment-service
docker compose up -d --wait mysql redis kafka
./scripts/generate-local-auth-keys.sh
./gradlew :member-service:bootRun --args='--spring.profiles.active=local'
```

나머지는 서비스 이름을 바꿔 각각 실행한다. 호스트 프로필의 주소는 localhost이며 컨테이너 실행에서는 사용하지 않는다.

| 서비스 | 포트 | 소유 DB·계정 |
| --- | --- | --- |
| 회원 | 8081 | `pueblo_member` |
| 상품·재고 | 8082 | `pueblo_catalog` |
| 주문 | 8083 | `pueblo_order` |
| 결제 | 8084 | `pueblo_payment` |

Flyway는 서비스별 `src/main/resources/db/migration`을 해당 계정으로 적용한다. `outbox_event`와 `consumed_event`는 각 DB에 별도로 생성된다. 서비스 간 Gradle 프로젝트 의존성과 교차 DB 권한은 없다. JDBC 시간대와 로컬 MySQL 서버 시간대는 UTC로 설정했다.

`local` 프로필 없이 실행하면 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`를 반드시 제공한다. 회원 서비스에는 JWT·메일 암호화 키와 SMTP 설정도 필요하다. `SERVER_PORT`는 선택 사항이다. Kafka TLS·인증, 서비스 간 사용자 인증 연결, 백업·복구, 실제 Kubernetes 배치는 후속 범위다.

## 상태·보안 확인

```sh
./scripts/smoke-local.sh
```

호스트 프로세스 방식의 추가 검증이 필요하면 서비스 컨테이너를 중지하고 빌드·인프라만 기동한 뒤 다음 명령을 실행한다. 기본 컨테이너 상태 확인에는 smoke-local.sh를 사용한다. JDK 21이 `JAVA_HOME` 또는 PATH에 있어야 한다. 스크립트는 임시 앱 4개를 실행하고 종료하며, 로그 디렉터리를 출력한다. 회원 V1·V2, 다른 서비스 V1 마이그레이션이 있고 메시징 테이블이 비어 있는 개발 DB를 대상으로 한다. POSIX 셸·curl·grep을 사용한다.

```sh
./scripts/verify-bootstrap.sh
```

`smoke-local.sh`는 4개 서비스의 readiness 성공과 미인증 보호 경로의 `401`을 확인한다. 회원 서비스는 명시된 인증 API만 공개하고 /me에 JWT·세션 검사를 적용한다. 다른 서비스는 health 외 경로를 차단한다. 기본 비밀번호 발급·폼 로그인·HTTP Basic은 구성하지 않는다.

- `/actuator/health/liveness`: 프로세스 생존 상태
- `/actuator/health/readiness`: 애플리케이션과 DB 연결 상태
- `/actuator/health`: 기본 health. 상세 정보는 공개하지 않는다.

회원 readiness는 DB와 Redis를 확인하며 다른 서비스는 DB를 확인한다. 현재 readiness는 Kafka 연결·이벤트 소비 지연을 검사하지 않는다. 실제 발행·소비 기능이 생기면 그 기능의 장애 허용 정책에 맞춰 별도 지표·상태를 추가한다. 이번 보안 단위 테스트는 업무 경로 차단을 검사하며 DB·Kafka 검증을 대체하지 않는다.

## 데이터 보존과 종료

```sh
docker compose stop
docker compose up -d --wait
```

MySQL·Redis·Kafka 데이터는 이름 있는 볼륨에 보존한다. MySQL 초기 DB·계정 생성 스크립트는 빈 데이터 디렉터리에서만 실행된다. 기존 볼륨에 대한 계정·스키마 변경은 별도 변경 절차로 적용한다. 볼륨 삭제는 데이터 유실을 일으키므로 일반 종료 절차에 포함하지 않는다.

Kafka는 개발용 24시간 보관·파티션별 보관량 제한과 1분 정리 주기를 사용한다. 보관량은 하드 디스크 상한이 아니며 토픽·파티션 수에 따라 증가한다. 실제 토픽과 보관 정책은 계약 설계 후 명시적으로 생성한다. 자동 토픽 생성은 껐다. 단일 브로커 볼륨 자체가 유실되면 이벤트를 복구할 수 있다고 보장하지 않는다.

실제 서버 2대의 영속 볼륨·MySQL·Kafka 배치는 아직 구현하지 않았다. 앱의 Kubernetes·Istio mTLS 배포 기반은 [Istio 문서](istio-mtls.md)에 있으며 실제 클러스터 적용은 미수행이다. Compose의 메모리 제한은 로컬 초기값이며 부하 측정 후 조정한다.

## 골격 검증 기록 — 2026-10-24

- `./gradlew build`: 4개 서비스 실행 JAR 생성, 보안 테스트 총 4개 통과
- `docker compose config --quiet`: Compose 구문 검증 통과
- `docker compose up -d --wait`: MySQL·Kafka health 확인
- `./scripts/verify-bootstrap.sh`: 앱 4개 readiness·401, Flyway V1·메시징 테이블 생성·교차 DB 접근 차단 확인
- Kafka 일회용 토픽에서 메시지 1건 발행·소비 확인 후 해당 토픽 삭제

검증 후 앱 프로세스와 로컬 인프라는 종료하고 MySQL·Kafka 볼륨은 보존했다.

이는 개발 환경의 골격 검증 결과이며 결제·재고 동시성·Outbox 복구 요구사항의 검수 완료를 의미하지 않는다.

## 컨테이너 전환 검증 — 2026-10-24

서비스 이미지 4개 빌드 후 Compose의 서비스 4개·인프라 3개 모두 healthy를 확인했다. auth-keys-init은 exit 0으로 정상 종료했다. 호스트 8081~8084의 readiness·미인증 경로 차단 검사도 통과했다. 회원 컨테이너 UID 10001과 인증 키 읽기 전용 접근을 확인했다. 기존 호스트 Java 서비스는 종료했고 데이터 볼륨은 유지했다.
