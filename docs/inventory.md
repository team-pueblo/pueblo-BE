# 재고 예약과 내부 명령

카탈로그 서비스가 SKU 가용 재고와 예약 원장을 소유한다. `catalog_sku.stock`은 예약되지 않은 수량이다. 예약 시 차감하고 확정 시 다시 차감하지 않으며 해제·만료·확정 후 환불 복원 시 돌려준다. Redis 분산락은 사용하지 않는다.

주문·결제 서비스의 실제 업무 흐름, 운영자 입고/재고 조정 API, 출고 검증은 후속 작업이다. 이 변경은 재고 명령 처리기·Kafka 수신·Outbox 결과·만료 작업을 제공한다. 재고 변경 HTTP 엔드포인트는 없다.

## 상태와 트랜잭션

| 명령 | 허용 상태 및 결과 |
| --- | --- |
| RESERVE / REACQUIRE | 새 예약: 조건부 차감 성공 시 RESERVED, 실패 시 REJECTED |
| COMMIT | RESERVED → COMMITTED. 만료 시 EXPIRED로 해제하고 확정 거부 |
| RELEASE | RESERVED → RELEASED. 예약보다 먼저 오면 RELEASED 의도를 저장 |
| EXPIRE | 기한이 지난 RESERVED → EXPIRED. 내부 만료 작업만 호출 |
| RESTORE | COMMITTED → RESTORED. 동일 refundId 재시도는 추가 복원 없음 |

- 예약 ID·주문 ID는 UUID, 세대는 양의 정수다. 주문별 원장을 잠가 현재 예약과 세대를 직렬화한다. 이전 세대의 명령은 새 예약을 수정하지 않는다.
- 이미 RESERVED/COMMITTED인 주문에는 새 세대 예약을 허용하지 않는다. 기존 예약 해제·만료·복원 후 새 ID와 더 큰 세대로 재확보한다. 이미 만료 시각이 지난 RESERVED도 먼저 만료 처리되어야 새 세대가 가능하다.
- 동일 명령 ID는 정규화한 본문 SHA-256에 연결된다. 동일 본문 재시도는 최초 결과를 반환하고, 다른 본문은 거부한다. 별도 명령 ID로 같은 예약을 재요청해도 재고 차감은 반복하지 않는다. 변경된 SKU·수량으로 같은 예약 ID를 재사용하면 거부한다.
- 입력 목록은 최대 100개다. 같은 SKU 수량을 합산해 1~9 및 SKU별 구매 상한을 검사한다. SKU ID 순서대로 조건부 UPDATE를 실행하고 활성 SKU·공개 상품·활성 브랜드도 검사한다.
- 여러 SKU 중 하나라도 부족하거나 구매할 수 없으면 savepoint까지 되돌린 후 REJECTED 결과를 기록한다. 일부 차감이나 항목이 남지 않는다. 원장·명령 결과·Outbox 저장 실패는 전체 트랜잭션을 롤백한다.
- DB 교착·잠금 획득 실패는 전체 트랜잭션을 최대 3회 시도한다. SQL 한 개만 재시도하지 않는다.
- DB UTC 시각을 기준으로 명령 유효기한을 검사한다. 예약 명령은 수신 처리 시점부터 최대 30초 이내 기한이어야 하며, 마지막 SKU 처리 후에도 기한을 재검사한다. 성공한 예약은 그 시각부터 15분 유효하다.
- 만료 작업은 기본 10초 간격으로 최대 100건을 처리한다. 각 예약은 독립 트랜잭션으로 처리하며 두 인스턴스가 같은 건을 선택해도 한 번만 해제된다. 재시작하면 DB의 만료 대상부터 재개한다.
- 환불 복원은 order-service가 **출고 전 전액 환불 성공을 확인한 뒤** 보낸다. 재고 서비스는 결제 DB를 조회하지 않는다. refundId는 전역 고유하며 해제/만료된 예약을 RESTORE로 다시 증가시키지 않는다.
- 복원으로 INT 재고 상한을 넘으면 전체 롤백하고 운영자 조정 대상으로 남긴다. 완료된 예약/명령 기록을 임의 삭제하면 중복 방지 보장이 깨지므로 현재 자동 정리하지 않는다.

## Kafka 계약 v1

- 명령 토픽: `pueblo.inventory.commands.v1`, group: `catalog-inventory-v1`.
- 결과 토픽: `pueblo.inventory.events.v1`.
- 두 토픽의 key는 orderId다. 테스트는 명령 파티션 2개·결과 1개로 검증한다. 토픽은 배포 시 운영 환경에 맞춰 사전 생성한다.
- 공통 `EventEnvelope`에 `eventType=InventoryCommand`, `schemaVersion=1`, `producer=order-service`, `aggregateId=orderId`를 넣는다. `eventId`와 payload.commandId는 같아야 한다.
- payload 예시의 UUID·시각은 구조 설명용이며 실행 시 새 식별자와 현재 시각을 사용한다.

```json
{
  "commandId": "00000000-0000-0000-0000-000000000001",
  "type": "RESERVE",
  "orderId": "00000000-0000-0000-0000-000000000002",
  "reservationId": "00000000-0000-0000-0000-000000000003",
  "generation": 1,
  "items": [{"skuId": "00000000-0000-0000-0000-000000000004", "quantity": 1}],
  "commandExpiresAt": "2026-10-25T03:00:25Z",
  "refundId": null
}
```

