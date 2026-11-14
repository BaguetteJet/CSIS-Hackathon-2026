# Setup

Run this service in containers against the dev-kit. Run these commands from `line-a-ledger/` unless a step says otherwise.

Use the **same container engine** for dev-kit and this service; a Podman service cannot join the Docker `hackathon` network, or vice versa. Examples use `podman`; substitute `docker` on a Docker machine.

Start dev-kit first:

```bash
cd ../dev-kit
podman compose --profile lite up -d
```

## One-time config

```bash
cd ../line-a-ledger
```

```bash
cp .env.example .env
./gradlew bootJar
```

`.env` must contain `SERVICE_NAME=ledger`.

`service.env` is for the containers. It must use dev-kit service names, never `localhost`:

| Variable | Value |
|---|---|
| `SERVICE_NAME` | `ledger` |
| `SERVER_PORT` | `8080` |
| `DB_URL` | `jdbc:mysql://mysql:3306/ledger` |
| `DB_USER` | `ledger` |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` |
| `CLOCK_URL` | `http://clock:8080/internal/clock` |
| `JWT_PUBLIC_KEY_URL` | `http://signin:8080/.well-known/jwks.json` |

`compose.yml` line 12 must be:

```yaml
image: ${IMAGE:-ledger:local}
```

`podman-compose` 1.5 mishandles the nested default `${IMAGE:-${SERVICE_NAME}:local}`. The line above works with Podman and Docker.

Check the resolved image name:

```bash
podman compose config | grep -E '^name:|image:'
```

Expected: `name: ledger` and `image: ledger:local`.

## Podman

```bash
podman compose up -d --build --force-recreate
podman compose ps
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8082/actuator/health
podman compose logs --tail=100 instance-1
podman compose down
```

`./check` needs Docker and `docker compose --progress`; on Podman run its steps manually: `./gradlew test`, `./gradlew bootJar`, `podman compose up -d --build --force-recreate`, then the health checks above.

for socket

```bash
curl --unix-socket "${DOCKER_HOST#unix://}" http://d/v1.40/version
```

```bash
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/run/user/$(id -u)/podman/podman.sock"
```

check OK
```bash
curl --unix-socket "${DOCKER_HOST#unix://}" http://d/v1.40/version
```

## Docker

```bash
docker compose up -d --build --force-recreate
docker compose ps
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8082/actuator/health
docker compose logs --tail=100 instance-1
docker compose down
```

`./check` works on Docker once `.env` exists:

```bash
./check
```

## CI-built image

```bash
podman login cs4297.skynet.ie
podman pull cs4297.skynet.ie/hackathon-2026/line-a-ledger:release
IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-ledger:release \
  podman compose up -d --no-build
```

Use `docker login`, `docker pull` and `docker compose` on Docker. Pass `IMAGE=...` inline, not in `.env`, so local runs keep using `ledger:local`.

## Point dev-kit at the containers

In `../dev-kit/.env`:

```dotenv
LEDGER_URL=http://ledger:8080
FAKE_EXCLUDE=ledger
```

Both instances have the network alias `ledger`; do not use a host address.

```bash
cd ../dev-kit
podman compose --profile lite up -d
```

If the old values persist:

```bash
podman compose --profile lite up -d --force-recreate gateway fakes
```

Verify:

```bash
podman compose exec gateway env | grep '^LEDGER_URL='
podman compose exec fakes env | grep '^FAKE_EXCLUDE='
TOKEN=$(./token aoife)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/ledger/hello
```

Expected response includes:

```json
{"service":"ledger","instance":"ledger-1","role":"STUDENT"}
```

`instance` may be `ledger-1` or `ledger-2`.

For an IDE run instead, use `LEDGER_URL=http://10.89.0.1:8081` on Podman or `http://host.docker.internal:8081` on Docker Desktop.

## Iteration

After a code change:

```bash
./gradlew bootJar
podman compose up -d --build --force-recreate
```

If only `service.env` changed:

```bash
podman compose up -d --force-recreate
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `network hackathon ... could not be found` | Start dev-kit, or use the same engine for both |
| Image name contains `${...}` or `ledger:local:local}` | Fix `compose.yml` line 12 as shown above |
| `/api/ledger/...` returns `502 NOTHING_ANSWERS` | Service containers are down, or `LEDGER_URL` is wrong |
| `pull access denied` | `podman login` or `docker login` to `cs4297.skynet.ie` |
| `Could not resolve placeholder` | Missing variable in `service.env` |
| `UnknownHostException: kafka` | `service.env` uses `localhost`; use `kafka:29092` |
| `Communications link failure` | `service.env` uses `localhost`; use `mysql:3306` |
| Health check fails | `podman compose logs --tail=100 instance-1` |
| Container exits with 137 | It hit the 512 MB limit; run one instance while debugging |

## Dev-kit DNS note

The dev-kit gateway resolver must match its engine:

- Podman: `resolver 10.89.0.1 valid=5s ipv6=off;`
- Docker: `resolver 127.0.0.11 valid=5s ipv6=off;`

This is in `../dev-kit/gateway/default.conf.template`.
