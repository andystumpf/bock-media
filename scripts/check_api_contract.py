#!/usr/bin/env python3
"""Smoke-check that key mobile API paths exist in the server and OpenAPI contract."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_API = (
    ROOT / "android/app/src/main/kotlin/com/bockmedia/console/data/api/BockMediaApi.kt"
).read_text(encoding="utf-8")
CONTRACT = (ROOT / "shared/api-contract/api-contract.yaml").read_text(encoding="utf-8")

ROUTES: set[str] = set()
for py in ROOT.glob("*.py"):
    ROUTES.update(re.findall(r"@app\.route\('([^']+)'", py.read_text(encoding="utf-8", errors="ignore")))
for extra in ("bock_routes.py", "bock_listen_agent.py", "bock_library_new.py"):
    path = ROOT / extra
    if path.exists():
        ROUTES.update(re.findall(r"@app\.route\('([^']+)'", path.read_text(encoding="utf-8", errors="ignore")))


def normalize(path: str) -> str:
    p = "/" + path.split("?")[0].lstrip("/")
    p = re.sub(r"\{[^}]+\}", "<var>", p)
    p = re.sub(r"<\w+:[^>]+>", "<var>", p)
    p = re.sub(r"<\w+>", "<var>", p)
    return p


def route_exists(api_path: str) -> bool:
    target = normalize(api_path)
    for route in ROUTES:
        if normalize(route) == target:
            return True
    base = target.replace("<var>", "").rstrip("/")
    if not base.startswith("/api/"):
        return False
    return any(normalize(r).startswith(base) for r in ROUTES)


def contract_documents(api_path: str) -> bool:
    static = "/" + api_path.split("?")[0].lstrip("/")
    if static in CONTRACT:
        return True
    prefix = static.split("{")[0].rstrip("/")
    return f"{prefix}:" in CONTRACT or f"{prefix}\n" in CONTRACT


def extract_android_paths() -> set[str]:
    out: set[str] = set()
    for m in re.finditer(r'@(GET|POST|PUT|PATCH|DELETE)\("([^"]+)"\)', ANDROID_API):
        path = m.group(2)
        if path.startswith("api/"):
            out.add(path)
    return out


# Paths added for recent parity work — must exist on server and in OpenAPI.
REQUIRED_PATHS = [
    "api/library/health",
    "api/library/artists/merge",
    "api/home",
    "api/search/pins",
    "api/notifications/followed",
    "api/followed-artists",
    "api/library/new",
]

errors: list[str] = []

for path in sorted(extract_android_paths()):
    if not route_exists(path):
        errors.append(f"Missing server route for Android path: {path}")

for path in REQUIRED_PATHS:
    if not route_exists(path):
        errors.append(f"Missing server route for required path: {path}")
    if not contract_documents(path):
        errors.append(f"Missing OpenAPI entry for required path: {path}")

if errors:
    print("API contract check failed:", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)
    sys.exit(1)

print("API contract smoke check OK")
