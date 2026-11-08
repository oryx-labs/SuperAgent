-- SuperAgent storage schema (MySQL): chat sessions/messages/events and MCP connectors.
-- Backend selected by superagent.storage.type=mysql.
-- Apply with: mysql -uroot < db/mysql/schema.sql
--
-- Every table belongs to a company: company_id leads each key, and every query filters on it. Tables from
-- before companies (no company_id) are not migrated: drop them and apply this file again.

CREATE DATABASE IF NOT EXISTS superagent
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE superagent;

CREATE TABLE IF NOT EXISTS chat_session (
  company_id    VARCHAR(64)  NOT NULL,
  id               VARCHAR(64)  NOT NULL,
  title            VARCHAR(255),
  agent_type       VARCHAR(32)  NOT NULL,
  agent_session_id VARCHAR(64),
  workspace_dir    VARCHAR(1024),
  status           VARCHAR(32)  NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  updated_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (company_id, id),
  KEY idx_session_updated (company_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
  company_id    VARCHAR(64)  NOT NULL,
  id         VARCHAR(64) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  role       VARCHAR(16) NOT NULL,
  content    MEDIUMTEXT,
  created_at DATETIME(3) NOT NULL,
  author     VARCHAR(128),
  PRIMARY KEY (company_id, id),
  KEY idx_message_session (company_id, session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_event (
  company_id    VARCHAR(64)  NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  message_id VARCHAR(64),
  seq        BIGINT      NOT NULL,
  type       VARCHAR(32) NOT NULL,
  payload    MEDIUMTEXT,
  created_at DATETIME(3) NOT NULL,
  KEY idx_event_session (company_id, session_id, created_at, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS connector (
  company_id    VARCHAR(64)  NOT NULL,
  id          VARCHAR(64)   NOT NULL,
  name        VARCHAR(255)  NOT NULL,
  description VARCHAR(1024),
  kind        VARCHAR(16)   NOT NULL,
  auth_method VARCHAR(32)   NOT NULL,
  enabled     TINYINT       NOT NULL,
  transport   VARCHAR(16)   NOT NULL,
  endpoint    VARCHAR(2048),
  token_env   VARCHAR(128),
  created_at  DATETIME(3)   NOT NULL,
  updated_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (company_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Connector secrets, encrypted: per employee (owner = username) or the company's own (owner = '');
-- kept out of the connector row and never returned by the API.
CREATE TABLE IF NOT EXISTS connector_credential (
  company_id    VARCHAR(64)  NOT NULL,
  connector_id  VARCHAR(64)   NOT NULL,
  owner         VARCHAR(128)  NOT NULL,
  secret_cipher VARCHAR(4096) NOT NULL,
  account_label VARCHAR(255),
  updated_at    DATETIME(3)   NOT NULL,
  PRIMARY KEY (company_id, connector_id, owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Provisioned users who may sign in (preprovision-only). The admin comes from config.
CREATE TABLE IF NOT EXISTS app_user (
  company_id    VARCHAR(64)  NOT NULL,
  id            VARCHAR(64)  NOT NULL,
  username      VARCHAR(190) NOT NULL,
  display_name  VARCHAR(255),
  roles         VARCHAR(512),
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  password_hash VARCHAR(255),
  phone         VARCHAR(32),
  external_id   VARCHAR(255),
  source        VARCHAR(16)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (company_id, id),
  UNIQUE KEY uq_user_username (username),
  KEY idx_user_phone (company_id, phone),
  KEY idx_user_external (company_id, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Document-driven agent runs. The execution stream and steering messages live in
-- agent_event / chat_message keyed by the task id (shared agent-run storage).
CREATE TABLE IF NOT EXISTS task (
  company_id    VARCHAR(64)  NOT NULL,
  id            VARCHAR(64)   NOT NULL,
  title         VARCHAR(512)  NOT NULL,
  brief         MEDIUMTEXT,
  source        VARCHAR(16)   NOT NULL,
  repo          VARCHAR(512),
  branch        VARCHAR(255),
  flow          VARCHAR(255),
  status        VARCHAR(32)   NOT NULL,
  progress      INT           NOT NULL DEFAULT 0,
  spend         DECIMAL(12,2) NOT NULL DEFAULT 0,
  model         VARCHAR(128),
  tier          VARCHAR(16),
  agent_type    VARCHAR(32)   NOT NULL,
  workspace_dir VARCHAR(1024),
  created_by    VARCHAR(255),
  error         MEDIUMTEXT,
  created_at    DATETIME(3)   NOT NULL,
  started_at    DATETIME(3),
  finished_at   DATETIME(3),
  updated_at    DATETIME(3)   NOT NULL,
  PRIMARY KEY (company_id, id),
  KEY idx_task_created (company_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
