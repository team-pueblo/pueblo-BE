# Istio mTLS 적용 기반

## 적용 상태

사용자 요청에 따라 단일 클러스터의 서비스 간 통신에 Istio mTLS를 포함했다. 저장소의 기본 배포 방식은 **sidecar**다. `pueblo` namespace의 자동 주입, 4개 앱의 ServiceAccount·Deployment·Service, namespace `STRICT` PeerAuthentication, 서비스별 `ISTIO_MUTUAL` DestinationRule을 작성했다.

실제 클러스터에는 적용하지 않았다. Istio 버전·Kubernetes 호환성·control plane 설치·인증서·게이트웨이·MySQL/Kafka 배치는 별도로 준비해야 한다. Ambient를 설치하는 경우 이 namespace 주입 설정과 혼용하지 말고 별도 overlay와 정책을 검토한다.

## 설정의 의미

- `PeerAuthentication/default`: `pueblo` namespace에서 sidecar가 보호하는 inbound 연결은 유효한 Istio mTLS가 있어야 한다. 포트별 평문 예외는 없다.
- `DestinationRule`: 4개 HTTP 서비스의 정확한 FQDN에만 `ISTIO_MUTUAL`을 적용한다. MySQL·Kafka까지 wildcard로 강제하지 않는다.
- ServiceAccount: 서비스마다 별도 신원을 사용한다. 기본 trust domain 기준 예시는 `cluster.local/ns/pueblo/sa/order-service`다.
- 앱은 8080 HTTP로 실행한다. Spring의 인증서 저장소나 `server.ssl` 설정을 추가하지 않는다.
- `holdApplicationUntilProxyStarts`: 프록시 준비 후 앱을 시작한다. probe rewrite를 활성화해 kubelet이 Istio 인증서 없이도 health probe를 수행하도록 한다.
- startup/liveness는 프로세스 상태, readiness는 앱·DB 상태를 본다. DB 장애를 liveness로 오인해 프로세스를 반복 재시작하지 않게 분리했다.
- 앱 종료 유예는 45초, Envoy drain은 30초, 기존 Spring graceful shutdown 단계는 20초다. 실제 처리 중단·복구를 검증한 후 조정한다.
- 앱 1개당 요청 100m CPU / 256Mi, 제한 1 CPU / 512Mi, proxy 요청 50m / 64Mi, 제한 500m / 192Mi는 초기 예산이다. Istiod·DB·Kafka·Kubernetes 자원은 여기에 포함되지 않는다.

정책이 있어도 sidecar가 실제 주입되지 않으면 해당 앱의 통신을 보호하지 못한다. namespace 라벨만 확인하고 검증을 끝내지 않는다. revision 기반 설치를 사용하는 클러스터는 `istio-injection`과 `istio.io/rev` 라벨을 혼용하지 않고 실제 revision에 맞게 namespace를 조정한다.

## 로컬 개발과의 관계

기존 Compose·호스트 `bootRun`에는 Istio를 적용하지 않는다. 로컬 DB·Kafka의 평문 개발 설정은 그대로이며, 이는 클러스터의 STRICT 정책과 별개다. 외부 PG HTTPS는 Java 클라이언트가 계속 수행한다. 메시지·DB 정합성 검증을 mTLS로 대체하지 않는다.

## 렌더링과 정적 검사

kubectl의 Kustomize와 Python 3 + PyYAML이 필요하다.

```sh
kubectl kustomize infra/k8s/base
python3 scripts/validate-kubernetes.py
```

18개 리소스를 렌더링하고 namespace·STRICT 정책·4개 서비스 신원·프록시 주입·probe·TLS 목적지의 정적 조건을 확인한다. API 서버에 접속하지 않는다. 이 검사는 Istio CRD schema 검증·실제 인증서 교환·서비스 통신을 대체하지 않는다.

## 이미지 준비

```sh
./gradlew build
docker build --build-arg SERVICE=member-service -t pueblo/member-service:dev .
docker build --build-arg SERVICE=catalog-service -t pueblo/catalog-service:dev .
docker build --build-arg SERVICE=order-service -t pueblo/order-service:dev .
docker build --build-arg SERVICE=payment-service -t pueblo/payment-service:dev .
```

공통 Dockerfile은 빌드된 서비스 JAR 하나만 복사하고 비 root 사용자로 실행한다. `.dockerignore`는 서비스 JAR과 Dockerfile만 빌드 context에 포함한다. 각 클러스터 노드가 이미지를 가져올 수 있도록 registry·이미지 이름·tag/digest를 overlay에서 변경하거나 개발 클러스터에 이미지를 적재한다. `:dev`는 개발용 이름이며 운영 이미지 고정 정책은 별도다.

## 배포 전 준비

1. 대상 클러스터·context를 확인하고 호환되는 Istio sidecar control plane과 CRD·주입 webhook을 준비한다.
2. MySQL·Kafka의 실제 endpoint와 각 서비스 DB·계정·영속 볼륨을 준비한다. `localhost`는 앱 Pod 자신이므로 기존 Compose 호스트 주소를 그대로 사용하지 않는다.
3. 실제 context를 명시해 namespace를 준비하고 서비스별 runtime Secret을 만든다. Secret 값은 저장소·셸 기록에 넣지 않는다.

