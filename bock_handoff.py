"""Playback handoff between phone, Echo, and other clients."""


def handoff_payload(from_device, to_device, offset_ms, context, read_np, file_to_stream_url,
                    alexa_play_fn=None, alexa_serial_for=None, encode_token_fn=None):
    """
    Returns dict with ok, streamUrl, filepath, offsetMs, method, warning.
    """
    offset_ms = int(offset_ms or 0)
    ctx = context or {}
    filepath = (ctx.get('path') or ctx.get('filepath') or '').strip()

    to_local = to_device in ('local-phone', 'local', 'phone') or str(to_device).startswith('client-')
    from_local = from_device in ('local-phone', 'local', 'phone') or str(from_device).startswith('client-')

    if not filepath and from_device and not from_local:
        st = read_np(from_device) if read_np else None
        if st:
            filepath = st.get('filepath') or ''
            if not offset_ms:
                offset_ms = int(st.get('offset_ms') or 0)

    if not filepath:
        return {'ok': False, 'error': 'no_track'}

    stream_url = file_to_stream_url(filepath) if file_to_stream_url else None
    norm_url = f'{stream_url}?normalize=1' if stream_url else None

    # Phone target: return stream info for local player
    if to_local:
        return {
            'ok': True,
            'method': 'local',
            'filepath': filepath,
            'offsetMs': offset_ms,
            'streamUrl': stream_url,
            'streamUrlWithNorm': norm_url,
            'context': ctx,
        }

    # Echo target
    serial = alexa_serial_for(to_device) if alexa_serial_for else None
    if serial and alexa_play_fn and stream_url and encode_token_fn:
        try:
            token = encode_token_fn({'tracks': [filepath], 'idx': 0, 'loop': False})
            alexa_play_fn(stream_url, token, offset_ms=offset_ms, device_serial=serial)
            return {
                'ok': True,
                'method': 'alexa_skill',
                'filepath': filepath,
                'offsetMs': offset_ms,
                'deviceId': to_device,
            }
        except Exception as e:
            return {'ok': False, 'error': str(e), 'warning': 'skill_play_failed'}

    return {
        'ok': True,
        'method': 'metadata_only',
        'filepath': filepath,
        'offsetMs': offset_ms,
        'streamUrl': stream_url,
        'warning': 'Could not resume mid-track on target device',
    }
