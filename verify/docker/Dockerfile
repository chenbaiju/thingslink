# E2E/L1 本地一次性 Linux Runner；不挂载宿主 Docker socket，不映射开发端口。
FROM docker:27-dind AS docker
FROM node:22-bookworm-slim AS node
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates curl git make python3 procps iptables iproute2 \
    xz-utils openssl jq tini && rm -rf /var/lib/apt/lists/*
COPY --from=docker /usr/local/bin/ /usr/local/bin/
COPY --from=docker /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm/ /usr/local/lib/node_modules/npm/
ARG PNPM_VERSION=11.9.0
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && npm install --global pnpm@${PNPM_VERSION}
COPY runner.sh /usr/local/bin/thingslink-local-ci
RUN chmod +x /usr/local/bin/thingslink-local-ci
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/thingslink-local-ci"]
