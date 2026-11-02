# 결제 결과 복구와 주문 후속 처리

## 구현 계약

토스 조회는 저장된 `paymentKey`를 URL 경로 한 구간으로 인코딩하여 `GET /v1/payments/{paymentKey}`로 호출한다. 공식 [결제 조회 계약](https://docs.tosspayments.com/reference)에 따르면 주문번호 조회는 `test_gsk`/`live_gsk` 키를 지원하지 않는다. HTTP 404는 실패 확정 근거가 아니다.

기존 GET은 저장된 결제 상태만 반환한다. 본인 회원 JWT·폐기 상태를 검증한 다음 아래 POST로 제공사 결과를 재조회할 수 있다.

```http
POST /api/v1/me/payments/orders/{orderId}/rechecks
Authorization: Bearer <accessToken>
```

본문은 없다. 응답은 기존 `{data: {paymentId, orderId, amount, currency, status}, requestId}`다. 정상 접수·대기·확인 필요 상태 모두 HTTP 200이며 `data.status`를 확인한다. 본인 소유권 확인 전에 외부 조회하지 않는다. 타인 결제는 404, 인증 누락은 401이다. GET의 의미를 변경하지 않는다.

자동 복구와 같은 60초 작업 점유 및 완료 후 10초 조회 간격을 사용한다. 점유 중이거나 간격 미도달이면 저장된 상태를 반환한다. `SUCCEEDED`/`FAILED`는 재조회하지 않는다. `REVIEW_REQUIRED`도 사용자가 명시적으로 재조회할 수 있지만, 결과가 `IN_PROGRESS`, 404, 네트워크 오류 등으로 불명확하면 확인 필요 상태를 유지한다. 자동 재시도 횟수를 초기화하거나 신규 승인·취소 요청을 보내지 않는다.

수동 재조회 의도는 `payment_recheck_audit`에 행위자·결제 ID·이전 상태와 함께 먼저 저장한다. 결과 반영·감사 완료·후속 Outbox는 같은 트랜잭션이다. 호출 중 종료되면 감사 기록은 미완료로 남고 점유 만료 후 다시 조회할 수 있다. 원문 키·응답·오류 메시지는 기록하지 않는다.

## 주문 결과 소비

`ORDER_PAYMENT_RESULTS_ENABLED=true`일 때 주문 서비스가 `pueblo.payment.results.v1`를 소비한다. `PAYMENT_EVENT_SECRET` HMAC, 생산자, 이벤트 종류, 주문·결제 ID, KRW 금액, 버전을 검증한다. 이벤트 ID/본문 해시와 주문의 결제 버전을 함께 사용한다. 결제 ID는 기존 `order_payment_attempt`와 일치해야 한다. 소비 기록·상태 전이·후속 명령 Outbox는 하나의 주문 DB 트랜잭션이다.

| 결과/조건 | 주문 처리 |
| --- | --- |
| `FAILED` (`ABORTED`/`EXPIRED` 식별·금액 대조 완료) | 예약이 살아 있거나 해제를 확인하지 못했으면 `ABORTING` → RELEASE 확인 후 `REJECTED`, reason `PAYMENT_FAILED` |
| 실패 시 이미 RELEASED/EXPIRED/REJECTED 확인 | 즉시 `REJECTED`, 재고 중복 증가 없음 |
| `SUCCEEDED`, 예약 유효 | `PAYMENT_CONFIRMING` → COMMIT 확인 후 `PAID` |
| 늦은 성공·기존 예약 해제/만료 | `REACQUIRING` → 이전 해제 확인 → 새 예약 ID/세대 2로 REACQUIRE → COMMIT 확인 후 `PAID` |
| 세대 2 재확보/확정 실패 | `REFUND_PENDING`, 전액 환불 명령 한 번 발행 |
| 전액 환불 성공 확인 | `REFUNDED` |
| 불확실한 승인 | 결제 가능 상태로 되돌리지 않음. 기존 주문의 만료·확인 필요 정책 유지 |
| 불확실한 환불 | `REFUND_PENDING`, reason `REFUND_REVIEW_REQUIRED` |

재확보는 한 세대만 추가한다. 당시 SKU·수량·주문 단가로 확보하며 가격 변경·품절도 실패로 처리한다. 이전 예약의 해제를 확인한 뒤 새 예약을 만들어 역순 해제가 새 예약을 취소하지 않게 한다. 신규 명령 ID들은 `order_inventory_command`에 기록해 늦게 도착한 이전 해제 결과도 안전하게 검증한다.

미결제 실패 주문은 `REJECTED`가 된 후 새 견적·새 멱등키로 새 주문을 생성할 수 있다. 같은 주문의 결제 ID를 교체하지 않는다. `PAID`는 결제 성공과 재고 COMMITTED가 모두 확인된 상태다. 이 작업은 출고 API를 추가하지 않는다.

## 재확보 실패 보상 환불

주문 Outbox는 `pueblo.payment.refunds.v1`, key=orderId로 `RefundRequested`를 보낸다. payload는 `refundId`, `paymentId`, `orderId`, `amount`, `currency`다. eventId=refundId, aggregateVersion=1, producer=order-service, causationId=paymentId다. `pueblo-payment-refund-signature` 헤더로 인증하며 주문·결제만 공유하는 `PAYMENT_EVENT_SECRET`을 사용한다.

`PAYMENT_REFUNDS_ENABLED=true`일 때 결제 서비스가 서명을 검증하고 자기 원장의 승인 성공·식별·금액을 대조하여 `refund_attempt`를 저장한다. 주문당/결제당 환불 한 건이며 같은 refundId 재전달은 추가 환불을 만들지 않는다. 이 원장은 인증된 자동 보상 명령의 감사 기록도 겸한다.

5초 주기로 최대 10건, 60초 점유와 10초 조회 간격을 사용한다. 최초 PENDING 의도를 PROCESSING으로 커밋한 후 트랜잭션 밖에서 전액 취소 API를 호출한다. 멱등키는 영속화한 refundId이고 부분 금액은 보내지 않는다. 불명확한 응답·프로세스 종료 이후에는 같은 paymentKey로 조회만 한다. 동일 결제 식별·원금·KRW·`CANCELED`·잔액 0·완료된 취소 합계=원금이 모두 일치해야 환불 성공이다. 20회 미확정은 `REVIEW_REQUIRED`로 보존한다.

결과는 기존 결제 결과 토픽으로 `RefundSucceeded`/`RefundUncertain`을 발행한다. payload는 기존 결제 결과 필드에 refundId를 추가하고 version은 환불 원장의 버전이다. 주문은 별도의 refund_version으로 검증한다. 환불 결과와 Outbox도 같은 로컬 트랜잭션이다.

이번 자동 환불은 **확정 재고가 없는 재확보 실패 보상**에 한정된다. 이미 RELEASED/EXPIRED/REJECTED인 재고를 RESTORE로 증가시키지 않는다. 출고 전 사용자 취소·확정 재고 환불 및 RESTORE 흐름은 별도 후속 범위다.

## 실행과 프론트 연결

```dotenv
ORDER_PAYMENT_RESULTS_ENABLED=true
PAYMENT_REFUNDS_ENABLED=true
```

기존 주문·결제·재고 활성화 및 `PAYMENT_EVENT_SECRET` 설정도 필요하다. Compose는 주문과 결제에 같은 결제 이벤트 키를 전달한다. Kubernetes에서는 각 runtime Secret에 동일 계약으로 주입한다. 새 기능은 기본 비활성이다. Kafka 토픽 `pueblo.payment.results.v1`, `pueblo.payment.refunds.v1` 및 기존 재고 토픽을 준비한다. 소비기는 DB 커밋 후 offset을 반영하고, 소비 중단 시 readiness를 DOWN으로 표시한다.

프론트의 **결제 결과 다시 확인** 버튼은 위 POST를 호출하고 이어서 `GET /api/v1/me/orders/{id}`를 조회한다. 주문 조회 버튼만 눌러서는 제공사를 재조회하지 않는다. Kafka 전파 전에 주문 상태가 잠시 이전 값일 수 있다. `PAID`만 주문 완료로 표시하고, `REJECTED`/`PAYMENT_FAILED` 또는 `REFUNDED` 이후 새 주문을 안내한다. `REFUND_PENDING`/`RECONCILIATION_REQUIRED`에는 새 결제를 유도하지 않는다.

기존 데이터는 삭제하지 않으며 기존 REVIEW_REQUIRED 결제를 일괄 초기화하지 않는다. V3/V4 결제 마이그레이션과 V4 주문 마이그레이션 적용 후 새 상태를 모르는 앱으로 단순 롤백하지 않는다.

## 제한

- `IN_PROGRESS`를 경과 시간만으로 실패로 바꾸지 않는다. 본인 재조회도 같은 결과면 화면의 확인 필요 안내가 유지되는 것이 맞다.
- 환불 의도 커밋 직후 실제 취소 호출 전에 종료된 경우 자동 복구는 조회만 하므로, 취소되지 않은 결제는 한도 초과 후 운영 확인 대상이 된다. 환불 재실행용 운영자 API는 아직 없다.
- 확인 필요 건의 수동 재조회는 결제 소유자에 한정된다. 다른 회원 건을 다루는 운영자 API, 웹훅, 출고·일반 취소/환불은 이 범위에 없다.
- 실제 토스 테스트 정상 승인과 주문·재고 확정은 아래 수동 검증으로 확인했다. 실제 토스 보상 환불·장애 시나리오 전체와 Kubernetes mTLS는 별도 검수가 필요하다. 기본 판매 비활성 정책을 유지한다.

## 검증 기록

2026-11-01 다음 명령으로 결제 단위 7건·통합 16건, 주문 단위 5건·통합 28건, 총 56건이 통과했다.

```sh
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew :payment-service:build :payment-service:integrationTest :order-service:build :order-service:integrationTest
```

결제 테스트는 실제 MySQL과 모의 회원·주문·토스 서버로 재조회 인증/소유권·간격 제한·404/미확정 유지·URL 경로 인코딩·환불 서명/동시성·응답 유실 후 재시작·확정 금액 불일치·한도 소진을 확인했다. 주문 테스트는 실제 MySQL·Kafka와 독립 주문/카탈로그 앱으로 실패 후 재고 해제·만료 후 실패 종료·재고 확정·새 세대 재확보·품절 보상 환불 명령·중복/역순·서명/금액 위조·미접수 결제의 불확실 이벤트·DB/Outbox 롤백을 확인했다.

결제 테스트의 주문 API와 주문 테스트의 결제 이벤트는 각각 모의 제공자다. 네 서비스와 실제 토스를 한 번에 연결한 종단 간 결제/환불 검수 결과로 해석하지 않는다. Compose 구문·문서 링크·`git diff --check`도 확인했다.

### 실제 토스 테스트 승인 확인

2026-11-01 17:39:46 KST 승인 건을 공식 토스 API로 읽기 재조회하여 HTTP 200, status `DONE`, amount/balanceAmount 98,000 KRW, mId `tgen_docs`를 확인했다. 반환된 orderId·paymentKey가 저장된 결제 원장과 일치했고, 로컬 결제 `SUCCEEDED`·주문 `PAID`·재고 `COMMITTED`(세대 1)도 대조했다. 자동 복구 조회 횟수는 0이었다.

실행 중 컨테이너는 테스트 키와 공식 토스 주소를 사용하며 mock 활성화 설정이 없었다. 최초 승인 응답 원문은 보관하지 않으므로 이 기록은 제공사 재조회 및 원장 대조 결과다. 문서용 테스트 상점의 승인으로, 실제 청구 또는 개별 상점의 운영 검수를 의미하지 않는다. 시크릿 키·paymentKey 원문은 기록하지 않는다.
