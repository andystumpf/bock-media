#!/usr/bin/env python3
"""Remove uitest-* playlists and automations from the live server (API token required)."""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.request

ARTIFACT_PREFIX = "uitest-"


def _token_and_base():
    token = os.environ.get("BOCK_TEST_API_TOKEN", "").strip()
    base = os.environ.get("BOCK_TEST_SERVER_URL", "http://your-server.local:3001").rstrip("/")
    props = os.path.join(os.path.dirname(os.path.dirname(__file__)), "android", "local.properties")
    if not token and os.path.isfile(props):
        for line in open(props, encoding="utf-8"):
            if line.startswith("bockmedia.mobileApiToken="):
                token = line.split("=", 1)[1].strip()
                break
            if line.startswith("bockmedia.externalServerUrl=") and not base:
                base = line.split("=", 1)[1].strip().rstrip("/")
    if not token:
        print("uitest_janitor: set BOCK_TEST_API_TOKEN or android/local.properties token", file=sys.stderr)
        sys.exit(1)
    return token, base


def _request(method, url, token, data=None):
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
        body = json.dumps(data).encode("utf-8")
    else:
        body = None
    with urllib.request.urlopen(req, data=body, timeout=15) as resp:
        return json.load(resp)


def main():
    ap = argparse.ArgumentParser(description="Delete uitest-* server artifacts")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    token, base = _token_and_base()

    deleted = {"playlists": 0, "automations": 0}
    pl_url = f"{base}/api/playlists?search={urllib.parse.quote(ARTIFACT_PREFIX)}&limit=200"
    playlists = _request("GET", pl_url, token).get("items") or []
    for pl in playlists:
        name = (pl.get("name") or "")
        if not name.startswith(ARTIFACT_PREFIX) and ARTIFACT_PREFIX not in pl.get("id", ""):
            continue
        pid = pl["id"]
        if args.dry_run:
            print(f"would delete playlist {pid} ({name})")
        else:
            _request("DELETE", f"{base}/api/playlists/{urllib.parse.quote(pid)}", token)
        deleted["playlists"] += 1

    auto_url = f"{base}/api/automations"
    automations = _request("GET", auto_url, token).get("items") or []
    for auto in automations:
        name = auto.get("name") or auto.get("label") or ""
        if not name.startswith(ARTIFACT_PREFIX):
            continue
        aid = auto["id"]
        if args.dry_run:
            print(f"would delete automation {aid} ({name})")
        else:
            _request("DELETE", f"{base}/api/automations/{urllib.parse.quote(aid)}", token)
        deleted["automations"] += 1

    print(json.dumps(deleted))


if __name__ == "__main__":
    main()
