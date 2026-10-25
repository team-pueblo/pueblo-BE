# Swagger UI


[주문 결제 시도 접수](order-payment-preparation.md)는 서비스 전용 내부 API로 Swagger에서 숨긴다. 호출 계약·인증·환경변수는 해당 문서를 따른다.
로컬 컨테이너에서 서비스별 Swagger UI와 OpenAPI JSON을 제공한다.

| 서비스 | Swagger UI | OpenAPI JSON |
| --- | --- | --- |
| 회원 | http://localhost:8081/swagger-ui/index.html | http://localhost:8081/v3/api-docs |
| 상품·재고 | http://localhost:8082/swagger-ui/index.html | http://localhost:8082/v3/api-docs |
| 주문 | http://localhost:8083/swagger-ui/index.html | http://localhost:8083/v3/api-docs |
| 결제 | http://localhost:8084/swagger-ui/index.html | http://localhost:8084/v3/api-docs |

`/swagger-ui.html`도 UI로 이동한다. 회원 서비스는 인증 API 10개, 상품 서비스는 공개 조회 API 5개를 표시한다. 주문 서비스는 `ORDER_FLOW_ENABLED=true`일 때 견적·접수·목록·상세·취소 API 5개를 표시한다. [주문 설정](orders.md)을 참고한다. 결제 서비스는 업무 API 구현 전으로 목록이 비어 있다. 상품 계약과 데이터 준비는 [카탈로그 문서](catalog.md)를 참고한다.

재고 변경은 인증된 내부 Kafka 명령으로 처리하며 Swagger용 HTTP 쓰기 API는 제공하지 않는다. [재고 계약](inventory.md)을 참고한다.

## 회원 API 실행

1. 회원 Swagger는 POST 요청 전에 CSRF 토큰을 자동 조회하고 `X-XSRF-TOKEN` 헤더에 넣는다. 브라우저는 HttpOnly CSRF 쿠키를 함께 전송한다.
2. Authorize에는 `bearerAuth`만 표시하며 CSRF 수동 입력란은 제공하지 않는다. 별도 클라이언트는 `GET /api/v1/auth/csrf` 응답의 `data.token`을 헤더에 넣고 응답 쿠키도 유지해야 한다. 누락·불일치 시 `CSRF_INVALID` 오류를 반환한다.
3. 가입·이메일 인증 후 로그인한다. 기존 인증된 회원이 있다면 바로 로그인할 수 있다. 가입 필수 동의는 TERMS/v1, PRIVACY/v1 각각 agreed=true다.
4. 로그인 응답의 `data.accessToken`만 Authorize → `bearerAuth`에 입력하고 `/api/v1/me`를 실행한다. `Bearer ` 접두사는 붙이지 않는다.
5. refresh·logout은 로그인 시 설정된 HttpOnly 쿠키를 자동 사용한다. 갱신 후 새로운 accessToken으로 bearerAuth 값을 갱신한다.

토큰은 페이지 메모리에만 보관하며 Swagger의 인증 정보 영구 저장은 끈다. 새로고침·만료 후에는 다시 입력한다. 쿠키·CSRF 정책을 우회하거나 HttpOnly를 제거하지 않았다. 로컬 메일은 SMTP를 사용한다. 전체 가입 흐름에는 [회원 인증 문서](member-auth.md)의 `.env` SMTP 설정을 적용해야 한다.

## 활성화와 보안

Spring Boot 3.5 호환 계열인 Springdoc 2.8.17을 사용한다. [공식 호환 표](https://springdoc.org/v2/faq.html)를 기준으로 선택했다.

기본 설정은 비활성화다. 로컬 Compose는 `PUEBLO_DOCS_ENABLED=true`, 호스트 local 프로필은 동일 문서 설정을 활성화한다. 운영에서는 이 값을 제공하지 않으며 Swagger 컨트롤러와 문서 접근용 보안 체인을 활성화하지 않는다.

문서 GET 경로에만 별도 보안 체인을 적용한다. Swagger 화면에 필요한 동일 출처 스크립트·스타일·API 연결만 CSP로 허용하며 업무 API에는 기존의 엄격한 CSP를 유지한다. 외부 Petstore·검증 서버를 사용하지 않고 OpenAPI 서버 주소는 `/`로 고정한다. JWT·CSRF·CORS·권한 정책은 그대로 유지한다.

## 실행

```sh
./scripts/start-local.sh
```

Swagger 설정이나 소스를 수정했다면 JAR와 이미지를 함께 다시 빌드해야 한다. 실행 스크립트가 두 작업을 수행한다. 인프라와 데이터 볼륨을 삭제할 필요는 없다.
