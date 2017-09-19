ALTER TABLE backtick_cron RENAME TO backtick_recurring;

ALTER SEQUENCE backtick_cron_id_seq RENAME TO backtick_recurring_id_seq;

ALTER INDEX backtick_cron_pkey RENAME TO backtick_recurring_pkey;
ALTER INDEX backtick_cron_name_index RENAME TO backtick_recurring_name_index;
ALTER INDEX backtick_cront_next_index RENAME TO backtick_recurring_next_index;

-- See migration 101b-backtick.up.sql
CREATE OR REPLACE FUNCTION
  backtick_upsert_interval(the_name TEXT,
                           the_interval integer,
                           the_next timestamp)
  RETURNS VOID AS
$$
  BEGIN
    LOOP
      -- A existing job, with the same interval => Do nothing
      IF EXISTS (SELECT 1 FROM backtick_recurring
                 WHERE name = the_name AND interval = the_interval)
      THEN
        RETURN;
      END IF;

      -- An existing job, with a new interval => Change the next and
      -- interval fields.
      UPDATE backtick_recurring
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
        INSERT INTO backtick_recurring
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
