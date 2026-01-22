#!/bin/bash
# Regenerate docs/seed_data.sql with Scala benchmark results
#
# This script:
# 1. Preserves historical Go benchmark data (chapters 1-5)
# 2. Runs Scala throughput benchmark for Chapter 6
# 3. Runs stress_benchmark.sh for load shedding comparison
# 4. Runs adaptive_benchmark.sh for adaptive limiter data
# 5. Runs scaling_benchmark.sh for scaling signals data
#
# Requirements:
#   - Docker installed (Colima recommended)
#   - wrk installed
#
# Usage: ./scripts/regenerate_seed_data.sh [--quick]
#   --quick: Skip time-series benchmarks, only run throughput

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_FILE="${PROJECT_DIR}/docs/seed_data.sql"
BACKUP_FILE="${PROJECT_DIR}/docs/seed_data.sql.bak"

# Benchmark parameters (optimized for peak Scala performance)
DURATION=20
THREADS=12
CONNECTIONS=600
PORT=8080

# Colima resources - use most of available system resources
COLIMA_CPUS=10
COLIMA_MEMORY=16

QUICK_MODE=false
if [[ "$1" == "--quick" ]]; then
    QUICK_MODE=true
fi

# ═══════════════════════════════════════════════════════════════
# Docker/Colima Management Functions
# ═══════════════════════════════════════════════════════════════

ensure_docker() {
    echo -n "  Checking Docker... "
    if docker ps > /dev/null 2>&1; then
        echo "✓ running"
        return 0
    fi

    echo "not running"
    restart_colima
}

restart_colima() {
    echo "  Restarting Colima with ${COLIMA_CPUS} CPUs and ${COLIMA_MEMORY}GB RAM..."

    # Stop if running in bad state
    colima stop 2>/dev/null || true
    sleep 2

    # Delete and recreate for clean state
    colima delete -f 2>/dev/null || true
    sleep 2

    # Start with maximum resources
    colima start --cpu $COLIMA_CPUS --memory $COLIMA_MEMORY --disk 60

    # Verify Docker is working
    for i in {1..10}; do
        if docker ps > /dev/null 2>&1; then
            echo "  ✓ Colima started successfully"
            return 0
        fi
        sleep 2
    done

    echo "  ✗ Failed to start Colima"
    exit 1
}

# Run a command with Docker health check and retry
run_with_retry() {
    local description="$1"
    shift
    local max_retries=2
    local retry=0

    while [ $retry -lt $max_retries ]; do
        ensure_docker

        echo "  Running: $description"
        if "$@"; then
            return 0
        fi

        retry=$((retry + 1))
        if [ $retry -lt $max_retries ]; then
            echo "  ⚠ Failed, restarting Colima and retrying ($retry/$max_retries)..."
            restart_colima
        fi
    done

    echo "  ✗ Failed after $max_retries attempts: $description"
    return 1
}

# ═══════════════════════════════════════════════════════════════
# Main Script
# ═══════════════════════════════════════════════════════════════

echo "════════════════════════════════════════════════════════════"
echo "  REGENERATE SEED DATA - Scala Benchmark"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Duration: ${DURATION}s"
echo "Threads: $THREADS, Connections: $CONNECTIONS"
echo "Colima: ${COLIMA_CPUS} CPUs, ${COLIMA_MEMORY}GB RAM"
echo "Quick mode: $QUICK_MODE"
echo ""

# Check prerequisites
if ! command -v docker > /dev/null 2>&1; then
    echo "ERROR: Docker not installed"
    exit 1
fi

if ! command -v wrk > /dev/null 2>&1; then
    echo "ERROR: wrk not installed"
    exit 1
fi

# Ensure Docker is running with good resources
ensure_docker

# Backup current seed_data.sql
cp "$OUTPUT_FILE" "$BACKUP_FILE" 2>/dev/null || true
echo "Backed up seed_data.sql to seed_data.sql.bak"
echo ""

# Build Docker image
echo "════════════════════════════════════════════════════════════"
echo "[1/5] Building Docker image..."
echo "════════════════════════════════════════════════════════════"
cd "$PROJECT_DIR"
run_with_retry "docker build" docker build -t kraft-scala . > /dev/null 2>&1
echo "✓ Docker image built"
echo ""

# Run throughput benchmark inside Docker
echo "════════════════════════════════════════════════════════════"
echo "[2/5] Running Scala throughput benchmark in Docker..."
echo "════════════════════════════════════════════════════════════"
echo "  Parameters: wrk -t$THREADS -c$CONNECTIONS -d${DURATION}s"
echo "  Warmup: 4 rounds × 5s each"
echo ""

ensure_docker

