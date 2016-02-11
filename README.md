# backtick

A background job processor

## Overview

Backtick is a library for Clojure that pulls tasks from a queue and
runs them.  There is one central persistent queue for the jobs and the
job runners can be distributed across multiple machines.

## Artifacts

`backtick` artifacts are [released to Clojars](https://clojars.org/clj-time/clj-time).

[![Clojars Project](http://clojars.org/ctdean/backtick/latest-version.svg)](http://clojars.org/ctdean/backtick)

## Quick start

### Database

First configure your Postgres datastore using Clams
https://github.com/standardtreasury/clams/wiki/Configuration

Backtick will read `:bt-database-url` or failing that will use the
`:database-url` configuration parameter.  For example:

```{
 :bt-database-url "jdbc:postgresql://localhost:5432/backtick?user=postgres"
}
```

### Jobs

You configure the jobs using the `define-worker` macro:

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

You may also run periodic jobs using `define-cron`

``` clj
(bt/define-cron heartbeat-every-5-minutes (* 1000 60 5) []
  (log/info "heartbeat"))
```

Both `define-worker` and `define-cron` create a regular Clojure
function of the same name.

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

Any data needed for the job is encoded as an EDN object in the Postgres table.

When a job is completed, it is marked in the queue as done.  If the
job fails, a recurring job will revive the dead one and place it back
in the queue.

Polling isn't a great way to run a high volume queue, but using a
Postgres table has the advantage of being simple, stable, and being
able to easily handle our load.

## Glossary

- Worker: A function that's registered with Backtick.
- Job: A unit of work that is scheduled to be run. Running a job causes a worker to be invoked.
- Runner: A process that executes a job.
- Cron: A job that recurs based on the time.

## Authors

Chris Dean <ctdean@sokitomi.com>

## License

Copyright (c) Chris Dean

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
