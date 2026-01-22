# Makefile for Kraft Event Search API - Scala Edition
#
# Quick reference:
#   make test             - Run Scala tests
#   make test-e2e         - Run E2E tests (starts real server automatically)
#   make test-all         - Run all tests
#   make docker-build     - Build Docker image
#   make docker-run       - Run server in Docker
#   make docker-benchmark - Benchmark current code in Docker (5 min)
#   make docs-render      - Render HTML from existing seed_data.sql
#   make docs-regenerate  - Benchmark and regenerate seed_data.sql + HTML

.PHONY: all run build test test-e2e test-all clean docker-build docker-run docker-benchmark docker-benchmark-quick docker-stress docs docs-render docs-open docs-regenerate dsl-data dsl-regenerate durable-data durable-regenerate help

# Default target
all: help

# =============================================================================
# Development targets
# =============================================================================

# Run the Scala server locally
run:
	sbt "runMain kraft.main 8080"

# Build fat JAR
build:
	sbt assembly

# Run all Scala tests
test:
	sbt test

# Run E2E tests (starts real server, runs tests, stops server)
test-e2e: build
	@echo "Starting server in background..."
	@sbt "runMain kraft.main 8080" > /tmp/kraft-server.log 2>&1 &
	@echo $$! > .server.pid
	@sleep 8
	@echo "Running E2E tests..."
	@curl -s http://localhost:8080/health | grep -q healthy && echo "✓ Health check passed" || echo "✗ Health check failed"
	@curl -s "http://localhost:8080/search" | grep -q events && echo "✓ Search endpoint passed" || echo "✗ Search endpoint failed"
	@curl -s http://localhost:8080/metrics | grep -q http_requests_total && echo "✓ Metrics endpoint passed" || echo "✗ Metrics endpoint failed"
	@echo "Stopping server..."
	@kill $$(cat .server.pid) 2>/dev/null || true
	@rm -f .server.pid
	@echo "E2E tests complete!"

# Run all tests
test-all: test test-e2e

# =============================================================================
# Docker targets
# =============================================================================

# Build Docker image
docker-build:
	docker build -t kraft-scala .

# Run server in Docker (interactive, port exposed)
docker-run: docker-build
	docker run --rm -it -p 8080:8080 kraft-scala

