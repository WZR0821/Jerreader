"""Offline SQLite check of shipping Room v5 -> learning v9 schema and data retention.

Uses the exported Room schemas and actual migration SQL. Device Room migration
tests remain a separate check; this does not start Android or an emulator.
"""
import json
import re
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "androidApp/schemas/com.jerreader.android.data.JerreaderDatabase"


def create_schema(connection, version):
    entities = json.loads((SCHEMAS / f"{version}.json").read_text())["database"]["entities"]
    for entity in entities:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    return entities


def main():
    old = sqlite3.connect(":memory:")
    expected = sqlite3.connect(":memory:")
    previous = create_schema(old, 5)
    current = create_schema(expected, 9)
    retained = {}
    for entity in previous:
        table = entity["tableName"]
        columns = list(old.execute(f"PRAGMA table_info(`{table}`)"))
        values = [None if not column[3] else
                  "original" if column[2] == "TEXT" else
                  12.5 if column[2] == "REAL" else 7 for column in columns]
        old.execute(f"INSERT INTO `{table}` VALUES ({','.join('?' for _ in values)})", values)
        retained[table] = ([column[1] for column in columns], values)
    source = (ROOT / "androidApp/src/main/kotlin/com/jerreader/android/data/JerreaderDatabase.kt").read_text()
    for version in range(5, 9):
        marker = f"private val MIGRATION_{version}_{version + 1}"
        block = source.split(marker, 1)[1].split("private val MIGRATION_", 1)[0]
        statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', block)
        assert statements, f"Missing migration SQL for {version}"
        for statement in statements:
            old.execute(statement)
    for entity in current:
        table = entity["tableName"]
        actual_columns = list(old.execute(f"PRAGMA table_info(`{table}`)"))
        expected_columns = list(expected.execute(f"PRAGMA table_info(`{table}`)"))
        # ALTER TABLE appends new fields; column order is not schema semantics.
        actual = {column[1]: column[2:] for column in actual_columns}
        wanted = {column[1]: column[2:] for column in expected_columns}
        assert actual == wanted, (table, actual, wanted)
        if table in retained:
            columns, values = retained[table]
            selection = ','.join(f'`{name}`' for name in columns)
            assert list(old.execute(f"SELECT {selection} FROM `{table}`").fetchone()) == values
    assert old.execute("SELECT count(*) FROM grammar_notes").fetchone()[0] == 0
    assert old.execute("SELECT reviewJson FROM word_lookup_records").fetchone()[0] is None
    assert old.execute("SELECT practiceJson FROM translation_favorites").fetchone()[0] is None
    assert old.execute("SELECT activityJson FROM books").fetchone()[0] is None
    print("PASS: actual v5→v9 migration SQL matches exported Room columns and preserves all original rows")


if __name__ == "__main__":
    main()
