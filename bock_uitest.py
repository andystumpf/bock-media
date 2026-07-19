"""Debug-only hooks for mobile UI resilience tests (X-UITest-Fail header)."""


def uitest_fail_response(endpoint, req=None):
    """Return a Flask response tuple when failure injection is active, else None."""
    from flask import has_app_context, jsonify, request as flask_request

    active = req if req is not None else flask_request
    flag = (active.headers.get('X-UITest-Fail') or '').strip().lower()
    if flag in (endpoint, 'all'):
        payload = {'error': 'uitest_injected_failure', 'endpoint': endpoint}
        if has_app_context():
            return jsonify(payload), 500
        return payload, 500
    return None
