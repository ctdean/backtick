-- :name queue-pop :? :*
-- :doc Pop the top element off the backtick queue
update backtick_queue bq
set    state = 'running',
       started_at = now(),
       updated_at = now(),
       tries = tries + 1
from (
   select id
   from backtick_queue
   where state = 'queued' and priority <= now()
   order by priority
   limit 1
   for update
   ) sub
where  bq.id = sub.id
returning bq.*;

-- :name queue-insert! :insert
-- :doc Insert a new job element
insert into backtick_queue
  (name, priority, state, tries, data, created_at, updated_at)
values
  (:name, coalesce(:priority, now()), :state, :tries, :data, now(), now());

-- :name queue-finish! :! :n
-- :doc Mark a job as finished
update backtick_queue
set    state = 'done', finished_at = now()
where  id = :id and state = 'running'

-- :name queue-running-job :? :*
-- :doc Find a job that is still running
select * from backtick_queue
where id = :id and state = 'running'

-- :name queue-killed-jobs :? :*
-- :doc Find jobs that haven't finished in time
select * from backtick_queue
where state = 'running'
   and (started_at is null or started_at < :killtime)

-- :name queue-abort-job! :! :n
-- :doc Abort a job that has been tried too many times
update backtick_queue
set state = 'exceeded', updated_at = now(), finished_at = now()
where id = :id and state = 'running'

-- :name queue-requeue-job! :! :n
-- :doc Put a job back in the queue that did not finish
update backtick_queue
set state = 'queued',
    priority = :priority,
    updated_at = now()
where id = :id and state = 'running'

-- :name queue-cancel-all-jobs! :! :n
-- :doc Cancel all queued jobs so that they won't run.
UPDATE backtick_queue
SET state = 'canceled', updated_at = now()
WHERE state = 'queued'

-- :name queue-delete-old-jobs! :! :n
-- :doc Delete very old jobs.
delete from backtick_queue
where (state = 'done' and finished_at < :finished) OR
      (state = 'canceled' and updated_at < :finished)

-- :name recurring-update-next! :! :n
-- :doc Update the next runtime for an existing recurring job
update backtick_recurring
set    next = :next
where  id = :id

-- :name recurring-all :? :*
-- :doc Find all recurring jobs
select * from backtick_recurring;

-- :name recurring-delete! :! :n
-- :doc Delete a recurring job
delete from backtick_recurring where name = :name

-- :name recurring-upsert-interval :? :*
-- :doc Update the interval on an existing recurring job or insert a new one
select backtick_upsert_interval(:name, :interval, :cronspec, :timezone, :next);

-- :name recurring-next :? :*
-- :doc Get the next recurring job to run
update backtick_recurring br
set    next = :next
from (
   select id
   from   backtick_recurring
   where  next < :now
   limit  1
   for update
   ) sub
where  br.id = sub.id
returning br.*;
