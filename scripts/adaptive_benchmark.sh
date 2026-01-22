#!/bin/bash
# Adaptive Limiter Benchmark
#
# Demonstrates the adaptive connection limiter finding the optimal limit.
# Runs synchronized wrk bursts and parses output directly for reliable RPS.
#
# Output: Appends to docs/seed_data.sql with adaptive_limiter_series data
#
# Usage: ./adaptive_benchmark.sh [output_file]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_FILE="${1:-${PROJECT_DIR}/docs/seed_data.sql}"

# Configuration
CONTAINER_NAME="kraft-adaptive-bench"
IMAGE_NAME="kraft-adaptive-bench"
ITERATIONS=18          # Number of measurement iterations
BURST_DURATION=5       # Seconds per measurement burst
INITIAL_LIMIT=800      # Starting connection limit
MIN_LIMIT=200
MAX_LIMIT=1500
STEP=100
THRESHOLD=10           # % drop to trigger decrease
STABLE_PERIODS=2       # Stable periods before increase

SERVER_PORT=8080
METRICS_PORT=9090
WRK_CONNECTIONS=1000   # Constant load from wrk
WRK_THREADS=4

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# Cleanup
cleanup() {
    log_info "Cleaning up..."
    pkill -f "wrk.*localhost:${SERVER_PORT}" 2>/dev/null || true
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
}
trap cleanup EXIT

# Run a wrk burst, capture metrics DURING load, return "rps,active,saturation,scaleout"
# This captures real active connections while load is applied
run_wrk_burst_with_metrics() {
    local connections=$1
    local duration=$2

    # Start wrk in background
    wrk -t$WRK_THREADS -c$connections -d${duration}s "http://localhost:${SERVER_PORT}/search" > /tmp/wrk_output_$$.txt 2>&1 &
    local wrk_pid=$!

    # Wait for connections to establish then capture metrics
    sleep 2

    # Fetch full metrics output
    local metrics_output=$(curl -s "http://localhost:${METRICS_PORT}/metrics" 2>/dev/null)

    # Parse metrics directly from output
    local active=$(echo "$metrics_output" | grep "^http_connections_active " | awk '{print $2}' | head -1)
    local saturation=$(echo "$metrics_output" | grep "^http_scaling_saturation_ratio " | awk '{print $2}' | head -1)
    local scaleout=$(echo "$metrics_output" | grep "^http_scaling_needs_scaleout " | awk '{print $2}' | head -1)

    # Wait for wrk to complete
    wait $wrk_pid 2>/dev/null || true

    # Parse RPS from output
    local rps=$(grep "Requests/sec:" /tmp/wrk_output_$$.txt 2>/dev/null | awk '{printf "%.0f", $2}')
    rm -f /tmp/wrk_output_$$.txt

    # Return all values as CSV: rps,active,saturation,scaleout
    echo "${rps:-0},${active:-0},${saturation:-0},${scaleout:-0}"
}

# Get metric value from Prometheus endpoint
get_metric() {
    local metric_name="$1"
    curl -s "http://localhost:${METRICS_PORT}/metrics" 2>/dev/null | \
        grep "^${metric_name} " | awk '{print $2}' | head -1
}

# Get active connections from real server metrics
get_active_connections() {
    get_metric "http_connections_active" || echo "0"
}

# Get saturation ratio from real server metrics
get_saturation_ratio() {
    get_metric "http_scaling_saturation_ratio" || echo "0"
}

# Get scale-out signal from real server metrics
get_needs_scaleout() {
    get_metric "http_scaling_needs_scaleout" || echo "0"
}

# Update limit (tracking only - the adaptive algorithm adjusts based on RPS)
update_limit() {
    local new_limit=$1
    CURRENT_LIMIT=$new_limit
}

