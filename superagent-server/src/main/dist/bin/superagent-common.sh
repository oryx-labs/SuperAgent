#!/usr/bin/env bash
# Shared by superagent-start.sh / superagent-stop.sh. Sourced, not run.

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_JAR="$APP_HOME/libs/superagent.jar"
PID_FILE="$APP_HOME/logs/superagent.pid"
CONSOLE_LOG="$APP_HOME/logs/console.out"

# Relative paths in the config (./data/...) resolve against the install dir, wherever the script is run from.
cd "$APP_HOME" || exit 1
mkdir -p "$APP_HOME/logs" "$APP_HOME/data"

# Deployment-specific values and secrets (admin password, DB password, OAuth secret) live here, not in the jar.
if [ -f "$APP_HOME/config/superagent.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$APP_HOME/config/superagent.env"
  set +a
fi

SERVER_PORT="${SERVER_PORT:-18080}"
STOP_TIMEOUT="${STOP_TIMEOUT:-35}"   # server.shutdown=graceful waits up to 30s for in-flight requests

# PID of the running server, or empty. Trusts the pid file only if that pid is still our jar
# (a stale file can point at an unrelated process after a reboot); falls back to a jar-path match.
running_pid() {
  local pid
  if [ -f "$PID_FILE" ]; then
    pid="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o command= 2>/dev/null | grep -q "$APP_JAR"; then
      echo "$pid"
      return
    fi
  fi
  pgrep -f "$APP_JAR" 2>/dev/null | head -n 1
}

# Graceful stop (TERM), escalating to KILL after STOP_TIMEOUT seconds. Returns 0 once stopped.
stop_server() {
  local pid waited=0
  pid="$(running_pid)"
  if [ -z "$pid" ]; then
    echo "SuperAgent is not running."
    rm -f "$PID_FILE"
    return 0
  fi
  echo "Stopping SuperAgent (pid $pid)..."
  kill -TERM "$pid" 2>/dev/null
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$STOP_TIMEOUT" ]; then
      echo "Still running after ${STOP_TIMEOUT}s, killing (pid $pid)."
      kill -KILL "$pid" 2>/dev/null
      sleep 1
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "Failed to stop pid $pid." >&2
    return 1
  fi
  rm -f "$PID_FILE"
  echo "SuperAgent stopped."
}
