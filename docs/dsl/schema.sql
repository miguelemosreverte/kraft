-- Schema for DSL Demo Outputs
-- Used by BookGen to render demo outputs in the DSL book

CREATE TABLE IF NOT EXISTS demo_outputs (
    demo_number INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    output TEXT NOT NULL,
    captured_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- Index for quick lookups
CREATE INDEX IF NOT EXISTS idx_demo_number ON demo_outputs(demo_number);
