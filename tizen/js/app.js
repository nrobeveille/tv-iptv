/* Appli IPTV pour TV Samsung (Tizen) : état, chargement des données, écrans. */
'use strict';

var GITHUB_REPO = 'nrobeveille/tv-iptv';
var PLAYLIST_MAX_AGE = 24 * 3600000;
var EPG_MAX_AGE = 12 * 3600000;

var USER_AGENTS = {
  'VLC': 'VLC/3.0.20 LibVLC/3.0.20',
  'IPTV Smarters': 'IPTVSmartersPlayer',
  'Navigateur': 'Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) Version/7.0 TV Safari/537.36',
  'Par défaut (TV)': '',
};
var ASPECTS = { fit: 'Ajusté', fill: 'Étiré', zoom: 'Zoom (recadré)' };

var SECTIONS = [
  { id: 'live', label: 'TV en direct', icon: '📺' },
  { id: 'movies', label: 'Films', icon: '🎬' },
  { id: 'series', label: 'Séries', icon: '🎞' },
  { id: 'favorites', label: 'Favoris', icon: '★' },
  { id: 'search', label: 'Rechercher', icon: '🔍' },
  { id: 'settings', label: 'Paramètres', icon: '⚙' },
];

var App = {
  settings: null,
  content: null,
  guide: new EPG.Guide({}),
  load: { loading: false, progress: null, error: null },
  epgState: { loading: false, error: null, url: null },
  update: { status: 'idle', latest: null, message: null },
  screens: [],
  section: 'live',
  sections: {},
  loadToken: 0,
  autoResumeDone: false,
};

/* ================================================================== Réglages */

App.defaults = function () {
  return { playlists: [], activeId: null, favorites: [], recents: [], aspect: 'fit', ua: 'VLC', resumeLast: false, lastChannel: null };
};

App.save = function () { Store.set('settings', App.settings); };

App.activePlaylist = function () {
  var s = App.settings;
  return s.playlists.filter(function (p) { return p.id === s.activeId; })[0] || s.playlists[0] || null;
};

App.userAgent = function () {
  var ua = USER_AGENTS[App.settings.ua];
  return ua == null ? USER_AGENTS.VLC : ua;
};

App.isFav = function (key) { return App.settings.favorites.indexOf(key) >= 0; };

App.toggleFav = function (key, label) {
  var f = App.settings.favorites;
  var i = f.indexOf(key);
  if (i >= 0) { f.splice(i, 1); toast('« ' + label + ' » retiré des favoris'); }
  else { f.push(key); toast('« ' + label + ' » ajouté aux favoris'); }
  App.save();
  App.invalidate(['favorites']);
  App.refreshVisible();
};

App.addRecent = function (ch) {
  if (ch.type !== 'live') return;
  var r = App.settings.recents.filter(function (k) { return k !== ch.url; });
  r.unshift(ch.url);
  App.settings.recents = r.slice(0, 30);
  App.settings.lastChannel = ch.url;
  App.save();
};

/** Position de lecture des films / épisodes : { url: [ms, pourcentage] }. */
App.positions = {
  all: null,
  load: function () { if (!this.all) this.all = Store.get('positions', {}); return this.all; },
  get: function (key) { var p = this.load()[key]; return p ? p[0] : 0; },
  progress: function (key) { var p = this.load()[key]; return p ? p[1] / 100 : 0; },
  save: function (key, pos, dur) {
    var all = this.load();
    if (dur <= 0) return;
    if (pos < 30000 || pos > dur - 90000) delete all[key];
    else all[key] = [pos, Math.round(pos * 100 / dur)];
    var keys = Object.keys(all);
    if (keys.length > 400) keys.slice(0, keys.length - 400).forEach(function (k) { delete all[k]; });
    Store.set('positions', all);
  },
};

/* ================================================================== Playlist et guide */

App.loadActive = function () {
  var token = ++App.loadToken;
  var p = App.activePlaylist();
  App.content = null;
  App.guide = new EPG.Guide({});
  App.epgState = { loading: false, error: null, url: null };
  App.invalidate();
  if (!p) {
    App.load = { loading: false, progress: null, error: null };
    App.renderHome();
    return Promise.resolve();
  }
  App.load = { loading: true, progress: 'Ouverture de la playlist…', error: null };
  App.renderHome();
  return DB.get('pl:' + p.id).then(function (text) {
    if (token !== App.loadToken) return;
    if (!text) return App.downloadPlaylist(p, false, token);
    return nextFrame().then(function () {
      App.setContent(M3U.parse(text), p);
      var meta = Store.get('plmeta:' + p.id, {});
      if (Date.now() - (meta.updated || 0) > PLAYLIST_MAX_AGE) App.downloadPlaylist(p, true, token);
    });
  });
};

App.setContent = function (content, p) {
  App.content = content;
  App.load = { loading: false, progress: null, error: null };
  App.invalidate();
  App.renderHome();
  App.loadEpg(p, false);
  App.maybeResume();
};

App.refreshPlaylist = function () {
  var p = App.activePlaylist();
  if (!p || App.load.loading) return;
  App.downloadPlaylist(p, false, App.loadToken);
};

/** Télécharge le texte d'une URL en signalant la progression. */
function fetchText(url, onProgress) {
  return fetch(url, { cache: 'no-store' }).catch(function () {
    throw new Error('Serveur injoignable (adresse, connexion ou accès refusé)');
  }).then(function (res) {
    if (res.status === 401 || res.status === 403) throw new Error('Accès refusé (' + res.status + ') : identifiants ou abonnement à vérifier');
    if (res.status === 404) throw new Error('Introuvable (404) : vérifie l\'adresse');
    if (!res.ok) throw new Error('Erreur du serveur (' + res.status + ')');
    if (!res.body || !res.body.getReader) return res.text();
    var reader = res.body.getReader();
    var decoder = new TextDecoder('utf-8');
    var parts = [];
    var total = 0;
    function pump() {
      return reader.read().then(function (r) {
        if (r.done) { parts.push(decoder.decode()); return parts.join(''); }
        total += r.value.length;
        parts.push(decoder.decode(r.value, { stream: true }));
        if (onProgress) onProgress(total);
        return pump();
      });
    }
    return pump();
  });
}

