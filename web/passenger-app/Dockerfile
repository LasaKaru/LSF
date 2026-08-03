# Builds the SPA. The output is copied into a shared volume by compose and
# served by the gateway's nginx, so the frontend can be rebuilt without
# rebuilding or restarting the gateway.
FROM node:22-alpine AS build
WORKDIR /app

# Lockfile first so the dependency layer caches independently of source changes.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund 2>/dev/null || npm install --no-audit --no-fund

COPY . .
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
COPY --from=build /app/dist ./dist
# compose overrides this to copy dist into the shared volume and exit.
CMD ["sh", "-c", "echo 'web assets built'"]
