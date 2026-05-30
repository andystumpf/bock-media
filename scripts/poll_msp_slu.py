#!/usr/bin/env python3
"""Poll Alexa Music Skill catalog ingestion until SLU_MODELING finishes.

Checks every 10 minutes by default. Logs to msp_slu_poll.log in the repo root.
On SUCCEEDED, optionally smoke-tests /music with a known playlist entityId.

Usage:
  python3 scripts/poll_msp_slu.py              # loop forever (Ctrl+C to stop)
  python3 scripts/poll_msp_slu.py --once       # single check, exit
  nohup python3 -u scripts/poll_msp_slu.py &   # background

Reads catalogId from config.json; uses the newest catalog upload unless --upload-id is set.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_PATH = os.path.join(HERE, 'msp_slu_poll.log')
ASK_ENV = {**os.environ, 'PATH': '/home/linuxbrew/.linuxbrew/bin:' + os.environ.get('PATH', '')}

# Yacht Rock Mix — sanity check once the voice model is live
_SMOKE_PLAYLIST_ID = 'be7ef454-87de-40c9-b688-e330d93dd0d2'


def log(msg):
    line = f"{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')} {msg}"
    print(line, flush=True)
    with open(LOG_PATH, 'a', encoding='utf-8') as f:
        f.write(line + '\n')


def ask(*args):
    out = subprocess.run(['ask', 'smapi', *args], capture_output=True, text=True, env=ASK_ENV)
    if out.returncode != 0:
        raise RuntimeError(f'ask smapi {args[0]} failed: {out.stderr or out.stdout}')
    return json.loads(out.stdout)


def load_config():
    with open(os.path.join(HERE, 'config.json'), encoding='utf-8') as f:
        return json.load(f)


def latest_upload_id(catalog_id):
    data = ask('list-uploads-for-catalog', '-c', catalog_id)
    uploads = data.get('uploads') or []
    if not uploads:
        return None
    uploads.sort(key=lambda u: u.get('lastUpdatedDate') or u.get('createdDate') or '', reverse=True)
    return uploads[0]['id']


def ingestion_status(catalog_id, upload_id):
    data = ask('get-content-upload-by-id', '-c', catalog_id, '--upload-id', upload_id)
    steps = {s['name']: s['status'] for s in data.get('ingestionSteps') or []}
    return data.get('status'), steps


def smoke_test_music(access_token):
    cfg = load_config()
    url = (cfg.get('msp') or {}).get('endpoint') or cfg.get('publicUrl', '').rstrip('/') + '/music'
    body = {
        'header': {'namespace': 'Alexa.Media.Search', 'name': 'GetPlayableContent', 'payloadVersion': '1.0'},
        'payload': {
            'requestContext': {'user': {'id': 'poll', 'accessToken': access_token}},
            'selectionCriteria': {'attributes': [{'type': 'PLAYLIST', 'entityId': _SMOKE_PLAYLIST_ID}]},
        },
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                               headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def poll_once(catalog_id, upload_id):
    overall, steps = ingestion_status(catalog_id, upload_id)
    slu = steps.get('SLU_MODELING', '?')
    parts = ' '.join(f'{k}={v}' for k, v in steps.items())
    log(f'upload={upload_id[-12:]} overall={overall} SLU_MODELING={slu} | {parts}')
    return slu, overall


def main():
    ap = argparse.ArgumentParser(description='Poll MSP catalog SLU_MODELING status')
    ap.add_argument('--interval', type=int, default=600, help='Seconds between checks (default 600 = 10 min)')
    ap.add_argument('--catalog-id', help='Override catalog id (default: config.json msp.catalogId)')
    ap.add_argument('--upload-id', help='Specific upload id (default: newest upload for catalog)')
    ap.add_argument('--once', action='store_true', help='Run one check and exit')
    args = ap.parse_args()

    cfg = load_config()
    catalog_id = args.catalog_id or (cfg.get('msp') or {}).get('catalogId')
    if not catalog_id:
        sys.exit('No catalog id — set config.json msp.catalogId or pass --catalog-id')

    upload_id = args.upload_id
    if not upload_id:
        upload_id = latest_upload_id(catalog_id)
        if not upload_id:
            sys.exit(f'No uploads found for catalog {catalog_id}')

    log(f'polling catalog={catalog_id} upload={upload_id} every {args.interval}s')

    while True:
        try:
            slu, overall = poll_once(catalog_id, upload_id)
        except Exception as ex:
            log(f'ERROR: {ex}')
            if args.once:
                sys.exit(1)
            time.sleep(args.interval)
            continue

        if slu == 'SUCCEEDED':
            log('SLU_MODELING SUCCEEDED — catalog voice model is live. Safe to test on Echo.')
            token = (cfg.get('mspOauth') or {}).get('accessToken')
            if token:
                try:
                    resp = smoke_test_music(token)
                    name = (((resp.get('payload') or {}).get('content') or {})
                            .get('metadata') or {}).get('name', {}).get('display')
                    log(f'smoke /music GetPlayableContent OK -> {name!r}')
                except Exception as ex:
                    log(f'smoke /music failed (Echo test still recommended): {ex}')
            sys.exit(0)

        if slu == 'FAILED' or overall == 'FAILED':
            log('SLU_MODELING or upload FAILED — consider re-upload: python3 scripts/upload_msp_catalog.py')
            sys.exit(1)

        if args.once:
            sys.exit(2 if slu == 'PENDING' else 0)

        time.sleep(args.interval)


if __name__ == '__main__':
    main()