App.downloadPlaylist = function (p, silent, token) {
  var hadContent = !!App.content;
  if (!silent || !hadContent) {
    App.load = { loading: true, progress: 'Téléchargement…', error: null };
    App.renderHome();
  }
  var lastUpdate = 0;
  return fetchText(p.url, function (bytes) {
    if ((silent && hadContent) || Date.now() - lastUpdate < 400) return;
    lastUpdate = Date.now();
    App.load.progress = 'Téléchargement… ' + formatSize(bytes);
    App.renderStatus();
  }).then(function (text) {
    if (token !== App.loadToken) return;
    App.load.progress = 'Analyse de la playlist…';
    App.renderStatus();
    return nextFrame().then(function () {
      var content = M3U.parse(text);
      if (content.isEmpty) {
        throw new Error(text.slice(0, 200).indexOf('#EXTM3U') >= 0 ? 'La playlist est vide' :
          'Ce n\'est pas une playlist M3U (vérifie l\'adresse et l\'abonnement)');
      }
      DB.set('pl:' + p.id, text).catch(function (e) { console.warn('cache playlist', e); });
      Store.set('plmeta:' + p.id, { updated: Date.now() });
      if (!silent) toast('Playlist à jour : ' + content.allLive.length + ' chaînes, ' + content.allMovies.length + ' films, ' + content.allSeries.length + ' séries');
      App.setContent(content, p);
    });
  }).catch(function (e) {
    if (token !== App.loadToken) return;
    App.load = { loading: false, progress: null, error: e.message || 'Téléchargement impossible' };
    if (hadContent && !silent) toast(App.load.error);
    App.renderHome();
  });
};

App.epgUrl = function (p) {
  return (p.epgUrl && p.epgUrl.trim()) || (App.content && App.content.headerEpg) || EPG.xtreamUrl(p.url);
};

App.loadEpg = function (p, force) {
  var url = App.epgUrl(p);
  App.epgState = { loading: false, error: null, url: url };
  if (!url || !App.content) { App.guide = new EPG.Guide({}); return Promise.resolve(); }
  var token = App.loadToken;
  var content = App.content;
  return DB.get('epg:' + p.id).then(function (cached) {
    if (token !== App.loadToken) return;
    var valid = cached && cached.url === url;
    if (valid && !force) {
      App.guide = new EPG.Guide(cached.data);
      App.refreshVisible();
    }
    if (!force && valid && Date.now() - cached.updated < EPG_MAX_AGE) return;
    App.epgState.loading = true;
    App.renderStatus();
    var done = function (data) {
      if (token !== App.loadToken) return;
      App.guide = new EPG.Guide(data);
      App.epgState = { loading: false, error: null, url: url };
      DB.set('epg:' + p.id, { url: url, updated: Date.now(), data: data }).catch(function () { });
      Store.set('epgmeta:' + p.id, { updated: Date.now() });
      App.refreshVisible();
      App.renderStatus();
    };
    var promise = (typeof TextDecoderStream !== 'undefined' && typeof ReadableStream !== 'undefined')
      ? EPG.download(url, content)
      : fetchText(url).then(function (text) { var parser = new EPG.Parser(content); parser.feed(text); return parser.finish(); });
    return promise.then(done).catch(function (e) {
      App.epgState = { loading: false, error: (e && e.message) || 'Guide TV indisponible', url: url };
      App.refreshVisible();
    });
  });
};

App.maybeResume = function () {
  if (App.autoResumeDone) return;
  App.autoResumeDone = true;
  var s = App.settings;
  if (!s.resumeLast || !s.lastChannel || App.screens.length > 1) return;
  var ch = App.content.byKey.get(s.lastChannel);
  if (!ch || ch.type !== 'live') return;
  var cat = App.content.live.filter(function (c) { return c.name === ch.group; })[0];
  var list = cat ? cat.items : [ch];
  App.play(list, Math.max(0, list.indexOf(ch)));
};

/* ================================================================== Mises à jour */

App.checkUpdate = function (silent) {
  if (App.update.status === 'checking') return;
  if (!silent) { App.update = { status: 'checking' }; App.refreshVisible(); }
  fetch('https://api.github.com/repos/' + GITHUB_REPO + '/releases?per_page=30', { cache: 'no-store' })
    .then(function (r) { if (!r.ok) throw new Error('GitHub (' + r.status + ')'); return r.json(); })
    .then(function (releases) {
      var latest = null;
      releases.forEach(function (r) {
        var m = /^tizen-v(\d+)\.(\d+)\.(\d+)$/.exec(r.tag_name || '');
        if (m && !r.draft && (!latest || +m[3] > latest.build)) latest = { build: +m[3], name: r.tag_name.replace('tizen-v', ''), url: r.html_url };
      });
      if (!latest) App.update = { status: silent ? 'idle' : 'error', message: 'Aucune version Samsung publiée pour l\'instant' };
      else if (latest.build > (window.APP_VERSION ? APP_VERSION.build : 0)) App.update = { status: 'available', latest: latest };
      else App.update = { status: 'uptodate', latest: latest };
      App.refreshVisible();
      App.renderNav();
    })
    .catch(function (e) {
      App.update = { status: silent ? 'idle' : 'error', message: 'Vérification impossible : ' + e.message };
      App.refreshVisible();
    });
};

/* ================================================================== Navigation entre écrans */

App.top = function () { return App.screens[App.screens.length - 1]; };

App.push = function (screen) {
  var prev = App.top();
  if (prev) {
    var z = prev.zones[prev.zone];
    if (z) z.blur();
    prev.el.classList.add('hidden');
    if (prev.onHide) prev.onHide();
  }
  App.screens.push(screen);
  document.getElementById('screens').appendChild(screen.el);
  if (screen.onShow) screen.onShow();
  App.focusZone(screen, screen.zone, true);
};

App.pop = function () {
  var s = App.screens.pop();
  if (s) {
    if (s.destroy) s.destroy();
    s.el.remove();
  }
  var top = App.top();
  if (top) {
    top.el.classList.remove('hidden');
    if (top.onShow) top.onShow();
    App.focusZone(top, top.zone, true);
  }
};

App.focusZone = function (screen, name, force) {
  var target = screen.zones[name];
  if (!target || !target.canFocus()) return false;
  var old = screen.zones[screen.zone];
  if (old && old !== target) old.blur();
  if (old !== target || force) {
    screen.zone = name;
    target.focus();
  }
  return true;
};

