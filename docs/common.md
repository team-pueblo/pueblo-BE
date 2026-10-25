# 공통 오류·응답·보안·로깅

Gradle 모듈은 기존 `api-web`을 유지하고, 구현 패키지를 `com.pueblo.common`으로 분리했다. 회원·상품·주문·결제 서비스가 같은 전송 계층을 사용한다. 도메인 모델·서비스 DB·리프레시 회전 업무 로직은 공통 모듈로 옮기지 않는다.

```text
api-web/src/main/java/com/pueblo/common/
├── exception/
│   ├── ErrorCode                    오류 코드·HTTP 상태·메시지 키 인터페이스
│   ├── CommonErrorCode              공통 입력·HTTP·인증 오류
│   ├── BusinessException            ErrorCode와 선택적 Retry-After
│   └── GlobalExceptionHandler       MVC 예외 → 오류 응답
├── response/
│   ├── ApiResponse                  기존 성공 응답
│   ├── ApiErrorResponse             error + requestId
│   ├── ApiErrorDetail               field + code + message
│   ├── ApiErrorResponseFactory      DTO 생성·상세 오류 최대 100개
│   └── ApiMessageResolver           Accept-Language·메시지 키 해석
├── security/
│   ├── ApiAuthenticationEntryPoint  미인증 401 / 인증 서비스 장애 503
│   ├── ApiAccessDeniedHandler       접근 거부 / CSRF 오류
│   ├── ApiSecurityErrorWriter       필터 단계 오류 JSON 작성
│   ├── ApiCorsProcessor             CORS 거부 응답
│   ├── RequestBodyLimitFilter       65,536바이트 입력 제한
│   ├── SecurityEventLogger          요청 ID·오류 종류·HTTP 상태 기록
│   ├── SensitiveDataMasker          알려진 민감 필드·형식 마스킹
│   ├── SensitiveMessageConverter   Logback 메시지 출력 마스킹
│   ├── SensitiveThrowableConverter 예외 메시지 제외·스택 위치 유지
│   ├── validation/ExternalUrlPolicy
│   └── redis/
│       ├── SecurityKeyPurpose      현재 REFRESH_SESSION 용도
│       ├── SecurityKeyNamespace    환경·서비스별 키 생성
│       └── SecurityTtlPolicy       만료 상한·영구 키 거부 정책
└── logging/
    ├── RequestIdFilter             서버 생성 UUID·응답 헤더·MDC
    └── RequestLoggingFilter        라우트 패턴·상태·처리 시간
```

자동 등록은 기존 `com.pueblo.api.ApiWebAutoConfiguration`이 맡는다. Swagger 설정도 기존 자동 설정 클래스를 유지한다. `com.pueblo.api.ApiException`, enum `ErrorCode`, `ApiErrors`, `ApiExceptionHandler`는 제거했고 저장소 안의 모든 사용처를 새 패키지로 바꿨다.

## 오류 확장과 응답 호환

`member-service`의 `MemberErrorCode`, `order-service`의 `OrderErrorCode`가 `ErrorCode`를 구현한다. 회원 가입·리프레시·주문 견적 오류를 공통 enum에 추가하지 않는다. 새 오류는 해당 서비스 enum에 코드·상태·메시지 키를 정의하고 번역을 등록한다. 기존 번역 카탈로그 `api-web/src/main/resources/messages/errors*.properties`는 호환을 위해 유지한다.

```java
throw new BusinessException(OrderErrorCode.PRICE_CHANGED);
throw new BusinessException(CommonErrorCode.RATE_LIMITED, 30L);
```

기존 `error.code`, `error.message`, `requestId`, HTTP 상태를 유지한다. Bean Validation·필수 파라미터 누락·자료형 오류에는 선택적인 `error.details`를 추가한다.

```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "요청 값을 확인해 주세요.",
    "details": [
      {"field": "email", "code": "Email", "message": "이메일 형식을 확인해 주세요."}
    ]
  },
  "requestId": "서버가 생성한 UUID"
}
```

검증 오류의 거부된 입력값, 검증기의 원문 메시지, 예외 메시지·스택은 응답하지 않는다. 상세 오류가 없으면 `details` 필드를 생략한다. 서비스의 수동 업무 검증은 기존 코드/메시지 오류만 반환한다. 잘못된 JSON은 파싱 원문을 노출하지 않고 `INVALID_INPUT`으로 처리한다.

