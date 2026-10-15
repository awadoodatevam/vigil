-- Event Publication Registry (AR-R-13): the PostgreSQL DDL that spring-modulith-events-jdbc 2.1.1
-- ships as org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql, with the
-- table names qualified by the schema platform.
CREATE TABLE IF NOT EXISTS platform.event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON platform.event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON platform.event_publication (completion_date);

-- AR-P-06: technical table.
GRANT USAGE ON SCHEMA platform TO vigil_app;
GRANT SELECT, INSERT, UPDATE ON platform.event_publication TO vigil_app;