App.back = function () {
  var top = App.top();
  if (top && top.back && top.back()) return;
  if (App.screens.length > 1) { App.pop(); return; }
  var home = App.screens[0];
  if (home.zone !== 'nav') { App.focusZone(home, 'nav'); return; }
  if (App.section !== 'live') { App.showSection('live'); return; }
  Modal.menu('Quitter IPTV ?', [
    { label: 'Quitter', action: exitApp },
    { label: 'Annuler', action: function () { } },
  ]);
};

App.play = function (items, index) {
  if (!items.length) return;
  var top = App.top();
  if (top && top.isPlayer) App.pop();
  App.push(new PlayerScreen(items, Math.max(0, Math.min(index, items.length - 1))));
};

/* ================================================================== Accueil : menu + rubriques */

App.buildHome = function () {
  var el = document.createElement('div');
  el.className = 'screen home';
  el.innerHTML =
    '<nav class="sidebar"><div class="brand">IPTV</div><div class="pl-name"></div><div class="nav-list"></div>' +
    '<div class="clock"></div><div class="status"></div></nav><main class="main"></main>';
  var home = { el: el, zones: {}, neighbors: {}, zone: 'nav' };
  home.zones.nav = new ListZone(el.querySelector('.nav-list'), {
    rowHeight: 76,
    scrollbar: false,
    render: function (s) {
      var badge = s.id === 'settings' && App.update.status === 'available' ? '<i class="dot"></i>' : '';
      return '<div class="nav-item"><span class="ico">' + s.icon + '</span><span>' + esc(s.label) + '</span>' + badge + '</div>';
    },
    isSelected: function (s) { return s.id === App.section; },
    onFocus: function (s) { App.showSection(s.id); },
    onEnter: function () { App.enterSection(); },
  });
  home.zones.nav.setItems(SECTIONS, 0);
  home.key = function (code) {
    // Depuis le menu, la flèche droite entre dans la rubrique.
    if (home.zone === 'nav' && code === Keys.RIGHT) { App.enterSection(); return true; }
    return false;
  };
  home.onShow = function () { App.refreshVisible(); };
  App.home = home;
  App.push(home);
  setInterval(function () { App.renderClock(); }, 15000);
  App.renderClock();
};

App.renderClock = function () {
  var c = App.home && App.home.el.querySelector('.clock');
  if (c) c.textContent = fmtTime(Date.now());
};

App.renderNav = function () {
  if (!App.home) return;
  var p = App.activePlaylist();
  App.home.el.querySelector('.pl-name').textContent = p ? p.name : 'Aucune playlist';
  App.home.zones.nav.refresh();
};

App.renderStatus = function () {
  if (!App.home) return;
  var parts = [];
  if (App.load.loading && App.content) parts.push(App.load.progress || 'Mise à jour…');
  if (App.epgState.loading) parts.push('Guide TV…');
  App.home.el.querySelector('.status').textContent = parts.join(' · ');
  var msg = App.home.el.querySelector('.main .message p');
  if (msg && App.load.loading && !App.content) msg.textContent = App.load.progress || '';
};

/** Rubrique affichée dans la partie droite. */
App.showSection = function (id) {
  App.section = id;
  App.renderHome();
};

App.enterSection = function () {
  var home = App.home;
  var sec = App.sections[App.currentSectionKey()];
  if (sec && sec.entry && App.focusZone(home, sec.entry)) return;
};

App.currentSectionKey = function () {
  if (App.section === 'settings') return 'settings';
  if (!App.activePlaylist()) return 'welcome';
  if (!App.content) return App.load.loading ? 'loading' : 'error';
  return App.section;
};

/** Oublie les rubriques construites (contenu changé) pour les reconstruire à l'affichage. */
App.invalidate = function (only) {
  Object.keys(App.sections).forEach(function (k) {
    if (only && only.indexOf(k) < 0) return;
    var s = App.sections[k];
    if (s.el) s.el.remove();
    delete App.sections[k];
  });
};

App.renderHome = function () {
  var home = App.home;
  if (!home) return;
  App.renderNav();
  App.renderStatus();
  var key = App.currentSectionKey();
  if (key === 'loading' || key === 'error' || key === 'welcome') App.invalidate([key]);
  var main = home.el.querySelector('.main');
  var sec = App.sections[key];
  if (!sec) {
    sec = App.sections[key] = Sections[key]();
    main.appendChild(sec.el);
  }
  // Une seule rubrique visible ; ses zones remplacent celles de la précédente.
  Object.keys(App.sections).forEach(function (k) { App.sections[k].el.classList.toggle('hidden', k !== key); });
  var focusedZone = home.zone;
  Object.keys(home.zones).forEach(function (z) { if (z !== 'nav') delete home.zones[z]; });
  home.neighbors = { nav: {} };
  Object.keys(sec.zones || {}).forEach(function (z) { home.zones[z] = sec.zones[z]; });
  Object.keys(sec.neighbors || {}).forEach(function (z) { home.neighbors[z] = sec.neighbors[z]; });
  if (sec.entry) home.neighbors.nav.right = sec.entry;
  if (focusedZone !== 'nav') {
    var z = home.zones[focusedZone];
    if (!z || !z.canFocus()) {
      home.zone = 'nav';
      home.zones.nav.focus();
    } else if (!z.active && App.top() === home) {
      z.focus(); // rubrique reconstruite : on redonne le focus à la nouvelle zone
    }
  }
  if (sec.onShow) sec.onShow();
};

/** Rafraîchit ce qui est à l'écran (favoris, programmes en cours, état de mise à jour). */
App.refreshVisible = function () {
  var top = App.top();
  if (!top) return;
  if (top === App.home) {
    var sec = App.sections[App.currentSectionKey()];
    if (sec && sec.refresh) sec.refresh();
    App.renderNav();
  } else if (top.refresh) top.refresh();
};

/* ================================================================== Rubriques */

function messageSection(title, text, buttons) {
  var el = document.createElement('div');
  el.className = 'section message-section';
  el.innerHTML = '<div class="message"><h1>' + esc(title) + '</h1>' + (text ? '<p>' + esc(text) + '</p>' : '') + '<div class="buttons"></div></div>';
  var sec = { el: el, zones: {}, neighbors: {} };
  if (buttons && buttons.length) {
    var list = el.querySelector('.buttons');
    list.style.height = buttons.length * 76 + 'px';
    var zone = new ListZone(list, {
      rowHeight: 76,
      scrollbar: false,
      render: function (b) { return '<div class="button">' + esc(b.label) + '</div>'; },
      onEnter: function (b) { b.action(); },
    });
    zone.setItems(buttons);
    sec.zones.buttons = zone;
    sec.neighbors.buttons = { left: 'nav' };
    sec.entry = 'buttons';
  }
  return sec;
}

