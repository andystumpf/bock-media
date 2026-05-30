#!/usr/bin/env python3
"""
Update config.json publicUrl and the Alexa dev skill HTTPS endpoint to match a new base URL.
Used after Cloudflare quick tunnel (or any URL) changes.

Environment:
  ASK_CLI   path to ask (default: ask on PATH)
  SKILL_ID  Alexa skill id (default: Our Media dev skill in this repo)
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONFIG = REPO / "config.json"
DEFAULT_SKILL = "amzn1.ask.skill.c13622d4-8780-4bea-93a5-0ded84307466"


def _public_url_from_arg(s: str) -> str:
    s = s.strip()
    if not s:
        raise SystemExit("usage: sync_alexa_public_url.py <https://host>")
    if not re.match(r"^https?://", s, re.I):
        s = "https://" + s
    return s.rstrip("/")


def _update_config(base: str) -> None:
    data = {}
    if CONFIG.is_file():
        with open(CONFIG) as f:
            data = json.load(f)
    data["publicUrl"] = base
    with open(CONFIG, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print(f"Wrote {CONFIG} publicUrl={base}")


def _ask_cli() -> str:
    return os.environ.get("ASK_CLI", "ask")


def _subprocess_env() -> dict:
    """Homebrew node first; ask-cli requires Node 18+ (systemd often has /usr/bin/node v12)."""
    e = os.environ.copy()
    prefix = str(Path("/home/linuxbrew/.linuxbrew/bin"))
    p = e.get("PATH", "")
    if not p.startswith(prefix):
        e["PATH"] = f"{prefix}{os.pathsep}{p}" if p else prefix
    return e


def _smapi_get_manifest(skill_id: str) -> dict:
    r = subprocess.run(
        [
            _ask_cli(),
            "smapi",
            "get-skill-manifest",
            "--skill-id",
            skill_id,
            "-g",
            "development",
        ],
        capture_output=True,
        text=True,
        env=_subprocess_env(),
    )
    if r.returncode != 0:
        print(r.stderr or r.stdout, file=sys.stderr)
        raise SystemExit(f"get-skill-manifest failed: {r.returncode}")
    return json.loads(r.stdout)


def _smapi_update_manifest(skill_id: str, body: dict) -> None:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as t:
        json.dump(body, t, indent=2)
        t.flush()
        path = t.name
    try:
        r = subprocess.run(
            [
                _ask_cli(),
                "smapi",
                "update-skill-manifest",
                "--skill-id",
                skill_id,
                "-g",
                "development",
                "--manifest",
                f"file:{path}",
            ],
            capture_output=True,
            text=True,
            env=_subprocess_env(),
        )
        if r.returncode != 0:
            print(r.stderr or r.stdout, file=sys.stderr)
            raise SystemExit(f"update-skill-manifest failed: {r.returncode}")
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    out = (r.stdout or "").strip()
    if out:
        print(out)
    print("Alexa skill manifest endpoint updated (development).")


def _skill_enablement(skill_id: str) -> None:
    if os.environ.get("SKIP_SKILL_ENABLEMENT", "").strip() in ("1", "true", "yes"):
        return
    r = subprocess.run(
        [
            _ask_cli(),
            "smapi",
            "set-skill-enablement",
            "--skill-id",
            skill_id,
        ],
        capture_output=True,
        text=True,
        env=_subprocess_env(),
    )
    if r.returncode != 0:
        print(
            r.stderr or r.stdout or "set-skill-enablement failed",
            file=sys.stderr,
        )
        return
    print("Skill testing enablement refreshed (development).")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: sync_alexa_public_url.py <https://host[.trycloudflare.com]>")
    base = _public_url_from_arg(sys.argv[1])
    skill_id = os.environ.get("SKILL_ID", DEFAULT_SKILL)
    alexa_uri = f"{base}/alexa"

    _update_config(base)

    data = _smapi_get_manifest(skill_id)
    m = data.get("manifest") or {}
    apis = m.get("apis") or {}
    custom = apis.get("custom") or {}
    ep = custom.get("endpoint") or {}
    old = ep.get("uri", "")
    ep["uri"] = alexa_uri
    ep["sslCertificateType"] = "Wildcard"
    custom["endpoint"] = ep
    apis["custom"] = custom
    m["apis"] = apis
    data["manifest"] = m
    if old and old != alexa_uri:
        print(f"Endpoint: {old!r} -> {alexa_uri!r}")
    else:
        print(f"Endpoint: {alexa_uri!r}")
    _smapi_update_manifest(skill_id, data)
    _skill_enablement(skill_id)


if __name__ == "__main__":
    main()
