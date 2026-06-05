"""Shared tag read/write + normalization helpers for ourMedia maintenance scripts.

Centralizes the embedded-tag logic that backfill_genres.py / backfill_duration.py
each grew independently, so audit_metadata.py and backfill_metadata.py read (and
write) tags the same way. Uses mutagen; the "easy" interface gives one uniform
key set (title/artist/album/albumartist/tracknumber/discnumber/date/genre/composer)
across MP3, MP4, FLAC, Ogg, etc., with a raw-frame fallback for anything easy misses.
"""
import re
import sqlite3
import time

SUPPORTED = {'.mp3', '.m4a', '.flac', '.ogg', '.opus', '.wav', '.aac', '.wma', '.aiff', '.alac'}

# Core text fields we manage, mapped to mutagen "easy" keys.
_EASY_KEYS = {
    'title': 'title',
    'artist': 'artist',
    'album': 'album',
    'album_artist': 'albumartist',
    'composer': 'composer',
    'genre': 'genre',
}

# Raw-frame fallbacks (ID3 / MP4 / Vorbis) for when the easy interface is empty.
_RAW_KEYS = {
    'title': ('TIT2', '\xa9nam', 'TITLE'),
    'artist': ('TPE1', '\xa9ART', 'ARTIST'),
    'album': ('TALB', '\xa9alb', 'ALBUM'),
    'album_artist': ('TPE2', 'aART', 'ALBUMARTIST', '----:com.apple.iTunes:ALBUMARTIST'),
    'composer': ('TCOM', '\xa9wrt', 'COMPOSER'),
    'genre': ('TCON', '\xa9gen', 'GENRE', '----:com.apple.iTunes:GENRE'),
}

_YEAR_RE = re.compile(r'(19|20)\d{2}')
_GENRE_SPLIT = re.compile(r'[/;\u0000|]+')
# Leading track-number noise in titles: "01 - ", "01. ", "01_", "1) ".
_TRACK_PREFIX_RE = re.compile(r'^\s*\d{1,3}\s*[.\-_)]+\s+')
_MULTISPACE_RE = re.compile(r'\s+')


# ── value coercion ───────────────────────────────────────────────────────────

def _tag_to_str(val):
    if val is None:
        return ''
    if hasattr(val, 'text'):  # ID3 frame
        parts = val.text
        return str(parts[0]).strip() if parts else ''
    if isinstance(val, (list, tuple)):
        return str(val[0]).strip() if val else ''
    return str(val).strip()


def _first(val):
    """First value of an easy-tag list (which is always a list), else ''."""
    if isinstance(val, (list, tuple)):
        return str(val[0]).strip() if val else ''
    return _tag_to_str(val)


def normalize_text(s):
    """Trim and collapse internal whitespace. Returns None for empty."""
    if s is None:
        return None
    s = _MULTISPACE_RE.sub(' ', str(s)).strip()
    return s or None


def strip_track_prefix(title):
    """Drop a leading track-number prefix like '01 - ' from a title."""
    if not title:
        return title
    stripped = _TRACK_PREFIX_RE.sub('', title, count=1).strip()
    return stripped or title


def normalize_genre(raw):
    s = _tag_to_str(raw) if not isinstance(raw, str) else raw.strip()
    if not s:
        return ''
    # ID3v1 numeric genre "(17)" — keep as-is if mutagen didn't resolve it.
    if s.startswith('(') and s.endswith(')') and s[1:-1].isdigit():
        return s
    parts = _GENRE_SPLIT.split(s)
    return parts[0].strip() if parts else s


def parse_year(raw):
    s = _tag_to_str(raw) if not isinstance(raw, str) else raw.strip()
    if not s:
        return None
    m = _YEAR_RE.search(s)
    if not m:
        return None
    y = int(m.group(0))
    return y if 1900 <= y <= 2100 else None


def parse_int_field(raw):
    """Parse a track/disc value: '3', '3/12', (3, 12) -> 3."""
    if raw is None:
        return None
    if isinstance(raw, (list, tuple)) and raw:
        raw = raw[0]
    if isinstance(raw, (list, tuple)) and raw:  # MP4 trkn = [(3, 12)]
        raw = raw[0]
    if isinstance(raw, int):
        return raw if raw > 0 else None
    s = _tag_to_str(raw)
    if not s:
        return None
    m = re.match(r'\s*(\d+)', s)
    if not m:
        return None
    n = int(m.group(1))
    return n if n > 0 else None


