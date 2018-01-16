# backtick

A background job processor

## Overview

Backtick is a library for Clojure that pulls tasks from a queue and
runs them.  There is one central persistent queue for the jobs and the
job runners can be distributed across multiple machines.

## Artifacts

`backtick` artifacts are [released to Clojars](https://clojars.org/ctdean/backtick).

[![Clojars Project](http://clojars.org/ctdean/backtick/latest-version.svg)](https://clojars.org/ctdean/backtick)

## Quick start

### Database

First configure your Postgres datastore by running `make rebuild`.

Backtick will read `:bt-database-url` or failing that will use the
`:database-url` configuration parameter.  For example:

```
{
 :bt-database-url "jdbc:postgresql://localhost:5432/backtick?user=postgres"
}
```

### Environment

Use must install the libsodium shared library. On a OS X that is `brew install libsodium`

### Jobs

You configure jobs using the `define-worker` macro:

``` clj
(require '[backtick.core :as bt])
(require '[clojure.tools.logging :as log])

(bt/define-worker log-sum [x y]
  (log/infof "sum %s + %s = %s" x y (+ x y)))
```

and put the job in the queue by calling the `schedule` function:

``` clj
(schedule log-sum 3 4)
(schedule log-sum 88 99)
```

You may also run periodic jobs using `define-recurring`:

``` clj
(bt/define-recurring heartbeat-every-5-minutes (* 1000 60 5) []
  (log/info "heartbeat"))
```

Or if you need you job to run at specific wall clock/calendar times,
you may use `define-cron`:

```clj
(bt/define-cron twice-in-the-afternoon "0 0 14,16 * * *" []
  (log/info "do a thing"))
```

Backtick depends on the
[clj-cron-parse library](https://github.com/shmish111/clj-cron-parse)
to parse Cron specifications. The format is broadly the same as a standard
crontab, with the addition of seconds in the first position. Briefly:

| field        | allowed values                                           |
| -----        | --------------                                           |
| second       | 0-59                                                     |
| minute       | 0-59                                                     |
| hour         | 0-23                                                     |
| day of month | 1-31 L W                                                 |
| month        | 1-12 (or names)                                          |
| day of week  | 0-7 (0 or 7 is Sun, or use names) W 1L 2L 3L 4L 5L 6L 7L |

For more details on supported formats, consult
[clj-cron-parse's documentation](https://github.com/shmish111/clj-cron-parse/blob/master/src/clj_cron_parse/core.clj#L296-L347).

Each of `define-worker`, `define-recurring`, and `define-cron` creates a regular
Clojure function of the same name.

### Servers

You can run as many servers as you like to process the jobs.  The
server may be run using the start function:

``` clj
(bt/start)
```

or by running `backtick.core` from a pre-built jar.

## Theory of operation

Backtick works by polling a Postgres table that acts as an incoming
queue and then running jobs from that queue.  There is one thread that
polls Postgres and then hands the job to be processed by a separate
thread pool.

Any data needed for the job is encoded as an EDN object in the
Postgres table.

When a job is completed, it is marked in the queue as done.  If the
job fails, a recurring job will revive the dead one and place it back
in the queue.

Polling isn't a great way to run a high volume queue, but using a
Postgres table has the advantage of being simple, stable, and able to
easily handle our load.

## Glossary

- Worker: A function that's registered with Backtick.
- Job: A unit of work that is scheduled to be run. Running a job
  causes a worker to be invoked.
- At job: A job scheduled to run at a particular time in the future.
- Recurring job: A job scheduled to run repeatedly after a particular time interval
  has elapsed.
- Cron job: A job that recurs based on the clock or calendar time.
- Runner: A process that executes a job.

## Authors

- Chris Dean <ctdean@sokitomi.com>
- Jim Brusstar <jim.brusstar@gmail.com>

## License

Copyright (c) Chris Dean

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
