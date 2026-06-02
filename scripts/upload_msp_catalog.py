#!/usr/bin/env python3
"""Upload a Music Skill catalog file to Alexa and wait for ingestion.

Flow (per Amazon's catalog upload API):
  1. create-content-upload  -> uploadId + presigned PUT url(s)
  2. PUT the file to each presigned url, capturing the ETag header
  3. complete-catalog-upload with the (eTag, partNumber) pairs
  4. poll get-content-upload-by-id until ingestion SUCCEEDS/FAILS

Usage:
  python3 scripts/upload_msp_catalog.py \
      --catalog-id amzn1.ask-catalog.cat.XXXX \
      --file skill/catalog_playlists.json
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASK_ENV = {**os.environ, 'PATH': '/home/linuxbrew/.linuxbrew/bin:' + os.environ.get('PATH', '')}


def ask(*args):
    out = subprocess.run(['ask', 'smapi', *args], capture_output=True, text=True, env=ASK_ENV)
    if out.returncode != 0:
        raise SystemExit(f'ask smapi {args[0]} failed:\n{out.stderr or out.stdout}')
    txt = (out.stdout or '').strip()
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt


def put_part(url, data):
    req = urllib.request.Request(url, data=data, method='PUT',
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        return r.headers.get('ETag')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--catalog-id', required=True)
    ap.add_argument('--file', default=os.path.join(HERE, 'skill', 'catalog_playlists.json'))
    args = ap.parse_args()

    with open(args.file, 'rb') as f:
        data = f.read()
    print(f'Catalog file: {args.file} ({len(data)} bytes)')

    print('1) create-content-upload …')
    up = ask('create-content-upload', '-c', args.catalog_id, '--number-of-upload-parts', '1')
    upload_id = up['id']
    parts = up['presignedUploadParts']
    print(f'   uploadId={upload_id}, parts={len(parts)}')

    print('2) uploading part(s) …')
    etags = []
    for p in parts:
        etag = put_part(p['url'], data)
        etags.append({'eTag': etag, 'partNumber': p['partNumber']})
        print(f'   part {p["partNumber"]} -> ETag {etag}')

    print('3) complete-catalog-upload …')
    ask('complete-catalog-upload', '-c', args.catalog_id, '--upload-id', upload_id,
        '--part-e-tags', json.dumps(etags))

    print('4) polling ingestion status …')
    for i in range(60):
        info = ask('get-content-upload-by-id', '-c', args.catalog_id, '--upload-id', upload_id)
        status = info.get('status')
        steps = {s.get('name'): s.get('status') for s in info.get('ingestionSteps', [])}
        print(f'   [{i}] status={status} steps={steps}')
        if status in ('SUCCEEDED', 'FAILED'):
            if status == 'FAILED':
                print(json.dumps(info, indent=2))
                sys.exit(1)
            print('Catalog ingestion SUCCEEDED.')
            return
        time.sleep(10)
    print('Timed out waiting for ingestion (it may still complete server-side).')


if __name__ == '__main__':
    main()
