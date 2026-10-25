#!/bin/sh
# Run from the repository root with JDK 21 and Docker Desktop available.
set -eu
./scripts/generate-local-auth-keys.sh
./gradlew bootJar --no-daemon
docker compose up -d --build --wait --wait-timeout 180
./scripts/smoke-local.sh
