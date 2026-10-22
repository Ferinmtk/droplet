-- One row per visit (a page view, sent by the page itself) or download-link click.
CREATE TABLE IF NOT EXISTS events (
  id      INTEGER PRIMARY KEY,
  ts      INTEGER NOT NULL,  -- milliseconds since 1970, UTC
  kind    TEXT NOT NULL,     -- 'visit' or 'download'
  what    TEXT NOT NULL,     -- the page ('/') or the download ('android', 'windows', 'store', 'linux', 'mac')
  visitor TEXT NOT NULL,     -- the day's code for this person (changes every day; not reversible)
  country TEXT, city TEXT, device TEXT,
  source  TEXT               -- where they came from, if the browser said
);
CREATE INDEX IF NOT EXISTS events_ts ON events (ts);
