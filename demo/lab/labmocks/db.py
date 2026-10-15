"""The subscriber database (role db, DD-F-10 mock_behaviour.subscriber_database)."""

import os

from .common import BAD_REQUEST, NOT_FOUND, SCHEMAS, Counts, Outcomes, log, parse_body, write_status

LOW_SPACE_BYTES = 268435456  # 256 MiB
READ_ONLY_FLAG = "db_read_only"


class Database:
    """Answers reads and writes of the 6 schemas; a write fails while the read-only flag exists or /data is low.

    It only reads /data's free space; it never creates, changes or deletes a file under /data.
    """

    def __init__(self, data_path, control_dir, status_dir, statvfs=os.statvfs):
        self._data_path = data_path
        self._flag = os.path.join(control_dir, READ_ONLY_FLAG)
        self._status_dir = status_dir
        self._statvfs = statvfs
        self.writes = Counts(SCHEMAS)
        self.write_errors = Counts(SCHEMAS)
        self._outcomes = Outcomes("write of schema")
        self._read_only = False

    def write(self, schema):
        error = self._write_error()
        self.writes.add(schema)
        if error is not None:
            self.write_errors.add(schema)
        self._outcomes.record(schema, error)
        if error is not None:
            return 409, {"ok": False, "error": error}
        return 200, {"ok": True}

    def _write_error(self):
        read_only = os.path.exists(self._flag)
        if read_only != self._read_only:
            self._read_only = read_only
            log.info("read-only flag %s", "set" if read_only else "removed")
        if read_only:
            return "database is read-only"
        stat = self._statvfs(self._data_path)
        if stat.f_bavail * stat.f_frsize < LOW_SPACE_BYTES:
            return "No space left on device"
        return None

    def read(self, schema):
        return 200, {"ok": True}

    def handle(self, path, raw):
        if path not in ("/write", "/read"):
            return NOT_FOUND
        try:
            body = parse_body(raw)
        except ValueError:
            return BAD_REQUEST
        if body is None or set(body) != {"schema"} or body["schema"] not in SCHEMAS:
            return BAD_REQUEST
        if path == "/write":
            return self.write(body["schema"])
        return self.read(body["schema"])

    def write_status(self):
        writes = self.writes.snapshot()
        errors = self.write_errors.snapshot()
        for schema in SCHEMAS:
            write_status(self._status_dir, f"db_writes_{schema}", writes[schema])
            write_status(self._status_dir, f"db_write_errors_{schema}", errors[schema])
