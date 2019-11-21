ALTER TABLE backtick_queue     ADD COLUMN queue_name text default 'default';
ALTER TABLE backtick_recurring ADD COLUMN queue_name text default 'default';

create index on backtick_queue(queue_name);
create index on backtick_recurring(queue_name);
