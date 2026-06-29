/**
 * Spotify-style home feed — parity with Android/iOS HomeFeedComposer.
 * Shared golden fixture: shared/fixtures/home_feed/
 */
(function (root) {
  'use strict';

  const LIMITS = {
    JUMP_BACK_IN: 24,
    FAVORITES: 16,
    TOP_MIXES: 16,
    MOOD_SECTION_CARDS: 500,
    BROWSE_GENRES: 16,
    EXPLORE_THEMES: 18,
    LIBRARY_GENRE_EXTRAS: 6,
    DAILY_MIXES: 12,
    RECENT_PLAYLISTS: 24,
    RADIO: 16,
    DISCOVER: 24,
    MORE_PLAYLISTS: 60,
  };

  const RE = {
    dailyMix: /daily mix|daylist/i,
    discover: /discover weekly|new release|fresh find|new to you/i,
    genreMix: /\bmix\b|\bmixes\b|\bremix\b|\bremixes\b|essentials|\bdecade\b|\bera\b|\bhits\b|\bparty\b|\bfocus\b|\bfavorites\b/i,
    mixLikeName: /\bmix\b|\bmixes\b|\bremix\b|\bremixes\b/i,
    explicitRadio: /\bradio\b|\bstation\b/i,
    mixLike: /\bmix\b|daily|discover weekly|essentials|station/i,
  };

  function plTracks(p) { return p.trackCount ?? p.tracks ?? 0; }

  function hashCode(str) {
    let h = 0;
    for (let i = 0; i < (str || '').length; i++) h = (Math.imul(31, h) + str.charCodeAt(i)) | 0;
    return h;
  }

  function seededRandom(seed) {
    let s = (seed >>> 0) || 0x4d595449;
    return () => {
      s = (s + 0x6d2b79f5) | 0;
      let t = Math.imul(s ^ (s >>> 15), 1 | s);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function shuffled(arr, seed) {
    const rng = seededRandom(seed === 0 ? 0x4d595449 : seed);
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  function parseSortDate(raw) {
    if (!raw) return 0;
    const t = Date.parse(raw);
    return Number.isFinite(t) ? t : 0;
  }

  function dayOfYear(d = new Date()) {
    const start = new Date(d.getFullYear(), 0, 0);
    return Math.floor((d - start) / 86400000);
  }

  const Rules = {
    isDailyMixName: (name) => RE.dailyMix.test(name),
    isDiscoverName: (name) => RE.discover.test(name),
    isGenreMixPlaylistName(name, genre) {
      if (this.isDailyMixName(name) || this.isDiscoverName(name)) return false;
      if (!RE.genreMix.test(name)) return false;
      if (genre == null) return true;
      return this.nameContainsGenre(name, genre);
    },
    hasMixLikeName(name) {
      return RE.mixLikeName.test(name);
    },
    nameContainsGenre(name, genre) {
      const g = (genre || '').trim();
      if (!g) return false;
      if (name.toLowerCase().includes(g.toLowerCase())) return true;
      const tokens = g.split(/\s+/).filter((t) => t.length > 1);
      return tokens.length > 1 && tokens.every((t) => name.toLowerCase().includes(t.toLowerCase()));
    },
    genreMixNameScore(name, genre) {
      let score = 0;
      if (name.toLowerCase() === `${genre.toLowerCase()} mix`) score += 100;
      if (name.toLowerCase().startsWith(genre.toLowerCase())) score += 40;
      const wordRe = new RegExp(`\\b${genre.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i');
      if (wordRe.test(name)) score += 30;
      else if (name.toLowerCase().includes(genre.toLowerCase())) score += 10;
      return score;
    },
    bestGenreMixPlaylist(all, genre) {
      const g = (genre || '').trim();
      if (!g) return null;
      const candidates = all.filter((p) => plTracks(p) > 0 && this.isGenreMixPlaylistName(p.name, g));
      if (!candidates.length) return null;
      return candidates.sort((a, b) => {
        const ds = this.genreMixNameScore(b.name, g) - this.genreMixNameScore(a.name, g);
        if (ds !== 0) return ds;
        return plTracks(b) - plTracks(a);
      })[0];
    },
    isExplicitRadioPlaylistName(name) {
      if (RE.mixLike.test(name) && !RE.explicitRadio.test(name)) return false;
      return RE.explicitRadio.test(name);
    },
    isAutomationPlaylistName(name) {
      return (name || '').trim().toLowerCase().startsWith('automations');
    },
    isSpecialHomePlaylistName(name) {
      return this.isDailyMixName(name) || this.isDiscoverName(name) ||
        this.isGenreMixPlaylistName(name) || this.isExplicitRadioPlaylistName(name) ||
        this.isAutomationPlaylistName(name);
    },
    /** "Classical Era Mix" → "Classical Era" */
    mixGenreLabel(title) {
      const t = (title || '').trim();
      if (t.endsWith(' Mix') && t.length > 4) return t.slice(0, -4);
      return null;
    },
    /** "Jazz Radio" → "Jazz" */
    genreRadioLabel(displayTitle) {
      const t = (displayTitle || '').trim();
      if (t.endsWith(' Radio') && t.length > 6) return t.slice(0, -6);
      return null;
    },
    historyMatchesGenre(row, genre) {
      const hay = [row.sourceLabel, row.playlist, row.album, row.artist].filter(Boolean);
      const g = genre.toLowerCase();
      return hay.some((h) => h.toLowerCase().includes(g));
    },
    artPathForArtistDistinct(history, artist, used) {
      const u = used || new Set();
      const row = history.find((r) => r.filepath && !u.has(r.filepath) &&
        (r.artist || '').toLowerCase() === (artist || '').toLowerCase());
      return row?.filepath || null;
    },
    artPathForGenreDistinct(history, genre, used) {
      const u = used || new Set();
      const row = history.find((r) => r.filepath && !u.has(r.filepath) && this.historyMatchesGenre(r, genre));
      return row?.filepath || null;
    },
    artPathForPlaylistDistinct(history, playlistName, used) {
      const u = used || new Set();
      const target = (playlistName || '').toLowerCase();
      const row = history.find((r) => r.filepath && !u.has(r.filepath) &&
        (r.playlist || '').toLowerCase() === target);
      return row?.filepath || null;
    },
    nextDistinctArtPath(history, used) {
      const u = used || new Set();
      const row = history.find((r) => r.filepath && !u.has(r.filepath));
      return row?.filepath || null;
    },
    topArtistForGenre(history, genre) {
      const counts = {};
      for (const row of history) {
        if (!row.artist || !this.historyMatchesGenre(row, genre)) continue;
        const k = row.artist.toLowerCase();
        counts[k] = (counts[k] || 0) + 1;
      }
      const best = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
      if (!best) return null;
      const row = history.find((r) => (r.artist || '').toLowerCase() === best[0]);
      return row?.artist || null;
    },
    matchesKeywords(text, keywords) {
      const h = (text || '').toLowerCase();
      return keywords.some((kw) => h.includes(kw.toLowerCase()));
    },
    playlistSearchText(p) {
      return [p.name, p.sourceName, p.source].filter(Boolean).join(' ');
    },
    playlistMatchesThemeName(name, theme) {
      return this.matchesKeywords(name, theme.playlistKeywords);
    },
    playlistMatchesTheme(p, theme) {
      return this.playlistThemeScore(this.playlistSearchText(p), theme) > 0;
    },
    genreMatchesTheme(name, theme) {
      return this.matchesKeywords(name, theme.genreKeywords);
    },
    historyMatchesTheme(row, theme) {
      const hay = [row.sourceLabel, row.playlist, row.album, row.artist].filter(Boolean);
      const keywords = theme.playlistKeywords.concat(theme.genreKeywords);
      return hay.some((h) => this.matchesKeywords(h, keywords));
    },
    topArtistForTheme(history, theme) {
      const counts = {};
      for (const row of history) {
        if (!row.artist || !this.historyMatchesTheme(row, theme)) continue;
        const k = row.artist.toLowerCase();
        counts[k] = (counts[k] || 0) + 1;
      }
      const best = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
      if (!best) return null;
      const row = history.find((r) => (r.artist || '').toLowerCase() === best[0]);
      return row?.artist || null;
    },
    matchingLibraryGenre(theme, libraryGenres) {
      const g = libraryGenres.find((x) => this.genreMatchesTheme(x.name, theme));
      return g?.name || null;
    },
    matchingLibraryGenreForLabel(label, libraryGenres) {
      const g = (label || '').trim();
      if (!g) return null;
      const exact = libraryGenres.find((x) => x.name.toLowerCase() === g.toLowerCase());
      if (exact) return exact;
      return libraryGenres.find((x) =>
        this.nameContainsGenre(x.name, g) || this.nameContainsGenre(g, x.name)) || null;
    },
    playlistThemeScore(name, theme) {
      const hay = (name || '').toLowerCase();
      let score = 0;
      for (const kw of theme.playlistKeywords) {
        if (hay.includes(kw.toLowerCase())) score += 10;
      }
      for (const kw of theme.genreKeywords) {
        if (hay.includes(kw.toLowerCase())) score += 4;
      }
      return score;
    },
    playlistMatchesMoodSection(p, theme) {
      return this.matchesKeywords(this.playlistSearchText(p), theme.playlistKeywords);
    },
    playlistMatchesMoodName(name, theme) {
      return this.matchesKeywords(name, theme.playlistKeywords);
    },
    playlistsForMoodSection(all, theme) {
      return all
        .filter((p) => plTracks(p) > 0 && this.playlistMatchesMoodSection(p, theme))
        .sort((a, b) => {
          const sa = theme.playlistKeywords.filter((kw) =>
            this.playlistSearchText(a).toLowerCase().includes(kw.toLowerCase())).length;
          const sb = theme.playlistKeywords.filter((kw) =>
            this.playlistSearchText(b).toLowerCase().includes(kw.toLowerCase())).length;
          if (sb !== sa) return sb - sa;
          return a.name.localeCompare(b.name);
        });
    },
    browsablePlaylists(all) {
      return all.filter((p) => plTracks(p) > 0 && !this.isSpecialHomePlaylistName(p.name));
    },
    shuffledBrowsablePlaylists(all, seed) {
      return shuffled(this.browsablePlaylists(all), seed);
    },
    // Every playlist eligible for the home catch-all row (excludes automations and
    // the server's auto-generated daily mixes, which have their own row).
    allHomePlaylists(all) {
      return all.filter((p) => plTracks(p) > 0 &&
        !this.isAutomationPlaylistName(p.name) && !this.isDailyMixName(p.name));
    },
    shuffledAllPlaylists(all, seed) {
      return shuffled(this.allHomePlaylists(all), seed);
    },
  };

  function theme(id, title, subtitle, playlistKeywords, genreKeywords) {
    return { id, title, subtitle, playlistKeywords, genreKeywords };
  }

  const HomeMoodSections = [
    { id: 'dinner', title: 'Dinner & entertaining', theme: theme('dinner', 'Dinner playlist', 'Cooking, hosting & table music',
      ['dinner', 'cooking', 'kitchen', 'entertaining', 'cocktail', 'wine', 'supper', 'table', 'host', 'feast'],
      ['dinner', 'jazz', 'lounge', 'easy listening']) },
    { id: 'french', title: 'French music', theme: theme('french', 'French favorites', 'Chanson, pop & café culture',
      ['french', 'français', 'francais', 'france', 'chanson', 'paris'], ['french', 'français', 'francais', 'chanson']) },
    { id: 'italian', title: 'Italian music', theme: theme('italian', 'Italian classics', 'Pop, opera & la dolce vita',
      ['italian', 'italiano', 'italia', 'italy', 'canzone', 'rome'], ['italian', 'italiano', 'italia']) },
    { id: 'yacht-rock', title: 'Yacht Rock', theme: theme('yacht-rock', 'Yacht Rock', 'Smooth sailing & soft rock',
      ['yacht'], ['yacht rock', 'soft rock']) },
    { id: 'work-from-home', title: 'Work from home', theme: theme('work-from-home', 'Focus flow', 'Deep work & concentration',
      ['work', 'focus', 'wfh', 'concentration', 'office', 'productivity', 'coding', 'study', 'deep work', 'instrumental'],
      ['ambient', 'classical', 'electronic', 'instrumental']) },
    { id: 'road-trip', title: 'Road trip', theme: theme('road-trip', 'Highway mix', 'Driving, windows down & open road',
      ['road trip', 'roadtrip', 'driving', 'highway', 'travel', 'car', 'journey', 'on the road', 'windows down'],
      ['rock', 'country', 'pop', 'classic rock']) },
    { id: 'sunday-morning', title: 'Sunday morning', theme: theme('sunday-morning', 'Easy Sunday', 'Brunch, coffee & slow starts',
      ['sunday', 'morning', 'brunch', 'coffee', 'easy', 'wake', 'weekend', 'sunrise', 'lazy'],
      ['folk', 'acoustic', 'jazz', 'easy listening', 'gospel']) },
    { id: 'party', title: 'Party & guests', theme: theme('party', 'House party', 'Dance floor & backyard BBQ',
      ['party', 'dance', 'bbq', 'guests', 'celebration', 'grill', 'house party', 'get together', 'hits'],
      ['dance', 'pop', 'funk', 'disco', 'hip-hop']) },
    { id: 'wind-down', title: 'Wind down', theme: theme('wind-down', 'Evening calm', 'Relax, unwind & bedtime',
      ['chill', 'relax', 'evening', 'unwind', 'bedtime', 'sleep', 'calm', 'soft', 'night', 'nature'],
      ['ambient', 'acoustic', 'classical', 'new age', 'folk']) },
  ];

  const ThemeCatalog = (() => {
    const pinned = [
      theme('german', 'German Music', 'Schlager, rock & pop', ['german', 'deutsch', 'deutschland', 'germany', 'schlager'], ['german', 'deutsch', 'schlager']),
      theme('spanish-latin', 'Spanish & Latin', 'Pop, salsa & reggaeton', ['spanish', 'español', 'espanol', 'latin', 'latino', 'latina', 'salsa', 'reggaeton', 'mexican', 'mexico'], ['spanish', 'español', 'espanol', 'latin', 'latino', 'salsa', 'reggaeton']),
      theme('jazz', 'Jazz', 'Swing, standards & fusion', ['jazz', 'swing', 'bebop', 'blues jazz'], ['jazz', 'swing', 'bebop']),
      theme('classical', 'Classical', 'Orchestra, opera & piano', ['classical', 'orchestra', 'symphony', 'opera', 'baroque', 'chamber'], ['classical', 'orchestra', 'symphony', 'opera', 'baroque']),
      theme('gospel', 'Gospel & Hymns', 'Worship, hymns & spiritual', ['gospel', 'hymn', 'hymns', 'worship', 'spiritual', 'christian', 'lutheran'], ['gospel', 'hymn', 'worship', 'spiritual', 'christian']),
    ];
    const rotating = [
      theme('portuguese', 'Portuguese & Brasil', 'MPB, samba & bossa', ['portuguese', 'português', 'portugues', 'brasil', 'brazil', 'bossa', 'samba', 'mpb'], ['portuguese', 'português', 'portugues', 'brasil', 'brazil', 'bossa', 'samba']),
      theme('country', 'Country', 'Nashville, bluegrass & roots', ['country', 'bluegrass', 'americana', 'nashville', 'honky'], ['country', 'bluegrass', 'americana']),
      theme('blues-soul', 'Blues & Soul', 'R&B, funk & soul', ['blues', 'soul', 'r&b', 'rnb', 'funk', 'motown'], ['blues', 'soul', 'r&b', 'rnb', 'funk', 'motown']),
      theme('reggae', 'Reggae & Caribbean', 'Reggae, ska & dancehall', ['reggae', 'ska', 'dancehall', 'caribbean', 'dub'], ['reggae', 'ska', 'dancehall', 'caribbean']),
      theme('celtic-folk', 'Celtic & Folk', 'Irish, Scottish & acoustic', ['celtic', 'irish', 'scottish', 'folk', 'trad', 'traditional'], ['celtic', 'irish', 'folk', 'traditional']),
      theme('80s', '80s Hits', 'Synth-pop, rock & new wave', ['80s', 'eighties', '1980', "'80s"], ['80s', 'eighties', '1980']),
      theme('90s', '90s Hits', 'Grunge, pop & hip-hop', ['90s', 'nineties', '1990', "'90s"], ['90s', 'nineties', '1990']),
      theme('acoustic', 'Acoustic & Unplugged', 'Singer-songwriter & soft rock', ['acoustic', 'unplugged', 'singer-songwriter', 'singer songwriter'], ['acoustic', 'unplugged', 'folk']),
      theme('electronic', 'Electronic & Dance', 'EDM, house & techno', ['electronic', 'edm', 'dance', 'techno', 'house', 'trance', 'disco'], ['electronic', 'edm', 'dance', 'techno', 'house', 'trance']),
      theme('hip-hop', 'Hip-Hop & Rap', 'Rap, trap & beats', ['hip-hop', 'hip hop', 'hiphop', 'rap', 'trap'], ['hip-hop', 'hip hop', 'hiphop', 'rap', 'trap']),
      theme('rock-metal', 'Rock & Metal', 'Hard rock, punk & metal', ['rock', 'metal', 'hard rock', 'punk', 'alternative', 'grunge'], ['rock', 'metal', 'punk', 'alternative', 'grunge']),
      theme('soundtracks', 'Soundtracks', 'Film, TV & game scores', ['soundtrack', 'score', 'film', 'movie', 'tv', 'video game', 'ost'], ['soundtrack', 'score', 'film', 'movie']),
      theme('kids', 'Kids & Family', "Children's songs & sing-alongs", ['kids', 'kid', 'children', 'family', 'disney', 'nursery', 'sing-along'], ['children', 'kids', 'family']),
      theme('world', 'World Music', 'Global sounds & traditions', ['world', 'international', 'global', 'african', 'asian', 'middle eastern'], ['world', 'international', 'african', 'asian']),
    ];
    return {
      themesForDay(seed) {
        return pinned.concat(shuffled(rotating, seed));
      },
    };
  })();

  function ptPlaylist(id, name) { return { kind: 'playlist', id, name }; }
  function ptSong(path, title) { return { kind: 'song', path, title: title || path }; }
  function ptAlbum(name, artist) { return { kind: 'album', name, artist: artist || '' }; }
  function ptArtist(name) { return { kind: 'artist', name }; }
  function ptRadio(title, seedKind, seed, artPath) {
    return { kind: 'radio', title, seedKind, seed, artPath: artPath || null };
  }

  const TILE_STALE_MS = 4 * 24 * 60 * 60 * 1000;
  let _testEngagement = null;

  const TileEngagement = {
    load() {
      if (_testEngagement !== null) return { ..._testEngagement };
      if (typeof localStorage !== 'undefined') {
        try {
          const raw = localStorage.getItem('bock_home_tile_engagement');
          return raw ? JSON.parse(raw) : {};
        } catch {
          return {};
        }
      }
      return {};
    },
    save(map) {
      if (_testEngagement !== null) {
        _testEngagement = { ...map };
        return;
      }
      if (typeof localStorage === 'undefined') return;
      try {
        if (!Object.keys(map).length) localStorage.removeItem('bock_home_tile_engagement');
        else localStorage.setItem('bock_home_tile_engagement', JSON.stringify(map));
      } catch { /* quota */ }
    },
    noteCardsPresent(cardIds) {
      const now = Date.now();
      const map = this.load();
      let changed = false;
      for (const id of cardIds || []) {
        if (!id || map[id]) continue;
        map[id] = { firstSeenMs: now };
        changed = true;
      }
      if (changed) this.save(map);
    },
    isStale(cardId, nowMs = Date.now()) {
      const entry = this.load()[cardId];
      if (!entry) return false;
      const anchor = entry.lastSelectedMs ?? entry.firstSeenMs;
      return nowMs - anchor >= TILE_STALE_MS;
    },
  };

  function isRotatableSectionKind(kind) {
    return kind !== 'RatedSongs' && kind !== 'Offline' && kind !== 'Favorites';
  }

  function rotationSubtitle(kind, pl) {
    const tracks = plTracks(pl);
    switch (kind) {
      case 'JumpBackIn': return `${tracks} tracks · Suggested for you`;
      case 'TopMixes':
      case 'ExploreThemes':
      case 'Mood':
      case 'DailyMixes': return 'Suggested mix';
      case 'Radio': return 'From your library';
      case 'Discover': return `${tracks} tracks · Discover`;
      case 'RecentPlaylists': return `${tracks} tracks · Suggested for you`;
      default: return `${tracks} tracks`;
    }
  }

  function applyTileRotation(feed, input, nowMs = Date.now()) {
    const allPlaylists = (input.allPlaylists || []).map((p) => ({ ...p, tracks: plTracks(p) }));
    const cardIds = (feed.sections || []).flatMap((s) => (s.cards || []).map((c) => c.id));
    TileEngagement.noteCardsPresent(cardIds);

    const usedPlaylistIds = new Set();
    const usedCardIds = new Set();
    const usedPlaylistNames = new Set();
    for (const card of (feed.sections || []).flatMap((s) => s.cards || [])) {
      usedCardIds.add(card.id);
      if (card.playlistId) usedPlaylistIds.add(card.playlistId);
      usedPlaylistNames.add((card.title || '').toLowerCase());
    }

    let rotationIndex = 0;
    const sections = (feed.sections || []).map((section) => {
      if (!isRotatableSectionKind(section.kind)) return section;
      const cards = (section.cards || []).map((card) => {
        if (!TileEngagement.isStale(card.id, nowMs)) return card;
        const seed = (input.shuffleSeed || 0) + rotationIndex * 17 + hashCode(card.id);
        const pool = Rules.shuffledBrowsablePlaylists(allPlaylists, seed).filter((pl) =>
          plTracks(pl) > 0 &&
          !usedPlaylistIds.has(pl.id) &&
          !usedPlaylistNames.has(pl.name.toLowerCase()) &&
          !Rules.isSpecialHomePlaylistName(pl.name),
        );
        const playlist = pool[0];
        if (!playlist) return card;
        const replacementId = `pl-${playlist.id}`;
        if (usedCardIds.has(replacementId)) return card;
        rotationIndex += 1;
        usedCardIds.add(replacementId);
        usedPlaylistIds.add(playlist.id);
        usedPlaylistNames.add(playlist.name.toLowerCase());
        const replacement = {
          id: replacementId,
          title: playlist.name,
          subtitle: rotationSubtitle(section.kind, playlist),
          artPath: null,
          playlistId: playlist.id,
          playTarget: ptPlaylist(playlist.id, playlist.name),
          kind: section.kind,
        };
        TileEngagement.noteCardsPresent([replacement.id]);
        return replacement;
      });
      return { ...section, cards };
    });
    return { sections };
  }

  function HomeFeedRegistry() {
    const usedPlaylistIds = new Set();
    const usedPlaylistNameKeys = new Set();
    const usedCardIds = new Set();
    const usedArtPaths = new Set();
    return {
      usedArtPaths,
      claimPlaylist(id, name) {
        const nameKey = (name || '').toLowerCase();
        if (usedPlaylistIds.has(id) || usedPlaylistNameKeys.has(nameKey)) return false;
        usedPlaylistIds.add(id);
        usedPlaylistNameKeys.add(nameKey);
        return true;
      },
      registerCard(card) {
        usedCardIds.add(card.id);
        if (card.artPath) usedArtPaths.add(card.artPath);
        if (card.playlistId) {
          usedPlaylistIds.add(card.playlistId);
          usedPlaylistNameKeys.add((card.title || '').toLowerCase());
        }
      },
      registerMoodCard(card) { usedCardIds.add(card.id); },
      reserveMoodPlaylists(cards) {
        for (const card of cards) {
          if (card.playlistId) {
            usedPlaylistIds.add(card.playlistId);
            usedPlaylistNameKeys.add((card.title || '').toLowerCase());
          }
        }
      },
      hasCard(id) { return usedCardIds.has(id); },
      canUsePlaylist(p) {
        return !usedPlaylistIds.has(p.id) && !usedPlaylistNameKeys.has((p.name || '').toLowerCase());
      },
      claimArtPath(path) {
        if (!path || usedArtPaths.has(path)) return null;
        usedArtPaths.add(path);
        return path;
      },
    };
  }

  function section(id, title, kind, cards) {
    if (!cards || !cards.length) return null;
    return { id, title, kind, cards };
  }

  function buildRatedSongCards(ratedItems, registry) {
    const byStar = {};
    for (const row of ratedItems || []) {
      if (row.kind !== 'song') continue;
      const stars = Number(row.stars || 0);
      if (stars < 1 || stars > 5) continue;
      (byStar[stars] ||= []).push(row);
    }
    const cards = [];
    for (const stars of [5, 4, 3, 2, 1]) {
      const songs = byStar[stars] || [];
      if (!songs.length) continue;
      const playlistId = `rated-stars-${stars}`;
      const title = `${stars}★ songs`;
      const card = {
        id: `rated-${stars}`,
        title,
        subtitle: `${songs.length} tracks`,
        artPath: songs[0].id,
        playlistId,
        playTarget: { kind: 'playlist', id: playlistId, name: title },
        kind: 'RatedSongs',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        cards.push(card);
      }
    }
    return cards;
  }

  function dashboardJumpCards(dashboard, playlistByName, artByPlaylist) {
    const items = dashboard?.recent || [];
    return items.map((item) => {
      const playlistName = (item.playlist || '').trim();
      if (playlistName) {
        if (Rules.isAutomationPlaylistName(playlistName)) return null;
        const pl = playlistByName[playlistName.toLowerCase()];
        if (!pl) return null;
        return {
          id: `pl-${pl.id}`,
          title: pl.name,
          subtitle: item.artist || 'Recently played',
          artPath: artByPlaylist[playlistName.toLowerCase()] || item.path,
          playlistId: pl.id,
          playTarget: ptPlaylist(pl.id, pl.name),
          kind: 'JumpBackIn',
        };
      }
      const path = (item.path || '').trim();
      const title = (item.track || '').trim();
      if (!path || !title) return null;
      return {
        id: `dash-${hashCode(path)}`,
        title,
        subtitle: item.artist || 'Recently played',
        artPath: path,
        playTarget: ptSong(path, title),
        kind: 'JumpBackIn',
      };
    }).filter(Boolean);
  }

  function buildMoodSectionCards(mood, input, registry, playlistById, topArtists, resolveMixArt) {
    const themeObj = mood.theme;
    const kind = 'Mood';
    const limit = LIMITS.MOOD_SECTION_CARDS;
    const cards = [];

    function addCard(card) {
      if (!card || registry.hasCard(card.id)) return;
      if (cards.some((c) => c.id === card.id)) return;
      registry.registerMoodCard(card);
      cards.push(card);
    }

    function moodPlaylistCard(pl, subtitle) {
      if (plTracks(pl) <= 0) return null;
      const cardId = `mood-${mood.id}-pl-${pl.id}`;
      if (registry.hasCard(cardId)) return null;
      return {
        id: cardId,
        title: pl.name,
        subtitle,
        artPath: pl.artPath || null,
        playlistId: pl.id,
        playTarget: ptPlaylist(pl.id, pl.name),
        kind,
      };
    }

    for (const pl of Rules.playlistsForMoodSection(input.allPlaylists, themeObj)) {
      if (cards.length >= limit) break;
      addCard(moodPlaylistCard(pl, mood.theme.subtitle));
    }
    for (const sp of (input.smartPlaylists || []).filter((s) => s.enabled && Rules.playlistMatchesMoodName(sp.name, themeObj))) {
      if (cards.length >= limit) break;
      const pl = sp.playlistId ? playlistById[sp.playlistId] : null;
      if (pl) addCard(moodPlaylistCard(pl, mood.theme.subtitle));
    }
    if (!cards.length) {
      const seedArtist = Rules.topArtistForTheme(input.history, themeObj) ||
        topArtists[0]?.name || topArtists[0]?.label || mood.title;
      addCard({
        id: `mood-${mood.id}-fallback`,
        title: mood.theme.title,
        subtitle: mood.theme.subtitle,
        artPath: resolveMixArt(mood.title, seedArtist, hashCode(mood.id)),
        playTarget: ptRadio(`${mood.title} Radio`, 'genre', seedArtist),
        kind,
      });
    }
    const seen = new Set();
    return cards.filter((c) => {
      if (seen.has(c.id)) return false;
      seen.add(c.id);
      return true;
    }).slice(0, limit);
  }

  function buildRadioCards(history, topArtists, topGenres, allPlaylists, registry, limit) {
    const cards = [];
    function resolveRadioArt(preferred, artist, index) {
      if (registry.claimArtPath(preferred)) return preferred;
      if (artist) {
        const p = Rules.artPathForArtistDistinct(history, artist, registry.usedArtPaths);
        if (registry.claimArtPath(p)) return p;
      }
      const ta = topArtists[index]?.name || topArtists[index]?.label;
      if (ta) {
        const p = Rules.artPathForArtistDistinct(history, ta, registry.usedArtPaths);
        if (registry.claimArtPath(p)) return p;
      }
      return registry.claimArtPath(Rules.nextDistinctArtPath(history, registry.usedArtPaths));
    }

    for (let index = 0; index < Math.min(8, topArtists.length); index++) {
      if (cards.length >= limit) break;
      const artist = topArtists[index]?.name || topArtists[index]?.label;
      if (!artist) continue;
      const card = {
        id: `radio-artist-${artist}`,
        title: `${artist} Radio`,
        subtitle: 'Infinite · artist seed',
        artPath: resolveRadioArt(null, artist, index),
        playTarget: ptRadio(`${artist} Radio`, 'artist', artist),
        kind: 'Radio',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        cards.push(card);
      }
    }

    const songSeeds = history.filter((r) => r.filepath && r.track).filter((r, i, a) =>
      a.findIndex((x) => x.filepath === r.filepath) === i).slice(0, 4);
    for (let index = 0; index < songSeeds.length; index++) {
      if (cards.length >= limit) break;
      const row = songSeeds[index];
      const card = {
        id: `radio-song-${row.filepath}`,
        title: row.track,
        subtitle: [row.artist, 'Song radio'].filter(Boolean).join(' · '),
        artPath: resolveRadioArt(row.filepath, row.artist, index),
        playTarget: ptRadio(`${row.track} Radio`, 'song', row.track, row.filepath),
        kind: 'Radio',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        cards.push(card);
      }
    }

    for (let index = 0; index < Math.min(6, topGenres.length); index++) {
      if (cards.length >= limit) break;
      const genre = topGenres[index]?.name || topGenres[index]?.label;
      if (!genre) continue;
      const seedArtist = Rules.topArtistForGenre(history, genre) ||
        topArtists[index]?.name || topArtists[index]?.label;
      if (!seedArtist) continue;
      const card = {
        id: `radio-genre-${genre}`,
        title: `${genre} Radio`,
        subtitle: 'Infinite · genre seed',
        artPath: resolveRadioArt(Rules.artPathForGenreDistinct(history, genre, registry.usedArtPaths), seedArtist, index),
        playTarget: ptRadio(`${genre} Radio`, 'genre', seedArtist),
        kind: 'Radio',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        cards.push(card);
      }
    }

    for (const pl of allPlaylists.filter((p) => Rules.isExplicitRadioPlaylistName(p.name))
      .sort((a, b) => plTracks(b) - plTracks(a))) {
      if (cards.length >= limit) break;
      if (!registry.canUsePlaylist(pl)) continue;
      const card = {
        id: `pl-${pl.id}`,
        title: pl.name,
        subtitle: 'Radio station',
        artPath: null,
        playlistId: pl.id,
        playTarget: ptPlaylist(pl.id, pl.name),
        kind: 'Radio',
      };
      registry.registerCard(card);
      cards.push(card);
    }

    const seen = new Set();
    return cards.filter((c) => {
      if (seen.has(c.id)) return false;
      seen.add(c.id);
      return true;
    }).slice(0, limit);
  }

  function buildExploreThemeCards(input, registry, playlistById, topArtists, topGenres, resolveMixArt, playlistCard) {
    const cards = [];
    const coveredGenreKeys = new Set();
    const themes = ThemeCatalog.themesForDay(input.shuffleSeed);
    const addedIds = new Set();

    function registerThemeCard(card) {
      if (addedIds.has(card.id)) return;
      if (!registry.hasCard(card.id)) registry.registerCard(card);
      addedIds.add(card.id);
      cards.push(card);
    }

    for (let index = 0; index < themes.length; index++) {
      if (cards.length >= LIMITS.EXPLORE_THEMES) break;
      const th = themes[index];

      const playlistMatch = input.allPlaylists
        .filter((p) => Rules.playlistMatchesTheme(p, th) && plTracks(p) > 0)
        .sort((a, b) => {
          const ds = Rules.playlistThemeScore(Rules.playlistSearchText(b), th) -
            Rules.playlistThemeScore(Rules.playlistSearchText(a), th);
          if (ds !== 0) return ds;
          return plTracks(b) - plTracks(a);
        })[0];
      if (playlistMatch) {
        const c = playlistCard(playlistMatch, null, 'ExploreThemes', th.subtitle, true);
        if (c) registerThemeCard(c);
        continue;
      }

      const smart = (input.smartPlaylists || []).find((sp) => sp.enabled && Rules.playlistMatchesThemeName(sp.name, th));
      const smartPl = smart?.playlistId ? playlistById[smart.playlistId] : null;
      if (smartPl) {
        const c = playlistCard(smartPl, null, 'ExploreThemes', th.subtitle, true);
        if (c) registerThemeCard(c);
        continue;
      }

      const libraryGenre = Rules.matchingLibraryGenre(th, input.libraryGenres || []);
      const analyticsGenre = topGenres.find((row) => {
        const name = row.name || row.label;
        return name && Rules.genreMatchesTheme(name, th);
      });
      const genreLabel = libraryGenre || analyticsGenre?.name || analyticsGenre?.label;

      if (genreLabel) {
        coveredGenreKeys.add(genreLabel.toLowerCase());
        const libraryItem = (input.libraryGenres || []).find((g) => g.name.toLowerCase() === genreLabel.toLowerCase());
        const seedArtist = Rules.topArtistForGenre(input.history, genreLabel) ||
          Rules.topArtistForTheme(input.history, th) ||
          topArtists[index]?.name || topArtists[index]?.label ||
          topArtists[0]?.name || topArtists[0]?.label || genreLabel;
        registerThemeCard({
          id: `theme-${th.id}`,
          title: th.title,
          subtitle: th.subtitle,
          artPath: libraryItem?.artPath || resolveMixArt(genreLabel, seedArtist, index),
          playTarget: ptArtist(seedArtist),
          kind: 'ExploreThemes',
        });
        continue;
      }

      const seedArtist = Rules.topArtistForTheme(input.history, th) || topArtists[index]?.name || topArtists[index]?.label;
      if (seedArtist) {
        registerThemeCard({
          id: `theme-${th.id}`,
          title: th.title,
          subtitle: th.subtitle,
          artPath: resolveMixArt(th.title, seedArtist, index),
          playTarget: ptArtist(seedArtist),
          kind: 'ExploreThemes',
        });
        continue;
      }

      const fallbackSeed = topArtists[0]?.name || topArtists[0]?.label ||
        input.libraryGenres?.[0]?.name || 'Library';
      registerThemeCard({
        id: `theme-${th.id}`,
        title: th.title,
        subtitle: th.subtitle,
        artPath: resolveMixArt(th.title, fallbackSeed, index),
        playTarget: ptRadio(`${th.title} Radio`, 'genre', fallbackSeed),
        kind: 'ExploreThemes',
      });
    }

    return cards.filter((c, i, a) => a.findIndex((x) => x.id === c.id) === i);
  }

  function buildBrowseGenreCards(libraryGenres, registry) {
    const cards = [];
    const sorted = [...(libraryGenres || [])].sort(
      (a, b) => (b.tracks ?? b.track_count ?? 0) - (a.tracks ?? a.track_count ?? 0),
    );
    for (const genre of sorted) {
      if (cards.length >= LIMITS.BROWSE_GENRES) break;
      const tracks = genre.tracks ?? genre.track_count ?? 0;
      if (tracks < 8 || !genre.name) continue;
      const cardId = `browse-genre-${genre.name}`;
      if (registry.hasCard(cardId)) continue;
      const card = {
        id: cardId,
        title: genre.name,
        subtitle: `${tracks} tracks`,
        artPath: registry.claimArtPath(genre.artPath),
        playTarget: ptRadio(`${genre.name} Radio`, 'genre', genre.name),
        kind: 'BrowseGenres',
      };
      registry.registerCard(card);
      cards.push(card);
    }
    return cards;
  }

  function compose(input) {
    const registry = HomeFeedRegistry();
    const allPlaylists = (input.allPlaylists || []).map((p) => ({ ...p, tracks: plTracks(p) }));
    const playlistByName = Object.fromEntries(allPlaylists.map((p) => [p.name.toLowerCase(), p]));
    const playlistById = Object.fromEntries(allPlaylists.map((p) => [p.id, p]));
    const topGenres = (input.analytics?.topGenres || []).slice(0, 12);
    const topArtists = input.analytics?.topArtists || [];
    const shuffledGeneric = Rules.shuffledBrowsablePlaylists(allPlaylists, input.shuffleSeed);

    const recentPlaylistNames = [];
    const recentSeen = new Set();
    const artByPlaylist = {};
    for (const row of (input.history || [])) {
      const name = (row.playlist || '').trim();
      if (name && !Rules.isAutomationPlaylistName(name) && !recentSeen.has(name.toLowerCase())) {
        recentSeen.add(name.toLowerCase());
        recentPlaylistNames.push(name);
        if (row.filepath) artByPlaylist[name.toLowerCase()] = artByPlaylist[name.toLowerCase()] || row.filepath;
      }
    }

    function artPathForPlaylistSeed(history, playlistName) {
      const target = (playlistName || '').toLowerCase();
      const row = (history || []).find((r) => r.filepath && (r.playlist || '').toLowerCase() === target);
      return row?.filepath || null;
    }

    function resolvePlaylistArt(pl, genreHint) {
      return null;
    }

    function playlistCard(pl, artPath, kind, subtitle, claim = true, genreHint = null) {
      if (Rules.isAutomationPlaylistName(pl.name)) return null;
      if (claim && !registry.claimPlaylist(pl.id, pl.name)) return null;
      // Prefer the playlist's own cover (first track, from /api/playlists) so the tile
      // renders from the cached feed — no separate cover fetch, survives navigation.
      const resolvedArt = (artPath && registry.claimArtPath(artPath)) || pl.artPath || resolvePlaylistArt(pl, genreHint);
      const card = {
        id: `pl-${pl.id}`,
        title: pl.name,
        subtitle: subtitle ?? `${plTracks(pl)} tracks`,
        artPath: resolvedArt || null,
        playlistId: pl.id,
        playTarget: ptPlaylist(pl.id, pl.name),
        kind,
      };
      registry.registerCard(card);
      return card;
    }

    function fillPlaylists(pool, target, kind, subtitleFn) {
      const cards = [];
      for (const pl of pool) {
        if (cards.length >= target) break;
        if (plTracks(pl) <= 0 || !registry.canUsePlaylist(pl)) continue;
        const c = playlistCard(pl, null, kind, subtitleFn(pl));
        if (c) cards.push(c);
      }
      return cards;
    }

    function resolveMixArt(genre, artist, index) {
      const lib = Rules.matchingLibraryGenreForLabel(genre, input.libraryGenres || []);
      if (lib?.artPath && registry.claimArtPath(lib.artPath)) return lib.artPath;
      let p = Rules.artPathForGenreDistinct(input.history, genre, registry.usedArtPaths);
      if (registry.claimArtPath(p)) return p;
      if (artist) {
        p = Rules.artPathForArtistDistinct(input.history, artist, registry.usedArtPaths);
        if (registry.claimArtPath(p)) return p;
      }
      const ta = topArtists[index]?.name || topArtists[index]?.label;
      if (ta) {
        p = Rules.artPathForArtistDistinct(input.history, ta, registry.usedArtPaths);
        if (registry.claimArtPath(p)) return p;
      }
      return null;
    }

    const moodSections = HomeMoodSections.map((mood) => {
      const cards = buildMoodSectionCards(mood, { ...input, allPlaylists }, registry, playlistById, topArtists, resolveMixArt);
      return section(`mood-${mood.id}`, mood.title, 'Mood', cards);
    }).filter(Boolean);
    registry.reserveMoodPlaylists(moodSections.flatMap((s) => s.cards));

    const jumpBackIn = [];
    const jumpSeen = new Set();
    function addJump(card) {
      if (!card || jumpSeen.has(card.id)) return;
      if (!registry.hasCard(card.id)) registry.registerCard(card);
      jumpSeen.add(card.id);
      jumpBackIn.push(card);
    }

    const resume = input.continueResume;
    if (resume?.filepath) {
      const dur = resume.durationMs || 0;
      const pct = dur > 0 ? Math.floor(((resume.offsetMs || 0) * 100) / dur) : 0;
      addJump({
        id: `continue-${resume.filepath}`,
        title: resume.track || 'Continue listening',
        subtitle: `${pct}% · ${resume.artist || resume.context?.name || 'Pick up where you left off'}`,
        artPath: resume.filepath,
        playlistId: resume.context?.id || null,
        playTarget: ptSong(resume.filepath, resume.track || resume.filepath),
        kind: 'JumpBackIn',
      });
    }
    for (const card of dashboardJumpCards(input.dashboard, playlistByName, artByPlaylist)) {
      if (jumpBackIn.length >= LIMITS.JUMP_BACK_IN) break;
      addJump(card);
    }
    for (const name of recentPlaylistNames) {
      if (jumpBackIn.length >= LIMITS.JUMP_BACK_IN) break;
      const pl = playlistByName[name.toLowerCase()];
      if (!pl) continue;
      const c = playlistCard(pl, null, 'JumpBackIn', 'Recently played');
      if (c) addJump(c);
    }
    const seenAlbums = new Set();
    for (const row of (input.history || [])) {
      if (jumpBackIn.length >= LIMITS.JUMP_BACK_IN) break;
      const album = (row.album || '').trim();
      if (!album) continue;
      const key = `${album.toLowerCase()}|${(row.artist || '').toLowerCase()}`;
      if (seenAlbums.has(key)) continue;
      seenAlbums.add(key);
      addJump({
        id: `album-${key}`,
        title: album,
        subtitle: row.artist || 'Recently played album',
        artPath: row.filepath,
        playTarget: ptAlbum(album, row.artist),
        kind: 'JumpBackIn',
      });
    }
    jumpBackIn.push(...fillPlaylists(
      allPlaylists.slice().sort((a, b) => parseSortDate(b.createDate) - parseSortDate(a.createDate)),
      LIMITS.JUMP_BACK_IN - jumpBackIn.length,
      'JumpBackIn',
      () => 'Recently added',
    ));
    jumpBackIn.push(...fillPlaylists(
      shuffledGeneric,
      LIMITS.JUMP_BACK_IN - jumpBackIn.length,
      'JumpBackIn',
      (pl) => `${plTracks(pl)} tracks · From your library`,
    ));
    const jumpBackInFinal = jumpBackIn.filter((c, i, a) => a.findIndex((x) => x.id === c.id) === i).slice(0, LIMITS.JUMP_BACK_IN);

    const favoriteCards = [];
    const ratedSongCards = buildRatedSongCards(input.ratedSongItems || [], registry);

    const genreMixes = [];
    for (let index = 0; index < topGenres.length; index++) {
      if (genreMixes.length >= LIMITS.TOP_MIXES) break;
      const genre = topGenres[index]?.name || topGenres[index]?.label;
      if (!genre) continue;
      const smart = (input.smartPlaylists || []).find((sp) =>
        !Rules.isDailyMixName(sp.name) && sp.name.toLowerCase().includes(genre.toLowerCase()));
      if (smart?.playlistId) {
        const pl = playlistById[smart.playlistId];
        if (pl && !Rules.isDailyMixName(pl.name)) {
          const c = playlistCard(pl, null, 'TopMixes', `${genre} mix`, true, genre);
          if (c) genreMixes.push(c);
          continue;
        }
      }
      const named = Rules.bestGenreMixPlaylist(allPlaylists, genre);
      if (named) {
        // A real genre-mix playlist represents this genre — never synthesize a
        // "${genre} Mix" artist card, even if the playlist already appears elsewhere.
        const c = playlistCard(named, null, 'TopMixes', `${genre} mix`, true, genre);
        if (c) genreMixes.push(c);
        continue;
      }
      const seedArtist = Rules.topArtistForGenre(input.history, genre) ||
        topArtists[index]?.name || topArtists[index]?.label || topArtists[0]?.name;
      if (!seedArtist) continue;
      const card = {
        id: `mix-${genre}`,
        title: `${genre} Mix`,
        subtitle: 'Based on your listening',
        artPath: resolveMixArt(genre, seedArtist, index),
        playTarget: ptRadio(`${genre} Mix`, 'genre', seedArtist),
        kind: 'TopMixes',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        genreMixes.push(card);
      }
    }
    const genreMixPool = allPlaylists.filter((p) => Rules.isGenreMixPlaylistName(p.name) && !Rules.isDailyMixName(p.name))
      .sort((a, b) => plTracks(b) - plTracks(a));
    genreMixes.push(...fillPlaylists(
      genreMixPool.concat(shuffledGeneric),
      LIMITS.TOP_MIXES - genreMixes.length,
      'TopMixes',
      (pl) => (Rules.isGenreMixPlaylistName(pl.name) ? 'Curated mix' : `${plTracks(pl)} tracks · Suggested mix`),
    ));

    const dailyMixes = [];
    for (const sp of (input.smartPlaylists || []).filter((s) => s.enabled && Rules.isDailyMixName(s.name))) {
      if (dailyMixes.length >= LIMITS.DAILY_MIXES) break;
      const pl = sp.playlistId ? playlistById[sp.playlistId] : null;
      if (pl) {
        const c = playlistCard(pl, null, 'DailyMixes', 'Daily mix');
        if (c) dailyMixes.push(c);
      }
    }
    for (const pl of allPlaylists.filter((p) => Rules.isDailyMixName(p.name))
      .sort((a, b) => parseSortDate(b.lastUsed) - parseSortDate(a.lastUsed))) {
      if (dailyMixes.length >= LIMITS.DAILY_MIXES) break;
      const c = playlistCard(pl, null, 'DailyMixes', 'Daily mix');
      if (c) dailyMixes.push(c);
    }
    const mixLike = allPlaylists.filter((p) =>
      Rules.isGenreMixPlaylistName(p.name) || Rules.hasMixLikeName(p.name)).sort((a, b) => plTracks(b) - plTracks(a));
    dailyMixes.push(...fillPlaylists(
      mixLike.concat(shuffledGeneric),
      LIMITS.DAILY_MIXES - dailyMixes.length,
      'DailyMixes',
      () => 'Mix playlist',
    ));
    const dailyMixesFinal = dailyMixes.filter((c, i, a) => a.findIndex((x) => x.id === c.id) === i).slice(0, LIMITS.DAILY_MIXES);

    const browseGenres = buildBrowseGenreCards(input.libraryGenres || [], registry);

    const exploreThemes = buildExploreThemeCards(
      { ...input, allPlaylists },
      registry,
      playlistById,
      topArtists,
      topGenres,
      resolveMixArt,
      playlistCard,
    );

    const recentPlaylists = [];
    for (const name of recentPlaylistNames) {
      if (recentPlaylists.length >= LIMITS.RECENT_PLAYLISTS) break;
      const pl = playlistByName[name.toLowerCase()];
      if (!pl) continue;
      const c = playlistCard(pl, null, 'RecentPlaylists', 'Played recently');
      if (c) recentPlaylists.push(c);
    }
    recentPlaylists.push(...fillPlaylists(
      allPlaylists.slice().sort((a, b) => parseSortDate(b.lastUsed) - parseSortDate(a.lastUsed)).concat(shuffledGeneric),
      LIMITS.RECENT_PLAYLISTS - recentPlaylists.length,
      'RecentPlaylists',
      () => 'From your library',
    ));

    const radioCards = buildRadioCards(input.history || [], topArtists, topGenres, allPlaylists, registry, LIMITS.RADIO);

    const discoverCandidates = [];
    for (const pl of allPlaylists.filter((p) => Rules.isDiscoverName(p.name))
      .sort((a, b) => parseSortDate(b.createDate) - parseSortDate(a.createDate))) {
      if (discoverCandidates.length >= LIMITS.DISCOVER) break;
      const c = playlistCard(pl, null, 'Discover', 'Discover Weekly');
      if (c) discoverCandidates.push(c);
    }
    for (const sp of (input.smartPlaylists || []).filter((s) => s.enabled)) {
      if (discoverCandidates.length >= LIMITS.DISCOVER) break;
      const pl = sp.playlistId ? playlistById[sp.playlistId] : null;
      if (!pl) continue;
      const sub = Rules.isDiscoverName(sp.name) ? 'New to you' : 'Smart playlist';
      const c = playlistCard(pl, null, 'Discover', sub);
      if (c) discoverCandidates.push(c);
    }
    const large = allPlaylists.filter((p) => !Rules.isSpecialHomePlaylistName(p.name)).sort((a, b) => plTracks(b) - plTracks(a));
    discoverCandidates.push(...fillPlaylists(
      large.concat(shuffledGeneric),
      LIMITS.DISCOVER - discoverCandidates.length,
      'Discover',
      (pl) => `${plTracks(pl)} tracks · Discover`,
    ));
    const discoverFinal = discoverCandidates.filter((c, i, a) => a.findIndex((x) => x.id === c.id) === i).slice(0, LIMITS.DISCOVER);

    // Catch-all so no library playlist is permanently hidden from home; the daily
    // shuffle seed rotates which lead, and any not shown elsewhere land here.
    const allRotated = Rules.shuffledAllPlaylists(allPlaylists, input.shuffleSeed);
    const morePlaylists = fillPlaylists(
      allRotated.concat(allPlaylists.slice().sort((a, b) => plTracks(b) - plTracks(a))),
      LIMITS.MORE_PLAYLISTS,
      'RecentPlaylists',
      (pl) => `${plTracks(pl)} tracks · From your library`,
    );

    const releaseRadar = [];
    if (input.releaseRadarLabel) {
      const card = {
        id: 'release-radar',
        title: 'Release Radar',
        subtitle: input.releaseRadarLabel,
        artPath: input.releaseRadarArtPath || null,
        playTarget: ptRadio('New in library', 'genre', 'Library', input.releaseRadarArtPath),
        kind: 'Discover',
      };
      if (!registry.hasCard(card.id)) {
        registry.registerCard(card);
        releaseRadar.push(card);
      }
    }

    const sections = [
      section('jump-back-in', 'Jump back in', 'JumpBackIn', jumpBackInFinal),
      section('rated-songs', 'Rated Songs', 'RatedSongs', ratedSongCards),
      section('browse-genres', 'Browse by genre', 'BrowseGenres', browseGenres),
      section('top-mixes', 'Your top mixes', 'TopMixes', genreMixes),
      ...moodSections,
      section('release-radar', 'Release Radar', 'Discover', releaseRadar),
      section('discover-weekly', 'Discover Weekly', 'Discover', input.discoverWeeklyCards || []),
      section('explore-themes', 'Explore genres & worlds', 'ExploreThemes', exploreThemes),
      section('daily-mixes', 'New daily mixes', 'DailyMixes', dailyMixesFinal),
      section('recent-playlists', 'Recent playlists', 'RecentPlaylists', recentPlaylists),
      section('radio', 'Radio', 'Radio', radioCards),
      section('discover', 'Discover', 'Discover', discoverFinal),
      section('more-playlists', 'More playlists', 'RecentPlaylists', morePlaylists),
    ].filter(Boolean);

    return { sections };
  }

  const homeShortcutMixKinds = new Set(['TopMixes', 'BrowseGenres', 'Mood', 'DailyMixes', 'ExploreThemes', 'RecentPlaylists']);

  function eligibleForHomeShortcut(card) {
    const t = card.playTarget;
    if (!t || t.kind === 'album' || t.kind === 'song') return false;
    if (t.kind === 'playlist') return !Rules.isAutomationPlaylistName(t.name);
    if (t.kind === 'artist' || t.kind === 'radio') return homeShortcutMixKinds.has(card.kind);
    return false;
  }

  function homeShortcutCards(feed, limit = 6) {
    const jump = (feed.sections.find((s) => s.kind === 'JumpBackIn')?.cards || [])
      .filter(eligibleForHomeShortcut);
    const seen = new Set(jump.map((c) => c.id));
    const result = jump.slice();
    if (result.length >= limit) return result.slice(0, limit);
    for (const sec of feed.sections) {
      if (!homeShortcutMixKinds.has(sec.kind)) continue;
      for (const card of sec.cards) {
        if (!eligibleForHomeShortcut(card) || seen.has(card.id)) continue;
        result.push(card);
        seen.add(card.id);
        if (result.length >= limit) return result;
      }
    }
    return result;
  }

  function cardPlayOpts(card) {
    const t = card.playTarget;
    if (t.kind === 'playlist') return { kind: 'playlist', id: t.id, name: t.name };
    if (t.kind === 'song') return { kind: 'song', name: t.title, path: t.path, artist: '' };
    if (t.kind === 'album') return { kind: 'album', name: t.name, artist: t.artist || '' };
    if (t.kind === 'artist') return { kind: 'artist', name: t.name };
    if (t.kind === 'radio') {
      if (t.seedKind === 'artist') return { kind: 'artist', name: t.seed };
      if (t.seedKind === 'song') return { kind: 'song', name: t.seed, path: t.artPath || '', artist: '' };
      const genre = Rules.mixGenreLabel(card.title) || Rules.genreRadioLabel(t.title) || t.seed;
      return { kind: 'genre', name: genre };
    }
    return null;
  }

  function cardHref(card) {
    const t = card.playTarget;
    if (t.kind === 'playlist') return `#playlists/detail/${encodeURIComponent(t.id)}`;
    if (card.playlistId) return `#playlists/detail/${encodeURIComponent(card.playlistId)}`;
    if (t.kind === 'album') return `#songs/album/${encodeURIComponent(t.name)}`;
    if (t.kind === 'artist') return `#songs/artist/${encodeURIComponent(t.name)}`;
    if (t.kind === 'radio') {
      if (t.seedKind === 'artist') return `#songs/artist/${encodeURIComponent(t.seed)}`;
      if (t.seedKind === 'genre') {
        const label = Rules.mixGenreLabel(card.title) || Rules.genreRadioLabel(t.title) || t.seed;
        return `#genres/${encodeURIComponent(label)}`;
      }
    }
    if (card.kind === 'BrowseGenres') return `#genres/${encodeURIComponent(card.title)}`;
    if (card.kind === 'Offline') return '#library';
    return '#search';
  }

  function cardIcon(card) {
    if (card.kind === 'Radio' || card.playTarget.kind === 'radio') return 'fa-tower-broadcast';
    if (card.kind === 'TopMixes' || card.kind === 'DailyMixes') return 'fa-wand-magic-sparkles';
    if (card.playTarget.kind === 'playlist') return 'fa-list';
    if (card.playTarget.kind === 'album') return 'fa-compact-disc';
    return 'fa-music';
  }

  const api = {
    compose,
    applyTileRotation,
    Rules,
    homeShortcutCards,
    eligibleForHomeShortcut,
    cardPlayOpts,
    cardHref,
    cardIcon,
    dayOfYear,
    LIMITS,
    __testEngagement: {
      STALE_MS: TILE_STALE_MS,
      reset() { _testEngagement = {}; },
      put(cardId, entry) {
        const map = TileEngagement.load();
        map[cardId] = entry;
        TileEngagement.save(map);
      },
    },
  };
  root.HomeFeed = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
