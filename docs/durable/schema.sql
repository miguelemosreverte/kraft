-- Schema for durable execution demo outputs
-- Used by durable-book.html generation

CREATE TABLE IF NOT EXISTS demo_outputs (
    demo_number INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    output TEXT NOT NULL,
    captured_at TEXT DEFAULT CURRENT_TIMESTAMP
);