# Build Docker image with iptables support for Scala
build_docker() {
    log_info "Building Docker image with iptables support..."

    # Create Dockerfile for adaptive benchmarking with iptables
    cat > /tmp/Dockerfile.adaptive << 'EOF'
# Fever Scala - Adaptive Benchmark Image
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.0_3.7.4 AS builder
WORKDIR /app
COPY build.sbt .
COPY project/plugins.sbt project/
COPY project/build.properties project/
RUN sbt update
COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl wrk procps iptables iproute2 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/scala-3.4.2/kraft-scala-assembly-0.1.0.jar ./kraft-server.jar
EXPOSE 8080 9090
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx512m", "-jar", "kraft-server.jar", "8080"]
EOF

    docker build -f /tmp/Dockerfile.adaptive -t "$IMAGE_NAME" "$PROJECT_DIR" > /dev/null 2>&1

    if [[ $? -ne 0 ]]; then
        log_error "Docker build failed"
        docker build -f /tmp/Dockerfile.adaptive -t "$IMAGE_NAME" "$PROJECT_DIR" 2>&1 | tail -20
        rm /tmp/Dockerfile.adaptive
        exit 1
    fi
    rm /tmp/Dockerfile.adaptive
    log_info "Docker image built"
}

# Start container
start_container() {
    log_info "Starting container..."
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

    docker run -d \
        --name "$CONTAINER_NAME" \
        --privileged \
        -p ${SERVER_PORT}:8080 \
        -p ${METRICS_PORT}:9090 \
        "$IMAGE_NAME" > /dev/null 2>&1

    # Wait for server
    log_info "Waiting for server..."
    for i in {1..30}; do
        if curl -s "http://localhost:${SERVER_PORT}/health" > /dev/null 2>&1; then
            log_info "Server ready"
            return 0
        fi
        sleep 1
    done

    log_warn "Server not responding"
    exit 1
}

# Initialize connection limit (for display/tracking only - no iptables)
# The adaptive benchmark shows how a server would adjust limits based on RPS changes
init_limit() {
    log_info "Initial limit set to: $INITIAL_LIMIT (no iptables blocking)"
    CURRENT_LIMIT=$INITIAL_LIMIT
}

# Warmup the JVM
warmup_server() {
    log_info "Warming up server..."
    wrk -t2 -c100 -d5s "http://localhost:${SERVER_PORT}/search" > /dev/null 2>&1
    wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d5s "http://localhost:${SERVER_PORT}/search" > /dev/null 2>&1
    log_info "Warmup complete"
}

