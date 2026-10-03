-- docs/02 Data Model

CREATE TABLE members (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  display_name  TEXT,
  status        TEXT NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'REVOKED')),
  time_zone     TEXT,
  allowed_at    INTEGER NOT NULL,
  activated_at  INTEGER,
  revoked_at    INTEGER,
  last_seen_at  INTEGER
);

CREATE TABLE devices (
  id               TEXT PRIMARY KEY,
  member_id        TEXT NOT NULL REFERENCES members(id),
  installation_id  TEXT NOT NULL UNIQUE,
  fcm_token        TEXT NOT NULL,
  active           INTEGER NOT NULL CHECK (active IN (0, 1)),
  model            TEXT,
  app_version      INTEGER,
  registered_at    INTEGER NOT NULL,
  last_seen_at     INTEGER NOT NULL
);

CREATE UNIQUE INDEX devices_active_token ON devices(fcm_token) WHERE active = 1;
CREATE INDEX devices_member ON devices(member_id);

CREATE TABLE channels (
  slug            TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  description     TEXT,
  required        INTEGER NOT NULL CHECK (required IN (0, 1)),
  auto_subscribe  INTEGER NOT NULL CHECK (auto_subscribe IN (0, 1)),
  created_at      INTEGER NOT NULL,
  archived_at     INTEGER
);

CREATE TABLE subscriptions (
  member_id     TEXT NOT NULL REFERENCES members(id),
  channel       TEXT NOT NULL REFERENCES channels(slug),
  min_severity  TEXT NOT NULL DEFAULT 'LOW' CHECK (min_severity IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
  muted         INTEGER NOT NULL DEFAULT 0 CHECK (muted IN (0, 1)),
  muted_until   INTEGER,
  created_at    INTEGER NOT NULL,
  PRIMARY KEY (member_id, channel)
);

CREATE INDEX subscriptions_channel ON subscriptions(channel);

CREATE TABLE quiet_hours (
  member_id  TEXT PRIMARY KEY REFERENCES members(id),
  enabled    INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  start_min  INTEGER NOT NULL CHECK (start_min BETWEEN 0 AND 1439),
  end_min    INTEGER NOT NULL CHECK (end_min BETWEEN 0 AND 1439)
);

CREATE TABLE senders (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL UNIQUE,
  key_hash          TEXT NOT NULL UNIQUE,
  allowed_channels  TEXT NOT NULL,
  created_at        INTEGER NOT NULL,
  last_used_at      INTEGER,
  revoked_at        INTEGER
);

CREATE TABLE messages (
  id              TEXT PRIMARY KEY,
  sender_id       TEXT REFERENCES senders(id),
  target_type     TEXT NOT NULL CHECK (target_type IN ('CHANNEL', 'USERS', 'ALL')),
  target_channel  TEXT,
  target_emails   TEXT,
  title           TEXT NOT NULL,
  body            TEXT NOT NULL,
  severity        TEXT NOT NULL CHECK (severity IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
  data            TEXT,
  created_at      INTEGER NOT NULL
);

CREATE INDEX messages_created ON messages(created_at);

CREATE TABLE message_recipients (
  message_id  TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  member_id   TEXT NOT NULL REFERENCES members(id),
  result      TEXT NOT NULL CHECK (result IN ('DELIVER', 'QUIET', 'MUTED', 'BELOW_MIN', 'NOT_ACTIVE', 'NO_DEVICE')),
  PRIMARY KEY (message_id, member_id)
);

CREATE TABLE deliveries (
  id               TEXT PRIMARY KEY,
  message_id       TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  device_id        TEXT NOT NULL REFERENCES devices(id),
  quiet            INTEGER NOT NULL CHECK (quiet IN (0, 1)),
  status           TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'INVALID_TOKEN', 'CANCELLED')),
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  INTEGER,
  last_error       TEXT,
  updated_at       INTEGER NOT NULL
);

CREATE INDEX deliveries_pending ON deliveries(next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX deliveries_device ON deliveries(device_id)
