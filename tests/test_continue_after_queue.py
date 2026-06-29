"""Echo continue-after-queue respects per-member client prefs."""
import json

import bock_client_prefs
import server


def test_continue_mode_member_overrides_household(isolated_paths, monkeypatch):
    prefs_path = isolated_paths / 'mma' / 'client_prefs.json'
    bock_client_prefs.put_prefs(
        str(prefs_path),
        member_id='p-andy',
        member_prefs={'continueAfterQueue': 'off'},
    )
    bock_client_prefs.put_prefs(
        str(prefs_path),
        member_id='p-emma',
        member_prefs={'continueAfterQueue': 'similar'},
    )

    prefs_xml = isolated_paths / 'mma' / 'Preferences.xml'
    text = prefs_xml.read_text(encoding='utf-8')
    if '<ContinueAfterQueue>' in text:
        import re
        text = re.sub(
            r'<ContinueAfterQueue>.*?</ContinueAfterQueue>',
            '<ContinueAfterQueue>similar</ContinueAfterQueue>',
            text,
        )
    else:
        text = text.replace('</Preferences>', '  <ContinueAfterQueue>similar</ContinueAfterQueue>\n</Preferences>')
    prefs_xml.write_text(text, encoding='utf-8')

    server.write_np_state_for_device('echo-kitchen', {
        'track': 'T', 'artist': 'A', 'filepath': '/x.mp3', 'token': 'q1:0',
        'memberId': 'p-andy', 'playing': True, 'timestamp': 1.0,
    })
    assert server._continue_after_queue_mode('echo-kitchen') == 'off'

    server.write_np_state_for_device('echo-kitchen', {
        'track': 'T', 'artist': 'A', 'filepath': '/x.mp3', 'token': 'q1:0',
        'memberId': 'p-emma', 'playing': True, 'timestamp': 1.0,
    })
    assert server._continue_after_queue_mode('echo-kitchen') == 'similar'

    assert server._continue_after_queue_mode(None) == 'similar'


def test_device_id_for_queue_context_from_np(isolated_paths):
    server._save_queues({
        'qabc': {'tracks': ['/a.mp3'], 'ts': 1.0, 'shuffle': False, 'loop': False},
    })
    server.write_np_state_for_device('echo-office', {
        'track': 'T', 'token': 'qabc:0', 'playing': True, 'timestamp': 1.0,
    })
    data = server.decode_token('qabc:0') or {}
    assert server._device_id_for_queue_context(data) == 'echo-office'
