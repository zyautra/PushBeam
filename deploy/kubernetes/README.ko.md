[English](README.md) | **한국어**

# Kubernetes 배포

`base/`는 어느 클러스터에서나 쓸 수 있게 만든 최소 구성이다. Server Pod 하나, PVC 하나, `ClusterIP` Service 하나만 있고, 네임스페이스, 이미지 레지스트리, 도메인, 인증서, 호스트 경로, 노드, UID/GID는 정하지 않는다.

클러스터마다 다른 값은 overlay에 둔다. `overlays/*-local/`은 git이 무시하므로 개인 설정을 그 안에 둔다.

## overlay가 정해야 하는 것

| 항목 | 예 |
| --- | --- |
| 네임스페이스, 이름 접두어 | `namespace: my-public`, `namePrefix: my-` |
| 이미지 | `images:`로 레지스트리 이미지 지정, 또는 노드에 직접 넣은 이미지와 `imagePullPolicy: Never` |
| 실행 UID/GID | 데이터 디렉터리를 소유한 전용 계정의 숫자 UID/GID (`runAsUser`, `runAsGroup`, `fsGroup`) |
| 저장소 | StorageClass 또는 직접 만든 PV |
| ConfigMap `pushbeam-config` | `firebase-project-number` |
| Secret `pushbeam-secrets` | `firebase-service-account.json`, `operator-token` |
| 외부 공개 | Gateway API `HTTPRoute` 또는 Ingress. **`/api/v1`만** 공개하고 `/health`는 공개하지 않는다 |
| 네트워크 정책 | Gateway/Ingress 데이터 플레인에서 오는 8080만 허용. 서버는 FCM과 Firebase API로 나가는 HTTPS가 필요하다 |

## Secret과 ConfigMap 만들기

저장소에 비밀 값을 넣지 않는다. 운영자 터미널에서 직접 만든다.

```bash
kubectl -n <namespace> create configmap pushbeam-config \
  --from-literal=firebase-project-number=<Firebase 프로젝트 번호>

kubectl -n <namespace> create secret generic pushbeam-secrets \
  --from-file=firebase-service-account.json=<서버 서비스 계정 키 경로> \
  --from-file=operator-token=<Operator Token 파일 경로>
```

직접 만든 ConfigMap과 Secret은 kustomize가 이름을 바꾸지 않으므로 overlay에서 `namePrefix`를 써도 위 이름 그대로 둔다.

## 이미지

```bash
docker build -f server/Dockerfile -t pushbeam-server:<version> .
```

레지스트리 없이 노드에 바로 넣을 때 (containerd):

```bash
docker save pushbeam-server:<version> | sudo ctr -n k8s.io images import -
```

## 적용과 확인

```bash
kubectl apply -k deploy/kubernetes/overlays/<overlay>
kubectl -n <namespace> rollout status deployment/<prefix>pushbeam-server
curl -fsS https://<도메인>/api/v1/admin/status -H "Authorization: Bearer <Operator Token>"
```
