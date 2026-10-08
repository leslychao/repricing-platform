FROM node:24.15.0-alpine@sha256:d1b3b4da11eefd5941e7f0b9cf17783fc99d9c6fc34884a665f40a06dbdfc94f
WORKDIR /app
COPY deploy/keycloak/provision.mjs /app/provision.mjs
USER node
ENTRYPOINT ["node", "/app/provision.mjs"]
