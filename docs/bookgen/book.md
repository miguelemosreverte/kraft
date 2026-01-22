# BookGen: Living Documentation Generator

*Empowering teams to document their reasoning*

---

## Philosophy

Traditional documentation becomes stale the moment it's written. BookGen takes a different approach: **documentation lives alongside your data**.

Instead of static markdown, BookGen processes narrative documents with embedded directives that pull live data from your benchmarks, metrics, and version history. When you run a new benchmark, your documentation updates automatically.

**Why this matters for teams:**

- **Document reasoning, not just code** — Explain *why* you made architectural decisions, backed by real performance data
- **Proposals with proof** — When proposing a new feature or optimization, embed the benchmarks that justify it
- **Living changelogs** — Your performance journey is documented as you go, not reconstructed later
- **Lower barrier to contribution** — Engineers write Markdown they know, directives handle the complexity

---

## Live Demo

This section demonstrates BookGen directives rendering live data. Everything below is generated from the demo database — the same techniques power the Server Library documentation.

### Live Metrics

Our demo optimization journey took us from **<!-- @metric:tag="v1-baseline" format="comma" --> RPS** to **<!-- @metric:tag="v5-final" format="comma" --> RPS** — an improvement of **<!-- @metric:tag="v5-final" field="improvement_percent" format="decimal" -->%**.

### Version Timeline

<!-- @git:show="branches" -->

### Performance Chart

<!-- @chart:title="RPS by Version"
     query="SELECT tag, baseline_rps FROM versions ORDER BY chapter_number"
     type="bar" -->

### Latency Analysis

<!-- @chart:title="P99 Latency at Different Concurrency Levels"
     query="SELECT br.concurrency, br.p99_latency_us, c.tag FROM benchmark_results br JOIN benchmark_runs r ON br.run_id = r.id JOIN commits c ON r.commit_hash = c.hash WHERE c.tag IN ('v1-baseline', 'v3-connection-pooling', 'v5-final') ORDER BY c.tag, br.concurrency"
     x="concurrency"
     y="p99_latency_us"
     series="tag" -->

### Version Summary Table

<!-- @table:title="Optimization Milestones"
     query="SELECT tag as Version, title as Technique, printf('%,.0f', baseline_rps) as RPS, COALESCE(printf('+%.1f%%', improvement_percent), '-') as Improvement FROM versions ORDER BY chapter_number"
     columns='{"RPS":{"class":"rps"},"Improvement":{"class":"improvement"}}' -->

---

## Quick Start

```bash
# Generate a single document
go run ./cmd/bookgen -input docs/book.md -output book.html -db docs/benchmarks.db

# Generate without database (static markdown only)
go run ./cmd/bookgen -input README.md -output readme.html -no-db

# Generate all project documentation
make docs
```

---

## Directive Reference

Directives are HTML comments with a special syntax:

```
<!-- @directive:param="value" param2="value2" -->
```

### @metric — Display Live Metrics

Pull values directly from your benchmark database.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `tag` | Yes | Version tag to query (e.g., "v1-baseline") |
| `field` | No | Specific field: `improvement_percent`, `p99_latency_us`, `p50_latency_us` |
| `format` | No | Output format: `short` (541K), `comma` (541,000), `decimal` (302.6) |

**Examples:**

```markdown
Our server handles <!-- @metric:tag="v5-response-cache" format="short" --> requests per second.

P99 latency is <!-- @metric:tag="v4-observability" field="p99_latency_us" format="comma" -->µs.

That's a <!-- @metric:tag="v5-response-cache" field="improvement_percent" format="decimal" -->% improvement.
```

---

### @chart — Render Data Visualizations

Create Chart.js visualizations from SQL queries.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `title` | No | Chart title (default: "Performance Chart") |
| `query` | Yes | SQL query returning x, y, and optionally series columns |
| `type` | No | Chart type: `line`, `bar`, `scatter` (default: line) |
| `x` | No | Column name for x-axis |
| `y` | No | Column name for y-axis |
| `series` | No | Column name for series grouping |
| `datasets` | No | JSON config for styling each series |
| `options` | No | Chart.js options object |