```sh
kubectl --context <대상-context> apply -f infra/k8s/base/namespace.yaml
kubectl --context <대상-context> -n pueblo create secret generic member-service-runtime --from-env-file=/secure/path/member.env
```

같은 방법으로 `catalog-service-runtime`, `order-service-runtime`, `payment-service-runtime`을 만든다. 각 파일에는 해당 서비스 전용 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`를 넣는다. DB URL의 UTC 설정도 유지한다. 파일에 `SPRING_PROFILES_ACTIVE=local`을 넣지 않는다. Secret이 없으면 앱을 기본 자격 증명으로 실행하지 않고 시작에 실패하도록 했다.

4. 실제 설치 버전의 istioctl로 설정을 분석하고 대상 클러스터에서 server dry-run을 수행한다.

```sh
kubectl kustomize infra/k8s/base > /tmp/pueblo-k8s.yaml
istioctl --context <대상-context> analyze --use-kube=false /tmp/pueblo-k8s.yaml
kubectl --context <대상-context> apply --dry-run=server -k infra/k8s/base
```

5. 전제 조건과 분석 결과를 확인한 뒤 배포한다.

```sh
kubectl --context <대상-context> apply -k infra/k8s/base
kubectl --context <대상-context> -n pueblo get pods
istioctl --context <대상-context> proxy-status
```

외부 게이트웨이·도메인·TLS Secret·라우트는 포함하지 않았다. 앱 Service는 모두 ClusterIP다. 외부 접근은 gateway의 실제 신원과 라우트 계약을 정한 후 추가한다.

## 클러스터에서 필요한 검증

- 모든 앱 Pod에 실제 `istio-proxy`가 있고 두 컨테이너가 Ready인지 확인한다.
- 프록시가 각 앱의 전용 ServiceAccount 신원을 사용하며 인증서·설정이 동기화되는지 확인한다.
- mesh 내부 호출자가 다른 앱의 `/actuator/health/readiness`에 접근할 때 200인지 확인한다.
- sidecar가 없는 별도 테스트 Pod의 같은 요청은 평문으로 성공하지 않는지 확인한다. Spring의 업무 API 401은 mTLS 검증 성공의 증거가 아니므로 health 경로를 사용한다.
- `istioctl proxy-config clusters`로 수신 서비스 FQDN의 TLS transport socket을 확인한다.
- STRICT 활성 상태에서 HTTP health probe가 통과하고 앱 재시작·proxy 재시작 후에도 복구하는지 확인한다.
- DB·Kafka의 별도 접근/인증과 PG·메일 egress 정책을 확인한다. mesh egress가 REGISTRY_ONLY면 실제 외부 도메인의 ServiceEntry가 추가로 필요하다.

서비스별 API 계약이 정해지면 AuthorizationPolicy를 추가한다. mTLS 인증만으로 모든 mesh 워크로드에 업무 접근을 허용하거나, 프록시 헤더를 회원 인증으로 신뢰하지 않는다. 현재 Spring의 업무 경로 차단·향후 JWT·운영자·소유권 검사는 유지한다.

## 저장소 검증 기록 — 2026-10-24

- Kustomize: 18개 리소스 렌더링, `scripts/validate-kubernetes.py` 정적 조건 통과
- 공식 릴리스 체크섬을 확인한 `istioctl 1.31.3`: `analyze --use-kube=false --failure-threshold Warning /tmp/pueblo-k8s.yaml` 경고·오류 없음
- `docker build --build-arg SERVICE=member-service -t pueblo/member-service:dev .`: 공통 Dockerfile의 회원 서비스 이미지 빌드 성공
- 문서 링크·`git diff --check` 통과

실제 클러스터 server dry-run·배포·프록시 주입·mTLS handshake·평문 차단은 아직 검증하지 않았다. 분석 CLI 버전은 설치할 Istio 버전을 확정한 것이 아니다.

## 공식 근거

- [Sidecar 자동 주입](https://istio.io/latest/docs/setup/additional-setup/sidecar-injection/)
- [PeerAuthentication과 STRICT](https://istio.io/latest/docs/tasks/security/authentication/authn-policy/)
- [DestinationRule TLS 설정](https://istio.io/latest/docs/reference/config/networking/destination-rule/)
- [Health probe rewrite](https://istio.io/latest/docs/ops/configuration/mesh/app-health-check/)

## 회원 인증 키

회원 Deployment는 `member-auth-keys` Secret의 `mail.key`를 읽기 전용으로 마운트하고, `member-service-runtime` Secret에서 HS256 서명용 `JWT_TOKEN`을 환경변수로 받는다. 이 키들은 Istio 인증서와 별개다. 생성/SMTP/CSRF 설정은 [회원 인증 문서](member-auth.md)를 따른다. Secret을 준비하지 않으면 Pod가 시작되지 않는다.

회원 서비스 runtime Secret에는 REDIS_HOST, REDIS_PORT, REDIS_USERNAME, REDIS_PASSWORD, REDIS_SSL, AUTH_REDIS_NAMESPACE도 필요하다. Redis TLS는 Istio 앱 간 mTLS와 별개이며 기본 활성화다. Redis 워크로드의 실제 클러스터 배치는 별도 준비한다.
