#!/usr/bin/env python3
"""Generate Discover Weekly recommendations cache."""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

import bock_discover  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-dir', default=os.environ.get('OURMEDIA_DATA_DIR', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data')))
    parser.add_argument('--db', default=os.environ.get('OURMEDIA_DB_PATH', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'songs_cache.db')))
    args = parser.parse_args()

    import sqlite3

    def get_db():
        c = sqlite3.connect(args.db)
        c.row_factory = sqlite3.Row
        return c

    def db_query(sql, params=()):
        conn = get_db()
        try:
            cur = conn.execute(sql, params)
            return [dict(r) for r in cur.fetchall()]
        finally:
            conn.close()

    household_path = os.path.join(args.data_dir, 'household.json')
    member_ids = ['household']
    if os.path.isfile(household_path):
        with open(household_path) as f:
            h = json.load(f)
        member_ids = [m.get('id') for m in (h.get('members') or []) if m.get('id')] or member_ids

    cache_path = os.path.join(args.data_dir, 'recommendations_cache.json')
    history_path = os.path.join(args.data_dir, 'streaming_history.jsonl')
    cache = bock_discover.run_weekly_job(cache_path, db_query, history_path, member_ids)
    print(json.dumps({'generatedAt': cache.get('generatedAt'), 'members': list(cache.get('members', {}).keys())}))


if __name__ == '__main__':
    main()