# Benchmark current code in Docker (5 minutes)
# Uses --privileged to enable io_uring kernel-bypass I/O
docker-benchmark: docker-build
	@mkdir -p reports
	docker run --rm --privileged \
		-v $(PWD)/reports:/app/reports \
		kraft-scala \
		bash -c "./scripts/benchmark.sh 300 1000 && cp /app/*.md /app/reports/ 2>/dev/null || true"
	@echo ""
	@echo "Reports saved to ./reports/"
	@ls -la reports/*.md 2>/dev/null || true

# Quick benchmark in Docker (2 minutes)
docker-benchmark-quick: docker-build
	@mkdir -p reports
	docker run --rm --privileged \
		-v $(PWD)/reports:/app/reports \
		kraft-scala \
		bash -c "./scripts/benchmark.sh 120 500 && cp /app/*.md /app/reports/ 2>/dev/null || true"

# Ultra-fast benchmark for iteration (15 seconds, prints RPS only)
bench: docker-build
	@docker run --rm --privileged kraft-scala \
		sh -c 'java --enable-native-access=ALL-UNNAMED -XX:+UseZGC -XX:+ZGenerational \
		-Xms256m -Xmx512m -Dio.netty.incubator.channel.uring.ioUringIoRatio=100 \
		-Dio.netty.buffer.checkAccessible=false -Dio.netty.buffer.checkBounds=false \
		-jar /app/kraft-server.jar 8080 2>&1 & \
		sleep 4 && \
		wrk -t4 -c100 -d3s http://localhost:8080/search > /dev/null 2>&1 && \
		wrk -t8 -c200 -d3s http://localhost:8080/search > /dev/null 2>&1 && \
		wrk -t12 -c400 -d15s --latency http://localhost:8080/search 2>&1 | grep -E "Requests/sec|Latency|Transfer"'

# Stress test with connection waves
docker-stress: docker-build
	@mkdir -p reports
	docker run --rm \
		-v $(PWD)/reports:/app/reports \
		kraft-scala \
		bash -c "./scripts/stress_test.sh 120 600 4 800 && cp /app/*.md /app/reports/ 2>/dev/null || true"

# =============================================================================
# Documentation targets
# =============================================================================

# Generate all documentation (quick - uses existing seed_data.sql)
docs: docs-render

# Render HTML from existing seed_data.sql (fast, for iterating on rendering)
docs-render:
	@echo "Creating benchmarks database from seed data..."
	@rm -f docs/benchmarks.db
	@cd docs && cat schema.sql seed_data.sql | sqlite3 benchmarks.db
	@echo "Creating DSL demo database from seed data..."
	@rm -f docs/dsl/demo_outputs.db
	@cd docs/dsl && cat schema.sql seed_data.sql 2>/dev/null | sqlite3 demo_outputs.db || echo "  (No DSL seed data yet - run 'make dsl-data' first)"
	@echo "Generating Index Page..."
	@sbt -error "runMain kraft.BookGenMain -input docs/index.md -output index.html -db docs/benchmarks.db"
	@echo "Generating Application Book..."
	@sbt -error "runMain kraft.BookGenMain -input docs/book.md -output app-book.html -no-db"
	@echo "Generating Server Book..."
	@sbt -error "runMain kraft.BookGenMain -input docs/server/book.md -output server-book.html -db docs/benchmarks.db"
	@echo "Generating BookGen Documentation..."
	@sbt -error "runMain kraft.BookGenMain -input docs/bookgen/book.md -output bookgen-book.html -db docs/bookgen/benchmarks.db"
	@echo "Generating DSL Documentation..."
	@sbt -error "runMain kraft.BookGenMain -input docs/dsl/book.md -output dsl-book.html -db docs/dsl/demo_outputs.db"
	@echo "Creating Durable Execution database from seed data..."
	@rm -f docs/durable/demo_outputs.db
	@cd docs/durable && cat schema.sql seed_data.sql 2>/dev/null | sqlite3 demo_outputs.db || echo "  (No durable seed data yet - run 'make durable-data' first)"
	@echo "Generating Durable Execution Book..."
	@sbt -error "runMain kraft.BookGenMain -input docs/durable/book.md -output durable-book.html -db docs/durable/demo_outputs.db" || echo "  (Durable book skipped - run 'make durable-data' first)"
	@echo ""
	@echo "Documentation generated!"
	@ls -la *.html

# Render and open documentation in browser
docs-open: docs-render
	open index.html

# Benchmark current Scala implementation and update seed_data.sql
docs-regenerate:
	@echo "════════════════════════════════════════════════════════════"
	@echo "  Regenerating benchmark data and documentation"
	@echo "════════════════════════════════════════════════════════════"
	@echo ""
	@./scripts/regenerate_seed_data.sh
	@echo ""
	@echo "Regenerating documentation with new data..."
	@$(MAKE) docs-render
	@echo ""
	@echo "Done! Review changes with: git diff docs/seed_data.sql"
	@echo "Open the book with: open index.html"

# Run DSL demos and generate seed_data.sql
dsl-data:
	@./scripts/regenerate_dsl_data.sh

# Run DSL demos and regenerate the DSL book
dsl-regenerate: dsl-data
	@echo ""
	@echo "Regenerating DSL documentation with new data..."
	@rm -f docs/dsl/demo_outputs.db
	@cd docs/dsl && cat schema.sql seed_data.sql | sqlite3 demo_outputs.db
	@sbt -error "runMain kraft.BookGenMain -input docs/dsl/book.md -output dsl-book.html -db docs/dsl/demo_outputs.db"
	@echo ""
	@echo "Done! Review changes with: git diff docs/dsl/seed_data.sql"
	@echo "Open the book with: open dsl-book.html"

# Run Durable Execution demos and generate seed_data.sql
durable-data:
	@./scripts/regenerate_durable_data.sh

# Run Durable Execution demos and regenerate the book
durable-regenerate: durable-data
	@echo ""
	@echo "Regenerating Durable Execution documentation with new data..."
	@rm -f docs/durable/demo_outputs.db
	@cd docs/durable && cat schema.sql seed_data.sql | sqlite3 demo_outputs.db
	@sbt -error "runMain kraft.BookGenMain -input docs/durable/book.md -output durable-book.html -db docs/durable/demo_outputs.db"
	@echo ""
	@echo "Done! Review changes with: git diff docs/durable/seed_data.sql"
	@echo "Open the book with: open durable-book.html"

# =============================================================================
# Utility targets
# =============================================================================

# Clean generated files
clean:
	sbt clean
	rm -rf reports/
	rm -rf target/
	rm -f docs/benchmarks.db
	rm -f docs/dsl/demo_outputs.db
	rm -f docs/durable/demo_outputs.db
	rm -f index.html app-book.html server-book.html bookgen-book.html dsl-book.html durable-book.html
	rm -f .server.pid
	docker rmi kraft-scala 2>/dev/null || true

# Show help
help:
	@echo "Kraft Event Search API - Scala Edition"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Development targets:"
	@echo "  run                   Run server locally on port 8080"
	@echo "  build                 Build fat JAR with sbt assembly"
	@echo "  test                  Run Scala tests"
	@echo "  test-e2e              Run E2E tests (starts server automatically)"
	@echo "  test-all              Run all tests"
	@echo ""
	@echo "Docker targets:"
	@echo "  docker-build          Build Docker image"
	@echo "  docker-run            Run server in Docker (port 8080)"
	@echo "  docker-benchmark      Benchmark in Docker (5 min)"
	@echo "  docker-benchmark-quick  Quick benchmark in Docker (2 min)"
	@echo "  docker-stress         Stress test with connection waves"
	@echo ""
	@echo "Documentation targets:"
	@echo "  docs-render           Render HTML from existing seed_data.sql"
	@echo "  docs-open             Render and open in browser"
	@echo "  docs-regenerate       Benchmark and regenerate seed_data.sql + HTML"
	@echo "  dsl-data              Run DSL demos and generate seed_data.sql"
	@echo "  dsl-regenerate        Run DSL demos and regenerate dsl-book.html"
	@echo "  durable-data          Run Durable Execution demos and generate seed_data.sql"
	@echo "  durable-regenerate    Run Durable Execution demos and regenerate durable-book.html"
	@echo ""
	@echo "Other targets:"
	@echo "  clean                 Remove generated files"
	@echo ""
	@echo "Quick start:"
	@echo "  make docker-build && make docker-run"
