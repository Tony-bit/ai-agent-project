# MySQL Read-only Codex Skill Design

## Goal

Create a personal Codex skill at `C:\Users\Denny\.codex\skills\mysql-readonly` that converts natural-language database questions into safe, read-only MySQL inspection and query operations. Connection details are read exclusively from local environment variables and are never stored in the skill or workspace.

## Scope

The skill supports connectivity checks, table discovery, table descriptions, query explanation, read-only SQL execution, and structured result export. It does not support data mutation, schema changes, stored procedure execution, administrative commands, transactions controlled by the caller, or credential management.

## Alternatives Considered

1. A Python read-only executor is selected because it provides deterministic validation, structured output, predictable error handling, and no dependency on a separately installed MySQL CLI.
2. Calling `mysql.exe` directly was rejected because client availability, password handling, and structured output vary across local environments.
3. A MySQL MCP server was deferred because it requires a separately installed and maintained service and is larger than the requested personal skill.

## Skill Structure

```text
mysql-readonly/
|-- SKILL.md
|-- agents/
|   `-- openai.yaml
`-- scripts/
    |-- mysql_readonly.py
    `-- test_mysql_readonly.py
```

`SKILL.md` defines natural-language triggers, the schema-first query workflow, security rules, dependency checks, output expectations, and troubleshooting guidance. `agents/openai.yaml` exposes concise UI metadata. `mysql_readonly.py` is the deterministic connection and query boundary. Its unit tests cover parsing, validation, configuration, redaction, and output limits without requiring a live database.

No README, sample credential file, or redundant reference file will be added.

## Configuration Contract

Required environment variables:

- `MYSQL_URL`: `mysql://host:port/database`; credentials must not be embedded in this URL.
- `MYSQL_USER`: MySQL username.
- `MYSQL_PASSWORD`: MySQL password.

Optional environment variables:

- `MYSQL_CONNECT_TIMEOUT`: positive integer seconds, default `10`.
- `MYSQL_SSL_CA`: path to a CA certificate; when present, TLS certificate verification is enabled.

The executor reports missing variable names but never prints variable values. URL query parameters and embedded credentials are rejected to keep configuration behavior explicit and prevent accidental credential disclosure.

## Command Interface

The executor provides these subcommands:

- `ping`: verify configuration and database connectivity.
- `tables`: list visible tables and views in the configured database.
- `describe <table>`: return column metadata for one validated identifier.
- `query --sql <statement>`: run one approved read-only statement.

Query output defaults to JSON for reliable machine interpretation. `query` also supports CSV output to a caller-selected workspace path. Console results are capped at 200 rows by default; a caller may request a lower or higher positive limit up to a fixed safety ceiling of 10,000 rows. The executor reports whether results were truncated.

## Data Flow

1. Codex identifies the requested database question and checks that it is read-only.
2. Codex runs `ping` when connection state is unknown.
3. Codex uses `tables` and `describe` only as needed to confirm real schema names and types.
4. Codex writes the smallest sufficient SQL statement and passes it to `query`.
5. The executor loads and validates configuration, validates SQL, opens the connection without multi-statement capability, starts a read-only transaction, executes the statement, serializes results, and rolls back before closing.
6. Codex summarizes the answer, query scope, row count, truncation state, and any limitations. It does not echo credentials.

## Read-only Enforcement

Read-only behavior uses defense in depth:

1. Accept exactly one statement and allow only `SELECT`, `SHOW`, `DESCRIBE`/`DESC`, and `EXPLAIN` statement families.
2. Reject comments used to obscure statement boundaries, multi-statements, DML, DDL, privilege operations, transaction control, prepared statements, stored program calls, locking clauses, and server file operations such as `INTO OUTFILE`, `INTO DUMPFILE`, and `LOAD_FILE`.
3. Do not enable the driver's multi-statement client flag.
4. Execute queries in a read-only transaction and always roll back.
5. Require the operator to use a database account granted only the minimum `SELECT` and metadata permissions for the intended schema. Client checks are supplemental and do not replace server-side privileges.

If a statement cannot be classified confidently, reject it and explain the unsupported construct rather than attempting execution.

## Dependency Strategy

Use Python 3.9 or later and PyMySQL. The skill performs a preflight import check and gives the shortest local installation command when PyMySQL is missing. It does not install packages automatically or modify the user's Python environment without an explicit request.

## Error Handling

Errors are divided into configuration, dependency, validation, connectivity/authentication, authorization, SQL, and output errors. User-facing messages remain concise and redact passwords and connection secrets. Database exception details may include safe server codes and messages, but configuration values and driver connection representations are never emitted.

An empty result is reported as a valid empty result, not a connection failure. Partial console output is explicitly marked as truncated. CSV files are written only to paths explicitly requested by the caller.

## Testing And Validation

Unit tests must verify:

- valid URL and environment parsing;
- missing and malformed configuration errors;
- accepted statement families;
- rejection of mutations, DDL, calls, locks, files, comments, and multi-statements;
- identifier validation for `describe`;
- password redaction;
- row limiting and truncation metadata;
- JSON and CSV serialization.

The implementation must pass the unit tests, Python syntax compilation, and the skill creator's `quick_validate.py`. A live smoke test runs only when the user has configured a disposable or approved read-only database connection.

## Operator Setup

The operator creates a dedicated MySQL account with only the permissions required for the target schema, then sets the required variables in the environment used to launch Codex. The operator does not provide secrets in chat or commit them to files. Restarting Codex may be necessary after changing persistent environment variables so the desktop process inherits them.

## Acceptance Criteria

- Codex discovers and can explicitly invoke `$mysql-readonly`.
- No credential is stored under the skill or workspace.
- The executor can inspect schema and run approved read-only queries with structured output.
- Unsupported or potentially mutating SQL is rejected before connecting or executing.
- Results have bounded output and explicit truncation metadata.
- Automated validation passes without access to a live database.
- A live `ping` and harmless `SELECT 1` succeed after the operator configures an approved read-only account.
