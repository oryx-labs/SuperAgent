#!/usr/bin/env bash
# Starts SuperAgent in the background.
#   bin/superagent-start.sh          start; refuses if already running
#   bin/superagent-start.sh force    stop the running process first (graceful, then kill), then start
set -u

# shellcheck disable=SC1091
. "$(dirname "$0")/superagent-common.sh"

case "${1:-}" in
  "") ;;
  force) FORCE=1 ;;
  *) echo "Usage: $0 [force]" >&2; exit 2 ;;
esac

pid="$(running_pid)"
if [ -n "$pid" ]; then
  if [ "${FORCE:-0}" != 1 ]; then
    echo "SuperAgent is already running (pid $pid). Use '$0 force' to restart it."
    exit 1
  fi
  stop_server || exit 1
fi

# --- Java 21+ ---
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java || true)"
fi
if [ -z "$JAVA" ]; then
  echo "Java not found. Install Java 21+ or set JAVA_HOME (in config/superagent.env)." >&2
  exit 1
fi
java_major="$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
if [ -z "$java_major" ] || [ "$java_major" -lt 21 ]; then
  echo "Java 21+ is required, found: $("$JAVA" -version 2>&1 | head -n 1)" >&2
  exit 1
fi

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g}"
# shellcheck disable=SC2206
cmd=("$JAVA" $JAVA_OPTS
  -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$APP_HOME/logs"
  "-Dlogging.file.name=$APP_HOME/logs/superagent.log"
  -jar "$APP_JAR"
  "--spring.config.additional-location=optional:file:$APP_HOME/config/")

echo "Starting SuperAgent (port $SERVER_PORT, home $APP_HOME)..."
echo "==== $(date '+%Y-%m-%d %H:%M:%S') start ====" >> "$CONSOLE_LOG"
# Own session where available so closing the terminal doesn't take the server down.
if command -v setsid >/dev/null 2>&1; then
  setsid "${cmd[@]}" >> "$CONSOLE_LOG" 2>&1 < /dev/null &
else
  "${cmd[@]}" >> "$CONSOLE_LOG" 2>&1 < /dev/null &
fi
pid=$!
echo "$pid" > "$PID_FILE"

# Wait for the health endpoint so "started" means serving, not just forked.
HEALTH_URL="http://127.0.0.1:$SERVER_PORT/actuator/health"
for _ in $(seq 1 "${START_TIMEOUT:-120}"); do
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "SuperAgent exited during startup. Last log lines ($CONSOLE_LOG):" >&2
    tail -n 30 "$CONSOLE_LOG" >&2
    exit 1
  fi
  if ! command -v curl >/dev/null 2>&1; then
    echo "SuperAgent started (pid $pid); curl not found, skipped the health check."
    exit 0
  fi
  if curl -fsS -o /dev/null "$HEALTH_URL" 2>/dev/null; then
    echo "SuperAgent started (pid $pid): http://127.0.0.1:$SERVER_PORT"
    exit 0
  fi
  sleep 1
done
echo "SuperAgent (pid $pid) is still not healthy after ${START_TIMEOUT:-120}s; see $CONSOLE_LOG and logs/superagent.log" >&2
exit 1
