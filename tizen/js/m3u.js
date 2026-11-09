/*
 * Lecture d'une playlist M3U « étendue » (format IPTV).
 * Les comptes Xtream exportés en M3U mélangent chaînes, films (/movie/) et épisodes (/series/) :
 * on les répartit automatiquement, et on regroupe les épisodes par série et par saison.
 */
'use strict';

var M3U = (function () {
  var ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  var EP_SXXEXX = /^(.*?)[\s._\-:|]*\bS(\d{1,2})[\s._\-]*E(\d{1,4})\b/i;
  var EP_FR = /^(.*?)[\s._\-:|]*\bSaison\s*(\d{1,2})[\s._\-,]*[ÉE]pisode\s*(\d{1,4})/i;
  var VIDEO_EXT = { mp4: 1, mkv: 1, avi: 1, mov: 1, wmv: 1, flv: 1, m4v: 1, webm: 1, mpg: 1, mpeg: 1 };
  var NO_GROUP = 'Sans catégorie';

  function titleStart(line) {
    var inQuotes = false;
    for (var i = 0; i < line.length; i++) {
      var c = line.charCodeAt(i);
      if (c === 34) inQuotes = !inQuotes;
      else if (c === 44 && !inQuotes) return i;
    }
    return -1;
  }

  function attributes(line) {
    var start = titleStart(line);
    var head = start >= 0 ? line.slice(0, start) : line;
    var map = {};
    var m;
    ATTR.lastIndex = 0;
    while ((m = ATTR.exec(head))) map[m[1].toLowerCase()] = m[2];
    return map;
  }

  function typeOf(url, title) {
    var path = url.split('?')[0].toLowerCase();
    if (path.indexOf('/movie/') >= 0 || path.indexOf('/movies/') >= 0) return 'movie';
    if (path.indexOf('/series/') >= 0) return 'series';
    var file = path.slice(path.lastIndexOf('/') + 1);
    var dot = file.lastIndexOf('.');
    var ext = dot >= 0 ? file.slice(dot + 1) : '';
    if (VIDEO_EXT[ext]) return EP_SXXEXX.test(title) || EP_FR.test(title) ? 'series' : 'movie';
    return 'live';
  }

  function episodeInfo(ch) {
    var m = EP_SXXEXX.exec(ch.name) || EP_FR.exec(ch.name);
    if (m) {
      var name = m[1].trim().replace(/[\-_.:| ]+$/, '') || ch.name;
      ch.seriesName = name;
      ch.season = parseInt(m[2], 10) || 1;
      ch.episode = parseInt(m[3], 10) || 0;
    } else {
      ch.seriesName = ch.name;
      ch.season = 1;
      ch.episode = 0;
    }
    return ch;
  }

  function nonEmpty(v) { return v && v.trim() ? v.trim() : null; }

  /** Analyse le texte d'une playlist et renvoie le contenu prêt à afficher. */
  function parse(text) {
    var live = new Map(), movies = new Map(), series = new Map();
    var headerEpg = null;
    var info = null, group = null, ua = null, ref = null;
    var liveNumber = 0;

    var pos = 0, len = text.length;
    if (text.charCodeAt(0) === 0xfeff) pos = 1;
    while (pos < len) {
      var nl = text.indexOf('\n', pos);
      if (nl < 0) nl = len;
      var line = text.slice(pos, nl).trim();
      pos = nl + 1;
      if (!line) continue;

      if (line.charCodeAt(0) === 35) { // '#'
        var upper = line.slice(0, 12).toUpperCase();
        if (upper.indexOf('#EXTM3U') === 0) {
          var a = attributes(line);
          var e = a['url-tvg'] || a['x-tvg-url'] || a['tvg-url'];
          if (e) headerEpg = e.split(',')[0].trim() || null;
        } else if (upper.indexOf('#EXTINF') === 0) {
          info = line;
        } else if (upper.indexOf('#EXTGRP:') === 0) {
          group = line.slice(8).trim();
        } else if (upper.indexOf('#EXTVLCOPT:') === 0) {
          var opt = line.slice(11);
          var k = opt.split('=')[0].trim().toLowerCase();
          var v = opt.slice(opt.indexOf('=') + 1).trim();
          if (k === 'http-user-agent') ua = v;
          else if (k === 'http-referrer' || k === 'http-referer') ref = v;
        }
        continue;
      }

      var url = line;
      var attrs = info ? attributes(info) : {};
      var ts = info ? titleStart(info) : -1;
      var title = (ts >= 0 ? info.slice(ts + 1).trim() : '') || nonEmpty(attrs['tvg-name']) ||
        url.split('?')[0].slice(url.split('?')[0].lastIndexOf('/') + 1);
      var g = nonEmpty(attrs['group-title']) || nonEmpty(group) || NO_GROUP;
      var type = typeOf(url, title);
      var ch = {
        name: title,
        url: url,
        logo: nonEmpty(attrs['tvg-logo']),
        group: g,
        tvgId: nonEmpty(attrs['tvg-id']),
        tvgName: nonEmpty(attrs['tvg-name']),
        type: type,
        ua: ua || nonEmpty(attrs['user-agent']),
        ref: ref || nonEmpty(attrs['referrer']),
        number: 0,
      };
      if (type === 'live') {
        ch.number = ++liveNumber;
        if (!live.has(g)) live.set(g, []);
        live.get(g).push(ch);
      } else if (type === 'movie') {
        if (!movies.has(g)) movies.set(g, []);
        movies.get(g).push(ch);
      } else {
        episodeInfo(ch);
        if (!series.has(g)) series.set(g, new Map());
        var shows = series.get(g);
        if (!shows.has(ch.seriesName)) shows.set(ch.seriesName, []);
        shows.get(ch.seriesName).push(ch);
      }
      info = null; group = null; ua = null; ref = null;
    }

    function cats(map) {
      var out = [];
      map.forEach(function (items, name) { out.push({ name: name, items: items }); });
      return out;
    }

    var seriesCats = [];
    series.forEach(function (shows, g) {
      var items = [];
      shows.forEach(function (episodes, name) {
        var counter = 0;
        episodes.forEach(function (e) { if (!e.episode) e.episode = ++counter; });
        episodes.sort(function (a, b) { return a.season - b.season || a.episode - b.episode; });
        var seasons = [];
        episodes.forEach(function (e) { if (seasons.indexOf(e.season) < 0) seasons.push(e.season); });
        seasons.sort(function (a, b) { return a - b; });
        var poster = null;
        for (var i = 0; i < episodes.length && !poster; i++) poster = episodes[i].logo;
        items.push({ key: 'series:' + g + ':' + name, name: name, group: g, poster: poster, episodes: episodes, seasons: seasons });
      });
      seriesCats.push({ name: g, items: items });
    });

    return build(cats(live), cats(movies), seriesCats, headerEpg);
  }

  function build(live, movies, series, headerEpg) {
    var c = { live: live, movies: movies, series: series, headerEpg: headerEpg };
    c.allLive = [].concat.apply([], live.map(function (x) { return x.items; }));
    c.allMovies = [].concat.apply([], movies.map(function (x) { return x.items; }));
    c.allSeries = [].concat.apply([], series.map(function (x) { return x.items; }));
    c.byKey = new Map();
    c.allMovies.forEach(function (m) { c.byKey.set(m.url, m); });
    c.allLive.forEach(function (l) { c.byKey.set(l.url, l); });
    c.seriesByKey = new Map();
    c.allSeries.forEach(function (s) { c.seriesByKey.set(s.key, s); });
    c.isEmpty = !c.allLive.length && !c.allMovies.length && !c.allSeries.length;
    return c;
  }

  return { parse: parse, NO_GROUP: NO_GROUP };
})();

// Pour les tests sous Node.
if (typeof module !== 'undefined') module.exports = M3U;
