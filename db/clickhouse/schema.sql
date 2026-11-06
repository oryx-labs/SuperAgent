-- SuperAgent storage schema (ClickHouse): chat sessions/messages/events and MCP connectors.
-- Backend selected by superagent.storage.type=clickhouse.
-- Apply with: clickhouse client --queries-file db/clickhouse/schema.sql
--
-- Sessions use ReplacingMergeTree keyed by id with updated_at as the version,
-- so an updated session is written as a new row and reads use FINAL to collapse
-- to the latest version. Messages and events are append-only (MergeTree).

CREATE DATABASE IF NOT EXISTS superagent;

CREATE TABLE IF NOT EXISTS superagent.chat_session (
  id               String,
  title            String,
  agent_type       String,
  agent_session_id String,
  workspace_dir    String,
  agent_id         String,
  status           String,
  created_at       DateTime64(3, 'UTC'),
  updated_at       DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

-- Agents (personas): a named role with free-text persona, commanded through chat.
-- ReplacingMergeTree keyed by id with updated_at as the version; reads use FINAL.
CREATE TABLE IF NOT EXISTS superagent.agent (
  id         String,
  name       String,
  avatar     String,
  character_key String,
  persona    String,
  model      String,
  status     String,
  owner      String,
  kind       String,
  managed_by String,
  created_at DateTime64(3, 'UTC'),
  updated_at DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

CREATE TABLE IF NOT EXISTS superagent.chat_message (
  id         String,
  session_id String,
  role       String,
  content    String,
  created_at DateTime64(3, 'UTC'),
  author     String
) ENGINE = MergeTree
ORDER BY (session_id, created_at);

CREATE TABLE IF NOT EXISTS superagent.agent_event (
  session_id String,
  message_id String,
  seq        Int64,
  type       String,
  payload    String,
  created_at DateTime64(3, 'UTC')
) ENGINE = MergeTree
ORDER BY (session_id, created_at, seq);

-- Connectors use ReplacingMergeTree keyed by id with updated_at as the version,
-- mirroring chat_session: save re-inserts a newer row and reads use FINAL.
CREATE TABLE IF NOT EXISTS superagent.connector (
  id          String,
  name        String,
  description String,
  kind        String,
  auth_method String,
  enabled     UInt8,
  transport   String,
  endpoint    String,
  token_env   String,
  created_at  DateTime64(3, 'UTC'),
  updated_at  DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

-- Connector secrets, encrypted: per employee (owner = username) or the company's own (owner = '').
CREATE TABLE IF NOT EXISTS superagent.connector_credential (
  connector_id  String,
  owner         String,
  secret_cipher String,
  account_label String,
  updated_at    DateTime64(3, 'UTC'),
  refresh_cipher String,
  expires_at    Nullable(DateTime64(3, 'UTC'))
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (connector_id, owner);

-- Provisioned users who may sign in (preprovision-only). The admin comes from config.
CREATE TABLE IF NOT EXISTS superagent.app_user (
  id            String,
  username      String,
  display_name  String,
  roles         String,
  enabled       UInt8,
  password_hash String,
  phone         String,
  external_id   String,
  source        String,
  created_at    DateTime64(3, 'UTC'),
  updated_at    DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

-- Document-driven agent runs; execution stream/steering messages live in
-- agent_event / chat_message keyed by the task id.
CREATE TABLE IF NOT EXISTS superagent.task (
  id            String,
  title         String,
  brief         String,
  source        String,
  repo          String,
  branch        String,
  flow          String,
  status        String,
  progress      Int32,
  spend         Decimal(12, 2),
  model         String,
  agent_type    String,
  workspace_dir String,
  created_by    String,
  error         String,
  created_at    DateTime64(3, 'UTC'),
  started_at    Nullable(DateTime64(3, 'UTC')),
  finished_at   Nullable(DateTime64(3, 'UTC')),
  updated_at    DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

-- One row per task holding its flow phase array as JSON (PRD markdown, per-step
-- status, verification evidence). ReplacingMergeTree keyed by task_id; reads use FINAL.
CREATE TABLE IF NOT EXISTS superagent.task_phase (
  task_id     String,
  phases_json String,
  updated_at  DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY task_id;