`Accept-Language`의 ko/en과 가중치를 적용한다. 지원하지 않는 언어·잘못된 헤더·생략은 한국어다. `Content-Language`, `Vary: Accept-Language`, `Cache-Control: no-store`를 설정한다. 번역 키가 없으면 내부 키 대신 일반 오류 안내를 사용한다. `Retry-After`는 양의 초 값만 허용한다.

MVC와 Spring Security·CORS·본문 제한 오류 모두 같은 Factory/Resolver로 응답한다. 회원의 쿠키·CSRF 정책, 주문의 Bearer 전용 Stateless 정책과 기존 허용 경로는 변경하지 않는다.

## 로그와 민감정보

`RequestIdFilter`는 클라이언트가 보낸 요청 ID를 신뢰하지 않고 UUID를 만든다. `X-Request-ID`, 오류 응답, MDC를 연결하고 요청 종료 시 MDC를 정리한다. 요청 로그는 HTTP 메서드, **라우트 패턴**(예: `/api/v1/me/orders/{id}`), 상태, 경과 시간을 기록한다. 매핑 전 거부·404는 `unmatched`다. 실제 경로 변수, query, body, Authorization/Cookie, 회원 이메일·주소를 요청 로그에 넣지 않는다. health probe는 요청 로그에서 제외한다.

공통 `logback-spring.xml`이 네 서비스에 적용되며 `%safeMsg`, `%safeEx`를 사용한다. 메시지는 비밀번호·토큰·인증번호·쿠키·이메일·전화번호·URL 자격 정보 등 알려진 필드/형식을 마스킹하고 개행을 정리한다. 예외는 원인·suppressed를 포함해 **메시지를 전부 생략**하고 예외 종류와 제한된 스택 위치를 보존한다. 메시지는 Java 문자열 기준 16,384자로 자르고 예외 깊이는 8, 예외별 프레임은 40개로 제한한다.

마스킹은 임의의 자유문에서 모든 개인정보를 식별하는 기능이 아니다. 애초에 원문 요청·DB 값·주소를 로깅하지 않는 원칙이 우선이다. 추가 appender나 별도 `logging.config`를 사용하면 해당 출력에도 이 변환기를 적용해야 한다. 보안 이벤트는 401/403/429·인증 저장소 장애를 기록하며 토큰·사용자 입력은 포함하지 않는다.

## Redis와 URL 검증

`SecurityKeyNamespace`는 `pueblo:<환경>:<서비스>:security:v1` 형식과 UUID 식별자를 검사한다. 기존 회원 리프레시 키 `pueblo:<환경>:member:security:v1:refresh:{<sessionId>}`를 유지해 Redis 마이그레이션이나 로그아웃이 필요하지 않다. 저장은 기존 회원 `RedisRefreshTokens`, 회전·재사용 탐지는 기존 Lua가 맡는다. Redis 연결/자격을 다른 서비스에 공유하지 않는다.

`SecurityTtlPolicy`는 현재보다 미래이면서 최대 14일인 절대 만료만 허용한다. 갱신은 TTL을 연장하지 않으며 TTL이 없거나 만료된 키를 활성 세션으로 취급하지 않는다. URL 검증은 기존 HTTPS 기본·명시적 localhost HTTP 허용 정책을 `security/validation`으로 이동했다. 서버 설정 URL 검증이며 사용자 입력 URL을 가져오는 범용 SSRF 방어기를 의미하지 않는다.

## 검증

```sh
./gradlew test :member-service:integrationTest :order-service:integrationTest :catalog-service:integrationTest --no-daemon
```

공통 테스트는 도메인 오류 확장, 한영 응답·상세 오류, Security/CSRF/본문 제한, Retry-After, 내부 예외 비노출, 안전한 요청 로그, 실제 Logback 변환기, Redis 키 호환·TTL을 확인한다. 실제 MySQL·Redis·Kafka 통합 테스트에서 기존 인증·주문·재고 동작도 확인한다. 실제 SMTP·사용자 로컬 DB·운영 데이터는 테스트 대상이 아니다.

2026-10-25 검증: 전체 빌드와 단위 23건·통합 50건 통과. 공통 모듈 테스트는 기존 URL/문서 설정 검증을 포함한 11건이다. 네 서비스의 bootJar에 공통 클래스와 Logback 설정이 포함되는 것도 확인했다.
