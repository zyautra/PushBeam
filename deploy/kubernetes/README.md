**English** | [한국어](README.ko.md)

# Kubernetes deployment

`base/` is a minimal configuration that works on any cluster: one Server Pod, one PVC and one `ClusterIP` Service. It does not choose a namespace, image registry, domain, certificate, host path, node, or UID/GID.

Cluster-specific values belong in an overlay. `overlays/*-local/` is git-ignored, so keep personal settings there.

## What an overlay must decide

| Item | Example |
| --- | --- |
| Namespace, name prefix | `namespace: my-public`, `namePrefix: my-` |
| Image | A registry image via `images:`, or an image imported on the node with `imagePullPolicy: Never` |
| Runtime UID/GID | The numeric UID/GID of a dedicated account that owns the data directory (`runAsUser`, `runAsGroup`, `fsGroup`) |
| Storage | A StorageClass or a pre-created PV |
| ConfigMap `pushbeam-config` | `firebase-project-number` |
| Secret `pushbeam-secrets` | `firebase-service-account.json`, `operator-token` |
| Exposure | A Gateway API `HTTPRoute` or an Ingress. Expose **only `/api/v1`**, never `/health` |
| Network policy | Allow port 8080 only from the Gateway/Ingress data plane. The server needs outbound HTTPS to FCM and Firebase APIs |

## Creating the Secret and ConfigMap

Never commit secrets. Create them from an operator terminal:

```bash
kubectl -n <namespace> create configmap pushbeam-config \
  --from-literal=firebase-project-number=<Firebase project number>

kubectl -n <namespace> create secret generic pushbeam-secrets \
  --from-file=firebase-service-account.json=<server service account key> \
  --from-file=operator-token=<Operator Token file>
```

Kustomize does not rename resources it does not manage, so keep these names even when the overlay uses `namePrefix`.

## Image

```bash
docker build -f server/Dockerfile -t pushbeam-server:<version> .
```

To load it directly on a node without a registry (containerd):

```bash
docker save pushbeam-server:<version> | sudo ctr -n k8s.io images import -
```

Make sure the image is listed on the node before applying: the Deployment uses the `Recreate` strategy, so a missing image means downtime.

## Apply and verify

```bash
kubectl apply -k deploy/kubernetes/overlays/<overlay>
kubectl -n <namespace> rollout status deployment/<prefix>pushbeam-server
curl -fsS https://<domain>/api/v1/admin/status -H "Authorization: Bearer <Operator Token>"
```
