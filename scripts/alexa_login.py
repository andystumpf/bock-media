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
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import alexa_remote  # noqa: E402


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
        # Shared with Settings → Start browser login (alexa_remote.start_proxy_login).
        import time
        host = args.host or alexa_remote.lan_ip()
        port = args.port
        try:
            st = alexa_remote.start_proxy_login(host=host, port=port)
        except alexa_remote.AlexaRemoteError as e:
            sys.exit(str(e))
        url = st.get('url') or f'http://{host}:{port}'
        print('\n' + '=' * 64)
        print('Open this URL in a browser on the same network and sign in:')
        print(f'    {url}')
        print('(Choose "password" if passkey is offered.)')
        print('Waiting for login to complete… (Ctrl-C to abort)')
        print('=' * 64)
        try:
            deadline = time.time() + alexa_remote.login_timeout_sec()
            while time.time() < deadline:
                st = alexa_remote.proxy_login_state()
                if st.get('status') == 'success':
                    print(f'\nLogin successful. Session saved.')
                    return
                if st.get('status') == 'error':
                    sys.exit(st.get('error') or 'Login failed.')
                if st.get('status') == 'stopped':
                    sys.exit('Login stopped.')
                time.sleep(1)
        except KeyboardInterrupt:
            alexa_remote.stop_proxy_login()
            sys.exit('Aborted.')
        mins = alexa_remote.login_timeout_sec() // 60
        sys.exit(f'Timed out waiting for login ({mins} min).')
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
