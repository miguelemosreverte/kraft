#!/bin/bash
# Startup script for kraft-server
# Applies iptables rules (if script exists) then starts server

set -e

# Apply kernel-level load shedding if experiment_setup.sh exists
if [[ -f /app/experiment_setup.sh ]]; then
    echo "Applying kernel-level load shedding..."
    /app/experiment_setup.sh
fi

# Start the server
exec java --enable-native-access=ALL-UNNAMED -XX:+UseZGC -XX:+ZGenerational -Xms256m -Xmx512m -jar /app/kraft-server.jar 8080