function chips(text) { return '<em>' + esc(text) + '</em>'; }

function channelRow(c, now) {
  var p = App.guide.now(c, now);
  return '<div class="ch">' +
    (c.number ? '<span class="num">' + c.number + '</span>' : '') +
    artwork(c.logo, c.name, 'logo') +
    '<div class="txt"><div class="t1">' + esc(c.name) + '</div>' +
    (p ? '<div class="t2">' + esc(p[2]) + '</div>' + progressBar(EPG.progress(p, now), 'mini') : '') +
    '</div>' + (App.isFav(c.url) ? '<span class="star">★</span>' : '') + '</div>';
}

function categoryRow(c) {
  return '<div class="cat"><span>' + esc(c.label) + '</span>' + chips(c.items.length) + '</div>';
}

function posterCell(title, image, fav, progress, caption) {
  return '<div class="poster">' + artwork(image, title, 'cover') +
    (fav ? '<span class="star">★</span>' : '') +
    (progress > 0 ? progressBar(progress, 'poster-bar') : '') + '</div>' +
    '<div class="ptitle">' + esc(title) + '</div>' + (caption ? '<div class="pcaption">' + esc(caption) + '</div>' : '');
}

function seasonsLabel(s) {
  var n = s.episodes.length;
  return (s.seasons.length > 1 ? s.seasons.length + ' saisons · ' : '') + n + ' épisode' + (n > 1 ? 's' : '');
}

function liveCategories() {
  var c = App.content, s = App.settings;
  var out = [];
  var favs = c.allLive.filter(function (ch) { return s.favorites.indexOf(ch.url) >= 0; });
  if (favs.length) out.push({ key: 'fav', label: '★ Favoris', items: favs });
  var rec = s.recents.map(function (k) { return c.byKey.get(k); }).filter(function (ch) { return ch && ch.type === 'live'; });
  if (rec.length) out.push({ key: 'recent', label: 'Récentes', items: rec });
  out.push({ key: 'all', label: 'Toutes les chaînes', items: c.allLive });
  c.live.forEach(function (g) { out.push({ key: 'g:' + g.name, label: g.name, items: g.items }); });
  return out;
}

function infoPanel(ch, now) {
  if (!ch) return '';
  var html = artwork(ch.logo, ch.name, 'big-logo') +
    '<h2>' + esc(ch.name) + '</h2><p class="sub">' + (ch.number ? 'N° ' + ch.number + ' · ' : '') + esc(ch.group) + '</p>';
  var up = App.guide.upcoming(ch, 5, now);
  if (!up.length) {
    var why = App.epgState.loading ? 'Chargement du guide TV…' : App.epgState.error ? App.epgState.error :
      !App.epgState.url ? 'Aucun guide TV pour cette playlist' : 'Pas de guide TV pour cette chaîne';
    return html + '<p class="faint">' + esc(why) + '</p>';
  }
  var first = up[0];
  var onNow = now >= first[0] && now < first[1];
  html += '<div class="label">' + (onNow ? 'EN CE MOMENT' : 'À SUIVRE') + '</div>' +
    '<div class="prog-title">' + esc(first[2]) + '</div>' +
    '<p class="sub">' + fmtTime(first[0]) + ' – ' + fmtTime(first[1]) + '</p>' +
    (onNow ? progressBar(EPG.progress(first, now)) : '') +
    (first[3] ? '<p class="desc">' + esc(first[3]) + '</p>' : '');
  if (up.length > 1) {
    html += '<div class="label">ENSUITE</div>' + up.slice(1).map(function (p) {
      return '<div class="next"><span>' + fmtTime(p[0]) + '</span>' + esc(p[2]) + '</div>';
    }).join('');
  }
  return html;
}

/** Catégories à gauche + grille d'affiches (films, séries). */
function posterSection(cfg) {
  var el = document.createElement('div');
  el.className = 'section browse';
  el.innerHTML = '<div class="col-cats"><div class="list cats"></div></div>' +
    '<div class="col-main"><div class="head"><h1></h1><p></p></div><div class="grid"></div></div>';
  var cats = cfg.categories();
  var selected = Math.max(0, cats.map(function (c) { return c.key; }).indexOf(App[cfg.memoryKey]));
  var grid = new GridZone(el.querySelector('.grid'), {
    cellWidth: 226, cellHeight: 400,
    render: cfg.renderCell,
    onEnter: cfg.onEnter,
    onLong: cfg.onLong,
    empty: 'Rien ici pour l\'instant',
  });
  function show(i) {
    selected = i;
    var c = cats[i];
    App[cfg.memoryKey] = c.key;
    el.querySelector('.head h1').textContent = c.label;
    el.querySelector('.head p').textContent = c.items.length + ' ' + cfg.unit + ' · OK : ouvrir · OK maintenu : favori';
    grid.setItems(c.items, 0);
  }
  var catZone = new ListZone(el.querySelector('.cats'), {
    rowHeight: 60,
    render: categoryRow,
    isSelected: function (c, i) { return i === selected; },
    onFocus: function (c, i) { if (i !== selected) show(i); catZone.render(); },
    onEnter: function () { App.focusZone(App.home, 'grid'); },
  });
  catZone.setItems(cats, selected);
  show(selected);
  return {
    el: el,
    zones: { cats: catZone, grid: grid },
    neighbors: { cats: { left: 'nav', right: 'grid' }, grid: { left: 'cats' } },
    entry: 'grid',
    refresh: function () { grid.refresh(); },
    onShow: function () { setTimeout(function () { grid.refresh(); catZone.refresh(); }, 0); },
  };
}

