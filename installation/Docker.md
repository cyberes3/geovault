# Docker Installation

GeoVault is also provided as a Docker container. **Provided as-is without any testing!**

You will still need an nginx server to do HTTPS.

## Prerequisites

Before starting the containers, you need to create the data directories on the host system:

```bash
sudo mkdir -p /srv/docker-data/geovault-postgres
sudo mkdir -p /srv/docker-data/geovault-redis
sudo mkdir -p /srv/docker-data/geovault
```

Set appropriate permissions:

```bash
sudo chown -R root:root /srv/docker-data
sudo chmod 755 /srv/docker-data
sudo chmod 700 /srv/docker-data/geovault-postgres
sudo chmod 700 /srv/docker-data/geovault-redis
sudo chmod 755 /srv/docker-data/geovault
```

## Quick Start

1. Create the data directories (see above)

2. Build and start the containers:

```bash
docker-compose up -d
```

3. The setup will automatically:
   - Initialize the PostgreSQL database with PostGIS extension
   - Create the database user and grant necessary privileges
   - Copy `config.example.yaml` to `/srv/docker-data/geovault/src/backend/config.yaml` if it doesn't exist
   - Update the config file with database and Redis connection settings
   - Run Django migrations

4. Review and update the configuration file:

```bash
sudo nano /srv/docker-data/geovault/src/backend/config.yaml
```

Important settings to configure:
- `site.domain` - Your domain name
- `security.secret_key` - Generate a secure key (or set `SECRET_KEY` environment variable)
- `database.password` - Database password (or set `DB_PASSWORD` environment variable)
- `email` settings - SMTP configuration for email functionality

5. Restart the container if you made config changes:

```bash
docker-compose restart geovault
```

## Environment Variables

You can customize the setup using environment variables. Create a `.env` file in the project root:

```bash
POSTGRES_PASSWORD=your_postgres_password
DB_PASSWORD=your_geovault_db_password
SECRET_KEY=your_django_secret_key
SITE_DOMAIN=geovault.example.com
```

## Services

The docker-compose setup includes:

- **postgres**: PostgreSQL 18 with PostGIS 3.4 extension
  - Data stored in: `/srv/docker-data/geovault-postgres`
  - Port: `5432`

- **redis**: Redis 7 for Channels/WebSockets
  - Data stored in: `/srv/docker-data/geovault-redis`
  - Port: `6379`

- **geovault**: GeoVault application
  - Data stored in: `/srv/docker-data/geovault`
  - Port: `8000`

## Accessing the Application

Once started, the application will be available at:
- `http://localhost:8000`

The first user to register will be automatically set as an admin.

## Stopping and Starting

Stop all services:
```bash
docker-compose down
```

Start services:
```bash
docker-compose up -d
```

View logs:
```bash
docker-compose logs -f geovault
```
