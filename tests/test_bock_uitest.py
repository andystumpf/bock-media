"""Tests for debug-only UI test failure injection."""
from bock_uitest import uitest_fail_response


class _Headers:
    def __init__(self, data):
        self._data = data

    def get(self, key, default=None):
        return self._data.get(key, default)


class _Req:
    def __init__(self, headers):
        self.headers = _Headers(headers)


def test_uitest_fail_response_none():
    assert uitest_fail_response('home', _Req({})) is None


def test_uitest_fail_response_home():
    body, code = uitest_fail_response('home', _Req({'X-UITest-Fail': 'home'}))
    assert code == 500
    assert body['endpoint'] == 'home'
