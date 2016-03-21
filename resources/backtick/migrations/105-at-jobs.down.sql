ALTER TABLE backtick_queue ADD COLUMN run_at timestamp without time zone;

CREATE INDEX backtick_queue_run_at_index ON backtick_queue (run_at);
