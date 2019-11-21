#
# Backtick Makefile
#

DATABASE?=backtick

all: run

run:
	lein trampoline run

install:
	lein install

###
### Database
###

db_init:
	-createuser -s postgres -h localhost
	-createdb -Upostgres -h localhost $(DATABASE)
	-createdb -Upostgres -h localhost $(DATABASE)_test

db_drop:
	-dropdb -Upostgres -h localhost $(DATABASE)
	-dropdb -Upostgres -h localhost $(DATABASE)_test

db_migrate:
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)?user=postgres" lein run -m common.db.migrate/run-migrate
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)_test?user=postgres" CONF_ENV=test lein with-profile +test run -m common.db.migrate/run-migrate

db_rebuild: db_drop db_init db_migrate

# Nuke the existing databases and recreate
rebuild: drop init migrate

test:
	CONF_ENV=test lein test

# Run test refresh with the correct profile and injections.
test_refresh:
	CONF_ENV=test lein test-refresh

.PHONY: test
