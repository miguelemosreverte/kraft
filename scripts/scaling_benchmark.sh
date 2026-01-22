#!/bin/bash
# Scaling Signals Benchmark
#
# Demonstrates scaling signals by gradually increasing load.
# Shows the correlation between saturation, RPS, and when scale-out is needed.
# Uses synchronous wrk bursts for reliable RPS measurement.
#
# Output: Appends to docs/seed_data.sql with scaling_signals_series data
#
# Usage: ./scaling_benchmark.sh [output_file]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_FILE="${1:-${PROJECT_DIR}/docs/seed_data.sql}"

# Configuration
CONTAINER_NAME="kraft-scaling-bench"
IMAGE_NAME="kraft-scaling-bench"
CONNECTION_LIMIT=500
BURST_DURATION=5       # Duration of each wrk burst
WARMUP_CONNECTIONS=100
MAX_CONNECTIONS=800    # Go beyond limit to show saturation > 100%
CONNECTION_STEP=100

SERVER_PORT=8080
METRICS_PORT=9090
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

# Get REAL active connections from server metrics
get_active_connections() {
    get_metric "http_connections_active" || echo "0"
}

# Get REAL saturation ratio from server metrics
get_saturation_ratio() {
    get_metric "http_scaling_saturation_ratio" || echo "0"
}

# Get REAL scale-out signal from server metrics
get_needs_scaleout() {
    get_metric "http_scaling_needs_scaleout" || echo "0"
}

# Build Docker image with iptables support for Scala
build_docker() {
    log_info "Building Docker image with iptables support..."

    # Create Dockerfile for scaling benchmarking with iptables
    cat > /tmp/Dockerfile.scaling << 'EOF'
# Fever Scala - Scaling Benchmark Image
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

    docker build -f /tmp/Dockerfile.scaling -t "$IMAGE_NAME" "$PROJECT_DIR" > /dev/null 2>&1

    if [[ $? -ne 0 ]]; then
        log_error "Docker build failed"
        docker build -f /tmp/Dockerfile.scaling -t "$IMAGE_NAME" "$PROJECT_DIR" 2>&1 | tail -20
        rm /tmp/Dockerfile.scaling
        exit 1
    fi
    rm /tmp/Dockerfile.scaling
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

# Initialize connection tracking (no iptables blocking - just tracking)
# Saturation is calculated as current_connections / CONNECTION_LIMIT
init_connection_tracking() {
    log_info "Connection limit for saturation calculation: $CONNECTION_LIMIT"
}

# Warmup the JVM
warmup_server() {
    log_info "Warming up server..."
    wrk -t2 -c100 -d5s "http://localhost:${SERVER_PORT}/search" > /dev/null 2>&1
    wrk -t$WRK_THREADS -c200 -d5s "http://localhost:${SERVER_PORT}/search" > /dev/null 2>&1
    log_info "Warmup complete"
}

# Run benchmark with gradual load increase using synchronous wrk bursts and REAL metrics
run_benchmark() {
    local results_file="/tmp/scaling_results_$$.txt"

    echo ""
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║           Scaling Signals Benchmark                        ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Connection Limit: $CONNECTION_LIMIT"
    echo "Load Progression: $WARMUP_CONNECTIONS → $MAX_CONNECTIONS (step: $CONNECTION_STEP)"
    echo "Burst Duration:   ${BURST_DURATION}s"
    echo "Metrics:          http://localhost:${METRICS_PORT}/metrics (REAL)"
    echo ""

    echo "# elapsed,rejection_rate,saturation_ratio,needs_scaleout,connection_limit,active_connections,observed_rps" > "$results_file"

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    printf "%-8s │ %-10s │ %-10s │ %-12s │ %-12s │ %-10s\n" "Elapsed" "RPS" "Active" "Saturation" "Target Conn" "Scale Out?"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    local elapsed=0
    local current_connections=$WARMUP_CONNECTIONS
    local measurements_per_level=2  # 2 measurements at each connection level

    while [ $current_connections -le $MAX_CONNECTIONS ]; do
        for m in $(seq 1 $measurements_per_level); do
            elapsed=$((elapsed + BURST_DURATION))

            # Run a wrk burst and capture metrics DURING the load
            # Returns: rps,active,saturation,scaleout
            local result=$(run_wrk_burst_with_metrics $current_connections $BURST_DURATION)

            # Parse CSV result
            local rps=$(echo "$result" | cut -d',' -f1)
            local active_conns=$(echo "$result" | cut -d',' -f2)
            local saturation_ratio=$(echo "$result" | cut -d',' -f3)
            local needs_scaleout=$(echo "$result" | cut -d',' -f4)

            # Handle empty values
            [[ -z "$rps" ]] && rps=0
            [[ -z "$active_conns" ]] && active_conns=0
            [[ -z "$saturation_ratio" ]] && saturation_ratio="0.0"
            [[ -z "$needs_scaleout" ]] && needs_scaleout=0

            # Format for display
            local saturation_pct=$(awk "BEGIN {printf \"%.1f\", $saturation_ratio * 100}")
            local scale_text="No"
            [[ "$needs_scaleout" == "1" ]] && scale_text="YES"

            printf "%-8s │ %-10s │ %-10s │ %-12s │ %-12s │ %-10s\n" "${elapsed}s" "$rps" "$active_conns" "${saturation_pct}%" "$current_connections" "$scale_text"

            # Store REAL values from server
            echo "$elapsed,0,$saturation_ratio,$needs_scaleout,$CONNECTION_LIMIT,$active_conns,$rps" >> "$results_file"
        done

        # Increase connections for next level
        current_connections=$((current_connections + CONNECTION_STEP))
        if [ $current_connections -le $MAX_CONNECTIONS ]; then
            log_info "Increasing load to $current_connections connections..."
        fi
    done

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    generate_sql "$results_file"
    rm -f "$results_file"

    log_info "Results appended to: $OUTPUT_FILE"
}

# Generate SQL
generate_sql() {
    local results_file="$1"

    log_info "Generating SQL output..."

    cat >> "$OUTPUT_FILE" << EOF

-- ═══════════════════════════════════════════════════════════════
-- Scaling Signals Series (Chapter 8)
-- Generated: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
-- Metrics source: Real server metrics from port ${METRICS_PORT}
-- ═══════════════════════════════════════════════════════════════

DELETE FROM scaling_signals_series;

EOF

    tail -n +2 "$results_file" | while IFS=',' read -r elapsed rejection_rate saturation_ratio needs_scaleout conn_limit active rps; do
        cat >> "$OUTPUT_FILE" << EOF
INSERT INTO scaling_signals_series (elapsed_seconds, rejection_rate, saturation_ratio, needs_scaleout, connection_limit, active_connections, observed_rps) VALUES
($elapsed, $rejection_rate, $saturation_ratio, $needs_scaleout, $conn_limit, $active, $rps);
EOF
    done
}

# Main
main() {
    cd "$PROJECT_DIR"

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
    init_connection_tracking
    warmup_server
    run_benchmark

    log_info "Scaling benchmark complete!"
}

main "$@"
