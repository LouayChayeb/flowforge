# Deploying FlowForge

## Images

One multi-stage [Dockerfile](../Dockerfile) builds either service. It compiles with the Maven wrapper (the Maven repository is a BuildKit cache, so rebuilds only recompile), splits the Spring Boot jar into layers so a code change only replaces a small top layer, and runs as a non-root user on a JRE base image with the heap sized from the container's memory limit.

```bash
docker build --build-arg MODULE=flowforge-orchestrator -t flowforge-orchestrator:0.1.0 .
docker build --build-arg MODULE=flowforge-worker -t flowforge-worker:0.1.0 .
```

CI builds both images on every push, after the tests pass, and lints and renders the chart.

## Helm chart

[`deploy/helm/flowforge`](helm/flowforge) installs:

| Resource | Default | Notes |
|---|---|---|
| Orchestrator Deployment + Service | 2 replicas | Every replica runs all three engine loops; `SKIP LOCKED` keeps them apart. A PodDisruptionBudget keeps one up during node drains |
| Worker Deployment | 3 replicas | Stateless; optional HorizontalPodAutoscaler (`worker.autoscaling.enabled`) |
| PostgreSQL StatefulSet | 1 instance, 2 Gi volume | For development clusters. Disable and set `postgresql.external.url` to use a managed database |
| Kafka StatefulSet (KRaft) | 1 node, 12 partitions | For development clusters. Disable and set `kafka.externalBootstrap` to use your own |

Every pod has startup, liveness and readiness probes on the actuator health groups, resource requests and memory limits, and shuts down gracefully on `SIGTERM`. Engine settings are passed through verbatim from `orchestrator.engine` (see [values.yaml](helm/flowforge/values.yaml)), so any property in [architecture.md](../docs/architecture.md) can be tuned per environment.

`kafka.partitions` caps worker parallelism: each partition is consumed by one thread at a time, so `worker.replicas x worker.listenerConcurrency` above it leaves threads idle, and a count that does not divide it evenly leaves some threads with twice the load of others (see [performance.md](../docs/performance.md)).

## Minikube

```bash
minikube start --driver=docker --cpus=6 --memory=8g

docker build --build-arg MODULE=flowforge-orchestrator -t flowforge-orchestrator:0.1.0 .
docker build --build-arg MODULE=flowforge-worker -t flowforge-worker:0.1.0 .
minikube image load flowforge-orchestrator:0.1.0
minikube image load flowforge-worker:0.1.0

helm install flowforge deploy/helm/flowforge -n flowforge --create-namespace --wait
kubectl port-forward -n flowforge svc/flowforge-orchestrator 8085:8080
```

Then use the API on `localhost:8085` as in the [README](../README.md).

To deploy a rebuilt image, give it a new tag and `helm upgrade --set image.tag=<tag>`: `minikube image load` does not replace an image that already exists under the same tag.

## Load testing

[`loadtest/LoadTest.java`](../loadtest/LoadTest.java) is a dependency-free, open-model load generator (it submits at a fixed rate regardless of response times). [`loadtest/run-k8s.ps1`](../loadtest/run-k8s.ps1) wraps it: port-forward, warm-up, measure.

```bash
java loadtest/LoadTest.java --base http://localhost:8085 --rate 20 --duration 60
```

Results and analysis are in [performance.md](../docs/performance.md).
