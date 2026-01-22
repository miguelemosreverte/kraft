# Smart DFS Node Docker Image
FROM eclipse-temurin:21-jdk

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy project files
COPY build.sbt .
COPY project/ project/

# Pre-fetch dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src/ src/

# Compile the project
RUN sbt compile

# Create storage directory
RUN mkdir -p /data

# Default port
EXPOSE 7800

# Environment variables
ENV NODE_PORT=7800
ENV STORAGE_PATH=/data

# Start the SmartFSNode
CMD sbt "runMain kraft.demos.SmartFSNode seed $NODE_PORT $STORAGE_PATH"
