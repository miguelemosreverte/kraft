#!/bin/bash
# Stress Benchmark - Load Shedding Comparison
#
# Self-contained script that:
#   1. Builds Docker image for current branch
#   2. Starts container with proper ports
#   3. Runs escalating stress test
#   4. Collects Prometheus metrics
#   5. Generates SQL output (appends to seed_data.sql)
#   6. Cleans up
#
# Usage:
#   ./stress_benchmark.sh [output_file] [test_type]
#
# Test types:
#   no_protection - No load shedding (server collapses under load)
#   app_level     - Application-level 503 rejection
#   kernel_level  - Kernel-level iptables rejection
#
# If test_type not provided, detects from branch name

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_FILE="${1:-${PROJECT_DIR}/docs/seed_data.sql}"

# Configuration
CONTAINER_NAME="kraft-stress-bench"
IMAGE_NAME="kraft-stress-bench"
DURATION_PER_STAGE=20
THREADS=4
WARMUP_DURATION=5
SERVER_PORT=8080
METRICS_PORT=9090

# Connection levels to test (escalating pressure) - reduced for faster runs
CONNECTION_LEVELS=(500 800 1000 1200 1500 1800 2000)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Cleanup function
cleanup() {
    log_info "Cleaning up..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
}
trap cleanup EXIT

# Detect test type from branch name
detect_test_type() {
    local branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

    case "$branch" in
        *load-shedding-none*)
            echo "no_protection"
            ;;
        *load-shedding-app*)
            echo "app_level"
            ;;
        *load-shedding-kernel*)
            echo "kernel_level"
            ;;
        *)
            echo "unknown"
            ;;
    esac
}

# Parse latency value to microseconds
parse_latency_us() {
    local val="$1"
    if [[ "$val" == *"ms" ]]; then
        echo "$val" | sed 's/ms//' | awk '{printf "%.0f", $1 * 1000}'
    elif [[ "$val" == *"us" ]]; then
        echo "$val" | sed 's/us//' | awk '{printf "%.0f", $1}'
    elif [[ "$val" == *"s" ]]; then
        echo "$val" | sed 's/s//' | awk '{printf "%.0f", $1 * 1000000}'
    else
        echo "0"
    fi
}

# Get Prometheus metric value
get_metric() {
    local metric_name="$1"
    curl -s "http://localhost:${METRICS_PORT}/metrics" 2>/dev/null | grep "^${metric_name} " | awk '{print $2}' | head -1
}

# Build Docker image with iptables support
build_docker() {
    local test_type="$1"
    log_info "Building Docker image for $test_type..."

    if [[ "$test_type" == "kernel_level" ]]; then
        # For kernel_level, use startup.sh which applies iptables BEFORE server starts
        cat > /tmp/Dockerfile.stress << 'EOF'
# Fever Scala - Kernel-Level Stress Test Image
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.0_3.7.4 AS builder
WORKDIR /app
COPY build.sbt .
COPY project/plugins.sbt project/
COPY project/build.properties project/
RUN sbt update
COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl wrk procps iptables iproute2 bash && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/scala-3.4.2/kraft-scala-assembly-0.1.0.jar ./kraft-server.jar
COPY scripts/startup.sh /app/startup.sh
COPY scripts/experiment_setup.sh /app/experiment_setup.sh
RUN chmod +x /app/startup.sh /app/experiment_setup.sh
EXPOSE 8080 9090
CMD ["/app/startup.sh"]
EOF
    else
        # For no_protection and app_level, just run the server directly
        cat > /tmp/Dockerfile.stress << 'EOF'
# Fever Scala - Stress Test Image
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
    fi

    docker build -f /tmp/Dockerfile.stress -t "$IMAGE_NAME" "$PROJECT_DIR" > /dev/null 2>&1

    if [[ $? -ne 0 ]]; then
        log_error "Docker build failed"
        docker build -f /tmp/Dockerfile.stress -t "$IMAGE_NAME" "$PROJECT_DIR" 2>&1 | tail -20
        rm /tmp/Dockerfile.stress
        exit 1
    fi
    rm /tmp/Dockerfile.stress
    log_info "Docker image built successfully"
}

