#!/bin/bash
# Adaptive Connection Limiter
#
# Uses iptables to limit connections at the kernel level.
# Monitors RPS via Prometheus and auto-adjusts the limit.
#
# Requires: root/privileged access for iptables
#
# Usage:
#   ./adaptive_limiter.sh [initial_limit] [min_limit] [max_limit] [duration]
#
# Example:
#   ./adaptive_limiter.sh 500 100 2000 120

set -e

INITIAL_LIMIT=${1:-500}
MIN_LIMIT=${2:-100}
MAX_LIMIT=${3:-2000}
DURATION=${4:-120}
METRICS_URL=${METRICS_URL:-"http://localhost:9090/metrics"}
INTERVAL=5
THRESHOLD=10  # % drop threshold
STEP=100
PORT=8080

CURRENT_LIMIT=$INITIAL_LIMIT
PEAK_RPS=0
STABLE_COUNT=0
STABLE_PERIODS_REQUIRED=3
START_TIME=$(date +%s)

echo "============================================================"
echo "Adaptive Connection Limiter"
echo "============================================================"
echo "Duration: ${DURATION}s"
echo "Initial limit: $INITIAL_LIMIT"
echo "Min/Max: $MIN_LIMIT / $MAX_LIMIT"
echo "============================================================"
echo ""

# Check iptables access
if ! iptables -L -n > /dev/null 2>&1; then
    echo "ERROR: iptables requires root/privileged access"
    echo "Run with: sudo ./adaptive_limiter.sh"
    exit 1
fi

# Update iptables connection limit
update_limit() {
    local new_limit=$1

    # Remove old rule
    iptables -D INPUT -p tcp --syn --dport $PORT -m connlimit --connlimit-above $CURRENT_LIMIT --connlimit-mask 0 -j REJECT --reject-with tcp-reset 2>/dev/null || true

    # Add new rule
    iptables -A INPUT -p tcp --syn --dport $PORT -m connlimit --connlimit-above $new_limit --connlimit-mask 0 -j REJECT --reject-with tcp-reset

    CURRENT_LIMIT=$new_limit
    echo "[$(date +%H:%M:%S)] Limit updated: $CURRENT_LIMIT connections"
}

# Get request count from Prometheus metrics
get_request_count() {
    local count=$(curl -s "$METRICS_URL" 2>/dev/null | grep "^kraft_requests_total " | awk '{print int($2)}')
    echo "${count:-0}"
}

# Get rejected connection count from iptables
get_rejected_connections() {
    local raw=$(iptables -L INPUT -v -n 2>/dev/null | grep "REJECT.*#conn" | awk '{print $1}')
    if [[ "$raw" == *K ]]; then
        local num=${raw%K}
        echo $((num * 1000))
    elif [[ "$raw" == *M ]]; then
        local num=${raw%M}
        echo $((num * 1000000))
    else
        echo "${raw:-0}"
    fi
}

# Cleanup on exit
cleanup() {
    echo ""
    echo "Cleaning up..."
    iptables -D INPUT -p tcp --syn --dport $PORT -m connlimit --connlimit-above $CURRENT_LIMIT --connlimit-mask 0 -j REJECT --reject-with tcp-reset 2>/dev/null || true
    rm -f /tmp/scaling_metrics
    echo ""
    echo "============================================================"
    echo "Final limit: $CURRENT_LIMIT"
    echo "Peak RPS: $PEAK_RPS"
    echo "============================================================"
}
trap cleanup EXIT

# Set initial limit
echo "Setting initial limit: $INITIAL_LIMIT"
update_limit $INITIAL_LIMIT
echo ""
echo "Monitoring started. Will run for ${DURATION}s."
echo ""
echo "Time     | Elapsed | Limit | RPS     | Peak    | Action"
echo "---------|---------|-------|---------|---------|------------------"

LAST_COUNT=$(get_request_count)
LAST_TIME=$(date +%s)
LAST_REJECTED=0

