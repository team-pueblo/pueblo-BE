#!/bin/sh
# Requires a successful build, JDK 21, and `docker compose up -d --wait mysql redis kafka` (application containers stopped).
set -eu
: "${JWT_TOKEN:?Export JWT_TOKEN before running host bootstrap verification}"
log_dir=$(mktemp -d "${TMPDIR:-/tmp}/pueblo-bootstrap.XXXXXX")
app_pids=''
./scripts/generate-local-auth-keys.sh
AUTH_KEY_DIRECTORY="$(pwd)/.local/auth"
export AUTH_KEY_DIRECTORY
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then java_command="${JAVA_HOME}/bin/java"; fi
cleanup() {
    for pid in $app_pids; do
        kill "$pid" 2>/dev/null || true
    done
    for pid in $app_pids; do
        wait "$pid" 2>/dev/null || true
    done
    echo "Application logs: $log_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for port in 8081 8082 8083 8084; do
    if curl --connect-timeout 1 --max-time 2 --silent --output /dev/null "http://127.0.0.1:${port}/actuator/health/liveness"; then
        echo "Port ${port} already responds; stop that process before bootstrap verification." >&2
        exit 1
    fi
done

for service in member catalog order payment; do
    "$java_command" -Xms64m -Xmx192m -jar "${service}-service/build/libs/${service}-service-0.1.0-SNAPSHOT.jar" \
        --spring.profiles.active=local >"${log_dir}/${service}.log" 2>&1 &
    app_pids="$app_pids $!"
done

attempt=0
until ./scripts/smoke-local.sh >"${log_dir}/smoke.log" 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 45 ]; then
        cat "${log_dir}/smoke.log" >&2
        echo 'Application readiness failed; inspect logs.' >&2
        exit 1
    fi
    for pid in $app_pids; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo 'An application exited before readiness; inspect logs.' >&2
            exit 1
        fi
    done
    sleep 1
done
cat "${log_dir}/smoke.log"

for service in member catalog order payment; do
    docker compose exec -T -e "MYSQL_PWD=local_${service}_only" mysql \
        mysql -u "pueblo_${service}" -N -B "pueblo_${service}" \
        -e 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1; SELECT COUNT(*) FROM outbox_event; SELECT COUNT(*) FROM consumed_event;' \
        >"${log_dir}/${service}-database.log"
    migrations=1
    if [ "$service" = member ]; then migrations=2; fi
    expected=$(printf '%s\n0\n0' "$migrations")
    if [ "$(cat "${log_dir}/${service}-database.log")" != "$expected" ]; then
        echo "Unexpected fresh database state for ${service}; use a separate empty development environment." >&2
        exit 1
    fi
    other=member
    if [ "$service" = member ]; then other=catalog; fi
    if docker compose exec -T -e "MYSQL_PWD=local_${service}_only" mysql \
        mysql -u "pueblo_${service}" -e "SELECT COUNT(*) FROM pueblo_${other}.outbox_event" \
        >"${log_dir}/${service}-isolation.log" 2>&1; then
        echo "FAIL: ${service} accessed another service database" >&2
        exit 1
    fi
    if ! grep -Eq 'ERROR 1142|ERROR 1044' "${log_dir}/${service}-isolation.log"; then
        echo "Database isolation failed for an unexpected reason; inspect logs." >&2
        exit 1
    fi
    echo "OK ${service}: Flyway migration, messaging tables, cross-database access denied"
done
