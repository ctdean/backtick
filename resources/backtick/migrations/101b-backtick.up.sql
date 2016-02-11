CREATE TABLE backtick_queue (
    id          serial primary key,
    name        text NOT NULL,
    priority    timestamp NOT NULL,
    state       text NOT NULL,
    tries       integer DEFAULT 0,
    data        jsonb,
    started_at  timestamp,
    finished_at timestamp,

    created_at  timestamp NOT NULL,
    updated_at  timestamp NOT NULL
);

CREATE INDEX backtick_queue_pri_index ON backtick_queue (priority);
CREATE INDEX backtick_queue_state_index ON backtick_queue (state);

CREATE TABLE backtick_cron (
    id              serial primary key,
    name            text NOT NULL, -- Name of the
    next            timestamp NOT NULL,
    interval        integer NOT NULL,

    created_at  timestamp NOT NULL,
    updated_at  timestamp NOT NULL
);

CREATE UNIQUE INDEX backtick_cron_name_index ON backtick_cron (name);
CREATE INDEX backtick_cront_next_index ON backtick_cron (next);

CREATE OR REPLACE FUNCTION
  backtick_upsert_interval(the_name TEXT,
                           the_interval integer,
                           the_next timestamp)
  RETURNS VOID AS
$$
  BEGIN
    LOOP
      -- A existing job, with the same interval => Do nothing
      IF EXISTS (SELECT 1 FROM backtick_cron
                 WHERE name = the_name AND interval = the_interval)
      THEN
        RETURN;
      END IF;

      -- An existing job, with a new interval => Change the next and
      -- interval fields.
      UPDATE backtick_cron
        SET
          interval = the_interval,
          next = the_next,
          updated_at = now()
        WHERE
          name = the_name;
      IF FOUND THEN
        RETURN;
      END IF;

      -- A new job => insert it
      BEGIN
        INSERT INTO backtick_cron
          (name, next, interval, created_at, updated_at)
        VALUES
          (the_name, the_next, the_interval, now(), now());
        RETURN;
      EXCEPTION WHEN unique_violation THEN
        -- Do nothing, and loop to try the UPDATE again.
      END;
    END LOOP;
  END;
$$
LANGUAGE plpgsql;
