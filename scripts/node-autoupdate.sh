#!/bin/bash
#
# KRAFT Auto-Updater - Tier 1 (Rarely changes)
# =============================================
# Polls GitHub and manages tiered updates:
#   - Tier 2 changes (Scala node): Full restart
#   - Tier 3 changes (clients only): Hot reload without node restart
#
# Usage: ./node-autoupdate.sh [poll_interval_seconds] [node_port]
#
# Environment variables:
#   KRAFT_REPO_URL - Git repository URL (default: origin)
#   KRAFT_BRANCH   - Branch to track (default: main)
#
# Update Tiers:
#   Tier 0: scripts/kraft-supervisor.sh - NEVER auto-updates
#   Tier 1: scripts/node-autoupdate.sh  - NEVER auto-updates (this file)
#   Tier 2: src/main/scala/**           - Requires node restart
#   Tier 3: examples/*, clients/*       - Hot reload (no restart needed)
#

set -e

POLL_INTERVAL=${1:-30}  # Default: check every 30 seconds
NODE_PORT=${2:-7810}    # Default port
BRANCH=${KRAFT_BRANCH:-main}
REPO_URL=${KRAFT_REPO_URL:-origin}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KRAFT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$KRAFT_DIR"

log() {
    echo -e "${BLUE}[AutoUpdate $(date '+%H:%M:%S')]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[AutoUpdate $(date '+%H:%M:%S')]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[AutoUpdate $(date '+%H:%M:%S')]${NC} $1"
}

log_error() {
    echo -e "${RED}[AutoUpdate $(date '+%H:%M:%S')]${NC} $1"
}

get_local_commit() {
    git rev-parse HEAD 2>/dev/null || echo "unknown"
}

get_remote_commit() {
    # git ls-remote is very fast - only fetches refs, not content
    git ls-remote "$REPO_URL" "refs/heads/$BRANCH" 2>/dev/null | cut -f1 || echo "unknown"
}

start_node() {
    log "Starting SmartFSNode on port $NODE_PORT..."

    # Kill any existing node on this port
    lsof -ti:$NODE_PORT | xargs kill -9 2>/dev/null || true
    sleep 1

    # Start the node in background
    cd "$KRAFT_DIR"
    nohup sbt "runMain kraft.demos.SmartFSNode seed $NODE_PORT" > /tmp/kraft-node-$NODE_PORT.log 2>&1 &
    NODE_PID=$!

    # Wait for node to start
    for i in {1..30}; do
        if curl -s "http://localhost:$NODE_PORT/health" > /dev/null 2>&1; then
            log_success "Node started successfully (PID: $NODE_PID)"
            return 0
        fi
        sleep 1
    done

    log_error "Node failed to start within 30 seconds"
    return 1
}

detect_change_tier() {
    local old_commit=$1
    local new_commit=$2

    # Get list of changed files
    local changed_files=$(git diff --name-only "$old_commit" "$new_commit" 2>/dev/null)

    # Check if Tier 1/0 changed (should NOT happen in normal operation)
    if echo "$changed_files" | grep -qE "^scripts/(kraft-supervisor|node-autoupdate)\.sh$"; then
        log_warn "WARNING: Updater scripts changed! Manual restart recommended."
        return 0  # Tier 0 - don't auto-update ourselves
    fi

    # Check if Tier 2 changed (Scala node code)
    if echo "$changed_files" | grep -qE "^src/main/scala/"; then
        return 2  # Tier 2 - requires node restart
    fi

    # Otherwise it's Tier 3 (clients, examples, etc.)
    return 3  # Tier 3 - hot reload only
}

update_and_restart() {
    local old_commit=$(get_local_commit)

    log "Pulling latest changes..."

    # Stash any local changes
    git stash 2>/dev/null || true

    # Pull latest
    if ! git pull "$REPO_URL" "$BRANCH"; then
        log_error "Git pull failed!"
        return 1
    fi

    local new_commit=$(get_local_commit)
    log_success "Code updated: ${old_commit:0:8} -> ${new_commit:0:8}"

    # Detect what tier of changes occurred
    detect_change_tier "$old_commit" "$new_commit"
    local change_tier=$?

    case $change_tier in
        0)
            log_warn "Tier 0/1 changes detected - manual intervention required"
            return 0
            ;;
        2)
            log "Tier 2 changes (Scala node) - full restart required"
            # Compile
            log "Compiling..."
            if sbt compile; then
                log_success "Compilation successful"
                start_node
                return 0
            else
                log_error "Compilation failed!"
                return 1
            fi
            ;;
        3)
            log "Tier 3 changes (clients only) - no restart needed"
            log_success "Clients will pick up changes on next request"
            return 0
            ;;
    esac
}

# Main loop
echo ""
echo "=========================================="
echo "  Kraft Node Auto-Updater"
echo "=========================================="
echo "  Repository: $REPO_URL"
echo "  Branch:     $BRANCH"
echo "  Node Port:  $NODE_PORT"
echo "  Poll:       Every ${POLL_INTERVAL}s"
echo "=========================================="
echo ""

# Initial start
LOCAL_COMMIT=$(get_local_commit)
log "Current local commit: ${LOCAL_COMMIT:0:8}"

# Check if node is running, start if not
if ! curl -s "http://localhost:$NODE_PORT/health" > /dev/null 2>&1; then
    start_node
fi

# Poll loop
while true; do
    sleep "$POLL_INTERVAL"

    REMOTE_COMMIT=$(get_remote_commit)
    LOCAL_COMMIT=$(get_local_commit)

    if [ "$REMOTE_COMMIT" = "unknown" ]; then
        log_warn "Could not fetch remote commit (network issue?)"
        continue
    fi

    if [ "$LOCAL_COMMIT" != "$REMOTE_COMMIT" ]; then
        log_warn "New commit detected!"
        log "  Local:  ${LOCAL_COMMIT:0:8}"
        log "  Remote: ${REMOTE_COMMIT:0:8}"

        update_and_restart

        LOCAL_COMMIT=$(get_local_commit)
        log_success "Now running commit: ${LOCAL_COMMIT:0:8}"
    fi
done
