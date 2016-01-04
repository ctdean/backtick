DROP TABLE backtick_queue;
DROP TABLE backtick_cron;
DROP FUNCTION backtick_upsert_interval(the_name TEXT,
                                       the_interval integer,
                                       the_next timestamp);