# Run adaptive limiter with synchronous wrk bursts and real metrics
run_adaptive_limiter() {
    local results_file="/tmp/adaptive_results_$$.txt"

    echo ""
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║           Adaptive Limiter Benchmark                       ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Iterations:    $ITERATIONS × ${BURST_DURATION}s"
    echo "Initial Limit: $INITIAL_LIMIT"
    echo "Limit Range:   $MIN_LIMIT - $MAX_LIMIT"
    echo "Load:          $WRK_CONNECTIONS connections"
    echo "Metrics:       http://localhost:${METRICS_PORT}/metrics"
    echo ""

    # Initialize results file with header
    echo "# elapsed,limit,rps,rejection_rate,active_conns,saturation,action" > "$results_file"

    # Print header
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    printf "%-8s │ %-6s │ %-8s │ %-8s │ %-10s │ %-20s\n" "Elapsed" "Limit" "RPS" "Active" "Saturation" "Action"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    local elapsed=0
    local peak_rps=0
    local stable_count=0
    local action="-"
    local iteration=0

    while [ $iteration -lt $ITERATIONS ]; do
        iteration=$((iteration + 1))
        elapsed=$((iteration * BURST_DURATION))

        # Run a wrk burst and capture metrics DURING the load
        # Returns: rps,active,saturation,scaleout
        local result=$(run_wrk_burst_with_metrics $WRK_CONNECTIONS $BURST_DURATION)

        # Parse CSV result
        local current_rps=$(echo "$result" | cut -d',' -f1)
        local active_conns=$(echo "$result" | cut -d',' -f2)
        local saturation=$(echo "$result" | cut -d',' -f3)
        local rejection_rate=0

        # Handle empty values
        [[ -z "$current_rps" ]] && current_rps=0
        [[ -z "$active_conns" ]] && active_conns=0
        [[ -z "$saturation" ]] && saturation="0.0"

        # Format saturation as percentage for display
        local saturation_pct=$(awk "BEGIN {printf \"%.1f\", $saturation * 100}")

        action="stable"

        # Skip if no traffic (unlikely with direct wrk)
        if [ "$current_rps" -lt 1000 ]; then
            action="low traffic"
            printf "%-8s │ %-6s │ %-8s │ %-8s │ %-10s │ %-20s\n" "${elapsed}s" "$CURRENT_LIMIT" "$current_rps" "$active_conns" "${saturation_pct}%" "$action"
            echo "$elapsed,$CURRENT_LIMIT,$current_rps,$rejection_rate,$active_conns,$saturation,$action" >> "$results_file"
            continue
        fi

        # Update peak
        if [ "$current_rps" -gt "$peak_rps" ]; then
            peak_rps=$current_rps
            stable_count=0
            action="new peak"
        else
            # Check for degradation
            if [ "$peak_rps" -gt 0 ]; then
                local drop_pct=$(( (peak_rps - current_rps) * 100 / peak_rps ))

                if [ "$drop_pct" -gt "$THRESHOLD" ]; then
                    # Performance degraded - decrease limit
                    local new_limit=$((CURRENT_LIMIT - STEP))
                    if [ "$new_limit" -ge "$MIN_LIMIT" ]; then
                        update_limit $new_limit
                        stable_count=0
                        peak_rps=$current_rps
                        action="DECREASE (-${drop_pct}%)"
                    else
                        action="at minimum"
                    fi
                elif [ "$drop_pct" -lt 5 ]; then
                    # Stable
                    stable_count=$((stable_count + 1))

                    if [ "$stable_count" -ge "$STABLE_PERIODS" ]; then
                        # Try increasing
                        local new_limit=$((CURRENT_LIMIT + STEP))
                        if [ "$new_limit" -le "$MAX_LIMIT" ]; then
                            update_limit $new_limit
                            stable_count=0
                            action="INCREASE (stable)"
                        else
                            action="at maximum"
                        fi
                    else
                        action="stable ($stable_count/$STABLE_PERIODS)"
                    fi
                else
                    stable_count=0
                    action="variance (${drop_pct}%)"
                fi
            fi
        fi

        printf "%-8s │ %-6s │ %-8s │ %-8s │ %-10s │ %-20s\n" "${elapsed}s" "$CURRENT_LIMIT" "$current_rps" "$active_conns" "${saturation_pct}%" "$action"
        echo "$elapsed,$CURRENT_LIMIT,$current_rps,$rejection_rate,$active_conns,$saturation,$action" >> "$results_file"
    done

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # Generate SQL
    generate_sql "$results_file"
    rm -f "$results_file"

    log_info "Results appended to: $OUTPUT_FILE"
}

# Generate SQL output
generate_sql() {
    local results_file="$1"

    log_info "Generating SQL output..."

    # Append adaptive limiter data
    cat >> "$OUTPUT_FILE" << EOF

-- ═══════════════════════════════════════════════════════════════
-- Adaptive Limiter Series (Chapter 7)
-- Generated: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
-- Metrics source: Real server metrics from port ${METRICS_PORT}
-- ═══════════════════════════════════════════════════════════════

DELETE FROM adaptive_limiter_series;

EOF

    # Generate INSERTs (skip header)
    # Format: elapsed,limit,rps,rejection_rate,active_conns,saturation,action
    tail -n +2 "$results_file" | while IFS=',' read -r elapsed limit rps rejection_rate active_conns saturation action; do
        cat >> "$OUTPUT_FILE" << EOF
INSERT INTO adaptive_limiter_series (elapsed_seconds, connection_limit, observed_rps, rejection_rate, active_connections, saturation_ratio, action) VALUES
($elapsed, $limit, $rps, $rejection_rate, $active_conns, $saturation, '$action');
EOF
    done
}

# Main
main() {
    cd "$PROJECT_DIR"

    # Check prerequisites
    if ! command -v wrk &> /dev/null; then
        log_warn "wrk not installed"
        exit 1
    fi

    if ! command -v docker &> /dev/null; then
        log_warn "docker not installed"
        exit 1
    fi

    build_docker
    start_container
    init_limit
    warmup_server
    run_adaptive_limiter

    log_info "Adaptive benchmark complete!"
}

main "$@"