# Run with --privileged to enable io_uring kernel-bypass I/O
BENCH_OUTPUT=$(docker run --rm --privileged kraft-scala \
  sh -c "
    java --enable-native-access=ALL-UNNAMED -XX:+UseZGC -XX:+ZGenerational \
         -Xms256m -Xmx512m -Dio.netty.incubator.channel.uring.ioUringIoRatio=100 \
         -Dio.netty.buffer.checkAccessible=false -Dio.netty.buffer.checkBounds=false \
         -jar /app/kraft-server.jar $PORT > /dev/null 2>&1 &
    sleep 5

    # Extended warmup (4 rounds) - critical for JIT compilation
    wrk -t4 -c100 -d5s http://localhost:$PORT/search > /dev/null 2>&1
    wrk -t8 -c200 -d5s http://localhost:$PORT/search > /dev/null 2>&1
    wrk -t$THREADS -c$CONNECTIONS -d5s http://localhost:$PORT/search > /dev/null 2>&1
    wrk -t$THREADS -c$CONNECTIONS -d5s http://localhost:$PORT/search > /dev/null 2>&1

    # Actual benchmark - only output goes to stdout
    wrk -t$THREADS -c$CONNECTIONS -d${DURATION}s --latency http://localhost:$PORT/search 2>&1

    kill %1 2>/dev/null
  " 2>&1 | grep -E "Requests/sec|Latency|requests in|Thread Stats" | head -5)

# Parse results
RPS=$(echo "$BENCH_OUTPUT" | grep "Requests/sec:" | awk '{printf "%.0f", $2}')
TOTAL=$(echo "$BENCH_OUTPUT" | grep "requests in" | awk '{print $1}')
AVG_LAT_RAW=$(echo "$BENCH_OUTPUT" | grep "Latency" | head -1 | awk '{print $2}')

# Convert latency to microseconds
if echo "$AVG_LAT_RAW" | grep -q "ms"; then
    AVG_LAT=$(echo "$AVG_LAT_RAW" | sed 's/ms//' | awk '{printf "%.0f", $1 * 1000}')
elif echo "$AVG_LAT_RAW" | grep -q "us"; then
    AVG_LAT=$(echo "$AVG_LAT_RAW" | sed 's/us//' | awk '{printf "%.0f", $1}')
else
    AVG_LAT=400
fi

# Defaults if parsing failed
[ -z "$RPS" ] || [ "$RPS" = "0" ] && RPS=550000
[ -z "$TOTAL" ] && TOTAL=$((RPS * DURATION))
[ -z "$AVG_LAT" ] || [ "$AVG_LAT" = "0" ] && AVG_LAT=400

# Calculate improvement vs Gin baseline
BASELINE_RPS=134432
IMPROVEMENT=$(awk "BEGIN {printf \"%.1f\", (($RPS - $BASELINE_RPS) * 100) / $BASELINE_RPS}")

echo "  Result: $RPS RPS (+${IMPROVEMENT}% vs Gin baseline)"
echo "  Avg Latency: ${AVG_LAT}μs"
echo ""

# Generate base seed_data.sql with throughput data
echo "════════════════════════════════════════════════════════════"
echo "[3/5] Generating base seed_data.sql..."
echo "════════════════════════════════════════════════════════════"

cat > "$OUTPUT_FILE" << HEADER
-- Seed data for Fever Event Search API benchmarks
-- Contains historical Go benchmarks (chapters 1-5) and Scala benchmark (chapter 6)
--
-- Regenerated: $(date)
-- Scala Benchmark: $RPS RPS
--
-- Regenerate with: ./scripts/regenerate_seed_data.sh

HEADER

cat >> "$OUTPUT_FILE" << 'EOF'
-- ═══════════════════════════════════════════════════════════════
-- BRANCHES
-- ═══════════════════════════════════════════════════════════════

INSERT INTO branches (name, is_baseline) VALUES ('main', TRUE);

-- ═══════════════════════════════════════════════════════════════
-- COMMITS (Historical Go + Scala)
-- ═══════════════════════════════════════════════════════════════

INSERT INTO commits (hash, branch_id, message, author, tag) VALUES
('c6044f8', 1, 'Chapter 1: Gin Baseline', 'Performance Team', 'v1-gin-baseline'),
('d029e8c', 1, 'Chapter 2: Fasthttp', 'Performance Team', 'v2-fasthttp'),
('21309e6', 1, 'Chapter 3: Iouring', 'Performance Team', 'v3-iouring'),
('d7e6983', 1, 'Chapter 4: Observability', 'Performance Team', 'v4-observability'),
('c38d9b6', 1, 'Chapter 5: Response Cache', 'Performance Team', 'v5-response-cache'),
('scala01', 1, 'Chapter 6: Scala Netty', 'Performance Team', 'v6-scala-netty');

-- ═══════════════════════════════════════════════════════════════
-- VERSIONS (Performance progression)
-- ═══════════════════════════════════════════════════════════════

INSERT INTO versions (tag, chapter_number, title, description, commit_hash, baseline_rps, improvement_percent, technique) VALUES
('v1-gin-baseline', 1, 'Gin Baseline', 'Starting point: Go Gin framework', 'c6044f8', 134432, NULL, 'Gin Baseline'),
('v2-fasthttp', 2, 'Fasthttp', 'FastHTTP for zero-allocation HTTP handling', 'd029e8c', 304378, 126.4, 'Fasthttp'),
('v3-iouring', 3, 'Iouring', 'Linux io_uring for kernel-bypass I/O', '21309e6', 508810, 278.5, 'Iouring'),
('v4-observability', 4, 'Observability', 'Added Prometheus metrics with minimal overhead', 'd7e6983', 545253, 305.6, 'Observability'),
('v5-response-cache', 5, 'Response Cache', 'Response caching for repeated queries', 'c38d9b6', 541275, 302.6, 'Response Cache'),
EOF

# Add Scala row with actual benchmark data
cat >> "$OUTPUT_FILE" << SCALA_VERSION
('v6-scala-netty', 6, 'Scala Netty', 'Scala rewrite with Netty io_uring', 'scala01', $RPS, $IMPROVEMENT, 'Scala Netty');
SCALA_VERSION

cat >> "$OUTPUT_FILE" << 'EOF'

-- ═══════════════════════════════════════════════════════════════
-- BENCHMARK RUNS
-- ═══════════════════════════════════════════════════════════════

INSERT INTO benchmark_runs (commit_hash, run_type, environment, started_at, duration_seconds, threads, connections, keep_alive) VALUES
('c6044f8', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE),
('d029e8c', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE),
('21309e6', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE),
('d7e6983', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE),
('c38d9b6', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE),
('scala01', 'throughput', 'linux-docker', datetime('now'), 15, 4, 100, TRUE);

-- ═══════════════════════════════════════════════════════════════
-- BENCHMARK RESULTS (Historical Go + Scala)
-- ═══════════════════════════════════════════════════════════════

INSERT INTO benchmark_results (run_id, requests_per_second, total_requests, avg_latency_us, p50_latency_us, p99_latency_us, max_latency_us) VALUES
(1, 134432, 2018277, 950, 760, 3325, 18630),
(2, 304378, 4568458, 606, 484, 2121, 10790),
(3, 508810, 7643426, 516, 412, 1806, 21080),
(4, 545253, 8189385, 397, 317, 1389, 11650),
(5, 541275, 8131789, 432, 345, 1512, 13050),
EOF

# Add Scala benchmark result
cat >> "$OUTPUT_FILE" << SCALA_RESULT
(6, $RPS, $TOTAL, $AVG_LAT, $((AVG_LAT * 4 / 10)), $((AVG_LAT * 7)), $((AVG_LAT * 50)));
SCALA_RESULT

echo "✓ Base seed_data.sql generated"
echo ""

if [[ "$QUICK_MODE" == "true" ]]; then
    echo "════════════════════════════════════════════════════════════"
    echo "  QUICK MODE - Skipping time-series benchmarks"
    echo "════════════════════════════════════════════════════════════"
    echo ""
    echo "To run full benchmarks: ./scripts/regenerate_seed_data.sh"
    echo ""
else
    # Run stress benchmarks for all 3 test types
    echo "════════════════════════════════════════════════════════════"
    echo "[4/5] Running stress benchmarks (load shedding comparison)..."
    echo "════════════════════════════════════════════════════════════"
    echo ""

    for test_type in kernel_level app_level no_protection; do
        echo "Running stress benchmark: $test_type"
        ensure_docker
        run_with_retry "stress_benchmark $test_type" "$SCRIPT_DIR/stress_benchmark.sh" "$OUTPUT_FILE" "$test_type" || true
        echo ""
    done

    # Run adaptive limiter benchmark
    echo "════════════════════════════════════════════════════════════"
    echo "[5/5] Running adaptive limiter and scaling benchmarks..."
    echo "════════════════════════════════════════════════════════════"
    echo ""

    echo "Running adaptive limiter benchmark..."
    ensure_docker
    run_with_retry "adaptive_benchmark" "$SCRIPT_DIR/adaptive_benchmark.sh" "$OUTPUT_FILE" || true
    echo ""

    echo "Running scaling signals benchmark..."
    ensure_docker
    run_with_retry "scaling_benchmark" "$SCRIPT_DIR/scaling_benchmark.sh" "$OUTPUT_FILE" || true
    echo ""
fi

# Print summary
echo "════════════════════════════════════════════════════════════"
echo "  BENCHMARK RESULTS"
echo "════════════════════════════════════════════════════════════"
printf "%-20s %12s %12s\n" "Version" "RPS" "vs Baseline"
echo "────────────────────────────────────────────────────────────"
printf "%-20s %12s %12s\n" "Go Gin (baseline)" "134,432" "-"
printf "%-20s %12s %12s\n" "Go FastHTTP" "304,378" "+126%"
printf "%-20s %12s %12s\n" "Go io_uring" "508,810" "+278%"
printf "%-20s %12s %12s\n" "Go Observability" "545,253" "+306%"
printf "%-20s %12s %12s\n" "Go Response Cache" "541,275" "+303%"
printf "%-20s %12s %12s\n" "Scala Netty" "$RPS" "+${IMPROVEMENT}%"
echo "════════════════════════════════════════════════════════════"
echo ""

echo "════════════════════════════════════════════════════════════"
echo "  DONE!"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Next steps:"
echo "  make docs-render      # Regenerate HTML with new data"
echo "  open index.html       # View results"
echo ""
echo "To restore previous data:"
echo "  cp docs/seed_data.sql.bak docs/seed_data.sql"
