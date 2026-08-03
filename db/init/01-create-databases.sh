#!/bin/bash
# =============================================================================
# Creates one database per service on first start of the Postgres container.
#
# Database-per-service is honoured strictly (docs/02 §3): no service can read
# another's tables even by accident. They share one Postgres instance here
# because running three for a demo would be theatre -- in production they are
# separate instances, and nothing in the code knows the difference since each
# service only ever sees its own JDBC URL.
#
# Runs only when the data directory is empty. Schema objects are NOT created
# here: each service owns its own migrations and applies them with Flyway at
# startup, so the schema always matches the code that reads it.
# =============================================================================
set -euo pipefail

for db in "${CATALOG_DB_NAME:-catalog_db}" "${BOOKING_DB_NAME:-booking_db}" "${PRICING_DB_NAME:-pricing_db}"; do
  echo "creating database $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
      SELECT 'CREATE DATABASE $db'
       WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
SQL
done

echo "databases ready"
