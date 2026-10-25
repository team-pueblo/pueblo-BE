# 상품 카탈로그와 데이터 가져오기

상품·브랜드·카테고리·SKU 기반과 공개 조회 API를 구현했다. 재고 예약·해제·주문 연결·운영자 상품 편집 API는 후속 작업이다. 현재 판매가는 가져오기 시점의 값이며 할인 기간 스케줄링은 포함하지 않는다.

## 공개 API

기본 주소는 `http://localhost:8082/api/v1`, Swagger는 `http://localhost:8082/swagger-ui/index.html`이다. 인증 없이 GET으로 조회하며 응답은 공통 `{data, requestId}` 형식이다.

| 경로 | 계약 |
| --- | --- |
| `/products` | `collection`, `categoryId`, `brandSlug`, `onSale`, `q`, `sort`, `page`, `size` |
| `/products/{id}` | 상품 설명·이미지·고시/배송/반품 안내·컬렉션·SKU 옵션·현재 가격/재고·구매 상한 |
| `/brands` | `q`, `page`, `size`로 이름·한글 이름 검색 |
| `/brands/{slug}` | 활성 브랜드 상세. 상품 없는 브랜드도 200 |
| `/categories` | 상품 종류 목록. 초기 모델은 평면 분류 |

- `collection`: `men`, `women`, `lifestyle`. 유니섹스는 여러 컬렉션에 연결한다.
- `categoryId`는 카테고리 API에서 받은 UUID다. `sort`는 `newest`, `priceAsc`, `priceDesc`; 동일 값에는 ID 정렬을 추가한다.
- `page` 기본 1·최대 100000, `size` 기본 20·최대 100. 목록 응답은 `items`, `page`, `size`, `total`이다.
- 상품명·브랜드명·한글 브랜드명을 MySQL LIKE로 검색한다. 검색어는 NFKC·앞뒤 공백·연속 공백을 정규화하며 `%`, `_`는 와일드카드가 아닌 문자로 검색한다. 최대 200자다.
- `q` 생략은 일반 목록, 명시적으로 빈 `q`는 빈 결과다. 프론트 검색 화면도 검색 전에는 요청하지 않거나 빈 `q`를 보내야 한다.
- 공개 상태 `PUBLISHED`와 활성 브랜드의 상품만 조회한다. `DRAFT`, `DISCONTINUED`, `DELETED` 상세는 404다. 상품의 `saleStatus`는 구매 가능한 SKU가 있으면 `ON_SALE`, 없으면 `SOLD_OUT`이다.
- 품절 상품은 목록에서 제외하지 않는다. `onSale=true`는 현재 판매가가 정가보다 낮은 상품이다.
- 가격은 KRW 정수다. SKU의 `price`는 override 또는 상품 판매가이며 `availableQuantity`는 현재 재고다. 구매 시 다시 검사해야 한다. 구매 상한은 최대 9다.
- SKU 비활성 옵션은 `UNAVAILABLE`, 가용 수량은 0으로 응답한다. 옵션 없는 SKU도 빈 `options` 객체로 표현할 수 있다.
- CORS는 `CATALOG_ALLOWED_ORIGINS`에 명시한 출처의 GET/OPTIONS만 허용한다. 공개 조회는 인증 쿠키를 요구하지 않는다. 쓰기·관리 API는 개방하지 않았다.

## 개발용 샘플

샘플은 가상의 브랜드·상품과 직접 작성한 SVG다. 실제 판매 상품이나 판매 계약을 의미하지 않는다. Flyway에는 스키마만 포함하며 샘플은 자동 삽입하지 않는다.

```sh
./gradlew :catalog-service:bootJar --no-daemon
docker compose up -d --no-deps --build --wait catalog-service
./scripts/import-catalog.sh demo
```

기본 샘플에는 공개 상품 5개와 비공개 초안 1개, 품절·다중 컬렉션·옵션별 가격을 포함한다. DB에는 서비스별 계정으로 접속한다. `demo` 가져오기도 운영 환경에 자동 실행하지 않는다.

## 자체 JSON 가져오기 계약


`catalog-service/src/main/resources/catalog/demo.json`이 전체 예시다. 최상위 키는 `brands`, `categories`, `products`다. 브랜드·카테고리는 slug를 사용하며 상품은 source+externalId, SKU는 product+externalId로 안정적인 UUID를 만든다. 금액은 소수점 없는 정수만 받는다. 공개 상품에는 이미지·컬렉션·최소 하나의 SKU가 필요하다.

한 파일은 하나의 트랜잭션으로 처리한다. 잘못된 외래키·중복 옵션 조합·음수 재고·잘못된 금액은 전체 롤백한다. 재가져오기는 메타데이터를 갱신하지만 기존 SKU 재고는 덮어쓰지 않는다. 파일에서 빠진 기존 SKU는 비활성화한다. 파일에 없는 상품 전체를 삭제하지 않는다. 새로운 externalId가 기존 옵션 조합과 충돌하면 기존 SKU를 바꾸지 않고 실패한다.

가져오기는 `spring.main.web-application-type=none`의 일회성 애플리케이션으로 실행한다. HTTP 쓰기 엔드포인트는 없으며 일반 웹 서버에 import 옵션을 넣으면 시작을 거부한다. `scripts/import-catalog.sh`는 해당 실행 옵션과 읽기 전용 파일 마운트를 지정한다.

## 검증

```sh
./gradlew :catalog-service:build :catalog-service:integrationTest --no-daemon
```

통합 검증은 실제 MySQL에서 공개/품절 상태, 필터 조합, 한글·영문 검색, 빈 검색, 안정적 페이지네이션, SKU 응답, 가져오기 재시도·재고 보존·전체 롤백과 공개 쓰기 차단을 검사한다. 조회 성능은 실제 부하로 아직 측정하지 않았으며 부분 문자열 검색의 대량 데이터 성능은 후속 측정 대상이다.
