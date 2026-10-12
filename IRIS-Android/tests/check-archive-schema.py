#!/usr/bin/env python3
"""Execute the notification-archive DDL against a real SQLite engine and prove it is append-only.

Why this exists: the write-once guarantee is the most important structural property of the archive,
and it is enforced by SQLite triggers rather than by Java code. Those triggers cannot be tested from
the project's offline Java suites (SQLiteOpenHelper is Android-only, and Robolectric is not available
here). Python ships a real SQLite engine, so the DDL can be executed and the guarantee actually
verified instead of assumed.

The SQL is extracted from ArchiveSchema.java rather than copied, so there is a single source of
truth. A duplicated copy here could drift and leave this test passing against SQL the app no longer
uses.

Run from the IRIS-Android directory:  python tests/check-archive-schema.py
"""
import re
import sqlite3
import sys
from pathlib import Path

SCHEMA_JAVA = Path("app/src/main/java/com/iris/assistant/ArchiveSchema.java")
BEGIN = "---SQL-BEGIN---"
END = "---SQL-END---"

checks = 0
failures = []


def check(condition, what):
    global checks
    checks += 1
    if not condition:
        failures.append(what)


def extract_statements():
    """Pull the single-line SQL string literals out of the marked region of ArchiveSchema.java."""
    if not SCHEMA_JAVA.exists():
        print(f"FAILED: {SCHEMA_JAVA} not found (run from the IRIS-Android directory)")
        sys.exit(1)
    text = SCHEMA_JAVA.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        print("FAILED: SQL markers missing from ArchiveSchema.java")
        sys.exit(1)
    region = text.split(BEGIN, 1)[1].split(END, 1)[0]
    # Each statement is one double-quoted literal on its own line, by convention documented in the
    # Java file precisely so this extraction stays simple and reliable.
    statements = re.findall(r'"((?:[^"\\]|\\.)*)"', region)
    return [s.replace('\\"', '"') for s in statements if s.strip()]


def build(statements):
    connection = sqlite3.connect(":memory:")
    for statement in statements:
        connection.execute(statement)
    connection.commit()
    return connection


def insert_row(connection, fingerprint, **overrides):
    values = {
        "pkg": "com.whatsapp",
        "app_label": "WhatsApp",
        "account_label": "WhatsApp (Main)",
        "user_handle": 0,
        "notif_key": "0|com.whatsapp|1|null|10",
        "raw_title": "Ana",
        "sender": "Ana",
        "group_name": "",
        "body": "see you at 8",
        "post_time": 1760000000000,
        "captured_time": 1760000000001,
        "fingerprint": fingerprint,
        "row_kind": 0,
    }
    values.update(overrides)
    columns = ", ".join(values)
    marks = ", ".join("?" for _ in values)
    cursor = connection.execute(
        f"INSERT INTO notifications ({columns}) VALUES ({marks})", list(values.values())
    )
    connection.commit()
    return cursor.lastrowid


