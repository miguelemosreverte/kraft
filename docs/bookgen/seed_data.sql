-- BookGen Demo Seed Data
-- Sample data to demonstrate directive rendering

-- Version progression (for @git and @metric directives)
INSERT OR REPLACE INTO versions (tag, chapter_number, title, description, baseline_rps, improvement_percent, technique) VALUES
('v1-baseline', 1.0, 'Initial Implementation', 'Standard Go HTTP server with encoding/json', 45000, NULL, 'net/http + encoding/json'),
('v2-sonic-json', 2.0, 'Sonic JSON', 'Replaced encoding/json with bytedance/sonic', 89000, 97.8, 'sonic JSON encoder'),
('v3-connection-pooling', 3.0, 'Connection Pooling', 'Added HTTP connection pooling and keep-alive', 156000, 246.7, 'connection reuse'),
('v4-response-cache', 4.0, 'Response Caching', 'Cache serialized responses for repeated queries', 312000, 593.3, 'LRU response cache'),
('v5-final', 5.0, 'Production Ready', 'Full optimization stack with observability', 425000, 844.4, 'complete stack');

-- Commits (for benchmark result linking)
INSERT OR REPLACE INTO commits (hash, tag, message, timestamp) VALUES
('abc1234', 'v1-baseline', 'Initial server implementation', '2024-01-01 10:00:00'),
('def5678', 'v2-sonic-json', 'Switch to sonic JSON encoder', '2024-01-15 14:30:00'),
('ghi9012', 'v3-connection-pooling', 'Add connection pooling', '2024-02-01 09:00:00'),
('jkl3456', 'v4-response-cache', 'Implement response caching', '2024-02-15 16:00:00'),
('mno7890', 'v5-final', 'Production release', '2024-03-01 12:00:00');

-- Benchmark runs
INSERT OR REPLACE INTO benchmark_runs (id, commit_hash, timestamp, description) VALUES
(1, 'abc1234', '2024-01-01 10:30:00', 'Baseline benchmark'),
(2, 'def5678', '2024-01-15 15:00:00', 'Sonic JSON benchmark'),
(3, 'ghi9012', '2024-02-01 09:30:00', 'Connection pooling benchmark'),
(4, 'jkl3456', '2024-02-15 16:30:00', 'Response cache benchmark'),
(5, 'mno7890', '2024-03-01 12:30:00', 'Final benchmark');

-- Benchmark results with latency data
INSERT OR REPLACE INTO benchmark_results (run_id, concurrency, requests_per_sec, p50_latency_us, p99_latency_us, avg_latency_us, max_latency_us) VALUES
-- v1-baseline results
(1, 100, 45000, 2100, 4500, 2200, 8900),
(1, 200, 44000, 4300, 9200, 4500, 15000),
(1, 500, 42000, 11000, 24000, 12000, 45000),

-- v2-sonic-json results
(2, 100, 89000, 1050, 2200, 1100, 4500),
(2, 200, 87000, 2200, 4600, 2300, 7800),
(2, 500, 85000, 5500, 12000, 5900, 22000),

-- v3-connection-pooling results
(3, 100, 156000, 600, 1300, 640, 2800),
(3, 200, 154000, 1250, 2700, 1300, 4500),
(3, 500, 150000, 3200, 6800, 3400, 12000),

-- v4-response-cache results
(4, 100, 312000, 300, 650, 320, 1400),
(4, 200, 308000, 620, 1350, 650, 2200),
(4, 500, 298000, 1600, 3400, 1700, 6000),

-- v5-final results
(5, 100, 425000, 220, 480, 235, 1000),
(5, 200, 420000, 460, 980, 480, 1600),
(5, 500, 410000, 1180, 2500, 1220, 4200);
