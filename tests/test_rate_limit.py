"""Auth failure rate limiting."""
import bock_rate_limit


def test_blocks_after_threshold():
    key = 'test-client-1'
    for _ in range(29):
        bock_rate_limit.record_auth_failure(key)
    assert not bock_rate_limit.is_blocked(key)
    bock_rate_limit.record_auth_failure(key)
    assert bock_rate_limit.is_blocked(key)
