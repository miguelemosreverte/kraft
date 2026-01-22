#!/bin/bash
# Stress test with overlapping connection waves
#
# Usage: ./scripts/stress_test.sh [duration] [base_connections] [waves] [wave_size]

DURATION=${1:-120}
BASE_CONNECTIONS=${2:-600}
WAVES=${3:-4}
WAVE_SIZE=${4:-800}
PORT=${PORT:-8080}

echo "════════════════════════════════════════════════════════════"
echo "  Fever Scala Server - Stress Test"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Duration: ${DURATION}s"
echo "Base connections: $BASE_CONNECTIONS"
echo "Waves: $WAVES (size: $WAVE_SIZE each)"
echo ""

# Check for wrk
if ! command -v wrk > /dev/null 2>&1; then
    echo "ERROR: wrk not installed"
    exit 1
fi

# Ensure server is running
if ! curl -s http://localhost:$PORT/health > /dev/null 2>&1; then
    echo "ERROR: Server not running on port $PORT"
    exit 1
fi

# Start base load
echo "[1] Starting base load ($BASE_CONNECTIONS connections)..."
wrk -t4 -c$BASE_CONNECTIONS -d${DURATION}s http://localhost:$PORT/search > /tmp/base_load.txt 2>&1 &
BASE_PID=$!

# Wait a bit, then launch waves
WAVE_INTERVAL=$((DURATION / (WAVES + 1)))
for i in $(seq 1 $WAVES); do
    sleep $WAVE_INTERVAL
    echo "[Wave $i] Launching $WAVE_SIZE additional connections..."
    wrk -t2 -c$WAVE_SIZE -d$((DURATION - i * WAVE_INTERVAL))s http://localhost:$PORT/search > /tmp/wave_${i}.txt 2>&1 &
done

# Wait for base load to finish
wait $BASE_PID

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  STRESS TEST COMPLETE"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "Base load results:"
grep "Requests/sec\|Latency" /tmp/base_load.txt | head -2
echo ""

# Cleanup
rm -f /tmp/base_load.txt /tmp/wave_*.txt
