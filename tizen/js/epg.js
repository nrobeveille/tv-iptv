/*
 * Guide TV (XMLTV). Le fichier peut peser des dizaines de Mo : on le lit au fil de l'eau
 * (sans construire de DOM), on décompresse le gzip, et on ne garde que les chaînes de la
 * playlist, sur une fenêtre de -3 h à +36 h.
 */
'use strict';

var EPG = (function () {
  var HOUR = 3600000;

  function normalize(name) {
    var s = String(name || '').toLowerCase();
    try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) { }
    return s.replace(/^[a-z]{2,3}\s*[:|\-]\s*/, '')
      .replace(/[^a-z0-9]/g, '')
      .replace(/(fhd|uhd|hd|sd|4k|hevc|h265|backup)+$/, '');
  }

  /** Format XMLTV : 20261109203000 +0100 */
  function parseTime(v) {
    if (!v) return null;
    var t = v.trim();
    if (t.length < 12) return null;
    var y = +t.slice(0, 4), mo = +t.slice(4, 6), d = +t.slice(6, 8), h = +t.slice(8, 10), mi = +t.slice(10, 12);
    var hasSec = /^\d\d$/.test(t.slice(12, 14));
    var s = hasSec ? +t.slice(12, 14) : 0;
    var rest = t.slice(hasSec ? 14 : 12).trim();
    var offset = 0;
    if (/^[+-]\d{4}/.test(rest)) {
      offset = (rest[0] === '-' ? -1 : 1) * (+rest.slice(1, 3) * 3600 + +rest.slice(3, 5) * 60);
    }
    var ms = Date.UTC(y, mo - 1, d, h, mi, s) - offset * 1000;
    return isNaN(ms) ? null : ms;
  }

  function decode(s) {
    return s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
      .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(+n); })
      .replace(/&#x([0-9a-f]+);/gi, function (_, n) { return String.fromCharCode(parseInt(n, 16)); })
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
      .replace(/&amp;/g, '&').trim();
  }

  function attr(tag, name) {
    var m = new RegExp('\\b' + name + '\\s*=\\s*"([^"]*)"').exec(tag) || new RegExp('\\b' + name + "\\s*=\\s*'([^']*)'").exec(tag);
    return m ? decode(m[1]) : null;
  }

  function firstText(block, name) {
    var m = new RegExp('<' + name + '\\b[^>]*>([\\s\\S]*?)</' + name + '>').exec(block);
    return m ? decode(m[1]) : null;
  }

  /** Analyseur incrémental : on lui donne le texte morceau par morceau. */
  function Parser(content, now) {
    now = now || Date.now();
    this.windowStart = now - 3 * HOUR;
    this.windowEnd = now + 36 * HOUR;
    this.buf = '';
    this.result = {}; // clé de chaîne (url) → [[début, fin, titre, description], …]
    this.targets = {}; // id du guide → [clés de chaînes]
    this.matched = {};
    this.byTvgId = {};
    this.byName = {};
    var self = this;
    content.allLive.forEach(function (c) {
      if (c.tvgId) (self.byTvgId[c.tvgId.toLowerCase()] = self.byTvgId[c.tvgId.toLowerCase()] || []).push(c.url);
      [c.tvgName, c.name].forEach(function (n) {
        var k = n && normalize(n);
        if (k) {
          var list = self.byName[k] = self.byName[k] || [];
          if (list.indexOf(c.url) < 0) list.push(c.url);
        }
      });
    });
  }

  Parser.prototype.link = function (id, names) {
    var set = [];
    var self = this;
    (this.byTvgId[id.toLowerCase()] || []).forEach(function (k) { if (set.indexOf(k) < 0) set.push(k); });
    names.forEach(function (n) {
      var k = normalize(n);
      if (!k) return;
      (self.byName[k] || []).forEach(function (key) { if (!self.matched[key] && set.indexOf(key) < 0) set.push(key); });
    });
    if (set.length) {
      this.targets[id] = (this.targets[id] || []).concat(set);
      set.forEach(function (k) { self.matched[k] = true; });
    }
  };

  Parser.prototype.feed = function (chunk) {
    this.buf += chunk;
    var buf = this.buf;
    var pos = 0;
    while (true) {
      var lt = buf.indexOf('<', pos);
      if (lt < 0) { pos = buf.length; break; }
      var isProg = buf.startsWith('<programme', lt);
      var isChan = !isProg && buf.startsWith('<channel', lt);
      if (!isProg && !isChan) {
        // Balise sans intérêt (<tv>, <?xml…>) : on la saute si elle est complète.
        var gt = buf.indexOf('>', lt);
        if (gt < 0) { pos = lt; break; }
        pos = gt + 1;
        continue;
      }
      var endTag = isProg ? '</programme>' : '</channel>';
      var headEnd = buf.indexOf('>', lt);
      if (headEnd < 0) { pos = lt; break; }
      var selfClosing = buf.charCodeAt(headEnd - 1) === 47;
      var end = selfClosing ? headEnd + 1 : buf.indexOf(endTag, headEnd);
      if (end < 0) { pos = lt; break; }
      var blockEnd = selfClosing ? end : end + endTag.length;
      var head = buf.slice(lt, headEnd + 1);
      if (isChan) {
        var id = attr(head, 'id');
        if (id) {
          var names = [];
          var re = /<display-name\b[^>]*>([\s\S]*?)<\/display-name>/g, m;
          var block = buf.slice(headEnd, blockEnd);
          while ((m = re.exec(block))) names.push(decode(m[1]));
          this.link(id, names);
        }
      } else {
        var ch = attr(head, 'channel');
        var keys = ch && this.targets[ch];
        if (keys) {
          var s = parseTime(attr(head, 'start')), e = parseTime(attr(head, 'stop'));
          if (s != null && e != null && e > this.windowStart && s < this.windowEnd) {
            var body = buf.slice(headEnd, blockEnd);
            var title = firstText(body, 'title') || 'Sans titre';
            var desc = firstText(body, 'desc');
            var p = [s, e, title, desc ? desc.slice(0, 400) : null];
            for (var i = 0; i < keys.length; i++) (this.result[keys[i]] = this.result[keys[i]] || []).push(p);
          }
        }
      }
      pos = blockEnd;
    }
    this.buf = buf.slice(pos);
  };

  Parser.prototype.finish = function () {
    var r = this.result;
    Object.keys(r).forEach(function (k) { r[k].sort(function (a, b) { return a[0] - b[0]; }); });
    return r;
  };

  /** Télécharge et analyse le guide. onProgress(octets) pendant le téléchargement. */
  function download(url, content, onProgress) {
    return fetch(url, { cache: 'no-store' }).then(function (res) {
      if (!res.ok) throw new Error('Guide TV : erreur du serveur (' + res.status + ')');
      var reader = res.body.getReader();
      var parser = new Parser(content);
      var total = 0;
      return reader.read().then(function (first) {
        if (first.done) throw new Error('Guide TV vide');
        var bytes = first.value;
        var gz = bytes[0] === 0x1f && bytes[1] === 0x8b;
        var source = new ReadableStream({
          start: function (c) { c.enqueue(bytes); },
          pull: function (c) {
            return reader.read().then(function (r) {
              if (r.done) c.close();
              else {
                total += r.value.length;
                if (onProgress) onProgress(total);
                c.enqueue(r.value);
              }
            });
          },
        });
        if (gz) {
          if (typeof DecompressionStream === 'undefined') throw new Error('Guide TV compressé (.gz) non pris en charge par cette TV');
          source = source.pipeThrough(new DecompressionStream('gzip'));
        }
        var textReader = source.pipeThrough(new TextDecoderStream()).getReader();
        function pump() {
          return textReader.read().then(function (r) {
            if (r.done) return parser.finish();
            parser.feed(r.value);
            return pump();
          });
        }
        return pump();
      });
    });
  }

  function xtreamUrl(playlistUrl) {
    var m = /^(https?:\/\/[^\/]+)\/get\.php\?(.*)$/i.exec(String(playlistUrl || '').trim());
    if (!m) return null;
    var params = {};
    m[2].split('&').forEach(function (p) { var i = p.indexOf('='); if (i > 0) params[p.slice(0, i)] = p.slice(i + 1); });
    if (!params.username || !params.password) return null;
    return m[1] + '/xmltv.php?username=' + params.username + '&password=' + params.password;
  }

  /** Guide chargé : recherche du programme en cours / à venir. */
  function Guide(data) { this.data = data || {}; this.count = Object.keys(this.data).length; }
  Guide.prototype.list = function (ch) { return (ch && this.data[ch.url]) || []; };
  Guide.prototype.now = function (ch, t) {
    t = t || Date.now();
    var l = this.list(ch);
    for (var i = 0; i < l.length; i++) if (t >= l[i][0] && t < l[i][1]) return l[i];
    return null;
  };
  Guide.prototype.next = function (ch, t) {
    t = t || Date.now();
    var l = this.list(ch);
    for (var i = 0; i < l.length; i++) if (l[i][0] >= t) return l[i];
    return null;
  };
  Guide.prototype.upcoming = function (ch, n, t) {
    t = t || Date.now();
    return this.list(ch).filter(function (p) { return p[1] > t; }).slice(0, n);
  };

  function progress(p, t) {
    t = t || Date.now();
    return p[1] <= p[0] ? 0 : Math.max(0, Math.min(1, (t - p[0]) / (p[1] - p[0])));
  }

  return { Parser: Parser, Guide: Guide, download: download, xtreamUrl: xtreamUrl, parseTime: parseTime, normalize: normalize, progress: progress };
})();

if (typeof module !== 'undefined') module.exports = EPG;