# Start container
start_container() {
    log_info "Starting container..."

    # Remove existing container if any
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

    # Start with privileged mode for io_uring and iptables support
    docker run -d \
        --name "$CONTAINER_NAME" \
        --privileged \
        -p ${SERVER_PORT}:8080 \
        -p ${METRICS_PORT}:9090 \
        "$IMAGE_NAME" > /dev/null 2>&1

    if [[ $? -ne 0 ]]; then
        log_error "Failed to start container"
        exit 1
    fi

    # Wait for server to be ready
    log_info "Waiting for server to be ready..."
    for i in {1..30}; do
        if curl -s "http://localhost:${SERVER_PORT}/health" > /dev/null 2>&1; then
            log_info "Server is ready!"
            return 0
        fi
        sleep 1
    done

    log_error "Server not responding after 30 seconds"
    docker logs "$CONTAINER_NAME" 2>&1 | tail -10
    exit 1
}

# Check if metrics endpoint is available
check_metrics() {
    if curl -s "http://localhost:${METRICS_PORT}/metrics" > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Run single benchmark stage
# Note: wrk runs OUTSIDE container to test iptables protection (which only applies to external traffic)
# Baseline throughput inside container is ~500-600K RPS; external traffic via Docker is ~100K RPS
run_stage() {
    local connections=$1
    local duration=$2
    wrk -t$THREADS -c$connections -d${duration}s "http://localhost:${SERVER_PORT}/search" 2>&1
}

# Main benchmark routine
run_stress_benchmark() {
    local test_type="$1"
    local results_file="/tmp/stress_results_$$.txt"

    echo ""
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║         Stress Benchmark - Load Shedding Test              ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Test Type:    $test_type"
    echo "Branch:       $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"
    echo "Duration:     $((${#CONNECTION_LEVELS[@]} * DURATION_PER_STAGE)) seconds"
    echo "Output File:  $OUTPUT_FILE"
    echo ""

    # Check if metrics are available
    local has_metrics="no"
    if check_metrics; then
        has_metrics="yes"
        log_info "Prometheus metrics available"
    else
        log_warn "Prometheus metrics not available"
    fi

    # Warmup
    log_info "Warming up (${WARMUP_DURATION}s)..."
    wrk -t2 -c50 -d${WARMUP_DURATION}s "http://localhost:${SERVER_PORT}/search" > /dev/null 2>&1
    echo ""

    # Initialize results file
    echo "# elapsed_sec,connections,rps,success_rate,rejection_rate,avg_latency_us" > "$results_file"

    # Print header
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    printf "%-12s │ %-11s │ %-12s │ %-10s │ %-12s │ %-12s\n" \
        "Elapsed(s)" "Connections" "RPS" "Success%" "Rejections" "Latency"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    local elapsed=0
    local stage=0
    local prev_requests=0
    local prev_rejections=0

    # Get initial metrics
    if [[ "$has_metrics" == "yes" ]]; then
        prev_requests=$(get_metric "http_requests_total")
        prev_rejections=$(get_metric "http_scaling_total_rejections")
        prev_requests=${prev_requests:-0}
        prev_rejections=${prev_rejections:-0}
    fi

    for connections in "${CONNECTION_LEVELS[@]}"; do
        # Run wrk for this stage
        local wrk_output=$(run_stage $connections $DURATION_PER_STAGE)

        # Parse wrk results
        local rps=$(echo "$wrk_output" | grep "Requests/sec:" | awk '{printf "%.0f", $2}')
        local latency_raw=$(echo "$wrk_output" | grep "Latency" | head -1 | awk '{print $2}')
        local latency_us=$(parse_latency_us "$latency_raw")

        # Get metrics if available
        local success_rate="100.0"
        local rejection_rate="0"

        if [[ "$has_metrics" == "yes" ]]; then
            local curr_requests=$(get_metric "http_requests_total")
            local curr_rejections=$(get_metric "http_scaling_total_rejections")
            curr_requests=${curr_requests:-0}
            curr_rejections=${curr_rejections:-0}

            local delta_requests=$((curr_requests - prev_requests))
            local delta_rejections=$((curr_rejections - prev_rejections))

            if [[ $delta_requests -gt 0 ]] && [[ $delta_rejections -gt 0 ]]; then
                local total_attempted=$((delta_requests + delta_rejections))
                success_rate=$(awk "BEGIN {printf \"%.1f\", ($delta_requests / $total_attempted) * 100}")
            fi

            rejection_rate=$((delta_rejections / DURATION_PER_STAGE))

            prev_requests=$curr_requests
            prev_rejections=$curr_rejections
        fi

        # Handle degraded/failed state
        if [[ -z "$rps" ]] || [[ "$rps" == "0" ]]; then
            rps="0"
            success_rate="0.0"
        fi

        # Calculate elapsed time
        elapsed=$((stage * DURATION_PER_STAGE))

        # Print results
        printf "%-12s │ %-11s │ %-12s │ %-10s │ %-12s │ %-12s\n" \
            "$elapsed" "$connections" "$rps" "${success_rate}%" "$rejection_rate/s" "${latency_us}us"

        # Save to results file
        echo "$elapsed,$connections,$rps,$success_rate,$rejection_rate,$latency_us" >> "$results_file"

        stage=$((stage + 1))
    done

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # Generate SQL output
    generate_sql "$test_type" "$results_file"

    # Cleanup results file
    rm -f "$results_file"

    log_info "Results appended to: $OUTPUT_FILE"
}

# Generate SQL INSERT statements
generate_sql() {
    local test_type="$1"
    local results_file="$2"

    log_info "Generating SQL output..."

    # Create header if file doesn't exist or is empty
    if [[ ! -s "$OUTPUT_FILE" ]]; then
        cat > "$OUTPUT_FILE" << 'EOF'
-- Auto-generated by stress_benchmark.sh
-- Stress test comparison data for load shedding strategies
--
-- Test types:
--   no_protection - Server accepts all connections until collapse
--   app_level     - Application-level load shedding (HTTP 503)
--   kernel_level  - Kernel-level load shedding (iptables/connlimit)

EOF
    fi

    # Append data for this test type
    cat >> "$OUTPUT_FILE" << EOF

-- ═══════════════════════════════════════════════════════════════
-- Test Type: $test_type
-- Generated: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
-- Branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
-- ═══════════════════════════════════════════════════════════════

DELETE FROM stress_test_series WHERE test_type = '$test_type';

EOF

    # Generate INSERTs (skip header line)
    tail -n +2 "$results_file" | while IFS=',' read -r elapsed connections rps success_rate rejection_rate latency_us; do
        cat >> "$OUTPUT_FILE" << EOF
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('$test_type', $elapsed, $rps, $success_rate, $connections, $rejection_rate, $latency_us);
EOF
    done
}

# Print usage
print_usage() {
    echo "Stress Benchmark - Load Shedding Comparison"
    echo ""
    echo "Usage: $0 [output_file]"
    echo ""
    echo "This script automatically:"
    echo "  1. Builds Docker image for current branch"
    echo "  2. Starts container with proper ports"
    echo "  3. Runs escalating stress test (500 -> 2000 connections)"
    echo "  4. Collects Prometheus metrics"
    echo "  5. Generates SQL output (appends to file)"
    echo "  6. Cleans up container"
    echo ""
    echo "Branch to test_type mapping:"
    echo "  experiment/load-shedding-none      -> 'no_protection'"
    echo "  experiment/load-shedding-app-level -> 'app_level'"
    echo "  experiment/load-shedding-kernel    -> 'kernel_level'"
}

# Main
main() {
    cd "$PROJECT_DIR"

    # Check for help flag
    if [[ "$1" == "-h" ]] || [[ "$1" == "--help" ]]; then
        print_usage
        exit 0
    fi

    # Check prerequisites
    if ! command -v wrk &> /dev/null; then
        log_error "wrk is not installed. Please install it first."
        exit 1
    fi

    if ! command -v docker &> /dev/null; then
        log_error "docker is not installed. Please install it first."
        exit 1
    fi

    # Get test_type from second argument or detect from branch
    local test_type="${2:-}"

    if [[ -z "$test_type" ]]; then
        test_type=$(detect_test_type)
    fi

    if [[ "$test_type" == "unknown" ]]; then
        log_warn "Could not detect test type from branch name."
        echo "Current branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"
        echo ""
        echo "Usage: $0 [output_file] [test_type]"
        echo ""
        echo "Test types:"
        echo "  no_protection  - No load shedding"
        echo "  app_level      - Application-level 503 rejection"
        echo "  kernel_level   - Kernel-level iptables rejection"
        echo ""
        read -p "Enter test type manually (no_protection/app_level/kernel_level): " test_type

        if [[ ! "$test_type" =~ ^(no_protection|app_level|kernel_level)$ ]]; then
            log_error "Invalid test type. Exiting."
            exit 1
        fi
    fi

    # Build and start (kernel_level has iptables baked into image via startup.sh)
    build_docker "$test_type"
    start_container

    # Run benchmark
    run_stress_benchmark "$test_type"

    echo ""
    log_info "Benchmark complete!"
}

main "$@"
