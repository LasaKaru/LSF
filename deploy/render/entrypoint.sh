#!/bin/sh
# =============================================================================
# Render entrypoint: turn Render's connection string into Spring's, then exec.
#
# Two mismatches to bridge, both deployment concerns rather than application
# ones -- which is why this is a shim and not a change to application.yml.
#
# 1. Render publishes DATABASE_URL as `postgres://user:pass@host:port/db`.
#    Spring wants a JDBC URL plus a separate username and password.
#
# 2. Render's free tier gives ONE PostgreSQL instance, but the three services
#    each own their own database in every other environment. They are kept
#    apart here by SCHEMA instead: each service migrates into its own schema
#    and never sees the others' tables.
#
# `public` stays on the search path because btree_gist lives there -- an
# extension is per-database, not per-schema, and the exclusion constraint's
# GiST operator classes have to resolve. Verified: the constraint is created
# correctly in the booking schema and still rejects overlapping segments.
# =============================================================================
set -eu

: "${SERVICE:?SERVICE must be set (booking-service | catalog-service | pricing-service)}"

if [ -n "${DATABASE_URL:-}" ] && [ -z "${SPRING_DATASOURCE_URL:-}" ]; then
    # postgres://user:pass@host:port/dbname  ->  its parts.
    # No external tools: this image has a shell, sed and the JRE, nothing else.
    no_scheme=${DATABASE_URL#*://}
    credentials=${no_scheme%%@*}
    hostpath=${no_scheme#*@}

    DB_USER=${credentials%%:*}
    DB_PASSWORD=${credentials#*:}
    DB_HOSTPORT=${hostpath%%/*}
    DB_NAME=${hostpath#*/}
    DB_NAME=${DB_NAME%%\?*}   # drop any query string Render appends

    # Render's managed PostgreSQL requires TLS. Note this is exactly the
    # configuration that exposed the virtual-thread pinning bug documented in
    # README §9.10 -- which is why THREADS_VIRTUAL_ENABLED defaults to false.
    SSL_MODE=${DB_SSL_MODE:-require}
    SCHEMA=${DB_SCHEMA:-public}

    export SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_HOSTPORT}/${DB_NAME}?sslmode=${SSL_MODE}&currentSchema=${SCHEMA},public"
    export SPRING_DATASOURCE_USERNAME="$DB_USER"
    export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"

    # Flyway migrates into this service's own schema and creates it on first boot.
    export SPRING_FLYWAY_SCHEMAS="$SCHEMA"
    export SPRING_FLYWAY_DEFAULT_SCHEMA="$SCHEMA"
    export SPRING_FLYWAY_CREATE_SCHEMAS="true"

    echo "render.entrypoint service=$SERVICE host=$DB_HOSTPORT db=$DB_NAME schema=$SCHEMA sslmode=$SSL_MODE"
fi

# Render routes all traffic to $PORT; the services default to their own ports.
if [ -n "${PORT:-}" ]; then
    export SERVER_PORT="$PORT"
fi

exec java ${JAVA_OPTS:-} -jar "/app/${SERVICE}.jar"
