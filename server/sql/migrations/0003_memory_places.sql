-- 0003: where things happened. An event carries the place he was in (by name), and a memory the
-- places what it covers happened in, most of it first, joined with "; ". Null where there are no places.
ALTER TABLE memory_events ADD COLUMN place text;
ALTER TABLE memories ADD COLUMN places text;
