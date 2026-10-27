# syntax=docker/dockerfile:1

ARG NODE_IMAGE=node:24.15.0-alpine
ARG CADDY_IMAGE=caddy:2-alpine

FROM ${NODE_IMAGE} AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build

FROM ${CADDY_IMAGE}

COPY --from=build /workspace/dist /srv
COPY deploy/Caddyfile /etc/caddy/Caddyfile

EXPOSE 8080 8443
