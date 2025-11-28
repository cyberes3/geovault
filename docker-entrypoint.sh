#!/bin/bash
set -e

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
until PGPASSWORD="${POSTGRES_PASSWORD}" psql -h "${POSTGRES_HOST:-postgres}" -U "${POSTGRES_USER:-postgres}" -d postgres -c '\q' 2>/dev/null; do
  >&2 echo "PostgreSQL is unavailable - sleeping"
  sleep 1
done
echo "PostgreSQL is ready!"

# Wait for Redis to be ready
echo "Waiting for Redis to be ready..."
until redis-cli -h "${REDIS_HOST:-redis}" -p "${REDIS_PORT:-6379}" ping > /dev/null 2>&1; do
  >&2 echo "Redis is unavailable - sleeping"
  sleep 1
done
echo "Redis is ready!"

# Set up data directory
DATA_DIR="/srv/docker-data/geovault"
BACKEND_DIR="${DATA_DIR}/src/backend"
CONFIG_FILE="${BACKEND_DIR}/config.yaml"
CONFIG_EXAMPLE="/app/src/backend/config.example.yaml"

# Create necessary directories
mkdir -p "${BACKEND_DIR}"
mkdir -p "${BACKEND_DIR}/data/icons"
mkdir -p "${BACKEND_DIR}/data/tiles"

# Copy config.yaml if it doesn't exist
if [ ! -f "${CONFIG_FILE}" ]; then
    echo "Config file not found, copying from example..."
    cp "${CONFIG_EXAMPLE}" "${CONFIG_FILE}"
    
    # Update config with environment variables if provided
    # Update database host (matches the database section)
    sed -i "/^database:/,/^[a-z]/s/  host: 127.0.0.1/  host: ${POSTGRES_HOST:-postgres}/" "${CONFIG_FILE}"
    # Update database password
    if [ -n "${DB_PASSWORD}" ]; then
        sed -i "s/password: your-database-password/password: ${DB_PASSWORD}/" "${CONFIG_FILE}"
    fi
    
    # Update Redis host (matches the redis section)
    sed -i "/^redis:/,/^[a-z]/s/  host: 127.0.0.1/  host: ${REDIS_HOST:-redis}/" "${CONFIG_FILE}"
    
    # Update Django settings
    if [ -n "${SECRET_KEY}" ]; then
        sed -i "s/secret_key: django-insecure-change-this-in-production/secret_key: ${SECRET_KEY}/" "${CONFIG_FILE}"
    fi
    if [ -n "${SITE_DOMAIN}" ]; then
        sed -i "s/domain: geovault.example.com/domain: ${SITE_DOMAIN}/" "${CONFIG_FILE}"
    fi
    
    echo "Config file created at ${CONFIG_FILE}"
    echo "Please review and update the configuration as needed."
else
    echo "Config file already exists at ${CONFIG_FILE}"
fi

# Initialize database if needed
echo "Checking database initialization..."
PGPASSWORD="${POSTGRES_PASSWORD}" psql -h "${POSTGRES_HOST:-postgres}" -U "${POSTGRES_USER:-postgres}" -d postgres <<EOF
DO \$\$
BEGIN
    -- Create user if it doesn't exist
    IF NOT EXISTS (SELECT FROM pg_user WHERE usename = 'geovault') THEN
        CREATE USER geovault WITH PASSWORD '${DB_PASSWORD:-geovault}';
        RAISE NOTICE 'User geovault created';
    END IF;
    
    -- Create database if it doesn't exist
    IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'geovault') THEN
        CREATE DATABASE geovault OWNER geovault;
        RAISE NOTICE 'Database geovault created';
    END IF;
END
\$\$;
EOF

# Grant privileges and create PostGIS extension
PGPASSWORD="${POSTGRES_PASSWORD}" psql -h "${POSTGRES_HOST:-postgres}" -U "${POSTGRES_USER:-postgres}" -d geovault <<EOF
-- Grant schema privileges
GRANT ALL ON SCHEMA public TO geovault;

-- Create PostGIS extension if it doesn't exist
CREATE EXTENSION IF NOT EXISTS postgis;

-- Grant privileges on PostGIS tables
GRANT ALL PRIVILEGES ON TABLE geometry_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE geography_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE spatial_ref_sys TO geovault;

-- Set default privileges
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO geovault;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO geovault;
EOF

echo "Database initialization complete!"

# Change to backend directory
cd /app/src/backend

# Run migrations
echo "Running Django migrations..."
python manage.py migrate --noinput

echo "Setup complete! Starting application..."

# Execute the command passed to the entrypoint
exec "$@"

