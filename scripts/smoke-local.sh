#!/bin/sh
# Run after starting infrastructure and all four applications.
set -eu
for port in 8081 8082 8083 8084; do
    endpoint="http://127.0.0.1:${port}"
    curl --connect-timeout 3 --max-time 10 --fail --silent --show-error "${endpoint}/actuator/health/readiness" >/dev/null
    code=$(curl --connect-timeout 3 --max-time 10 --silent --show-error --output /dev/null --write-out '%{http_code}' "${endpoint}/api/v1/private")
    if [ "$code" != '401' ]; then
        echo "FAIL port=${port}: business route returned ${code}, expected 401" >&2
        exit 1
    fi
    echo "OK port=${port}: database readiness and unauthenticated access rejection"
done
