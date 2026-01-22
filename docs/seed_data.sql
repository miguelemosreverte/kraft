-- Seed data for Fever Event Search API benchmarks
-- Contains historical Go benchmarks (chapters 1-5) and Scala benchmark (chapter 6)
--
-- Regenerated: Wed Jan 21 10:41:40 -03 2026
-- Scala Benchmark: 628956 RPS
--
-- Regenerate with: ./scripts/regenerate_seed_data.sh

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
('v6-scala-netty', 6, 'Scala Netty', 'Scala rewrite with Netty io_uring', 'scala01', 628956, 367.9, 'Scala Netty');

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
(6, 628956, 12640243, 1220, 488, 8540, 61000);

-- ═══════════════════════════════════════════════════════════════
-- Test Type: kernel_level
-- Generated: 2026-01-21 13:44:08 UTC
-- Branch: master
-- ═══════════════════════════════════════════════════════════════

DELETE FROM stress_test_series WHERE test_type = 'kernel_level';

INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 0, 79204, 100.0, 500, 0, 3880);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 20, 73550, 100.0, 800, 0, 4140);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 40, 74975, 100.0, 1000, 0, 4010);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 60, 75032, 100.0, 1200, 0, 3980);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 80, 72997, 100.0, 1500, 0, 4070);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 100, 71025, 100.0, 1800, 0, 4180);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('kernel_level', 120, 70458, 100.0, 2000, 0, 4200);

-- ═══════════════════════════════════════════════════════════════
-- Test Type: app_level
-- Generated: 2026-01-21 13:45:13 UTC
-- Branch: master
-- ═══════════════════════════════════════════════════════════════

DELETE FROM stress_test_series WHERE test_type = 'app_level';

INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 0, 104775, 100.0, 500, 0, 4700);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 20, 55519, 100.0, 800, 0, 7650);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 40, 0, 0.0, 1000, 0, 0);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 60, 0, 0.0, 1200, 0, 0);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 80, 0, 0.0, 1500, 0, 0);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 100, 0, 0.0, 1800, 0, 0);
INSERT INTO stress_test_series (test_type, elapsed_seconds, observed_rps, success_rate, active_connections, rejection_rate, avg_latency_us) VALUES
('app_level', 120, 0, 0.0, 2000, 0, 0);
