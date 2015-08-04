#
# Makefile - utility commands
# 
# Chris Dean

all: run

run:
	lein trampoline run

# Drop the tables
drop:
	mongo backtick --eval 'db.dropDatabase()'
	mongo backtick_test --eval 'db.dropDatabase()'

migrate:
	lein run -m clams.migrate migrate

# Nuke the existing databases and recreate
rebuild: drop 

test:
	lein test

# Run test refresh with the correct profile and injections.
test_refresh:
	lein with-profile +test trampoline test-refresh