def verify_query_semantics(connection):
    """Exercise the SQL that NotificationArchive relies on.

    NotificationArchive itself is Android-only and cannot run here, but its WHERE clauses are plain
    SQL. Running them against a real engine catches the mistakes that matter: picking the wrong row
    to flag, and LIKE wildcards in a searched name behaving as pattern metacharacters.
    """
    # A fresh table so the earlier tamper tests do not interfere.
    connection.execute("UPDATE archive_control SET v = 1 WHERE k = 'manual_delete_allowed'")
    connection.execute("DELETE FROM notifications")
    connection.execute("UPDATE archive_control SET v = 0 WHERE k = 'manual_delete_allowed'")
    connection.commit()

    base = 1760000000000
    first = insert_row(connection, "q-1", sender="Ana", raw_title="Ana", body="first", post_time=base)
    second = insert_row(connection, "q-2", sender="Ana", raw_title="Ana", body="second",
                        post_time=base + 1000)
    insert_row(connection, "q-3", sender="Bob", raw_title="Bob", body="from bob", post_time=base + 2000)
    marker_id = insert_row(connection, "q-4", sender="Ana", raw_title="Ana", body="a marker",
                           post_time=base + 3000, row_kind=2)

    # The flag target: most recent UNFLAGGED message row from that sender, markers excluded.
    # raw_title is only trusted as the sender for one-to-one rows (group_name empty). In a group the
    # title is the GROUP name, so matching it unconditionally would flag the wrong person's message
    # whenever a group is named after someone -- a real bug this suite caught.
    select_target = (
        "SELECT _id FROM notifications WHERE row_kind = 0 AND is_deleted = 0 AND is_suspected = 0 "
        "AND pkg = ? AND (sender = ? OR (group_name = '' AND raw_title = ?)) "
        "ORDER BY post_time DESC, _id DESC LIMIT 1"
    )
    target = connection.execute(select_target, ("com.whatsapp", "Ana", "Ana")).fetchone()[0]
    check(target == second, "flagging picks the most recent message from that sender")

    connection.execute(
        "UPDATE notifications SET is_deleted = 1, deletion_time = ? WHERE _id = ?",
        (base + 5000, second))
    connection.commit()

    # A second deletion must land on a different message, not re-flag the same one. This is why the
    # query filters on unflagged rows rather than simply taking the newest.
    next_target = connection.execute(select_target, ("com.whatsapp", "Ana", "Ana")).fetchone()[0]
    check(next_target == first, "a second deletion flags the next message, not the same one again")

    # Markers must never be chosen as a flag target even when they match the sender and are newest.
    check(next_target != marker_id, "a marker row is never chosen as a flag target")

    # Nothing left to flag reports no row, which is what triggers the orphan path.
    connection.execute("UPDATE notifications SET is_deleted = 1 WHERE _id = ?", (first,))
    connection.commit()
    exhausted = connection.execute(select_target, ("com.whatsapp", "Ana", "Ana")).fetchone()
    check(exhausted is None, "no unflagged message left reports nothing, triggering the orphan path")

    # ─── The group-title collision that the sender filter has to resist ───
    # A group literally named "Ana", carrying a message from Bob. Attributing a deletion to Ana must
    # not select Bob's message just because the chat title says Ana.
    bob_in_ana_group = insert_row(connection, "q-group", sender="Bob", raw_title="Ana",
                                  group_name="Ana", body="bob in a group called Ana",
                                  post_time=base + 9000)
    collision = connection.execute(select_target, ("com.whatsapp", "Ana", "Ana")).fetchone()
    check(collision is None,
          "a group named after a person does not cause another member's message to be flagged")
    # Bob's own deletion still finds it.
    bob_target = connection.execute(select_target, ("com.whatsapp", "Bob", "Bob")).fetchone()[0]
    check(bob_target == bob_in_ana_group, "the real sender in that group is still matched")

    # ─── LIKE wildcard escaping ───
    # A searched name containing % or _ must match literally. Unescaped, "100%" matches everything.
    insert_row(connection, "q-pct", sender="100% Ana", body="percent name", post_time=base + 4000)
    insert_row(connection, "q-und", sender="a_b", body="underscore name", post_time=base + 5000)
    insert_row(connection, "q-plain", sender="axb", body="should not match", post_time=base + 6000)

    def search_sender(term):
        escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        rows = connection.execute(
            "SELECT sender FROM notifications WHERE sender LIKE ? ESCAPE '\\'",
            (f"%{escaped}%",)).fetchall()
        return [r[0] for r in rows]

    pct = search_sender("100%")
    check(pct == ["100% Ana"], f"a percent sign is matched literally, not as a wildcard (got {pct})")

    und = search_sender("a_b")
    check(und == ["a_b"], f"an underscore is matched literally, not as any-character (got {und})")

    # And an ordinary search still works.
    plain = search_sender("Ana")
    check(len(plain) >= 1, "an ordinary sender search still matches")

    # ─── Date range ───
    ranged = connection.execute(
        "SELECT COUNT(*) FROM notifications WHERE row_kind = 0 AND post_time >= ? AND post_time <= ?",
        (base, base + 1000)).fetchone()[0]
    check(ranged == 2, "a date range selects only rows inside it")

    # ─── Range delete that preserves flagged rows ───
    connection.execute("UPDATE archive_control SET v = 1 WHERE k = 'manual_delete_allowed'")
    removed = connection.execute(
        "DELETE FROM notifications WHERE post_time >= ? AND post_time <= ? "
        "AND is_deleted = 0 AND is_suspected = 0",
        (base, base + 6000)).rowcount
    connection.execute("UPDATE archive_control SET v = 0 WHERE k = 'manual_delete_allowed'")
    connection.commit()
    check(removed > 0, "a range delete removes unflagged rows")
    survivors = connection.execute(
        "SELECT COUNT(*) FROM notifications WHERE is_deleted = 1").fetchone()[0]
    check(survivors == 2, "flagged rows survive a range delete, since they are the point of the archive")


