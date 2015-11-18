#
# Makefile - utility commands
# 
# Chris Dean

all: run

run:
	lein trampoline run

# Create the tables
init:
	createuser -s postgres -h localhost || exit 0
	createdb -Upostgres -h localhost api
	createdb -Upostgres -h localhost api_test

# Drop the tables
drop:
	dropdb -Upostgres -h localhost api      || exit 0
	dropdb -Upostgres -h localhost api_test || exit 0

migrate:
	DATABASE_URL="jdbc:postgresql://localhost:5432/api?user=postgres" \
	    lein run -m clams.migrate migrate
	DATABASE_URL="jdbc:postgresql://localhost:5432/api_test?user=postgres" \
	    lein run -m clams.migrate migrate

# Nuke the existing databases and recreate
rebuild: drop init migrate

test:
	lein test

# Run test refresh with the correct profile and injections.
test_refresh:
	lein with-profile +test trampoline test-refresh

.PHONY: test
