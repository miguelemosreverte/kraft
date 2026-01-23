#!/bin/bash
#
# KRAFT SUPERVISOR - Tier 0 (Never changes)
# ===========================================
# This is the root of the update hierarchy. It should NEVER need updating.
# It supervises the auto-updater which in turn manages the node and clients.
#
# Hierarchy:
#   Tier 0: kraft-supervisor.sh (THIS FILE - ultra-stable, never changes)
#   Tier 1: node-autoupdate.sh  (polls GitHub, restarts nodes when code changes)
#   Tier 2: SmartFSNode.scala   (Scala node - rarely changes)
#   Tier 3: clients/*           (Web UI, etc - changes frequently)
#
# Usage: ./kraft-supervisor.sh [node_port]
#

set -e

NODE_PORT=${1:-7810}
POLL_INTERVAL=${KRAFT_POLL_INTERVAL:-30}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KRAFT_DIR="$(dirname "$SCRIPT_DIR")"
SUPERVISOR_PID=$$

# State file for client persistence
STATE_DIR="$KRAFT_DIR/.kraft-state"
mkdir -p "$STATE_DIR"

log() {
    echo "[Supervisor $(date '+%H:%M:%S')] $1"
}

# Save state before updates (clients call this via API)
save_state() {
    local client_id=$1
    local state_file="$STATE_DIR/${client_id}.state"
    cat > "$state_file"
    log "Saved state for client: $client_id"
}

# Load state after updates
load_state() {
    local client_id=$1
    local state_file="$STATE_DIR/${client_id}.state"
    if [ -f "$state_file" ]; then
        cat "$state_file"
    fi
}

# The supervisor's only job: keep the auto-updater running
run_supervisor() {
    log "=== KRAFT Supervisor Started ==="
    log "Tier 0: Supervisor PID $SUPERVISOR_PID"
    log "State directory: $STATE_DIR"
    log ""

    while true; do
        log "Starting Tier 1: Auto-updater..."

        # Run the auto-updater (Tier 1)
        # If it crashes or exits, we restart it
        "$SCRIPT_DIR/node-autoupdate.sh" "$POLL_INTERVAL" "$NODE_PORT" || {
            log "Auto-updater exited (code $?), restarting in 5s..."
            sleep 5
        }
    done
}

# Cleanup on exit
cleanup() {
    log "Supervisor shutting down..."
    # Kill child processes
    pkill -P $SUPERVISOR_PID 2>/dev/null || true
    exit 0
}

trap cleanup SIGINT SIGTERM

run_supervisor