while true; do
    sleep $INTERVAL

    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))

    # Check if duration exceeded
    if [ $ELAPSED -ge $DURATION ]; then
        echo ""
        echo "Duration reached ($DURATION seconds). Stopping..."
        break
    fi

    # Calculate RPS from request delta
    CURRENT_COUNT=$(get_request_count)
    TIME_DELTA=$((CURRENT_TIME - LAST_TIME))

    if [ $TIME_DELTA -gt 0 ]; then
        CURRENT_RPS=$(( (CURRENT_COUNT - LAST_COUNT) / TIME_DELTA ))
    else
        CURRENT_RPS=0
    fi

    LAST_COUNT=$CURRENT_COUNT
    LAST_TIME=$CURRENT_TIME

    # Get rejection data
    CURRENT_REJECTED=$(get_rejected_connections)
    REJECTED_DELTA=$((CURRENT_REJECTED - LAST_REJECTED))
    if [ $TIME_DELTA -gt 0 ] && [ $REJECTED_DELTA -gt 0 ]; then
        REJECTION_RATE=$((REJECTED_DELTA / TIME_DELTA))
    else
        REJECTION_RATE=0
    fi
    LAST_REJECTED=$CURRENT_REJECTED

    # Write metrics for Prometheus scraping
    echo "rejection_rate $REJECTION_RATE" > /tmp/scaling_metrics
    echo "connection_limit $CURRENT_LIMIT" >> /tmp/scaling_metrics
    echo "total_rejections $CURRENT_REJECTED" >> /tmp/scaling_metrics

    ACTION="-"

    # Skip if no significant traffic
    if [ $CURRENT_RPS -lt 1000 ]; then
        ACTION="Low traffic"
        printf "%s | %5ds  | %5d | %7d | %7d | %s\n" "$(date +%H:%M:%S)" "$ELAPSED" "$CURRENT_LIMIT" "$CURRENT_RPS" "$PEAK_RPS" "$ACTION"
        continue
    fi

    # Update peak if new high
    if [ $CURRENT_RPS -gt $PEAK_RPS ]; then
        PEAK_RPS=$CURRENT_RPS
        STABLE_COUNT=0
        ACTION="New peak!"
    else
        # Check for degradation
        if [ $PEAK_RPS -gt 0 ]; then
            DROP_PCT=$(( (PEAK_RPS - CURRENT_RPS) * 100 / PEAK_RPS ))

            if [ $DROP_PCT -gt $THRESHOLD ]; then
                # Performance degraded - decrease limit
                NEW_LIMIT=$((CURRENT_LIMIT - STEP))
                if [ $NEW_LIMIT -ge $MIN_LIMIT ]; then
                    update_limit $NEW_LIMIT
                    STABLE_COUNT=0
                    PEAK_RPS=$CURRENT_RPS
                    ACTION="DECREASE (${DROP_PCT}% drop)"
                else
                    ACTION="At minimum"
                fi
            elif [ $DROP_PCT -lt 5 ]; then
                # Performance stable
                STABLE_COUNT=$((STABLE_COUNT + 1))
                if [ $STABLE_COUNT -ge $STABLE_PERIODS_REQUIRED ]; then
                    # Try increasing limit
                    NEW_LIMIT=$((CURRENT_LIMIT + STEP))
                    if [ $NEW_LIMIT -le $MAX_LIMIT ]; then
                        update_limit $NEW_LIMIT
                        STABLE_COUNT=0
                        ACTION="INCREASE (exploring)"
                    else
                        ACTION="At maximum"
                    fi
                else
                    ACTION="Stable ($STABLE_COUNT/$STABLE_PERIODS_REQUIRED)"
                fi
            else
                STABLE_COUNT=0
                ACTION="Minor variance (${DROP_PCT}%)"
            fi
        fi
    fi

    printf "%s | %5ds  | %5d | %7d | %7d | %s\n" "$(date +%H:%M:%S)" "$ELAPSED" "$CURRENT_LIMIT" "$CURRENT_RPS" "$PEAK_RPS" "$ACTION"
done
