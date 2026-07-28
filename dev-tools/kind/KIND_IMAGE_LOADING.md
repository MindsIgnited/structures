# KinD Image Loading Strategy

KinD cluster nodes run containerd with normal outbound network access, so they
**pull public registry images directly** (docker.elastic.co, docker.io, ghcr.io,
etc.) exactly like any other Kubernetes cluster. No manual loading is needed for
published images — the Helm charts reference the image/tag in their values files
and the nodes pull with `imagePullPolicy: IfNotPresent`.

`kind load` is only for images that exist **solely in the host Docker daemon**
— i.e. locally built images that no registry serves:

| Image | Source | How it reaches the cluster |
|-------|--------|---------------------------|
| `mindsignited/structures-server` (local build) | `./gradlew :structures-server:bootBuildImage` | `kind load docker-image` + `pullPolicy: Never` |
| `mindsignited/structures-migration` (local build) | `bootBuildImage` | `kind load docker-image` + `pullPolicy: Never` |
| Elasticsearch, Keycloak, PostgreSQL, ingress-nginx, cert-manager | Public registries | Pulled by the nodes directly |

## Loading a locally built image

```bash
./gradlew :structures-server:bootBuildImage
kind load docker-image mindsignited/structures-server:<version> --name structures-cluster
```

The structures-server values file sets `pullPolicy: Never` so the node uses the
loaded image and never tries to pull a tag that only exists locally.

## History: why Elasticsearch used to be pre-loaded

Earlier versions of these scripts pre-pulled the Elasticsearch image on the
host, re-tagged it as `localhost/elasticsearch:<version>`, and pushed it into
the nodes with `kind load docker-image`. That existed to work around
multi-platform manifest issues (`ctr: content digest ... not found`) in the
`kind load` path — but `kind load` itself was the only reason the image needed
to touch the host Docker daemon at all. Deploying the chart and letting the
nodes pull the image avoids the entire problem, and also avoids version skew
between a hardcoded pre-load tag in the script and the tag in
`config/elasticsearch/values.yaml` (they had already drifted: 8.18.1 vs
8.19.13).

## Troubleshooting

### Pod stuck in `ImagePullBackOff` (public image)
```bash
# Check events for the actual pull error
kubectl get events --sort-by='.lastTimestamp' | tail -20

# Docker Hub rate limiting? Authenticate the nodes or pre-load as a workaround:
docker pull <image> && kind load docker-image <image> --name structures-cluster
```

### Pod stuck in `ErrImageNeverPull` (local image)
The image was not loaded into the nodes. Re-run:
```bash
kind load docker-image <image> --name structures-cluster
```

### Verify what images a node has
```bash
docker exec structures-cluster-control-plane crictl images
```
