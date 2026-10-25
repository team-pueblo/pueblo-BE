# pueblo-BE

Java 21 / Spring Boot 기반 MSA e-Commerce입니다. 회원·상품/재고·주문·결제 서비스를 Gradle 멀티모듈로 관리합니다.

## 로컬 실행

JDK 21과 Docker Compose v2를 준비합니다.

```sh
./scripts/start-local.sh
```

서비스 4개와 MySQL·Redis·Kafka를 Docker Compose로 함께 실행합니다. 포트·DB 계정·나머지 실행 명령은 [로컬 개발 환경](docs/local-development.md)을 참고하세요. 회원 가입·이메일 인증·JWT 갱신·로그아웃·비밀번호 재설정 API를 구현했습니다. 상품·브랜드·카테고리·SKU 조회와 오프라인 데이터 가져오기도 구현했습니다. 주문·결제·재고 예약 API는 후속 단계입니다. Outbox 폴링 발행과 소비 중복 방지 기반은 구현했으며, 도메인별 이벤트 연결은 후속 단계입니다.

## 설계 문서

- [SMTP 환경변수 예시](.env.example): `.env`에 계정 값을 넣고 [application-local.yml](member-service/src/main/resources/application-local.yml)에서 참조

- [Swagger 접속·사용법](docs/swagger.md): 서비스별 UI 주소, JWT·CSRF 인증

- [보안 정책과 검사](docs/security.md): Stateless·Redis Rotation·한영 ErrorCode·보안 CI

- [회원 인증 API와 실행 설정](docs/member-auth.md): JWT·쿠키·CSRF·이메일·MySQL 통합 검증

- [백엔드 서비스 아키텍처](docs/backend-architecture.md): MSA 구성도와 Istio mTLS 경계
- [Istio 설정과 배포 전제](docs/istio-mtls.md): STRICT 정책·서비스별 신원·정적 검증

- [메시징 구현과 검증](docs/messaging.md): Outbox 선점·재시도, 소비 트랜잭션과 통합 테스트

- [백엔드 설계 협의 기록](docs/backend-design.md): 인터뷰 결정 사항, MSA 데이터 소유권, 주문·결제·재고 상태 전이, Kafka 이벤트 계약 초안, 장애 검증 계획과 미정 항목

- [상품 카탈로그 API와 데이터 가져오기](docs/catalog.md)
