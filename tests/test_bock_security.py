"""P0 security audit helpers."""
import bock_security


def test_production_tunnel_detects_real_hostname():
    assert bock_security.production_tunnel_configured({'publicUrl': 'https://bock.example.com'})
    assert not bock_security.production_tunnel_configured({'publicUrl': 'https://your-tunnel.example.com'})
    assert not bock_security.production_tunnel_configured({'publicUrl': ''})


def test_audit_open_lan_no_credentials():
    result = bock_security.audit(
        {},
        credentials_configured=False,
        allow_open_lan_api=True,
        allow_open_lan_media=True,
        allow_external_access=False,
        allow_tunnel_api=False,
        alexa_password_in_config=False,
    )
    assert result['insecureConfig'] is True
    ids = {w['id'] for w in result['warnings']}
    assert 'open_lan_no_credentials' in ids


def test_audit_tunnel_without_credentials():
    result = bock_security.audit(
        {'publicUrl': 'https://bock.example.com'},
        credentials_configured=False,
        allow_open_lan_api=True,
        allow_open_lan_media=True,
        allow_external_access=False,
        allow_tunnel_api=False,
        alexa_password_in_config=False,
    )
    ids = {w['id'] for w in result['warnings']}
    assert 'tunnel_without_credentials' in ids


def test_audit_alexa_password_in_config():
    result = bock_security.audit(
        {'alexaRemote': {'password': 'secret'}},
        credentials_configured=True,
        allow_open_lan_api=False,
        allow_open_lan_media=False,
        allow_external_access=False,
        allow_tunnel_api=False,
        alexa_password_in_config=True,
    )
    ids = {w['id'] for w in result['warnings']}
    assert 'alexa_password_in_config' in ids
