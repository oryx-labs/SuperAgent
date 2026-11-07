#!/bin/sh
# Runs SuperAgent in the foreground (the container's main process): the release's config/ overrides the jar's
# defaults, relative paths such as ./data resolve against /app, and the log goes to stdout as well as logs/.
set -e
cd /app
# shellcheck disable=SC2086 # JAVA_OPTS is a list of options
exec java $JAVA_OPTS \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs \
  -Dlogging.file.name=/app/logs/superagent.log \
  -jar /app/libs/superagent.jar \
  --spring.config.additional-location=optional:file:/app/config/ \
  "$@"
