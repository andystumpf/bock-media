#!/bin/bash
ROOT="$(dirname "$0")"
if [ -x "$ROOT/.venv/bin/python" ]; then
  exec "$ROOT/.venv/bin/python" "$ROOT/server.py"
fi
exec /usr/bin/python3 "$ROOT/server.py"
