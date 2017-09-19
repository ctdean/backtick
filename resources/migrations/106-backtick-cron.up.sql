ALTER TABLE backtick_recurring ADD COLUMN cronspec text;

ALTER TABLE backtick_recurring ALTER COLUMN interval DROP NOT NULL;

ALTER TABLE backtick_recurring
ADD CHECK ((interval IS NULL OR cronspec IS NULL) AND
           (interval IS NOT NULL OR cronspec IS NOT NULL));

-- See migration 104-recurring.up.sql
CREATE OR REPLACE FUNCTION
  backtick_upsert_interval(the_name TEXT,
                           the_interval integer,
                           the_cronspec text,
                           the_next timestamp)
  RETURNS VOID AS
$$
  BEGIN
    LOOP
      -- A existing job, with the same interval/cronspec => Do nothing
      IF EXISTS (SELECT 1 FROM backtick_recurring
                 WHERE name = the_name
                 AND interval IS NOT DISTINCT FROM the_interval
                 AND cronspec IS NOT DISTINCT FROM the_cronspec)
      THEN
        RETURN;
      END IF;

      -- An existing job, with a new interval/cronspec => Change the next,
      -- interval, and cronspec fields.
      UPDATE backtick_recurring
        SET
          interval = the_interval,
          cronspec = the_cronspec,
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
          (name, next, interval, cronspec, created_at, updated_at)
        VALUES
          (the_name, the_next, the_interval, the_cronspec, now(), now());
        RETURN;
      EXCEPTION WHEN unique_violation THEN
        -- Do nothing, and loop to try the UPDATE again.
      END;
    END LOOP;
  END;
$$
LANGUAGE plpgsql;
