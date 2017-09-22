#
# Backtick Makefile
#

DATABASE?=backtick

all: run

run:
	lein trampoline run

install:
	boot pom jar install

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
	    boot migrate
	DATABASE_URL="jdbc:postgresql://localhost:5432/$(DATABASE)_test?user=postgres" \
	    boot migrate

# Nuke the existing databases and recreate
rebuild: drop init migrate

test:
	CONF_ENV=test boot alt-test

# Run test refresh with the correct profile and injections.
test_refresh:
	CONF_ENV=test boot watch alt-test

.PHONY: test
