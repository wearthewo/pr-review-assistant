# syntax=docker/dockerfile:1
FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS dependencies

WORKDIR /app
RUN corepack enable npm && corepack install --global npm@11.19.1
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS build

WORKDIR /app
RUN corepack enable npm && corepack install --global npm@11.19.1
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1
RUN AUTH0_DOMAIN=auth.example.invalid \
    AUTH0_CLIENT_ID=container-build-placeholder \
    AUTH0_CLIENT_SECRET=container-build-placeholder \
    AUTH0_SECRET=1111111111111111111111111111111111111111111111111111111111111111 \
    AUTH0_AUDIENCE=https://api.example.invalid \
    APP_BASE_URL=https://app.example.invalid \
    BACKEND_BASE_URL=https://backend.example.invalid \
    npm run build

FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS runtime

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=10000 \
    HOSTNAME=0.0.0.0
WORKDIR /app
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 10000
CMD ["node", "server.js"]
