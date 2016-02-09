-- WARNING: This migration will nuke all in-flight jobs!

DELETE FROM backtick_queue;
ALTER TABLE backtick_queue ALTER COLUMN data SET DATA TYPE text;