RESERVE/REACQUIRE만 items와 commandExpiresAt을 전달한다. 나머지는 items를 빈 배열, commandExpiresAt을 null로 전달한다. RESTORE만 refundId가 필수다. 외부 Kafka 입력에서 EXPIRE는 거부한다.

결과는 `InventoryReserved`, `InventoryCommitted`, `InventoryReleased`, `InventoryExpired`, `InventoryRestored` 또는 `InventoryCommandRejected`다. 결과 payload는 commandId·orderId·reservationId·generation·eventType·state·reason·reservedAt·expiresAt·version이다. 성공 reason은 null이다. 주요 거부 사유는 `INVENTORY_UNAVAILABLE`, `COMMAND_EXPIRED`, `COMMAND_DEADLINE_TOO_FAR`, `STALE_GENERATION`, `ACTIVE_GENERATION`, `RESERVATION_ID_CONFLICT`, `RESERVATION_PAYLOAD_CONFLICT`, `RESERVATION_EXPIRED`, `INVALID_STATE`, `UNKNOWN_RESERVATION`이다.

version은 주문별 처리 순서다. 결과 소비자는 버전과 예약 ID/세대를 함께 확인해야 한다. 오래된 동일 명령 재시도는 최초 결과를 반환하므로 최신 상태 조회를 대신하지 않는다. 새로운 명령 ID의 업무 재시도는 새 결과 이벤트를 만들 수 있지만 재고 효과는 한 번만 발생한다. 결과 전달은 기존 Outbox의 at-least-once 계약이며 주문 서비스에서 이벤트 중복 처리가 필요하다.

## 내부 명령 인증

Kafka header `pueblo-inventory-signature`는 **전송하는 EventEnvelope JSON 전체 UTF-8 바이트**에 HMAC-SHA256을 적용한 64자리 hex 문자열의 ASCII 바이트다. JSON 직렬화 후 서명하고 동일한 바이트를 전송한다. 재직렬화하거나 공백을 바꾸면 서명이 달라진다.

- 키는 order-service와 catalog-service만 공유하는 별도 환경변수다. JWT 키와 재사용하지 않는다. 최소 32 UTF-8 바이트이며 `openssl rand -hex 32` 등으로 생성한다.
- payload가 64KiB를 넘거나 서명 누락/중복·변조·잘못된 생산자/키/스키마이면 재고를 변경하지 않는다. producer 필드만으로 인증하지 않는다.
- 인증·형식 오류 또는 재시도 후 DB 실패는 기존 `CommonContainerStoppingErrorHandler`가 소비기를 중단하고 미처리 offset을 넘기지 않는다. readiness의 inventory 상태도 DOWN으로 표시한다. 원인 해결 및 해당 레코드의 운영 처리가 필요하며 현재 자동 DLQ는 없다.
- HMAC은 메시지 인증이고 암호화는 아니다. 운영 Kafka에는 TLS·SASL·토픽 ACL이 추가로 필요하다. Istio HTTP mTLS만으로 Kafka 생산자 권한을 보장하지 않는다. 결과 토픽의 생산자 ACL도 필요하다.
- 현재 주문 서비스의 일반 Outbox 발행기는 이 서명 헤더를 생성하지 않는다. 주문 흐름 연결 시 생산자 서명 및 키 교체 절차를 추가해야 하며, 이번 변경만으로 주문·결제 전체 연동이 완성되지 않는다.

## 실행 설정

기본값은 비활성이다. `.env.example`의 설정을 참고하여 키와 주문 측 생산자 준비 후 명시적으로 켠다. 실제 `.env`와 기존 재고 데이터는 이번 작업에서 변경하지 않는다.

```dotenv
INVENTORY_ENABLED=true
INVENTORY_LISTENER_ENABLED=true
INVENTORY_COMMAND_SECRET=<별도 생성한 비밀 값>
INVENTORY_EXPIRY_ENABLED=true
```

`pueblo.messaging.enabled=true`가 필요하다. 개발 Compose는 환경변수를 전달하며 Kubernetes는 `catalog-service-runtime` Secret에 같은 변수를 넣는다. Kafka 소비기와 Outbox 폴러는 서비스 재시작 시 재개된다. 일회성 카탈로그 가져오기 스크립트는 재고 worker를 끈다.

V3 마이그레이션은 catalog DB에만 주문별 세대, 예약, 예약 항목, 명령 결과 테이블을 추가한다. 기존 SKU 수량을 초기화하지 않는다. 외래키 때문에 예약에 사용한 SKU의 물리 삭제는 막히며 비활성 상태로 유지해야 한다.

## 검증

```sh
./gradlew :catalog-service:build :catalog-service:integrationTest --no-daemon
```

실제 MySQL 8.4.7·Kafka 4.1.2 컨테이너와 독립 Spring 애플리케이션 컨텍스트 2개/커넥션 풀 2개를 사용한다. 100개 요청은 두 서비스 인스턴스에 나누어 병렬 실행한다. 한 컨텍스트를 종료·재시작한 뒤 만료 처리도 확인한다. 별도 OS 프로세스·실제 두 물리 서버의 부하 측정은 아니다. 인증된 Kafka 입력→DB 변경→Outbox→Kafka 결과도 검증한다. 운영 DB나 기존 샘플 재고로 장애 실험을 실행하지 않는다.