var Sections = {
  welcome: function () {
    return messageSection('Bienvenue !',
      'Ajoute ton compte IPTV : l\'adresse de ta playlist M3U (par exemple http://serveur:port/get.php?username=…&password=…&type=m3u_plus).',
      [{ label: 'Ajouter une playlist', action: function () { App.push(new EditorScreen(null)); } }]);
  },

  loading: function () {
    return messageSection('Chargement de la playlist', App.load.progress || 'Patiente un instant…');
  },

  error: function () {
    var p = App.activePlaylist();
    return messageSection('Impossible de charger la playlist', App.load.error, [
      { label: 'Réessayer', action: App.refreshPlaylist },
      { label: 'Modifier la playlist', action: function () { App.push(new EditorScreen(p && p.id)); } },
    ]);
  },

  live: function () {
    var c = App.content;
    if (!c.allLive.length) return messageSection('Aucune chaîne', 'Cette playlist ne contient que des films ou des séries.');
    var el = document.createElement('div');
    el.className = 'section live';
    el.innerHTML = '<div class="col-cats"><div class="list cats"></div></div>' +
      '<div class="col-main"><div class="head"><h1></h1><p></p></div><div class="list chans"></div></div>' +
      '<div class="col-info"></div>';
    var cats = liveCategories();
    var selected = Math.max(0, cats.map(function (x) { return x.key; }).indexOf(App.liveCat || 'all'));
    var info = el.querySelector('.col-info');
    var now = Date.now();
    var chans = new ListZone(el.querySelector('.chans'), {
      rowHeight: 92,
      render: function (ch) { return channelRow(ch, now); },
      onFocus: function (ch) { info.innerHTML = infoPanel(ch, now); },
      onEnter: function (ch, i) { App.liveCat = cats[selected].key; App.play(cats[selected].items, i); },
      onLong: function (ch) { App.toggleFav(ch.url, ch.name); },
      empty: 'Aucune chaîne',
    });
    function show(i) {
      selected = i;
      var cat = cats[i];
      App.liveCat = cat.key;
      el.querySelector('.head h1').textContent = cat.label;
      el.querySelector('.head p').textContent = cat.items.length + ' chaînes · OK : regarder · OK maintenu : favori';
      chans.setItems(cat.items, 0);
      info.innerHTML = infoPanel(cat.items[0], now);
    }
    var catZone = new ListZone(el.querySelector('.cats'), {
      rowHeight: 60,
      render: categoryRow,
      isSelected: function (x, i) { return i === selected; },
      onFocus: function (x, i) { if (i !== selected) show(i); catZone.render(); },
      onEnter: function () { App.focusZone(App.home, 'chans'); },
    });
    catZone.setItems(cats, selected);
    show(selected);
    return {
      el: el,
      zones: { cats: catZone, chans: chans },
      neighbors: { cats: { left: 'nav', right: 'chans' }, chans: { left: 'cats' } },
      entry: 'chans',
      refresh: function () {
        now = Date.now();
        chans.refresh();
        info.innerHTML = infoPanel(chans.current(), now);
      },
      onShow: function () { setTimeout(function () { chans.refresh(); catZone.refresh(); }, 0); },
    };
  },

  movies: function () {
    var c = App.content;
    if (!c.allMovies.length) return messageSection('Aucun film', 'Cette playlist ne contient pas de films (VOD).');
    return posterSection({
      memoryKey: 'movieCat',
      unit: 'films',
      categories: function () {
        var out = [];
        var favs = c.allMovies.filter(function (m) { return App.isFav(m.url); });
        if (favs.length) out.push({ key: 'fav', label: '★ Favoris', items: favs });
        out.push({ key: 'all', label: 'Tous les films', items: c.allMovies });
        c.movies.forEach(function (g) { out.push({ key: 'g:' + g.name, label: g.name, items: g.items }); });
        return out;
      },
      renderCell: function (m) { return posterCell(m.name, m.logo, App.isFav(m.url), App.positions.progress(m.url)); },
      onEnter: function (m) { App.play([m], 0); },
      onLong: function (m) { App.toggleFav(m.url, m.name); },
    });
  },

  series: function () {
    var c = App.content;
    if (!c.allSeries.length) return messageSection('Aucune série', 'Cette playlist ne contient pas de séries.');
    return posterSection({
      memoryKey: 'seriesCat',
      unit: 'séries',
      categories: function () {
        var out = [];
        var favs = c.allSeries.filter(function (s) { return App.isFav(s.key); });
        if (favs.length) out.push({ key: 'fav', label: '★ Favoris', items: favs });
        out.push({ key: 'all', label: 'Toutes les séries', items: c.allSeries });
        c.series.forEach(function (g) { out.push({ key: 'g:' + g.name, label: g.name, items: g.items }); });
        return out;
      },
      renderCell: function (s) { return posterCell(s.name, s.poster, App.isFav(s.key), 0, seasonsLabel(s)); },
      onEnter: function (s) { App.push(new SeriesScreen(s)); },
      onLong: function (s) { App.toggleFav(s.key, s.name); },
    });
  },

  favorites: function () {
    var c = App.content;
    var el = document.createElement('div');
    el.className = 'section single';
    el.innerHTML = '<div class="head"><h1>Favoris</h1><p>OK maintenu : retirer des favoris</p></div><div class="list favs"></div>';
    var now = Date.now();
    function items() {
      var live = c.allLive.filter(function (x) { return App.isFav(x.url); });
      var movies = c.allMovies.filter(function (x) { return App.isFav(x.url); });
      var shows = c.allSeries.filter(function (x) { return App.isFav(x.key); });
      var out = [];
      if (live.length) out.push({ header: 'Chaînes' });
      live.forEach(function (x, i) { out.push({ kind: 'live', ch: x, list: live, i: i }); });
      if (movies.length) out.push({ header: 'Films' });
      movies.forEach(function (x) { out.push({ kind: 'movie', ch: x }); });
      if (shows.length) out.push({ header: 'Séries' });
      shows.forEach(function (x) { out.push({ kind: 'series', s: x }); });
      return out;
    }
    var zone = new ListZone(el.querySelector('.favs'), {
      rowHeight: 92,
      skip: function (it) { return !!it.header; },
      empty: 'Aucun favori. Maintiens OK sur une chaîne, un film ou une série pour l\'ajouter ici.',
      render: function (it) {
        if (it.header) return '<div class="section-title">' + esc(it.header) + '</div>';
        if (it.kind === 'live') return channelRow(it.ch, now);
        if (it.kind === 'movie') return mediaRow(it.ch.name, it.ch.logo, 'Film · ' + it.ch.group);
        return mediaRow(it.s.name, it.s.poster, 'Série · ' + seasonsLabel(it.s));
      },
      onEnter: function (it) {
        if (it.kind === 'live') App.play(it.list, it.i);
        else if (it.kind === 'movie') App.play([it.ch], 0);
        else if (it.kind === 'series') App.push(new SeriesScreen(it.s));
      },
      onLong: function (it) {
        if (it.kind === 'series') App.toggleFav(it.s.key, it.s.name);
        else if (it.ch) App.toggleFav(it.ch.url, it.ch.name);
      },
    });
    zone.setItems(items());
    return {
      el: el,
      zones: { favs: zone },
      neighbors: { favs: { left: 'nav' } },
      entry: 'favs',
      refresh: function () { now = Date.now(); zone.refresh(); },
    };
  },

  search: function () {
    var el = document.createElement('div');
    el.className = 'section single';
    el.innerHTML = '<div class="head"><h1>Rechercher</h1><p>Chaînes, films et séries</p></div><div class="list results"></div>';
    var query = App.searchQuery || '';
    var now = Date.now();
    var zone = new ListZone(el.querySelector('.results'), {
      rowHeight: 92,
      skip: function (it) { return !!it.header; },
      render: function (it) {
        if (it.field) return '<div class="search-field">🔍 ' + (query ? esc(query) : '<span class="faint">Chaîne, film ou série… (OK pour saisir)</span>') + '</div>';
        if (it.header) return '<div class="section-title">' + esc(it.header) + '</div>';
        if (it.kind === 'live') return channelRow(it.ch, now);
        if (it.kind === 'movie') return mediaRow(it.ch.name, it.ch.logo, 'Film · ' + it.ch.group);
        return mediaRow(it.s.name, it.s.poster, 'Série · ' + seasonsLabel(it.s));
      },
      onEnter: function (it) {
        if (it.field) {
          Modal.prompt('Rechercher', query, function (v) { query = App.searchQuery = v; run(); });
        } else if (it.kind === 'live') App.play(it.list, it.i);
        else if (it.kind === 'movie') App.play([it.ch], 0);
        else if (it.kind === 'series') App.push(new SeriesScreen(it.s));
      },
      onLong: function (it) {
        if (it.kind === 'series') App.toggleFav(it.s.key, it.s.name);
        else if (it.ch) App.toggleFav(it.ch.url, it.ch.name);
      },
    });
    function run() {
      var out = [{ field: true }];
      var q = query.trim().toLowerCase();
      if (q.length >= 2) {
        var c = App.content;
        var match = function (n) { return n.toLowerCase().indexOf(q) >= 0; };
        var rank = function (a, b) { return (a.name.toLowerCase().indexOf(q) === 0 ? 0 : 1) - (b.name.toLowerCase().indexOf(q) === 0 ? 0 : 1); };
        var live = c.allLive.filter(function (x) { return match(x.name); }).sort(rank).slice(0, 150);
        var movies = c.allMovies.filter(function (x) { return match(x.name); }).sort(rank).slice(0, 150);
        var shows = c.allSeries.filter(function (x) { return match(x.name); }).sort(rank).slice(0, 150);
        if (!live.length && !movies.length && !shows.length) out.push({ header: 'Aucun résultat pour « ' + query + ' »' });
        if (live.length) out.push({ header: 'Chaînes (' + live.length + ')' });
        live.forEach(function (x, i) { out.push({ kind: 'live', ch: x, list: live, i: i }); });
        if (movies.length) out.push({ header: 'Films (' + movies.length + ')' });
        movies.forEach(function (x) { out.push({ kind: 'movie', ch: x }); });
        if (shows.length) out.push({ header: 'Séries (' + shows.length + ')' });
        shows.forEach(function (x) { out.push({ kind: 'series', s: x }); });
      }
      zone.setItems(out, 0);
    }
    run();
    return {
      el: el,
      zones: { results: zone },
      neighbors: { results: { left: 'nav' } },
      entry: 'results',
      refresh: function () { now = Date.now(); zone.refresh(); },
    };
  },

  settings: function () {
    var el = document.createElement('div');
    el.className = 'section single';
    el.innerHTML = '<div class="head"><h1>Paramètres</h1><p>OK pour changer · OK maintenu sur une playlist : plus d\'options</p></div><div class="list settings"></div>';
    var zone = new ListZone(el.querySelector('.settings'), {
      rowHeight: 84,
      skip: function (it) { return !!it.header; },
      render: function (it) {
        if (it.header) return '<div class="section-title">' + esc(it.header) + '</div>';
        return '<div class="setting' + (it.highlight ? ' highlight' : '') + '"><div class="txt"><div class="t1">' + esc(it.title) + '</div>' +
          (it.sub ? '<div class="t2">' + esc(it.sub) + '</div>' : '') + '</div>' +
          (it.value ? '<div class="value">' + esc(it.value) + '</div>' : '') + '</div>';
      },
      onEnter: function (it) { if (it.action) it.action(); },
      onLong: function (it) { if (it.long) it.long(); },
    });
    function entries() {
      var s = App.settings, p = App.activePlaylist(), c = App.content;
      var out = [{ header: 'Playlists' }];
      s.playlists.forEach(function (pl) {
        var active = p && pl.id === p.id;
        out.push({
          title: pl.name, sub: hostOf(pl.url) + (active ? ' · OK : modifier' : ' · OK : utiliser cette playlist'),
          value: active ? '✓ Active' : '', highlight: active,
          action: function () { if (active) App.push(new EditorScreen(pl.id)); else App.setActive(pl.id); },
          long: function () {
            Modal.menu(pl.name, [
              { label: 'Utiliser cette playlist', action: function () { App.setActive(pl.id); } },
              { label: 'Modifier', action: function () { App.push(new EditorScreen(pl.id)); } },
              { label: 'Supprimer', action: function () { App.confirmDelete(pl); } },
            ], hostOf(pl.url));
          },
        });
      });
      out.push({ title: 'Ajouter une playlist', sub: 'Adresse M3U de ton abonnement IPTV', value: '›', action: function () { App.push(new EditorScreen(null)); } });
      if (p) {
        out.push({ header: 'Contenu' });
        var meta = Store.get('plmeta:' + p.id, {});
        out.push({
          title: App.load.loading ? (App.load.progress || 'Mise à jour…') : 'Actualiser la playlist',
          sub: 'Mise à jour ' + ago(meta.updated) + (c ? ' · ' + c.allLive.length + ' chaînes · ' + c.allMovies.length + ' films · ' + c.allSeries.length + ' séries' : '') +
            (App.load.error ? ' · ⚠ ' + App.load.error : ''),
          action: App.refreshPlaylist,
        });
        var em = Store.get('epgmeta:' + p.id, {});
        out.push({
          title: App.epgState.loading ? 'Guide TV : chargement…' : 'Actualiser le guide TV',
          sub: !App.epgState.url ? 'Aucun guide trouvé : ajoute une adresse XMLTV dans la playlist' :
            App.epgState.error ? '⚠ ' + App.epgState.error : 'Mis à jour ' + ago(em.updated) + ' · ' + App.guide.count + ' chaînes avec programme',
          action: function () { if (App.content) App.loadEpg(p, true); zone.refresh(); },
        });
      }
      out.push({ header: 'Lecture' });
      out.push({ title: 'Format de l\'image', value: ASPECTS[s.aspect], action: function () {
        var keys = Object.keys(ASPECTS);
        s.aspect = keys[(keys.indexOf(s.aspect) + 1) % keys.length];
        App.save(); Player.setAspect(s.aspect); rebuild();
      } });
      out.push({ title: 'Identifiant du lecteur (User-Agent)', sub: 'À changer si des chaînes refusent de se lancer', value: s.ua, action: function () {
        var keys = Object.keys(USER_AGENTS);
        s.ua = keys[(keys.indexOf(s.ua) + 1) % keys.length];
        App.save(); rebuild();
      } });
      out.push({ title: 'Au démarrage, reprendre la dernière chaîne', value: s.resumeLast ? 'Oui' : 'Non', action: function () {
        s.resumeLast = !s.resumeLast; App.save(); rebuild();
      } });
      out.push({ title: 'Effacer les chaînes récentes', action: function () {
        s.recents = []; s.lastChannel = null; App.save(); App.invalidate(['live']); toast('Historique effacé');
      } });
      out.push({ header: 'Mise à jour' });
      var u = App.update, v = window.APP_VERSION ? APP_VERSION.name : 'dev';
      var upd = { title: 'Rechercher une mise à jour', sub: 'Version installée : ' + v, action: function () { App.checkUpdate(false); } };
      if (u.status === 'checking') upd = { title: 'Recherche d\'une mise à jour…', sub: 'Version installée : ' + v };
      else if (u.status === 'uptodate') upd.sub = 'Tu as la dernière version (' + v + ')';
      else if (u.status === 'error') upd.sub = u.message;
      else if (u.status === 'available') upd = {
        title: 'Nouvelle version ' + u.latest.name + ' disponible', highlight: true,
        sub: 'Version installée : ' + v + ' · relance le script d\'installation sur ton PC (voir README)',
        action: function () { App.checkUpdate(false); },
      };
      out.push(upd);
      out.push({ header: 'À propos' });
      out.push({ title: 'IPTV pour TV Samsung', sub: 'github.com/' + GITHUB_REPO, value: v + (Player.isNative() ? '' : ' · navigateur') });
      return out;
    }
    function rebuild() { var i = zone.index; zone.setItems(entries(), i); if (zone.active) zone.render(); }
    zone.setItems(entries());
    return {
      el: el,
      zones: { settings: zone },
      neighbors: { settings: { left: 'nav' } },
      entry: 'settings',
      refresh: rebuild,
      onShow: function () { rebuild(); if (App.update.status === 'idle') App.checkUpdate(true); },
    };
  },
};

