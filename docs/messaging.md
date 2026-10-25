# Outbox 발행과 소비 중복 방지

## 구현 범위

`messaging-jdbc`는 도메인 엔티티가 없는 인프라 라이브러리다. 4개 서비스가 의존하며 각 서비스의 DataSource·트랜잭션 매니저·KafkaTemplate을 사용한다. Outbox·소비 기록 테이블과 마이그레이션은 서비스별 DB가 소유한다. 다른 서비스 DB에는 접근하지 않는다.

Spring Boot 자동 설정으로 `JdbcOutbox`, `OutboxDispatcher`, `JdbcEventConsumer`, 폴링 작업을 등록한다. 업무 이벤트 리스너·토픽·주문/결제 도메인 상태 전이는 아직 구현하지 않았다. 라이브러리의 존재만으로 결제 정합성 검수가 끝난 것은 아니다.

## 이벤트 계약

`EventEnvelope`는 `eventId`, `eventType`, `schemaVersion`, `producer`, `aggregateId`, `aggregateVersion`, `occurredAt`, `correlationId`, `causationId`, `requestId`, `payload`를 갖는다. `OutboxRecord`가 별도로 Kafka topic·message key를 지정한다. 시각은 MySQL `DATETIME(6)`에 맞춰 UTC 마이크로초 정밀도로 정규화하고, payload는 복사해 보관한다.

`producer`는 서비스의 `spring.application.name`이며 append 시 검증한다. 현재 테이블은 producer를 별도 저장하지 않고 발행 시 서비스 이름으로 복원하므로, 미발행 이벤트가 있는 상태에서 서비스 이름을 변경하지 않는다. 이벤트 ID·업무 식별자는 재발행 시 유지한다. Kafka key와 원장 버전은 업무 흐름이 명시적으로 결정해야 한다.

## 업무 변경과 Outbox 기록

호출 서비스의 로컬 트랜잭션 안에서 업무 변경 후 `JdbcOutbox.append(record)`를 호출한다. 트랜잭션이 없으면 거부한다. append 실패·업무 실패를 호출자가 삼켜서 부분 커밋하지 않아야 한다. 전체 트랜잭션이 성공해야 업무와 이벤트가 함께 보존된다.

```java
transactionTemplate.executeWithoutResult(status -> {
    // 이 서비스가 소유하는 업무 원장을 변경한다.
    outbox.append(record);
});
```

## 발행·재시도·선점 만료

1. 폴러가 `FOR UPDATE SKIP LOCKED`로 처리 가능한 한 건을 읽는다. DB 시각으로 기한을 판단한다.
2. 새 선점 ID와 만료 시각, 증가한 시도 횟수를 저장하고 짧은 트랜잭션을 커밋한다.
3. 트랜잭션 밖에서 Kafka에 전송하고 브로커 확인을 기다린다.
4. 같은 선점 ID가 유효할 때만 `PUBLISHED`로 변경한다. 새 작업자가 가져간 선점을 이전 작업자가 덮어쓰지 못한다.
5. 전송 실패 시 지수 백오프 후 `PENDING`으로 재시도한다. 한도에 도달하면 `FAILED`로 보존한다.
6. `PROCESSING` 상태에서 프로세스가 사라지면 선점 만료 후 다른 작업자가 재처리한다. 마지막 허용 시도에서 중단돼도 이후 `FAILED`로 수렴한다.

브로커 확인 후 DB 완료 표시 전에 종료되면 같은 이벤트가 다시 발행될 수 있다. Kafka producer의 idempotence 설정과 별개로 애플리케이션 재발행은 중복될 수 있으므로 소비 중복 방지가 필요하다. 같은 aggregate의 여러 이벤트가 실패·다중 폴러에 의해 뒤바뀔 수 있으며, 엄격한 원장 순서는 보장하지 않는다. 업무 소비자가 버전·허용 전이·업무 고유 제약을 검사해야 한다.

| 설정 | 초기값 | 의미 |
| --- | --- | --- |
| `pueblo.messaging.enabled` | true | 메시징 자동 설정 |
| `pueblo.messaging.polling-enabled` | true | 예약 폴링. false여도 수동 dispatcher 사용 가능 |
| `pueblo.messaging.poll-interval-ms` | 1000 | 한 폴링 완료 후 대기 시간 |
| `pueblo.messaging.max-per-poll` | 10 | 한 폴링의 최대 처리 건수. 한 번에 한 건씩 선점 |
| `pueblo.messaging.lease-ms` | 30000 | 작업자 선점 기한 |
| `pueblo.messaging.send-timeout-ms` | 6000 | Kafka 전송 future 대기 기한 |
| `pueblo.messaging.max-attempts` | 20 | 최대 전송 시도 수. 중단된 작업의 선점도 시도로 계산 |
| `pueblo.messaging.retry-base-ms` | 1000 | 첫 재시도 지연 |
| `pueblo.messaging.retry-max-ms` | 30000 | 최대 백오프 |

