-- BookGen Demo Database Schema
-- This schema demonstrates the data structure for documentation directives

CREATE TABLE IF NOT EXISTS versions (
    tag TEXT PRIMARY KEY,
    chapter_number REAL,
    title TEXT,
    description TEXT,
    baseline_rps REAL,
    improvement_percent REAL,
    technique TEXT
);

CREATE TABLE IF NOT EXISTS commits (
    hash TEXT PRIMARY KEY,
    tag TEXT,
    message TEXT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS benchmark_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_hash TEXT REFERENCES commits(hash),
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

CREATE TABLE IF NOT EXISTS benchmark_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES benchmark_runs(id),
    concurrency INTEGER,
    requests_per_sec REAL,
    p50_latency_us REAL,
    p99_latency_us REAL,
    avg_latency_us REAL,
    max_latency_us REAL
);
