# SuperAgent image, in three stages:
#   cli-base  Java 21 + Node.js 24 + the coding-agent CLIs SuperAgent drives (published on its own as
#             ghcr.io/oryx-labs/superagent-base, for anyone who wants the CLIs without the server)
#   build     the release tarball (mvn -PreleaseTgz), built once on the build machine's own architecture
#   app       cli-base + the unpacked release
# Build from the repository root:  docker build -f docker/Dockerfile -t superagent .

ARG NODE_IMAGE=node:24-bookworm-slim
ARG JRE_IMAGE=eclipse-temurin:21-jre
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21

FROM ${JRE_IMAGE} AS jre

# ---------------------------------------------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS cli-base

ENV LANG=C.UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    NPM_CONFIG_PREFIX=/opt/cli \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false
ENV PATH=/opt/cli/bin:${JAVA_HOME}/bin:${PATH}

COPY --from=jre /opt/java/openjdk /opt/java/openjdk

# git and ripgrep: what the agent CLIs expect to find; tini: a proper PID 1 for the CLI subprocesses.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep tini procps less \
 && rm -rf /var/lib/apt/lists/*

# Claude Code refuses to skip permissions as root, so everything runs as this user.
RUN groupadd --gid 10001 superagent \
 && useradd --uid 10001 --gid superagent --create-home --home-dir /home/superagent --shell /bin/bash superagent \
 && mkdir -p /opt/cli && chown superagent:superagent /opt/cli

USER superagent
# The CLIs live in a prefix SuperAgent can write to, so "Upgrade" on the Models page works inside the container.
# CLI_REFRESH: pass a new value (the release passes the date) to pull the latest CLIs instead of a cached layer.
ARG CLI_REFRESH=unset
RUN echo "CLIs as of ${CLI_REFRESH}" \
 && npm install -g \
      @anthropic-ai/claude-code@latest \
      @openai/codex@latest \
      @google/gemini-cli@latest \
      @qwen-code/qwen-code@latest \
      @moonshot-ai/kimi-code@latest \
      @deepseek-ai/dsh@latest \
 && npm cache clean --force \
 && java -version 2>&1 | head -1 && node --version \
 && claude --version && codex --version && gemini --version && qwen --version && kimi --version && dsh --version

WORKDIR /home/superagent

# ---------------------------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY . .
# The jar is the same on every architecture: build it once, natively. Tests run in CI, not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -PreleaseTgz -DskipTests clean package \
 && mkdir /dist && tar -xzf superagent-server/target/superagent-*.tar.gz -C /dist --strip-components=1

# ---------------------------------------------------------------------------------------------------------------
FROM cli-base AS app

LABEL org.opencontainers.image.title="SuperAgent" \
      org.opencontainers.image.description="AI collaborative work system for companies" \
      org.opencontainers.image.source="https://github.com/oryx-labs/SuperAgent" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app
COPY --from=build --chown=superagent:superagent /dist/ /app/
COPY --chown=superagent:superagent --chmod=0755 docker/entrypoint.sh /app/bin/docker-entrypoint.sh

ENV SERVER_PORT=18080 \
    JAVA_OPTS="-Xms512m -Xmx2g"
# Agent workspaces, files, the private Node and the CLIs' own logins (their config under $HOME) survive upgrades.
VOLUME ["/app/data", "/app/logs", "/home/superagent"]
EXPOSE 18080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["tini", "--", "/app/bin/docker-entrypoint.sh"]