function mediaRow(title, image, sub) {
  return '<div class="ch media">' + artwork(image, title, 'thumb') +
    '<div class="txt"><div class="t1">' + esc(title) + '</div><div class="t2">' + esc(sub) + '</div></div></div>';
}

App.setActive = function (id) {
  App.settings.activeId = id;
  App.save();
  App.autoResumeDone = true;
  App.loadActive();
};

App.confirmDelete = function (pl) {
  Modal.menu('Supprimer « ' + pl.name + ' » ?', [
    { label: 'Annuler', action: function () { } },
    { label: 'Supprimer', action: function () { App.deletePlaylist(pl.id); } },
  ], 'La playlist et son guide TV seront retirés de la TV.');
};

App.deletePlaylist = function (id) {
  var s = App.settings;
  s.playlists = s.playlists.filter(function (p) { return p.id !== id; });
  if (s.activeId === id) s.activeId = s.playlists.length ? s.playlists[0].id : null;
  App.save();
  DB.del('pl:' + id); DB.del('epg:' + id);
  App.loadActive();
};

App.savePlaylist = function (id, name, url, epgUrl) {
  var s = App.settings;
  var existing = s.playlists.filter(function (p) { return p.id === id; })[0];
  var p = { id: id || uid(), name: name || 'Ma playlist', url: url.trim(), epgUrl: (epgUrl || '').trim() };
  if (existing) {
    if (existing.url !== p.url) { DB.del('pl:' + p.id); Store.set('plmeta:' + p.id, {}); }
    if (existing.epgUrl !== p.epgUrl) DB.del('epg:' + p.id);
    s.playlists = s.playlists.map(function (x) { return x.id === p.id ? p : x; });
  } else {
    s.playlists.push(p);
  }
  s.activeId = p.id;
  App.save();
  App.autoResumeDone = true;
  App.section = 'live';
  App.loadActive();
};

