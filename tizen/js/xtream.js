/*
 * Comptes Xtream Codes (serveur + identifiant + mot de passe) via player_api.php.
 * Beaucoup de fournisseurs ont fermé le téléchargement du M3U (get.php renvoie une erreur,
 * par exemple 884) mais l'API reste ouverte : c'est ce qu'utilisent les applis IPTV.
 */
'use strict';

var Xtream = (function () {

  function trimSlash(s) { return String(s || '').trim().replace(/\/+$/, ''); }

  /** Compte Xtream d'une playlist : saisi explicitement, ou déduit d'un lien get.php. */
  function fromPlaylist(p) {
    if (!p) return null;
    if (p.kind === 'xtream' && p.host && p.username) {
      var host = trimSlash(p.host);
      if (!/^https?:\/\//i.test(host)) host = 'http://' + host;
      return { host: host, user: p.username.trim(), pass: (p.password || '').trim(), explicit: true };
    }
    var m = /^(https?:\/\/[^\/?#]+)\/get\.php\?(.*)$/i.exec(String(p.url || '').trim());
    if (!m) return null;
    var params = {};
    m[2].split('&').forEach(function (kv) {
      var i = kv.indexOf('=');
      if (i > 0) { try { params[kv.slice(0, i)] = decodeURIComponent(kv.slice(i + 1)); } catch (e) { params[kv.slice(0, i)] = kv.slice(i + 1); } }
    });
    if (!params.username || !params.password) return null;
    return { host: m[1], user: params.username, pass: params.password, explicit: false };
  }

  function api(acc, action, extra) {
    var url = acc.host + '/player_api.php?username=' + encodeURIComponent(acc.user) + '&password=' + encodeURIComponent(acc.pass) +
      (action ? '&action=' + action : '') + (extra || '');
    return fetch(url, { cache: 'no-store' }).catch(function () {
      throw new Error('Serveur injoignable (' + acc.host + ')');
    }).then(function (res) {
      if (res.status === 401 || res.status === 403) throw new Error('Identifiant ou mot de passe refusé (' + res.status + ')');
      if (!res.ok) throw new Error('Le serveur refuse l\'accès (code ' + res.status + ')');
      return res.text();
    }).then(function (text) {
      try { return JSON.parse(text); } catch (e) { throw new Error('Réponse inattendue du serveur Xtream'); }
    });
  }

  /** Télécharge tout le catalogue (sans les épisodes, chargés à l'ouverture d'une série). */
  function download(acc, onProgress) {
    var raw = { host: acc.host, user: acc.user, pass: acc.pass };
    if (onProgress) onProgress('Connexion au compte…');
    return api(acc, null).then(function (info) {
      var u = info && info.user_info;
      if (!u || String(u.auth) !== '1') throw new Error('Identifiant ou mot de passe refusé');
      if (u.status && u.status !== 'Active') throw new Error('Abonnement ' + ({ Expired: 'expiré', Banned: 'banni', Disabled: 'désactivé' }[u.status] || u.status));
      raw.account = {
        expires: u.exp_date ? +u.exp_date * 1000 : null,
        maxConnections: u.max_connections,
        activeConnections: u.active_cons,
        formats: u.allowed_output_formats || [],
      };
      var steps = [
        ['liveCats', 'get_live_categories', 'Catégories des chaînes…'],
        ['live', 'get_live_streams', 'Chaînes…'],
        ['vodCats', 'get_vod_categories', 'Catégories des films…'],
        ['vod', 'get_vod_streams', 'Films…'],
        ['seriesCats', 'get_series_categories', 'Catégories des séries…'],
        ['series', 'get_series', 'Séries…'],
      ];
      return steps.reduce(function (chain, s) {
        return chain.then(function () {
          if (onProgress) onProgress(s[2]);
          return api(acc, s[1]).then(function (data) { raw[s[0]] = Array.isArray(data) ? data : []; })
            .catch(function (e) { if (s[0] === 'live') throw e; raw[s[0]] = []; });
        });
      }, Promise.resolve());
    }).then(function () { return raw; });
  }

  function catName(map, id) { return (id != null && map[String(id)]) || M3U.NO_GROUP; }

  function group(items, catList, keyOf) {
    var names = {};
    var order = [];
    (catList || []).forEach(function (c) { names[String(c.category_id)] = c.category_name; order.push(String(c.category_id)); });
    var byCat = {};
    items.forEach(function (it) {
      var id = keyOf(it);
      (byCat[id] = byCat[id] || []).push(it);
    });
    var out = [];
    order.forEach(function (id) { if (byCat[id]) { out.push({ name: names[id], items: byCat[id] }); delete byCat[id]; } });
    Object.keys(byCat).forEach(function (id) { out.push({ name: catName(names, id), items: byCat[id] }); });
    return out;
  }

  /** Transforme les données de l'API au même format que la lecture d'un M3U. */
  function build(raw) {
    var base = raw.host;
    var u = encodeURIComponent(raw.user), pw = encodeURIComponent(raw.pass);
    var formats = (raw.account && raw.account.formats) || [];
    var liveExt = !formats.length || formats.indexOf('ts') >= 0 ? 'ts' : 'm3u8';
    var liveCatNames = {}, vodCatNames = {}, seriesCatNames = {};
    (raw.liveCats || []).forEach(function (c) { liveCatNames[String(c.category_id)] = c.category_name; });
    (raw.vodCats || []).forEach(function (c) { vodCatNames[String(c.category_id)] = c.category_name; });
    (raw.seriesCats || []).forEach(function (c) { seriesCatNames[String(c.category_id)] = c.category_name; });

    var number = 0;
    var live = (raw.live || []).map(function (s) {
      number++;
      return {
        name: s.name || ('Chaîne ' + s.stream_id), url: base + '/live/' + u + '/' + pw + '/' + s.stream_id + '.' + liveExt,
        logo: s.stream_icon || null, group: catName(liveCatNames, s.category_id), tvgId: s.epg_channel_id || null,
        tvgName: s.name, type: 'live', number: +s.num || number, xtId: s.stream_id, catId: String(s.category_id),
      };
    });
    var movies = (raw.vod || []).map(function (s) {
      return {
        name: s.name || ('Film ' + s.stream_id), url: base + '/movie/' + u + '/' + pw + '/' + s.stream_id + '.' + (s.container_extension || 'mp4'),
        logo: s.stream_icon || null, group: catName(vodCatNames, s.category_id), type: 'movie', number: 0, catId: String(s.category_id),
      };
    });
    var shows = (raw.series || []).map(function (s) {
      var g = catName(seriesCatNames, s.category_id);
      return {
        key: 'xseries:' + s.series_id, name: s.name || ('Série ' + s.series_id), group: g, poster: s.cover || null,
        episodes: [], seasons: [], lazy: { seriesId: s.series_id }, catId: String(s.category_id),
        plot: s.plot || null,
      };
    });

    var content = {
      live: group(live, raw.liveCats, function (x) { return x.catId; }),
      movies: group(movies, raw.vodCats, function (x) { return x.catId; }),
      series: group(shows, raw.seriesCats, function (x) { return x.catId; }),
      headerEpg: base + '/xmltv.php?username=' + u + '&password=' + pw,
      xtream: { host: base, user: raw.user, pass: raw.pass },
      account: raw.account || null,
    };
    content.allLive = live;
    content.allMovies = movies;
    content.allSeries = shows;
    content.byKey = new Map();
    movies.forEach(function (m) { content.byKey.set(m.url, m); });
    live.forEach(function (l) { content.byKey.set(l.url, l); });
    content.seriesByKey = new Map();
    shows.forEach(function (s) { content.seriesByKey.set(s.key, s); });
    content.isEmpty = !live.length && !movies.length && !shows.length;
    return content;
  }

  /** Charge les saisons et épisodes d'une série (une seule fois). */
  function loadEpisodes(content, series) {
    if (!series.lazy || series.lazy.loaded) return Promise.resolve(series);
    var acc = { host: content.xtream.host, user: content.xtream.user, pass: content.xtream.pass };
    return api(acc, 'get_series_info', '&series_id=' + encodeURIComponent(series.lazy.seriesId)).then(function (data) {
      var episodes = [];
      var eps = (data && data.episodes) || {};
      Object.keys(eps).forEach(function (seasonKey) {
        (eps[seasonKey] || []).forEach(function (e, i) {
          var info = e.info || {};
          episodes.push({
            name: e.title || ('Épisode ' + (e.episode_num || i + 1)),
            url: acc.host + '/series/' + encodeURIComponent(acc.user) + '/' + encodeURIComponent(acc.pass) + '/' + e.id + '.' + (e.container_extension || 'mp4'),
            logo: info.movie_image || series.poster, group: series.group, type: 'series',
            seriesName: series.name, season: +(e.season || seasonKey) || 1, episode: +e.episode_num || i + 1, number: 0,
          });
        });
      });
      episodes.sort(function (a, b) { return a.season - b.season || a.episode - b.episode; });
      var seasons = [];
      episodes.forEach(function (e) { if (seasons.indexOf(e.season) < 0) seasons.push(e.season); });
      seasons.sort(function (a, b) { return a - b; });
      series.episodes = episodes;
      series.seasons = seasons;
      if (data && data.info && data.info.plot) series.plot = data.info.plot;
      series.lazy.loaded = true;
      return series;
    });
  }

  function b64(s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))); } catch (e) { try { return atob(s); } catch (e2) { return s; } }
  }

  /** Programmes d'une chaîne via l'API (quand le guide XMLTV est indisponible). */
  function shortEpg(content, channel) {
    var acc = { host: content.xtream.host, user: content.xtream.user, pass: content.xtream.pass };
    return api(acc, 'get_short_epg', '&stream_id=' + encodeURIComponent(channel.xtId) + '&limit=6').then(function (data) {
      return ((data && data.epg_listings) || []).map(function (p) {
        var start = p.start_timestamp ? +p.start_timestamp * 1000 : Date.parse(String(p.start).replace(' ', 'T'));
        var stop = p.stop_timestamp ? +p.stop_timestamp * 1000 : Date.parse(String(p.end).replace(' ', 'T'));
        return [start, stop, b64(p.title) || 'Sans titre', b64(p.description) || null];
      }).filter(function (p) { return p[0] && p[1]; }).sort(function (a, b) { return a[0] - b[0]; });
    });
  }

  return { fromPlaylist: fromPlaylist, download: download, build: build, loadEpisodes: loadEpisodes, shortEpg: shortEpg };
})();

if (typeof module !== 'undefined') module.exports = Xtream;
