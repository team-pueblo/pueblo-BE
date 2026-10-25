#!/bin/sh
# Import bundled synthetic samples or an operator-provided normalized JSON file.
set -eu
if [ "$#" -ne 1 ]; then
    echo 'Usage: ./scripts/import-catalog.sh demo|/path/to/catalog.json' >&2
    exit 1
fi
if [ "$1" = demo ]; then
    docker compose run --rm --no-deps catalog-service --spring.main.web-application-type=none \
        --pueblo.messaging.enabled=false --pueblo.inventory.enabled=false --pueblo.docs.enabled=false \
        --springdoc.api-docs.enabled=false --springdoc.swagger-ui.enabled=false --pueblo.catalog.import-file=demo
else
    input_path=$(python3 -c 'from pathlib import Path; import sys; p=Path(sys.argv[1]).resolve(strict=True); assert p.is_file(); print(p)' "$1")
    docker compose run --rm --no-deps -v "$input_path:/import/catalog.json:ro" catalog-service \
        --spring.main.web-application-type=none --pueblo.messaging.enabled=false --pueblo.inventory.enabled=false --pueblo.docs.enabled=false \
        --springdoc.api-docs.enabled=false --springdoc.swagger-ui.enabled=false --pueblo.catalog.import-file=/import/catalog.json
fi