# ── reading ──────────────────────────────────────────────────────────────────

def read_tags(path):
    """Read embedded tags. Returns a dict with keys title, artist, album,
    album_artist, composer, genre (str or None) and track_number, disc_number,
    year (int or None). Missing/unreadable -> all None."""
    from mutagen import File as MutaFile

    out = {
        'title': None, 'artist': None, 'album': None, 'album_artist': None,
        'composer': None, 'genre': None,
        'track_number': None, 'disc_number': None, 'year': None,
    }

    try:
        easy = MutaFile(path, easy=True)
    except Exception:
        easy = None
    if easy is not None and easy.tags:
        g = easy.tags
        for field, ekey in _EASY_KEYS.items():
            v = _first(g.get(ekey))
            if v:
                out[field] = v
        out['track_number'] = parse_int_field(g.get('tracknumber'))
        out['disc_number'] = parse_int_field(g.get('discnumber'))
        out['year'] = parse_year(_first(g.get('date')))

    # Raw-frame fallback for any text field the easy interface left empty.
    if any(out[f] is None for f in _RAW_KEYS):
        try:
            mf = MutaFile(path)
        except Exception:
            mf = None
        tags = getattr(mf, 'tags', None) if mf is not None else None
        if tags:
            for field, keys in _RAW_KEYS.items():
                if out[field]:
                    continue
                for key in keys:
                    if key in tags:
                        v = _tag_to_str(tags.get(key))
                        if v:
                            out[field] = v
                            break
            if out['track_number'] is None:
                for key in ('TRCK', 'trkn', 'TRACKNUMBER'):
                    if key in tags:
                        out['track_number'] = parse_int_field(tags.get(key))
                        if out['track_number']:
                            break
            if out['disc_number'] is None:
                for key in ('TPOS', 'disk', 'DISCNUMBER'):
                    if key in tags:
                        out['disc_number'] = parse_int_field(tags.get(key))
                        if out['disc_number']:
                            break
            if out['year'] is None:
                for key in ('TDRC', 'TYER', 'TDAT', '\xa9day', 'DATE'):
                    if key in tags:
                        out['year'] = parse_year(tags.get(key))
                        if out['year']:
                            break

    out['genre'] = normalize_genre(out['genre']) or None
    for f in ('title', 'artist', 'album', 'album_artist', 'composer'):
        out[f] = normalize_text(out[f])
    return out


# ── writing ──────────────────────────────────────────────────────────────────

def write_tags(path, fields, *, dry_run=False):
    """Write the given fields back into the file's embedded tags via the easy
    interface. `fields` keys are the same as read_tags. Skips None/empty values
    and unchanged values. Returns True if the file was (or would be) changed."""
    from mutagen import File as MutaFile

    try:
        audio = MutaFile(path, easy=True)
    except Exception:
        return False
    if audio is None:
        return False
    if audio.tags is None:
        try:
            audio.add_tags()
        except Exception:
            return False

    changed = False

    def _set(ekey, value):
        nonlocal changed
        try:
            cur = _first(audio.tags.get(ekey))
            if (cur or '') != str(value):
                audio.tags[ekey] = [str(value)]
                changed = True
        except (KeyError, ValueError, TypeError):
            # Format doesn't support this easy key (e.g. composer on some types).
            pass

    for field, ekey in _EASY_KEYS.items():
        val = fields.get(field)
        if val:
            _set(ekey, val)
    if fields.get('track_number'):
        _set('tracknumber', fields['track_number'])
    if fields.get('disc_number'):
        _set('discnumber', fields['disc_number'])
    if fields.get('year'):
        _set('date', fields['year'])

    if changed and not dry_run:
        try:
            audio.save()
        except Exception:
            return False
    return changed


# ── SQLite helpers (shared lock handling) ─────────────────────────────────────

def connect_db(db_path, *, readonly=False):
    if readonly:
        conn = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True, timeout=120.0)
    else:
        conn = sqlite3.connect(db_path, timeout=120.0)
        conn.execute('PRAGMA busy_timeout = 120000')
    conn.row_factory = sqlite3.Row
    return conn


def db_retry(fn, *, retries=12, label='db'):
    delay = 0.25
    for attempt in range(retries):
        try:
            return fn()
        except sqlite3.OperationalError as e:
            if 'locked' not in str(e).lower() or attempt >= retries - 1:
                raise
            print(f'  … {label} locked, retry {attempt + 1}/{retries} in {delay:.1f}s', flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 30.0)