Kafka producer에는 `acks=all`, idempotence와 유한한 `max.block.ms`·`request.timeout.ms`·`delivery.timeout.ms`를 설정했다. 단일 브로커이므로 `acks=all`이 다중 복제 내구성을 뜻하지 않는다. 선점은 전송 기한보다 충분히 길게 설정하고, 변경 시 producer blocking 시간까지 고려한다. 장애 시 최대 시도에 도달한 `FAILED`는 자동 조회 대상에서 제외한다.

## 소비와 offset

record 리스너는 `JdbcEventConsumer.consume(논리 소비자 이름, eventId, 업무 처리)`를 동기적으로 호출한다. 인스턴스마다 다른 소비자 이름을 만들지 않는다. 동일 논리 처리의 모든 인스턴스가 같은 이름을 사용한다.

- `(consumer_name, event_id)` 고유 키를 먼저 삽입하고 업무 변경·후속 Outbox를 같은 로컬 트랜잭션에서 커밋한다.
- 이미 처리한 이벤트는 업무를 다시 실행하지 않는다. 다른 논리 소비자는 같은 이벤트를 독립적으로 처리할 수 있다.
- 업무 실패 시 처리 기록까지 롤백하므로 재전달로 다시 시도할 수 있다.
- 리스너가 성공적으로 반환한 뒤 Kafka offset을 반영한다. 서비스 설정은 auto-commit 비활성·record ack다.
- DB 커밋 직후 offset 반영 전에 종료돼도 재전달 시 처리 기록으로 중복을 차단한다.

기본 `CommonContainerStoppingErrorHandler`는 리스너 실패 시 컨테이너를 중단한다. 실패 레코드를 건너뛰고 offset을 진행하지 않기 위한 초기 정책이다. 이후 서비스별 유한 재시도·격리·재시작 절차로 교체할 수 있다. 리스너 안에서 예외를 삼키거나 별도 스레드로 업무를 넘기면 이 보장을 깨뜨릴 수 있다.

## 검증 실행

JDK 21과 실행 중인 Docker 데몬이 필요하다.

```sh
./gradlew build :messaging-jdbc:integrationTest
```

통합 테스트는 기존 Compose DB에 쓰지 않고 별도 MySQL 8.4.7·Kafka 4.1.2 테스트 컨테이너를 사용한다. 서비스 소유 V1 마이그레이션을 그대로 적용하고, 테스트 컨테이너 안에서만 데이터를 초기화한다. Docker가 없으면 테스트를 성공으로 건너뛰지 않고 실패한다.

검증 대상은 트랜잭션 원자성, 동시 중복 소비, 소비자별 중복 방지 범위, 다중 폴러 선점, 선점 만료와 이전 작업자의 갱신 차단, 재시도 한도, 브로커 확인 이후 미완료 기록의 재발행, Kafka 응답 지연과 복구다. 프로세스 중단 경계는 완료 기록을 남기지 않은 선점을 재구성하는 방식으로 검증하며 OS 프로세스를 실제 강제 종료하는 시나리오는 후속 과제다.

## 검증 결과 — 2026-10-24

실제 MySQL·Kafka 테스트 컨테이너에서 통합 테스트 12개를 통과했다. 트랜잭션 롤백, 24건 동시 중복 소비, 다중 폴러 선점, 선점 만료·이전 작업자 차단, 재시도 한도, 발행 후 미완료 기록 재전달, 브로커 지연 복구, 실패 리스너 재시작 후 재전달을 확인했다.

4개 서비스의 빌드·기존 보안 테스트와 메시징 라이브러리 연결 후 실제 기동·DB readiness·교차 DB 접근 차단도 확인했다. 검증 앱은 종료하고 로컬 MySQL·Kafka는 볼륨을 보존한 채 중지했다.

Kafka pause 실험에서는 전송 측 타임아웃 뒤 복구 시 동일 이벤트가 두 번 전달되는 경우를 실제 관찰했다. 검증은 한 번의 전달을 기대하지 않고, 동일 ID 유지와 업무 반영 한 번을 확인한다. 다중 폴러는 `SKIP LOCKED`에 의해 일시적으로 빈 결과를 받을 수 있어 후속 폴링까지 포함해 전체 기록이 중복 선점 없이 처리 가능한지 확인한다.

## 남은 작업

- 실제 주문·재고·결제 토픽과 메시지 payload·버전 처리, 도메인 소비자
- `FAILED` 조회·감사 기록을 포함한 운영자 재처리 API, 보관·정리 정책
- 독성 메시지 격리와 서비스별 리스너 재시작 정책
- Outbox 미발행·오래된 선점·중단된 소비자 관측
- 실제 프로세스 강제 종료·재시작을 포함한 결제 장애 자동화

## 참고

- [MySQL 잠금 읽기와 SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [Spring Kafka 오류 처리](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [Testcontainers Kafka 모듈](https://java.testcontainers.org/modules/kafka/)
