#!/usr/bin/env bash
# Stops SuperAgent: graceful TERM, then KILL after STOP_TIMEOUT seconds (default 180).
set -u

# shellcheck disable=SC1091
. "$(dirname "$0")/superagent-common.sh"

stop_server