**Example — Version Progression:**

```markdown
<!-- @chart:title="Performance Over Versions"
     query="SELECT tag as version, baseline_rps as rps FROM versions ORDER BY chapter_number"
     type="bar" -->
```

**Example — Multi-Series with Custom Styling:**

```markdown
<!-- @chart:title="Throughput vs Latency"
     query="SELECT concurrency, rps, p99_us, 'throughput' as metric FROM stress_results
            UNION ALL
            SELECT concurrency, rps, p99_us, 'latency' as metric FROM stress_results"
     series="metric"
     datasets='{"throughput":{"borderColor":"#22c55e","label":"RPS"},"latency":{"borderColor":"#ef4444","label":"P99 Latency","yAxisID":"y1"}}'
     options='{"scales":{"y1":{"position":"right","grid":{"drawOnChartArea":false}}}}' -->
```

---

### @table — Data Tables from Queries

Render SQL results as styled HTML tables.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `title` | No | Table title (default: "Data Table") |
| `query` | Yes | SQL query |
| `columns` | No | JSON config for headers and CSS classes |

**Example:**

```markdown
<!-- @table:title="Version History"
     query="SELECT tag, title, baseline_rps as 'RPS',
            printf('%.1f%%', improvement_percent) as 'Improvement'
            FROM versions ORDER BY chapter_number"
     columns='{"RPS":{"class":"rps"},"Improvement":{"class":"improvement"}}' -->
```

---

### @git — Version Timeline Visualization

Display your performance journey as a git-style branch visualization.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `show` | Yes | What to display: `"branches"` |

**Example:**

```markdown
<!-- @git:show="branches" -->
```

This renders all versions from the database as commits on a branch, showing tags, titles, and RPS values.

---

### @c4 — Architecture Diagrams

Render C4 model diagrams using a custom DSL.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `title` | No | Diagram title |
| `diagram` | Yes | C4 diagram definition |

**Supported Elements:**
- `Person(id, "Name", "Description")`
- `System(id, "Name", "Description")`
- `System_Ext(id, "Name", "Description")`
- `Container(id, "Name", "Tech", "Description")`
- `ContainerDb(id, "Name", "Tech", "Description")`
- `Component(id, "Name", "Tech", "Description")`
- `Boundary(id, "Name") { ... }`
- `Rel(from, to, "Label")`

**Example:**

```markdown
<!-- @c4:title="System Context"
     diagram="
Person(user, \"Developer\", \"Writes documentation\")
System(bookgen, \"BookGen\", \"Generates HTML from Markdown\")
System_Ext(db, \"SQLite\", \"Benchmark data\")

Rel(user, bookgen, \"Writes Markdown\")
Rel(bookgen, db, \"Queries metrics\")
" -->
```

---

### @mermaid — General Diagrams

Embed any Mermaid diagram type.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `title` | No | Diagram title |
| `diagram` | Yes | Mermaid diagram definition |

**Example — Flowchart:**

```markdown
<!-- @mermaid:title="Processing Pipeline"
     diagram="
flowchart LR
    A[Markdown] --> B[Parse Directives]
    B --> C[Query Database]
    C --> D[Render HTML]
    D --> E[Output File]
" -->
```

**Example — Sequence Diagram:**

```markdown
<!-- @mermaid:diagram="
sequenceDiagram
    User->>BookGen: Run generator
    BookGen->>SQLite: Query metrics
    SQLite-->>BookGen: Return data
    BookGen-->>User: HTML output
" -->
```

---

### @meta — Document Metadata

Set document metadata (not rendered).

**Parameters:**
| Parameter | Description |
|-----------|-------------|
| `title` | Document title for HTML head |

**Example:**

```markdown
<!-- @meta:title="BookGen Documentation" -->
```

---

### @footer — Standard Footer

Add a consistent footer to your document.

```markdown
<!-- @footer:style="default" -->
```

---

## Database Schema

