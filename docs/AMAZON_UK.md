# Amazon UK (`amazon.co.uk`) support

Bock Media uses the same codebase for US and UK Amazon accounts. Nothing in the
Android app or web UI needs a regional build — only server `config.json` and
(optionally) Alexa Developer Console skill distribution.

## Coexistence with US setups

| Component | US | UK | Same server? |
|-----------|----|----|--------------|
| Library, Android app, web playback | ✓ | ✓ | Yes |
| `alexaRemote.url` | `amazon.com` | `amazon.co.uk` | One domain per install |
| `mspOauth.redirectUriPrefixes` | `.com` hosts | add `.co.uk` hosts | Yes — **add** UK prefixes; keep US |
| Alexa remote session (cookies) | `.com` login | `.co.uk` login | Separate per domain + email |

Your US config is unchanged if you keep `url: "amazon.com"`. A UK household runs
their own server (or their own `config.json`) with `amazon.co.uk`.

## 1. Alexa remote — “Play on device” / Rooms

In `config.json`:

```json
"alexaRemote": {
  "url": "amazon.co.uk",
  "email": "your@email",
  "password": "…",
  "otpSecret": ""
}
```

Then sign in (browser proxy is most reliable for passkey/2FA accounts):

```bash
python3 scripts/alexa_login.py --proxy --host <LAN-IP> --port 3005
```

Open the printed URL, sign in at **amazon.co.uk** (choose password if passkey
is offered — passkeys are origin-bound). Session is saved under
`<DATA_DIR>/.storage/alexa_media.<email>.pickle`.

Verify: Settings → Alexa remote shows connected; Rooms lists your Echos.

## 2. MSP account linking (music skill OAuth)

Add UK redirect prefixes alongside the US ones (do not remove US entries if you
support both):

```json
"redirectUriPrefixes": [
  "https://alexa.amazon.com/",
  "https://layla.amazon.com/",
  "https://pitangui.amazon.com/",
  "https://alexa.amazon.co.uk/",
  "https://layla.amazon.co.uk/",
  "https://pitangui.amazon.co.uk/"
]
```

Restart the server after editing `config.json`.

## 3. Skill availability in the UK

The bundled MSP manifest defaults to **US** distribution. UK Echo users may
need the custom skill and/or MSP enabled for **United Kingdom** in the
[Alexa Developer Console](https://developer.amazon.com/alexa/console/ask)
(distribution / availability). That is independent of `alexaRemote.url`.

Voice commands still use the same invocation: *“Alexa, ask bock media to …”*

## Troubleshooting

- **Login works but no devices** — wrong domain; use the same TLD you use in the
  browser for Alexa (e.g. `alexa.amazon.co.uk` → `url: "amazon.co.uk"`).
- **Same email on .com and .co.uk** — pick the domain where your Echos are
  registered; do not mix two `alexaRemote` configs with the same email on one
  server.
- **Cookies expire** — re-run `scripts/alexa_login.py --proxy`.
