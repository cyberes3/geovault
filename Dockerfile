# GeoVault Dockerfile
FROM python:3.12-slim as base

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    g++ \
    make \
    libgdal-dev \
    gdal-bin \
    libpq-dev \
    postgresql-client \
    redis-tools \
    curl \
    git \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js 22.x
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy backend requirements and install Python dependencies
COPY src/backend/requirements.txt /app/src/backend/requirements.txt
RUN pip install --no-cache-dir -r /app/src/backend/requirements.txt

# Copy frontend package.json first for better caching
COPY src/frontend/package.json /app/src/frontend/
WORKDIR /app/src/frontend
RUN npm install

# Copy the rest of the application (source files)
WORKDIR /app
COPY src/ /app/src/

# Make generate-map-fonts.sh executable and run it to generate font assets
WORKDIR /app/src/backend
RUN chmod +x generate-map-fonts.sh && ./generate-map-fonts.sh

# Build frontend and all extension frontends using the build script
WORKDIR /app/src
RUN chmod +x build-frontend.sh && ./build-frontend.sh

# Copy entrypoint script
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Set working directory to backend
WORKDIR /app/src/backend

# Expose port
EXPOSE 8000

# Healthcheck
HEALTHCHECK --interval=30s --timeout=60s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8000/api/health/ || exit 1

# Use entrypoint script
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["daphne", "--bind", "0.0.0.0", "--port", "8000", "--access-log", "/dev/null", "website.asgi:application"]