BookGen expects a SQLite database with this core schema:

```sql
-- Version milestones (required for @metric, @git)
CREATE TABLE versions (
    tag TEXT PRIMARY KEY,
    chapter_number REAL,
    title TEXT,
    description TEXT,
    baseline_rps REAL,
    improvement_percent REAL,
    technique TEXT
);

-- Benchmark runs (optional, for detailed metrics)
CREATE TABLE commits (
    hash TEXT PRIMARY KEY,
    tag TEXT,
    message TEXT,
    timestamp DATETIME
);

CREATE TABLE benchmark_runs (
    id INTEGER PRIMARY KEY,
    commit_hash TEXT REFERENCES commits(hash),
    timestamp DATETIME
);

CREATE TABLE benchmark_results (
    id INTEGER PRIMARY KEY,
    run_id INTEGER REFERENCES benchmark_runs(id),
    p50_latency_us REAL,
    p99_latency_us REAL,
    avg_latency_us REAL,
    max_latency_us REAL,
    requests_per_sec REAL
);
```

---

## Template Types

BookGen supports two HTML templates:

### Article Template (default)
Full-featured template with:
- Dark/light theme toggle
- Modern/editorial style toggle
- Code syntax highlighting
- Chart.js integration
- Mermaid diagram support

Used for: Technical documentation, performance books, architecture docs.

### Landing Template
Minimal template for index pages with:
- Metric cards
- Navigation cards
- Responsive grid layout

Triggered automatically when input file is named `index.md`.

---

## CLI Reference

```
bookgen [flags]

Flags:
  -input string    Path to Markdown file (default "book.md")
  -output string   Path to output HTML file (default "../index.html")
  -db string       Path to SQLite database (default "benchmarks.db")
  -no-db           Skip database (for static markdown)
  -all             Build all documentation files
```

---

## Best Practices

### 1. Document Decisions, Not Just Results

Bad:
> "We achieved 500K RPS."

Good:
> "After profiling revealed 40% of CPU time in JSON serialization, we implemented response caching. This reduced redundant work for repeated queries, achieving <!-- @metric:tag="v5-response-cache" format="short" --> RPS."

### 2. Embed Evidence in Proposals

When proposing a change, include the benchmark that justifies it:

```markdown
## Proposal: Add Response Caching

### Problem
Repeated queries serialize the same JSON response.

### Solution
Cache serialized responses keyed by query parameters.

### Evidence
<!-- @chart:title="Before vs After Caching"
     query="SELECT tag, baseline_rps FROM versions WHERE tag IN ('v4-observability','v5-response-cache')"
     type="bar" -->
```

### 3. Keep Queries Simple

Complex SQL in directives becomes hard to maintain. For complex analysis, create database views:

```sql
CREATE VIEW performance_summary AS
SELECT v.tag, v.baseline_rps, br.p99_latency_us
FROM versions v
JOIN commits c ON c.tag = v.tag
JOIN benchmark_runs r ON r.commit_hash = c.hash
JOIN benchmark_results br ON br.run_id = r.id;
```

Then query the view:
```markdown
<!-- @table:query="SELECT * FROM performance_summary" -->
```

### 4. Version Your Benchmarks

Always tag commits that correspond to benchmark runs. This creates a traceable history:

```bash
git tag v1-gin-baseline
./scripts/benchmark.sh
git tag v2-custom-router
./scripts/benchmark.sh
```

---

## Architecture

```
pkg/bookgen/
├── bookgen.go      # Interpreter, directive parsing
├── directives.go   # Render functions for each directive type
├── templates.go    # HTML template wrappers
└── docs/
    └── book.md     # This documentation

cmd/bookgen/
└── main.go         # CLI entry point
```

The Interpreter processes Markdown in two passes:
1. **Directive expansion** — Replace `<!-- @directive -->` with rendered HTML
2. **Markdown conversion** — Convert remaining Markdown to HTML via gomarkdown

---

*Built with BookGen — documentation that stays alive.*

<!-- @footer:style="default" -->
