# 토스페이먼츠 테스트 승인·조회

`payment-service`가 주문 서비스의 결제 접수 계약을 거쳐 토스 승인·조회 API를 호출한다. 회원·주문은 HTTP로 조회하고 결제 원장은 자신의 MySQL에만 저장한다. 기준 문서 목록은 사용자가 추가한 [toss-llms.txt](toss-llms.txt)이며 아래 공식 원문을 확인했다.

- [승인·조회 API와 Payment 객체](https://docs.tosspayments.com/reference): `POST /v1/payments/confirm`, `GET /v1/payments/orders/{orderId}`.
- [인증·멱등키](https://docs.tosspayments.com/reference/using-api/authorization): 시크릿 키 뒤 콜론을 붙여 Base64한 Basic 인증, POST의 `Idempotency-Key`.
- [결제 흐름](https://docs.tosspayments.com/guides/v2/get-started/payment-flow): 프론트 인증 결과와 서버 승인을 구분한다.

## 현재 범위

**테스트 키 전용**이다. 기본 비활성이며 라이브 키는 시작 시 거부한다. 실제 판매용 결제 완료 흐름이 아니다.

구현: JWT·회원 폐기 상태 확인, 본인 주문 금액 확인, 승인 의도 저장, 주문 결제 시도 접수, 토스 승인, 본인 결제 상태 조회, 미확정 결과 조회 복구, 서명된 결과 Outbox.

후속 범위: 프론트 결제 SDK 화면, 주문 서비스의 결제 결과 소비, 재고 COMMIT/재확보, 자동 환불·취소, 웹훅, 운영자 재처리 API. 따라서 결제 `SUCCEEDED`가 주문 `PAID`나 출고 가능 상태로 자동 반영되지는 않는다. 주문은 기존 예약 만료 정책에 따라 확인 필요 상태로 전환될 수 있다.

초기 검증은 카드·간편결제로 진행한다. 가상계좌의 `WAITING_FOR_DEPOSIT`은 성공으로 처리하지 않으며 입금·환불 흐름은 아직 지원하지 않는다.

## 환경변수와 실행

기존 주문·재고 활성화 설정에 더해 `.env`를 설정한다. `application.yml`에서 참조하고 `application-local.yml`도 이를 상속한다. 실제 `.env`는 이 작업에서 수정하지 않았다.

```dotenv
ORDER_FLOW_ENABLED=true
ORDER_PAYMENT_PREPARATION_ENABLED=true
ORDER_PAYMENT_SERVICE_TOKEN=<주문과 결제에 공통 주입할 32자 이상 별도 무작위 비밀>
PAYMENT_FLOW_ENABLED=true
TOSS_SECRET_KEY=<개발자센터에서 발급한 테스트 시크릿 키>
PAYMENT_EVENT_SECRET=<결제 결과 서명용 32바이트 이상 별도 무작위 비밀>
PAYMENT_RECONCILIATION_ENABLED=true
```

`TOSS_SECRET_KEY`는 `test_sk_` 또는 `test_gsk_` 형식을 허용한다. JWT 키·서비스 간 인증 비밀·Kafka 이벤트 서명 비밀과 각각 분리한다. 프론트 클라이언트 키는 프론트 환경변수에 둔다. 주문서/결제창 제품에 맞는 같은 상점·테스트 환경의 클라이언트 키와 시크릿 키를 사용한다. 키를 Swagger 입력·Git·채팅에 붙여 넣지 않는다.

Compose는 `PAYMENT_MEMBER_URL=http://member-service:8080`, `PAYMENT_ORDER_URL=http://order-service:8080`을 주입한다. 호스트 실행은 localhost:8081/8083이 기본값이다. 공식 토스 주소는 `https://api.tosspayments.com`으로 고정하고 리다이렉트를 따르지 않는다. 장애 테스트용 별도 설정 `pueblo.payments.mock=true`는 loopback HTTP 서버만 허용한다. `.env.example`에는 모의 서버 설정을 노출하지 않았다.

```sh
docker compose up -d --build order-service payment-service
```

회원·카탈로그·MySQL·Redis·Kafka도 준비되어 있어야 한다. Swagger는 `http://localhost:8084/swagger-ui/index.html`이다. `Authorize`에 회원 accessToken을 입력한다. 이 API는 Bearer 전용·Stateless이며 CSRF를 비활성화했다. CORS는 `AUTH_ALLOWED_ORIGINS`, credentials는 false다.

## 프론트와 API 계약

1. 기존 견적·주문 API로 생성한 UUID를 토스 SDK의 `orderId`로 사용한다. 주문이 `PENDING_PAYMENT`인지 확인한다.
2. 주문의 서버 금액으로 프론트에서 테스트 결제 인증을 진행한다. 성공 URL 도착만으로 결제 완료를 표시하지 않는다.
3. 성공 URL에서 받은 paymentKey·orderId·amount를 아래 승인 API에 전달한다.
4. 202 또는 처리 중이면 본인 결제 조회로 상태를 확인한다. 200도 `data.status`를 확인한다.

`POST /api/v1/payments/confirm`

```json
{
  "orderId": "f3fdce2e-f85a-4faf-a6ae-c4b7f029171b",
  "paymentKey": "프론트의 토스 인증으로 발급받은 값",
  "amount": 39000
}
```

`GET /api/v1/me/payments/orders/{orderId}`

두 경로 모두 회원 JWT가 필요하다. 응답 data는 `paymentId`, `orderId`, `amount`, `currency`, `status`이며 paymentKey·시크릿·토스 전체 응답은 반환하지 않는다. 오류는 공통 한국어·영어 체계를 사용한다. 주요 409 코드는 `PAYMENT_TOTAL_MISMATCH`, `PAYMENT_REQUEST_CONFLICT`, `PAYMENT_ORDER_CONFLICT`다. 없는 주문과 타인 주문은 404다.

| 상태 | 의미 |
| --- | --- |
| PREPARING | 시도 저장, 주문 접수 확인 전 |
| PROCESSING | 승인 호출 의도가 DB에 커밋됨 |
| UNKNOWN | 결과 미확정, 제공사 조회 대상 |
| SUCCEEDED | 동일 주문·paymentKey·KRW·원금·잔액과 DONE 확인 |
| FAILED | 동일 식별값·금액의 ABORTED 또는 EXPIRED 확인 |
| REVIEW_REQUIRED | 응답 불일치·예상 밖 상태 또는 조회 한도 소진 |

현재 주문당 시도는 하나다. 동일 orderId·paymentKey·amount의 반복 요청은 기존 시도를 반환하며 새 승인을 보내지 않는다. 다른 paymentKey·금액으로 기존 시도를 덮어쓰지 않는다. 금액·소유권 검증은 결제 원장을 만들기 전에 수행한다.

## 정합성과 복구

외부 호출 전 로컬 트랜잭션으로 결제 ID·paymentKey·금액·처리 의도를 저장한다. 조건부 UPDATE로 60초 작업 점유를 획득하고 HTTP 호출은 트랜잭션 밖에서 수행한다. 다른 인스턴스는 유효한 점유가 있으면 저장된 상태를 반환한다. 오래된 작업은 점유자 ID가 달라지면 결과를 덮어쓰지 못한다.

토스 승인 멱등키는 영속화한 paymentId다. 요청 키를 바꿔 자동 재승인하지 않는다. HTTP 200만으로 성공을 판단하지 않고 Payment 객체의 식별값·금액·통화를 대조한다. 4xx·5xx·404 조회 결과·잘못된 JSON·네트워크 오류는 임의로 결제 실패로 확정하지 않는다.

복구 작업은 5초 주기, 최대 10건을 확인하고 다음 조회까지 10초를 둔다. 20회 이후에도 불명확하면 `REVIEW_REQUIRED`로 보존한다. 작업 점유가 만료된 PROCESSING과 PREPARING도 조회 대상이다. 회원 JWT를 DB에 보관하지 않고 주문 서비스의 서비스 전용 조회 API를 사용한다.

승인 의도 저장 후 실제 호출 전에 종료되었거나 주문 접수 응답만 유실된 경우에도 **복구는 조회만** 한다. 조회에서 발견되지 않으면 확인 필요 상태로 남을 수 있다. 이런 건을 자동으로 새 승인하는 기능과 운영자 재처리는 후속 범위다. 결과 확인 중인 결제에 새 주문·새 결제를 유도하지 않는다.

결과 변경과 Outbox는 한 DB 트랜잭션이다. 토픽은 `pueblo.payment.results.v1`, key는 주문 ID, 이벤트는 `PaymentSucceeded`, `PaymentFailed`, `PaymentUncertain`이며 `pueblo-payment-result-signature` HMAC 헤더를 붙인다. payload는 paymentId·orderId·amount·currency·status·version만 포함한다. 후속 소비자는 서명·주문 식별·금액·중복·버전을 검증해야 한다. 아직 주문 소비자는 없으므로 이벤트 발행이 주문 확정을 의미하지 않는다.

V2 마이그레이션은 결제 DB에 `payment_attempt`를 추가한다. 로그·응답에 원문 키·제공사 오류 본문을 넣지 않는다. paymentKey는 후속 조회와 일치 검사에 필요한 식별자로 결제 DB에 보관하며 운영 접근·보존 기간 정책은 실제 판매 전에 확정한다.

## 검증과 제한

```sh
./gradlew :api-web:test :payment-service:build :payment-service:integrationTest
```

실제 MySQL과 모의 회원·주문·토스 HTTP 서버를 사용한다. 정상 승인·중복·동시 접수·금액/소유권 위조·응답 유실·재시작·주문 기한 만료·응답 불일치·Outbox 실패 롤백·Swagger 인증 계약·서명·응답 크기·리다이렉트 차단을 검증한다.

2026-10-25 결과: 공통 단위 11건·결제 단위 6건·결제 통합 11건 통과. Compose 구문과 diff 공백 검사도 통과했다. CI에도 결제 통합 테스트를 추가했다.

실제 토스 테스트망의 승인, 실제 JWT 암호 검증, Kafka 브로커 전달과 주문·재고 최종 반영, 클러스터 mTLS는 이 테스트에서 검증하지 않는다. 실제 테스트망 검수에는 사용자의 테스트 키와 프론트 인증으로 얻은 paymentKey가 필요하다. SDK 인증 없이 임의 paymentKey로 성공한 것처럼 처리하지 않는다.
