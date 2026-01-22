-- Schema for benchmark time-series data
-- Fever Event Search API Performance Journey

CREATE TABLE IF NOT EXISTS branches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    is_baseline BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS commits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hash TEXT NOT NULL UNIQUE,
    branch_id INTEGER NOT NULL,
    parent_hash TEXT,
    message TEXT NOT NULL,
    author TEXT,
    tag TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE TABLE IF NOT EXISTS benchmark_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_hash TEXT NOT NULL,
    run_type TEXT NOT NULL,
    environment TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    duration_seconds INTEGER,
    threads INTEGER,
    connections INTEGER,
    keep_alive BOOLEAN DEFAULT TRUE,
    FOREIGN KEY (commit_hash) REFERENCES commits(hash)
);

CREATE TABLE IF NOT EXISTS benchmark_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    requests_per_second REAL NOT NULL,
    total_requests INTEGER,
    bytes_transferred INTEGER,
    avg_latency_us REAL,
    p50_latency_us REAL,
    p90_latency_us REAL,
    p99_latency_us REAL,
    max_latency_us REAL,
    socket_errors INTEGER DEFAULT 0,
    timeout_errors INTEGER DEFAULT 0,
    FOREIGN KEY (run_id) REFERENCES benchmark_runs(id)
);

CREATE TABLE IF NOT EXISTS time_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    timestamp_offset_ms INTEGER NOT NULL,
    instant_rps REAL,
    instant_latency_us REAL,
    active_connections INTEGER,
    FOREIGN KEY (run_id) REFERENCES benchmark_runs(id)
);

CREATE TABLE IF NOT EXISTS versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tag TEXT NOT NULL UNIQUE,
    chapter_number REAL NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    commit_hash TEXT NOT NULL,
    baseline_rps REAL NOT NULL,
    improvement_percent REAL,
    technique TEXT,
    FOREIGN KEY (commit_hash) REFERENCES commits(hash)
);

CREATE TABLE IF NOT EXISTS comparisons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    run_a_id INTEGER NOT NULL,
    run_b_id INTEGER NOT NULL,
    speedup_factor REAL,
    notes TEXT,
    FOREIGN KEY (run_a_id) REFERENCES benchmark_runs(id),
    FOREIGN KEY (run_b_id) REFERENCES benchmark_runs(id)
);

-- Stress test time series for load shedding comparison
-- Shows degradation without kernel-level load shedding vs stable throughput with it
CREATE TABLE IF NOT EXISTS stress_test_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    test_type TEXT NOT NULL,  -- 'no_protection', 'app_level', 'kernel_level'
    elapsed_seconds INTEGER NOT NULL,
    observed_rps REAL,
    success_rate REAL,        -- percentage of successful responses (vs rejects)
    active_connections INTEGER,
    rejection_rate REAL,      -- connections rejected per second
    avg_latency_us REAL
);

-- Adaptive limiter time series for Chapter 7
-- Shows how the connection limit adapts over time based on observed throughput
CREATE TABLE IF NOT EXISTS adaptive_limiter_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    elapsed_seconds INTEGER NOT NULL,
    connection_limit INTEGER NOT NULL,  -- current iptables connlimit value
    observed_rps REAL,                  -- throughput at this limit
    rejection_rate REAL,                -- rejections per second
    active_connections INTEGER,         -- real active connections from metrics
    saturation_ratio REAL,              -- active_connections / connection_limit
    action TEXT                         -- 'increase', 'decrease', 'stable', etc.
);

-- Scaling signals time series for Chapter 8
-- Shows metrics that indicate when horizontal scaling is needed
CREATE TABLE IF NOT EXISTS scaling_signals_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    elapsed_seconds INTEGER NOT NULL,
    rejection_rate REAL,                -- rejections per second from iptables
    saturation_ratio REAL,              -- active_connections / connection_limit
    needs_scaleout INTEGER,             -- 1 if at capacity, 0 otherwise
    connection_limit INTEGER,           -- current limit
    active_connections INTEGER,         -- current active connections
    observed_rps REAL                   -- throughput at this point
);

CREATE INDEX IF NOT EXISTS idx_commits_branch ON commits(branch_id);
CREATE INDEX IF NOT EXISTS idx_commits_hash ON commits(hash);
CREATE INDEX IF NOT EXISTS idx_runs_commit ON benchmark_runs(commit_hash);
CREATE INDEX IF NOT EXISTS idx_timeseries_run ON time_series(run_id, timestamp_offset_ms);
CREATE INDEX IF NOT EXISTS idx_versions_chapter ON versions(chapter_number);

CREATE VIEW IF NOT EXISTS version_progression AS
SELECT
    v.chapter_number,
    v.tag,
    v.title,
    v.technique,
    v.baseline_rps,
    v.improvement_percent,
    LAG(v.baseline_rps) OVER (ORDER BY v.chapter_number) as previous_rps,
    v.baseline_rps - LAG(v.baseline_rps) OVER (ORDER BY v.chapter_number) as rps_delta
FROM versions v
ORDER BY v.chapter_number;

CREATE VIEW IF NOT EXISTS latest_baseline AS
SELECT * FROM versions
WHERE chapter_number = (SELECT MAX(chapter_number) FROM versions);
