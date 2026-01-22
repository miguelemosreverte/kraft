#!/bin/bash
#
# Node Auto-Updater
# Polls GitHub for new commits and automatically restarts the node when code changes
#
# Usage: ./node-autoupdate.sh [poll_interval_seconds] [node_port]
#
# Environment variables:
#   KRAFT_REPO_URL - Git repository URL (default: origin)
#   KRAFT_BRANCH   - Branch to track (default: main)
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

update_and_restart() {
    log "Pulling latest changes..."

    # Stash any local changes
    git stash 2>/dev/null || true

    # Pull latest
    if git pull "$REPO_URL" "$BRANCH"; then
        log_success "Code updated successfully"

        # Compile
        log "Compiling..."
        if sbt compile; then
            log_success "Compilation successful"

            # Restart node
            start_node
            return 0
        else
            log_error "Compilation failed!"
            return 1
        fi
    else
        log_error "Git pull failed!"
        return 1
    fi
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