/* ================================================================== Écran : édition de playlist */

function EditorScreen(id) {
  var existing = App.settings.playlists.filter(function (p) { return p.id === id; })[0];
  var data = { name: existing ? existing.name : '', url: existing ? existing.url : '', epg: existing ? existing.epgUrl : '' };
  var el = document.createElement('div');
  el.className = 'screen editor';
  el.innerHTML = '<h1>' + (existing ? 'Modifier la playlist' : 'Nouvelle playlist') + '</h1>' +
    '<p class="sub">Astuce : avec l\'appli SmartThings sur ton téléphone, tu peux taper (ou coller) l\'adresse au clavier du téléphone.</p>' +
    '<div class="list fields"></div>';
  this.el = el;
  var self = this;
  function valid() { return /^https?:\/\//i.test(data.url); }
  function rows() {
    var auto = EPG.xtreamUrl(data.url);
    var out = [
      { title: 'Nom', value: data.name || 'Ma playlist', action: function () { Modal.prompt('Nom de la playlist', data.name, function (v) { data.name = v; zone.refresh(); }); } },
      { title: 'Adresse de la playlist M3U', sub: data.url || 'http://serveur:port/get.php?username=…&password=…&type=m3u_plus', highlight: !valid(),
        action: function () { Modal.prompt('Adresse de la playlist M3U', data.url, function (v) { data.url = v; zone.setItems(rows(), zone.index); }, 'Fournie par ton abonnement IPTV (lien M3U / m3u_plus).'); } },
      { title: 'Guide TV (XMLTV) — facultatif', sub: data.epg || (auto ? 'Automatique (compte Xtream détecté)' : 'Automatique (celui annoncé par la playlist)'),
        action: function () { Modal.prompt('Adresse du guide TV (XMLTV)', data.epg, function (v) { data.epg = v; zone.refresh(); }, 'Laisse vide pour le guide automatique.'); } },
      { title: '✓ Enregistrer', button: true, action: function () {
        if (!valid()) { toast('L\'adresse doit commencer par http:// ou https://'); return; }
        App.pop();
        App.savePlaylist(id, data.name, data.url, data.epg);
      } },
    ];
    if (existing) out.push({ title: 'Supprimer cette playlist', button: true, action: function () { App.pop(); App.confirmDelete(existing); } });
    out.push({ title: 'Annuler', button: true, action: function () { App.pop(); } });
    return out;
  }
  var zone = new ListZone(el.querySelector('.fields'), {
    rowHeight: 92,
    scrollbar: false,
    render: function (it) {
      return '<div class="setting' + (it.highlight ? ' highlight' : '') + (it.button ? ' as-button' : '') + '"><div class="txt"><div class="t1">' + esc(it.title) + '</div>' +
        (it.sub ? '<div class="t2">' + esc(it.sub) + '</div>' : '') + '</div>' + (it.value ? '<div class="value">' + esc(it.value) + '</div>' : '') + '</div>';
    },
    onEnter: function (it) { it.action(); },
  });
  zone.setItems(rows(), existing ? 0 : 1);
  this.zones = { fields: zone };
  this.neighbors = {};
  this.zone = 'fields';
  this.refresh = function () { zone.refresh(); };
}

/* ================================================================== Écran : détail d'une série */

function SeriesScreen(series) {
  var el = document.createElement('div');
  el.className = 'screen series';
  el.innerHTML = '<div class="left">' + artwork(series.poster, series.name, 'series-cover') +
    '<h1>' + esc(series.name) + '</h1><p class="sub">' + esc(seasonsLabel(series)) + '</p><p class="sub">' + esc(series.group) + '</p>' +
    '<div class="fav-row"></div></div>' +
    '<div class="right"><div class="seasons"></div><div class="list eps"></div></div>';
  this.el = el;
  var season = series.seasons[0] || 1;
  var eps = [];
  var fav = new RowZone(el.querySelector('.fav-row'), {
    render: function () { return App.isFav(series.key) ? '★ Retirer des favoris' : '☆ Ajouter aux favoris'; },
    onEnter: function () { App.toggleFav(series.key, series.name); fav.render(); },
  });
  fav.setItems([1]);
  var seasons = new RowZone(el.querySelector('.seasons'), {
    render: function (s) { return 'Saison ' + s; },
    isSelected: function (s) { return s === season; },
    onFocus: function (s) { if (s !== season) { season = s; showSeason(); } },
  });
  seasons.setItems(series.seasons.length > 1 ? series.seasons : [], 0);
  var list = new ListZone(el.querySelector('.eps'), {
    rowHeight: 84,
    render: function (e) {
      var title = e.name.replace(series.name, '')
        .replace(/^[\s._\-:|]*(S\d{1,2}[\s._\-]*E\d{1,4}|Saison\s*\d+[\s._\-,]*[ÉE]pisode\s*\d+)[\s._\-:|]*/i, '') || ('Épisode ' + e.episode);
      var p = App.positions.progress(e.url);
      return '<div class="episode"><span class="epnum">E' + pad2(e.episode) + '</span><div class="txt"><div class="t1">' + esc(title) + '</div>' +
        (p > 0 ? progressBar(p, 'mini') : '') + '</div>' + (p > 0 ? '<span class="pct">' + Math.round(p * 100) + ' %</span>' : '') + '</div>';
    },
    onEnter: function (e, i) { App.play(eps, i); },
  });
  function showSeason() {
    eps = series.episodes.filter(function (e) { return e.season === season; });
    list.setItems(eps, 0);
  }
  showSeason();
  this.zones = { fav: fav, seasons: seasons, eps: list };
  this.neighbors = {
    fav: { right: 'eps' },
    seasons: { down: 'eps', left: 'fav' },
    eps: { up: 'seasons', left: 'fav' },
  };
  this.zone = 'eps';
  this.refresh = function () { list.refresh(); fav.render(); };
  this.onShow = this.refresh;
}

/* ================================================================== Démarrage */

App.handleKey = (function () {
  var longTimer = null;
  var enterDown = false;
  function zoneOf(screen) { return screen.zones[screen.zone]; }

  return {
    down: function (e) {
      var code = e.keyCode;
      if (Modal.isOpen()) { e.preventDefault(); if (code !== Keys.ENTER || !e.repeat) Modal.key(code); return; }
      var screen = App.top();
      if (!screen) return;
      if (code === Keys.ENTER) {
        e.preventDefault();
        if (e.repeat || enterDown) return;
        enterDown = true;
        clearTimeout(longTimer);
        longTimer = setTimeout(function () {
          longTimer = null;
          if (screen.long) screen.long(); else { var z = zoneOf(screen); if (z) z.long(); }
        }, 650);
        return;
      }
      e.preventDefault();
      if (screen.key && screen.key(code)) return;
      if (Keys.isBack(code) || code === Keys.EXIT) { App.back(); return; }
      var zone = zoneOf(screen);
      if (!zone) return;
      var r = zone.key(code);
      if (typeof r === 'string') {
        var nb = screen.neighbors[screen.zone] && screen.neighbors[screen.zone][r];
        if (nb) App.focusZone(screen, nb);
      }
    },
    up: function (e) {
      if (e.keyCode !== Keys.ENTER) return;
      enterDown = false;
      if (!longTimer) return; // appui long déjà traité
      clearTimeout(longTimer);
      longTimer = null;
      if (Modal.isOpen()) return;
      var screen = App.top();
      if (!screen) return;
      if (screen.enter) screen.enter(); else { var z = zoneOf(screen); if (z) z.enter(); }
    },
  };
})();

App.start = function () {
  registerKeys();
  App.settings = Object.assign(App.defaults(), Store.get('settings', {}));
  document.addEventListener('keydown', App.handleKey.down);
  document.addEventListener('keyup', App.handleKey.up);
  App.buildHome();
  App.loadActive();
  // Programmes en cours : rafraîchis chaque minute.
  setInterval(App.refreshVisible, 60000);
  setTimeout(function () { App.checkUpdate(true); }, 5000);
};

window.addEventListener('load', App.start);
