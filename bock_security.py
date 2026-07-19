"""Security posture audit — startup warnings and health flags."""
import os
import re

_PLACEHOLDER_PUBLIC = frozenset({
    '',
    'https://your-tunnel.example.com',
    'http://your-tunnel.example.com',
    'your-tunnel.example.com',
})


def _norm_public_url(raw):
    return (raw or '').strip().rstrip('/')


def production_tunnel_configured(cfg):
    """True when a real HTTPS tunnel hostname is configured (not the placeholder)."""
    url = _norm_public_url((cfg or {}).get('publicUrl'))
    if not url or url in _PLACEHOLDER_PUBLIC:
        return False
    if url.startswith('https://'):
        host = url[8:].split('/')[0].split(':')[0]
        if re.match(r'^\d+\.\d+\.\d+\.\d+$', host):
            return False
        return True
    if url.startswith('http://'):
        return False
    if re.match(r'^\d+\.\d+\.\d+\.\d+', url):
        return False
    return True


def audit(
    cfg,
    *,
    credentials_configured,
    allow_open_lan_api,
    allow_open_lan_media,
    allow_external_access,
    allow_tunnel_api,
    alexa_password_in_config=False,
):
    """Return structured warnings for /api/health and startup logs."""
    cfg = cfg or {}
    warnings = []
    insecure = False

    if not credentials_configured:
        if allow_open_lan_api and allow_open_lan_media:
            insecure = True
            warnings.append({
                'id': 'open_lan_no_credentials',
                'severity': 'critical',
                'message': (
                    'LAN API and media are open with no WebPassword or mobileApi.token — '
                    'any device on your Wi-Fi can read the library and trigger playback.'
                ),
                'action': 'Set WebPassword or mobileApi.token in Settings, or disable allowOpenLanApi/allowOpenLanMedia.',
            })
        elif allow_open_lan_api or allow_open_lan_media:
            insecure = True
            which = 'API' if allow_open_lan_api else 'media'
            warnings.append({
                'id': 'partial_open_lan_no_credentials',
                'severity': 'high',
                'message': f'LAN {which} access is open without credentials.',
                'action': 'Set credentials or disable the allowOpenLan* flag in config.json.',
            })

        if production_tunnel_configured(cfg):
            insecure = True
            warnings.append({
                'id': 'tunnel_without_credentials',
                'severity': 'critical',
                'message': (
                    'publicUrl is configured but no WebPassword or mobileApi.token is set — '
                    'LAN open-access flags are ignored until credentials are configured.'
                ),
                'action': 'Generate mobileApi.token and/or set WebPassword before exposing the tunnel.',
            })

        if allow_external_access:
            warnings.append({
                'id': 'external_access_no_credentials',
                'severity': 'high',
                'message': 'allowExternalAccess is enabled without API credentials — direct port-forward hits will 401.',
                'action': 'Set WebPassword or mobileApi.token for external API access.',
            })

    if alexa_password_in_config and not os.environ.get('ALEXA_REMOTE_PASSWORD'):
        warnings.append({
            'id': 'alexa_password_in_config',
            'severity': 'medium',
            'message': 'alexaRemote.password is stored in config.json on disk.',
            'action': 'Move to ALEXA_REMOTE_PASSWORD env var and remove the password from config.json.',
        })

    ma = cfg.get('mobileApi') or {}
    if (ma.get('token') or '').strip() and not credentials_configured:
        pass  # unreachable
    elif not (ma.get('token') or '').strip() and not allow_tunnel_api and production_tunnel_configured(cfg):
        warnings.append({
            'id': 'tunnel_api_token_recommended',
            'severity': 'medium',
            'message': 'Mobile apps need mobileApi.token (and allowTunnelApi) to reach the server over the tunnel.',
            'action': 'Set mobileApi.token and allowTunnelApi in config.json.',
        })

    return {
        'insecureConfig': insecure,
        'warnings': warnings,
    }


def log_startup_warnings(audit_result):
    for w in audit_result.get('warnings') or []:
        sev = (w.get('severity') or 'info').upper()
        print(f'SECURITY [{sev}] {w.get("message")} — {w.get("action")}', flush=True)
