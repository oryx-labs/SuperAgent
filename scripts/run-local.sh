#!/usr/bin/env bash
# Runs SuperAgent locally from its own copy of the executable jar, so building while it runs (mvn install, which
# rewrites the jars in ~/.m2 that spring-boot:run would load classes from) can't change what it runs.
#
#   scripts/run-local.sh                    build (mvn package, skipping tests) and run on :18080
#   SKIP_BUILD=1 scripts/run-local.sh       run the jar already in superagent-server/target
#   scripts/run-local.sh --server.port=18099 --superagent.agent.workspace-dir=/tmp/sa/workspaces
#                                           anything after the script goes to the server as is
#
# Env: RUN_DIR (default <repo>/run, where the jar copies live), KEEP (copies kept, default 3), JAVA_OPTS.
# The working directory is superagent-server/, the same as under spring-boot:run, so ./data is where it always was.
# The UI is served straight from its source folder: front-end edits show on a browser refresh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT/run}"
KEEP="${KEEP:-3}"

cd "$ROOT"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  # package, not install: the modules come from this build, and ~/.m2 is left alone
  mvn -q package -DskipTests -pl superagent-server -am
fi

jar="$(ls -1t superagent-server/target/superagent-server-*.jar 2>/dev/null | grep -v -e '-sources' -e '\.original$' | head -n 1 || true)"
if [ -z "$jar" ]; then
  echo "No executable jar in superagent-server/target: run without SKIP_BUILD=1." >&2
  exit 1
fi

mkdir -p "$RUN_DIR"
copy="$RUN_DIR/superagent-$(date +%Y%m%d-%H%M%S).jar"
cp "$jar" "$copy"
# Older copies go; an instance still running from one keeps its open file.
ls -1t "$RUN_DIR"/superagent-*.jar | tail -n +"$((KEEP + 1))" | while read -r old; do rm -f "$old"; done

cd "$ROOT/superagent-server"
echo "Running $copy"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar "$copy" \
  "--spring.web.resources.static-locations=file:$ROOT/superagent-ui/src/main/resources/static/,classpath:/static/" \
  "$@"
