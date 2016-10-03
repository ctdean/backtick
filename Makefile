#
# Backtick Makefile
# 

DATABASE?=backtick

all: run

run:
	lein trampoline run

# Create the tables
init:
	- createuser -s postgres -h localhost
	- createdb -Upostgres -h localhost $(DATABASE)
	- createdb -Upostgres -h localhost $(DATABASE)_test

# Drop the tables
drop:
	dropdb -Upostgres -h localhost $(DATABASE) || exit 0
	dropdb -Upostgres -h localhost $(DATABASE)_test || exit 0

migrate:
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)?user=postgres" \
	    lein migrate
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)_test?user=postgres" \
	    lein migrate

rollback:
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)?user=postgres" \
	    lein rollback
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)_test?user=postgres" \
	    lein rollback

# Nuke the existing databases and recreate
rebuild: drop init migrate

test:
	lein test

# Run test refresh with the correct profile and injections.
test_refresh:
	lein with-profile +test trampoline test-refresh

.PHONY: test
