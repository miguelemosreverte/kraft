#!/bin/bash
# Benchmark script for Fever Scala Server
#
# Usage: ./scripts/benchmark.sh [duration_seconds] [connections]
#
# Examples:
#   ./scripts/benchmark.sh              # 5 min, 1000 connections
#   ./scripts/benchmark.sh 120 500      # 2 min, 500 connections

DURATION=${1:-300}
CONNECTIONS=${2:-1000}
THREADS=${3:-4}
PORT=${PORT:-8080}

echo "════════════════════════════════════════════════════════════"
echo "  Fever Scala Server - Benchmark"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Duration: ${DURATION}s"
echo "Connections: $CONNECTIONS"
echo "Threads: $THREADS"
echo "Port: $PORT"
echo ""

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up..."
    kill $SERVER_PID 2>/dev/null || true
    kill $WRK_PID 2>/dev/null || true
}
trap cleanup EXIT

# Check for wrk
if ! command -v wrk > /dev/null 2>&1; then
    echo "ERROR: wrk not installed"
    echo "Install with: brew install wrk (macOS) or apt install wrk (Linux)"
    exit 1
fi

# Step 1: Start the server (if not already running)
if ! curl -s http://localhost:$PORT/health > /dev/null 2>&1; then
    echo "[1/4] Starting Scala server..."

    if [ -f "/app/kraft-server.jar" ]; then
        java -jar /app/kraft-server.jar $PORT > /tmp/server.log 2>&1 &
    elif [ -f "target/scala-3.4.2/kraft-scala-assembly-0.1.0.jar" ]; then
        java -jar target/scala-3.4.2/kraft-scala-assembly-0.1.0.jar $PORT > /tmp/server.log 2>&1 &
    else
        echo "ERROR: No JAR found. Run 'sbt assembly' first."
        exit 1
    fi
    SERVER_PID=$!
    sleep 5

    if ! curl -s http://localhost:$PORT/health > /dev/null 2>&1; then
        echo "ERROR: Server failed to start"
        cat /tmp/server.log
        exit 1
    fi
    echo "       Server ready (PID: $SERVER_PID)"
else
    echo "[1/4] Server already running on port $PORT"
fi
echo ""

# Step 2: Warmup
echo "[2/4] Warming up (10s)..."
wrk -t$THREADS -c$CONNECTIONS -d10s http://localhost:$PORT/search > /dev/null 2>&1
echo "       Warmup complete"
echo ""

# Step 3: Run benchmark
echo "[3/4] Running benchmark (${DURATION}s)..."
echo ""

RESULT_FILE="/tmp/wrk_result_$$.txt"
wrk -t$THREADS -c$CONNECTIONS -d${DURATION}s --latency http://localhost:$PORT/search > "$RESULT_FILE" 2>&1

# Step 4: Show results
echo "[4/4] Results:"
echo ""
cat "$RESULT_FILE"
echo ""

# Parse and display summary
RPS=$(grep "Requests/sec:" "$RESULT_FILE" | awk '{printf "%.0f", $2}')
LATENCY=$(grep "Latency" "$RESULT_FILE" | head -1 | awk '{print $2}')
P99=$(grep "99%" "$RESULT_FILE" | awk '{print $2}')

echo "════════════════════════════════════════════════════════════"
echo "  SUMMARY"
echo "════════════════════════════════════════════════════════════"
echo "  Requests/sec: $RPS"
echo "  Avg Latency:  $LATENCY"
echo "  P99 Latency:  $P99"
echo "════════════════════════════════════════════════════════════"

# Generate markdown report
REPORT_FILE="${REPORT_DIR:-/app}/benchmark_report_$(date +%Y%m%d_%H%M%S).md"
mkdir -p "$(dirname "$REPORT_FILE")"
cat > "$REPORT_FILE" << EOF
# Benchmark Report

**Date:** $(date)
**Duration:** ${DURATION}s
**Connections:** $CONNECTIONS
**Threads:** $THREADS

## Results

| Metric | Value |
|--------|-------|
| Requests/sec | $RPS |
| Avg Latency | $LATENCY |
| P99 Latency | $P99 |

## Raw Output

\`\`\`
$(cat "$RESULT_FILE")
\`\`\`
EOF

echo ""
echo "Report saved to: $REPORT_FILE"

rm -f "$RESULT_FILE"
