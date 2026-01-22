#!/bin/bash
# Experiment setup script - applies kernel-level load shedding
# This script is run inside the Docker container before server starts
#
# Note: Scala/Netty requires a lower connection limit (300) than Go (500)
# to maintain stability under extreme load due to JVM/Netty overhead.

CONN_LIMIT=${CONN_LIMIT:-300}

echo "Applying iptables connlimit: max $CONN_LIMIT connections on port 8080"

# Reject new connections (SYN packets) when over limit
iptables -A INPUT -p tcp --syn --dport 8080 \
    -m connlimit --connlimit-above $CONN_LIMIT --connlimit-mask 0 \
    -j REJECT --reject-with tcp-reset

echo "iptables rule applied successfully"
iptables -L INPUT -n -v