def main():
    statements = extract_statements()
    check(len(statements) >= 9, f"extracted all DDL statements (found {len(statements)})")

    connection = build(statements)
    check(True, "DDL executes against a real SQLite engine")

    # The schema itself
    tables = {r[0] for r in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    check("notifications" in tables, "notifications table created")
    check("archive_control" in tables, "archive_control table created")

    triggers = {r[0] for r in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='trigger'")}
    check("archive_content_is_immutable" in triggers, "immutability trigger created")
    check("archive_delete_needs_owner" in triggers, "delete-guard trigger created")

    indexes = {r[0] for r in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'")}
    for expected in ("idx_fingerprint", "idx_sender_lookup", "idx_post_time", "idx_flagged"):
        check(expected in indexes, f"{expected} created")

    # Inserting is always allowed: the archive is append-only, not read-only.
    row_id = insert_row(connection, "fp-aaa")
    check(row_id > 0, "a row can be inserted")

    # Duplicate suppression is enforced by the engine, not just by Java.
    try:
        insert_row(connection, "fp-aaa")
        check(False, "a duplicate fingerprint is rejected by the unique index")
    except sqlite3.IntegrityError:
        check(True, "a duplicate fingerprint is rejected by the unique index")

    # ─── The core guarantee: captured content cannot be edited ───
    immutable = {
        "body": "'tampered'",
        "raw_title": "'tampered'",
        "sender": "'tampered'",
        "pkg": "'com.other'",
        "post_time": "1",
        "captured_time": "1",
        "fingerprint": "'fp-zzz'",
        "notif_key": "'tampered'",
        "app_label": "'tampered'",
        "account_label": "'tampered'",
        "group_name": "'tampered'",
        "user_handle": "9",
        "row_kind": "2",
    }
    for column, new_value in immutable.items():
        try:
            connection.execute(
                f"UPDATE notifications SET {column} = {new_value} WHERE _id = ?", (row_id,))
            connection.commit()
            check(False, f"editing {column} is refused by the engine")
        except sqlite3.IntegrityError:
            check(True, f"editing {column} is refused by the engine")
        except sqlite3.OperationalError:
            check(True, f"editing {column} is refused by the engine")

    # The original content survived every attempt.
    body = connection.execute(
        "SELECT body FROM notifications WHERE _id = ?", (row_id,)).fetchone()[0]
    check(body == "see you at 8", "the intercepted text is unchanged after tampering attempts")

    # ─── Flagging must still work, or the feature cannot function ───
    # A deletion is discovered after the message was archived, so these three columns stay writable.
    connection.execute(
        "UPDATE notifications SET is_deleted = 1, deletion_time = ? WHERE _id = ?",
        (1760000500000, row_id))
    connection.commit()
    flagged = connection.execute(
        "SELECT is_deleted, deletion_time FROM notifications WHERE _id = ?", (row_id,)).fetchone()
    check(flagged[0] == 1, "is_deleted can be set")
    check(flagged[1] == 1760000500000, "deletion_time can be set")

    connection.execute(
        "UPDATE notifications SET is_suspected = 1 WHERE _id = ?", (row_id,))
    connection.commit()
    suspected = connection.execute(
        "SELECT is_suspected FROM notifications WHERE _id = ?", (row_id,)).fetchone()[0]
    check(suspected == 1, "is_suspected can be set")

    # ─── Deletion requires an explicit owner action ───
    try:
        connection.execute("DELETE FROM notifications WHERE _id = ?", (row_id,))
        connection.commit()
        check(False, "an unauthorised delete is refused")
    except (sqlite3.IntegrityError, sqlite3.OperationalError):
        check(True, "an unauthorised delete is refused")

    still_there = connection.execute(
        "SELECT COUNT(*) FROM notifications WHERE _id = ?", (row_id,)).fetchone()[0]
    check(still_there == 1, "the row survived the unauthorised delete")

    # A bulk wipe, the exact accident the trigger exists to prevent.
    try:
        connection.execute("DELETE FROM notifications")
        connection.commit()
        check(False, "an unauthorised bulk wipe is refused")
    except (sqlite3.IntegrityError, sqlite3.OperationalError):
        check(True, "an unauthorised bulk wipe is refused")

    remaining = connection.execute("SELECT COUNT(*) FROM notifications").fetchone()[0]
    check(remaining == 1, "no rows were lost to the attempted bulk wipe")

    # With the owner flag set, deletion works. This is the manual long-press path.
    connection.execute(
        "UPDATE archive_control SET v = 1 WHERE k = 'manual_delete_allowed'")
    connection.execute("DELETE FROM notifications WHERE _id = ?", (row_id,))
    connection.execute(
        "UPDATE archive_control SET v = 0 WHERE k = 'manual_delete_allowed'")
    connection.commit()
    gone = connection.execute(
        "SELECT COUNT(*) FROM notifications WHERE _id = ?", (row_id,)).fetchone()[0]
    check(gone == 0, "an authorised delete succeeds")

    # And the guard re-arms once the flag is cleared, so authorisation cannot leak.
    second = insert_row(connection, "fp-bbb")
    try:
        connection.execute("DELETE FROM notifications WHERE _id = ?", (second,))
        connection.commit()
        check(False, "the delete guard re-arms after an authorised delete")
    except (sqlite3.IntegrityError, sqlite3.OperationalError):
        check(True, "the delete guard re-arms after an authorised delete")

    # Marker rows share the table, so gaps are ordered alongside messages.
    marker = insert_row(connection, "fp-marker-1", row_kind=2, body="listener disconnected")
    check(marker > 0, "a listener-gap marker row can be inserted")

    verify_query_semantics(connection)

    connection.close()

    if failures:
        print(f"Archive schema check FAILED ({len(failures)} of {checks}):")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"Passed {checks} archive schema and append-only checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
