#!/usr/bin/env python3
"""One-time Alexa login for the "Play on device" feature.

Establishes an Amazon Alexa session (cookies) saved under <DATA_DIR>/.storage/
and reused by alexa_remote.py. Two modes:

  Password login (handles captcha / 2FA / OTP prompts):
      python3 scripts/alexa_login.py

  Proxy login (RECOMMENDED for passkey/2FA accounts — you sign in yourself in a
  browser through a local page; the proxy captures the OAuth token):
      python3 scripts/alexa_login.py --proxy
      # then open the printed URL in a browser on the same network and sign in

  Cookie import (usually insufficient — Amazon needs an OAuth token, not just
  web cookies — kept only as a last resort):
      python3 scripts/alexa_login.py --cookies /path/to/cookies.txt

Re-run whenever calls start failing with "not_authenticated" (cookies expire).
"""
import argparse
import asyncio
import os
import socket
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import alexa_remote  # noqa: E402


def _lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'


async def proxy_login(host_ip, port):
    from alexapy import AlexaProxy
    login = alexa_remote.make_login(debug=bool(os.environ.get('ALEXA_DEBUG')))
    base_url = f'http://{host_ip}:{port}'
    proxy = AlexaProxy(login, base_url)
    await proxy.start_proxy(host='0.0.0.0')
    print('\n' + '=' * 64)
    print('Open this URL in a browser on the same network and sign in:')
    print(f'    {base_url}')
    print('(Email/password/OTP are pre-filled from config; complete any passkey')
    print(' or 2FA prompt yourself. Choose "password" if passkey is offered.)')
    print('Waiting for login to complete… (Ctrl-C to abort)')
    print('=' * 64)
    try:
        for _ in range(600):  # ~10 min
            if getattr(login, 'access_token', None):
                break
            await asyncio.sleep(1)
    finally:
        await proxy.stop_proxy()
    if not getattr(login, 'access_token', None):
        await login.close()
        sys.exit('Timed out waiting for login.')
    await login.login()  # oauth path now has the captured token -> get_tokens
    ok = await login.test_loggedin()
    if ok:
        await login.save_cookiefile()
        print(f'\nLogin successful as {login.email}. Session saved.')
    await login.close()
    if not ok:
        sys.exit('Token captured but session test failed; try again.')


async def import_cookies(path):
    if not os.path.isfile(path):
        sys.exit(f'cookies file not found: {path}')
    with open(path) as f:
        cookies_txt = f.read()
    login = alexa_remote.make_login(debug=bool(os.environ.get('ALEXA_DEBUG')))
    jar = await login.load_cookie(cookies_txt=cookies_txt)
    await login.login(cookies=jar)
    if await login.test_loggedin():
        await login.save_cookiefile()
        print('\nLogin successful via imported cookies. Session saved.')
        await login.close()
        return
    await login.close()
    sys.exit('Imported cookies did not authenticate. Make sure you exported '
             'amazon.com cookies while logged into the Alexa site, and that '
             'config.json -> alexaRemote.email matches that account.')


def _prompt(msg):
    try:
        return input(msg).strip()
    except EOFError:
        return ''


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cookies', help='Path to a Netscape cookies.txt to import (last resort)')
    parser.add_argument('--proxy', action='store_true', help='Browser-based proxy login (recommended)')
    parser.add_argument('--host', help='LAN IP to advertise for --proxy (default: auto-detect)')
    parser.add_argument('--port', type=int, default=3005, help='Proxy port (default 3005)')
    args = parser.parse_args()

    if not alexa_remote.cfg().get('email'):
        sys.exit('config.json -> alexaRemote.email is required first.')

    if args.proxy:
        await proxy_login(args.host or _lan_ip(), args.port)
        return

    if args.cookies:
        await import_cookies(args.cookies)
        return

    if not alexa_remote.cfg().get('password'):
        sys.exit('No password set. For passkey accounts use: '
                 'python3 scripts/alexa_login.py --cookies /path/to/cookies.txt')

    login = alexa_remote.make_login(debug=bool(os.environ.get('ALEXA_DEBUG')))

    # Reuse an existing session if it's still valid.
    cookies = await login.load_cookie()
    if cookies:
        await login.login(cookies=cookies)
        if await login.test_loggedin():
            print('Already logged in — session is valid.')
            await login.save_cookiefile()
            await login.close()
            return

    data = {}
    for _ in range(12):
        await login.login(data=data)
        status = login.status or {}
        data = {}

        if status.get('login_successful'):
            await login.save_cookiefile()
            print('\nLogin successful. Session saved.')
            await login.close()
            return

        if status.get('login_failed'):
            await login.close()
            sys.exit(f"Login failed: {status.get('login_failed')} "
                     f"{status.get('message', '')}")

        if status.get('captcha_required'):
            print(f"\nCaptcha image: {status.get('captcha_image_url')}")
            data['captcha'] = _prompt('Enter the captcha text: ')
        elif status.get('securitycode_required'):
            # Auto-filled from otpSecret if present; otherwise prompt.
            code = _prompt('Enter your 2FA / OTP code (blank if otpSecret set): ')
            if code:
                data['securitycode'] = code
        elif status.get('claimspicker_required'):
            print('\nChoose a verification method:')
            print(status.get('claimspicker_message', ''))
            data['claimsoption'] = _prompt('Option number: ')
        elif status.get('authselect_required'):
            print('\nChoose an OTP delivery method:')
            print(status.get('authselect_message', ''))
            data['authselectoption'] = _prompt('Option number: ')
        elif status.get('verificationcode_required'):
            data['verificationcode'] = _prompt('Enter the verification code sent to you: ')
        elif status.get('action_required') or status.get('message'):
            print('\n' + str(status.get('message', '')))
            _prompt('Complete the required action in a browser, then press Enter… ')
        else:
            await login.close()
            sys.exit(f'Unhandled login state: {status}')

    await login.close()
    sys.exit('Gave up after too many login steps.')


if __name__ == '__main__':
    asyncio.run(main())
