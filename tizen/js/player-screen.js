/* Écran de lecture : direct (zapping, liste, bandeau) et films / séries (contrôles, reprise). */
'use strict';

function PlayerScreen(items, index) {
  this.isPlayer = true;
  this.items = items;
  this.index = index;
  this.retries = 0;
  this.error = null;
  this.panel = null;
  this.panelName = null;
  this.number = '';
  this.playing = false;
  this.zones = {};
  this.neighbors = {};
  this.zone = null;
  var el = this.el = document.createElement('div');
  el.className = 'screen player';
  el.innerHTML =
    '<div class="subtitle"></div><div class="number"></div><div class="error"></div>' +
    '<div class="banner"></div><div class="controls"></div><div class="panel"></div>';
  this.$ = function (sel) { return el.querySelector(sel); };
  var self = this;
  this.saveTimer = setInterval(function () { self.savePosition(); }, 15000);
  this.start();
}

PlayerScreen.prototype.channel = function () { return this.items[this.index]; };
PlayerScreen.prototype.isLive = function () { return this.channel().type === 'live'; };

PlayerScreen.prototype.onShow = function () { document.documentElement.classList.add('playing'); };

PlayerScreen.prototype.destroy = function () {
  this.savePosition();
  clearInterval(this.saveTimer);
  clearInterval(this.clockTimer);
  clearTimeout(this.bannerTimer);
  clearTimeout(this.controlsTimer);
  clearTimeout(this.numberTimer);
  clearTimeout(this.retryTimer);
  Player.stop();
  document.documentElement.classList.remove('playing');
};

PlayerScreen.prototype.savePosition = function () {
  var c = this.channel();
  if (!c || c.type === 'live') return;
  var d = Player.duration();
  if (d > 0) App.positions.save(c.url, Player.time(), d);
};

PlayerScreen.prototype.start = function () {
  var self = this;
  var c = this.channel();
  var live = c.type === 'live';
  this.setError(null);
  this.$('.subtitle').innerHTML = '';
  var startMs = live ? 0 : App.positions.get(c.url);
  Player.setAspect(App.settings.aspect);
  Player.open(c.url, { live: live, startMs: startMs, userAgent: c.ua || App.userAgent() }, {
    state: function (s) {
      self.playing = s === 'PLAYING';
      if (self.playing) { self.retries = 0; self.setError(null); }
      if (!live) self.renderControls();
    },
    buffering: function (b) { self.el.classList.toggle('buffering', !!b); },
    error: function (e) { self.onError(e); },
    ended: function () { self.onEnded(); },
    subtitle: function (t) { self.$('.subtitle').innerHTML = esc(t || '').replace(/\n/g, '<br>'); },
  });
  if (startMs > 0) toast('Reprise à ' + fmtDuration(startMs));
  App.addRecent(c);
  if (live) this.showBanner(); else this.showControls();
};

PlayerScreen.prototype.onError = function (code) {
  var self = this;
  this.el.classList.remove('buffering');
  if (this.retries < 3) {
    this.retries++;
    this.setError('Reconnexion… (' + this.retries + '/3)');
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(function () { self.start(); }, 1500 * this.retries);
    return;
  }
  var c = String(code || '');
  var msg = /CONNECTION|NETWORK|TIMEOUT/i.test(c) ? 'Connexion au flux impossible' :
    /NOT_SUPPORTED|FORMAT|CODEC|MEDIA_ERR_4/i.test(c) ? 'Format vidéo non pris en charge par la TV' :
    /INVALID_URI|NOT_FOUND/i.test(c) ? 'Flux introuvable' :
    /PERMISSION|FORBIDDEN|403|401/i.test(c) ? 'Flux refusé par le serveur (abonnement, trop d\'écrans ?)' :
    'Lecture impossible (' + c + ')';
  this.setError(msg, true);
};

PlayerScreen.prototype.setError = function (msg, final) {
  this.error = msg;
  this.errorFinal = !!final;
  var el = this.$('.error');
  if (!msg) { el.classList.remove('show'); return; }
  el.innerHTML = '<div class="small">' + esc(this.channel().name) + '</div><div>' + esc(msg) + '</div>' +
    (final ? '<div class="small">OK : réessayer' + (this.isLive() ? ' · ▲▼ : autre chaîne · Paramètres : changer d\'User-Agent' : '') + '</div>' : '');
  el.classList.add('show');
};

PlayerScreen.prototype.onEnded = function () {
  if (this.isLive()) { this.onError('ENDED'); return; }
  this.savePosition();
  if (this.index < this.items.length - 1) {
    toast('Épisode suivant');
    this.index++;
    this.start();
  } else {
    App.pop();
  }
};

PlayerScreen.prototype.zap = function (delta) {
  if (this.items.length < 2) return;
  this.savePosition();
  this.retries = 0;
  this.index = (this.index + delta + this.items.length) % this.items.length;
  this.start();
};

PlayerScreen.prototype.goTo = function (i) {
  if (i === this.index) return;
  this.savePosition();
  this.retries = 0;
  this.index = i;
  this.start();
};

/* ---------------------------------------------------------------- Bandeau (direct) */

PlayerScreen.prototype.showBanner = function () {
  var self = this;
  var c = this.channel();
  var now = Date.now();
  var cur = App.progNow(c, now, true), next = App.progNext(c, now);
  this.$('.banner').innerHTML = artwork(c.logo, c.name, 'banner-logo') +
    '<div class="txt"><div class="title">' + (c.number ? '<span class="num">' + c.number + '</span>' : '') + esc(c.name) +
    (App.isFav(c.url) ? ' <span class="star">★</span>' : '') + '</div>' +
    (cur ? '<div class="now">' + fmtTime(cur[0]) + ' – ' + fmtTime(cur[1]) + '  ' + esc(cur[2]) + '</div>' + progressBar(EPG.progress(cur, now), 'half') :
      '<div class="now faint">' + esc(c.group) + '</div>') +
    (next ? '<div class="next">Ensuite ' + fmtTime(next[0]) + '  ' + esc(next[2]) + '</div>' : '') +
    '<div class="hint">▲▼ chaînes · OK ou ◀ liste · OK maintenu : options · Retour : quitter</div></div>';
  this.$('.banner').classList.add('show');
  clearTimeout(this.bannerTimer);
  this.bannerTimer = setTimeout(function () { self.$('.banner').classList.remove('show'); }, 6000);
};

/** Programme reçu entre-temps (guide court Xtream) : on met à jour le bandeau affiché. */
PlayerScreen.prototype.refresh = function () {
  if (!this.isLive() || !this.$('.banner').classList.contains('show')) return;
  var timer = this.bannerTimer;
  this.showBanner();
  clearTimeout(this.bannerTimer);
  this.bannerTimer = timer;
};

PlayerScreen.prototype.toggleBanner = function () {
  if (this.$('.banner').classList.contains('show')) this.$('.banner').classList.remove('show');
  else this.showBanner();
};

/* ---------------------------------------------------------------- Contrôles (films / séries) */

PlayerScreen.prototype.showControls = function () {
  var self = this;
  this.$('.controls').classList.add('show');
  this.renderControls();
  clearInterval(this.clockTimer);
  this.clockTimer = setInterval(function () { self.renderControls(); }, 500);
  clearTimeout(this.controlsTimer);
  this.controlsTimer = setTimeout(function () {
    if (Player.isPlaying()) {
      self.$('.controls').classList.remove('show');
      clearInterval(self.clockTimer);
    }
  }, 5000);
};

PlayerScreen.prototype.renderControls = function () {
  if (this.isLive()) return;
  var t = Player.time(), d = Player.duration();
  var c = this.channel();
  this.$('.controls').innerHTML = '<div class="title">' + esc(c.name) + '</div>' +
    progressBar(d > 0 ? t / d : 0, 'big') +
    '<div class="times"><span>' + (Player.isPlaying() ? '▶ ' : '❚❚ ') + fmtDuration(t) + '</span><span>' + (d > 0 ? fmtDuration(d) : '') + '</span></div>' +
    '<div class="hint">OK : pause · ◀ −10 s · ▶ +30 s · ▼ : audio / sous-titres' + (this.index < this.items.length - 1 ? ' · ⏭ épisode suivant' : '') + '</div>';
  if (!Player.isPlaying()) this.$('.controls').classList.add('show');
};

PlayerScreen.prototype.seek = function (deltaMs) {
  Player.seekTo(Player.time() + deltaMs);
  this.showControls();
};

/* ---------------------------------------------------------------- Panneaux : liste, options */

PlayerScreen.prototype.closePanel = function () {
  this.panel = null;
  this.panelName = null;
  this.$('.panel').className = 'panel';
  this.$('.panel').innerHTML = '';
};

PlayerScreen.prototype.openList = function () {
  var self = this;
  var el = this.$('.panel');
  el.className = 'panel show left';
  el.innerHTML = '<h2>' + esc(this.channel().group) + '</h2><p class="sub">OK : regarder · OK maintenu : favori · Retour : fermer</p><div class="list"></div>';
  var now = Date.now();
  var zone = new ListZone(el.querySelector('.list'), {
    rowHeight: 84,
    render: function (c, i) { return channelRow(c, now).replace('class="ch"', 'class="ch' + (i === self.index ? ' playing' : '') + '"'); },
    onEnter: function (c, i) { self.closePanel(); self.goTo(i); },
    onLong: function (c) { App.toggleFav(c.url, c.name); zone.refresh(); },
  });
  zone.setItems(this.items, this.index);
  zone.focus();
  this.panel = zone;
  this.panelName = 'list';
  this.$('.banner').classList.remove('show');
};

PlayerScreen.prototype.openOptions = function () {
  var self = this;
  var el = this.$('.panel');
  el.className = 'panel show right';
  el.innerHTML = '<h2>Options</h2><p class="sub">OK pour changer · Retour pour fermer</p><div class="list"></div>';
  function rows() {
    var c = self.channel();
    var audio = Player.tracks('AUDIO');
    var subs = Player.tracks('TEXT');
    var curAudio = audio.filter(function (t) { return t.selected; })[0];
    var curSub = Player.subtitlesOn() ? subs.filter(function (t) { return t.selected; })[0] : null;
    return [
      { title: 'Format de l\'image', value: ASPECTS[App.settings.aspect], action: function () {
        var keys = Object.keys(ASPECTS);
        App.settings.aspect = keys[(keys.indexOf(App.settings.aspect) + 1) % keys.length];
        App.save();
        Player.setAspect(App.settings.aspect);
      } },
      { title: 'Piste audio', value: curAudio ? curAudio.label : 'Par défaut', sub: audio.length > 1 ? audio.length + ' pistes disponibles' : 'Une seule piste',
        action: function () {
          if (audio.length < 2) return;
          var i = audio.indexOf(curAudio);
          Player.selectTrack('AUDIO', audio[(i + 1) % audio.length].index);
        } },
      { title: 'Sous-titres', value: curSub ? curSub.label : 'Désactivés', sub: subs.length ? subs.length + ' disponibles' : 'Aucun sous-titre',
        action: function () {
          if (!subs.length) return;
          var i = curSub ? subs.indexOf(curSub) : -1;
          Player.setSubtitles(i + 1 >= subs.length ? null : subs[i + 1].index);
        } },
      { title: App.isFav(c.url) ? 'Retirer des favoris' : 'Ajouter aux favoris', action: function () { App.toggleFav(c.url, c.name); } },
    ];
  }
  var zone = new ListZone(el.querySelector('.list'), {
    rowHeight: 84,
    scrollbar: false,
    render: function (it) {
      return '<div class="setting"><div class="txt"><div class="t1">' + esc(it.title) + '</div>' + (it.sub ? '<div class="t2">' + esc(it.sub) + '</div>' : '') +
        '</div>' + (it.value ? '<div class="value">' + esc(it.value) + '</div>' : '') + '</div>';
    },
    onEnter: function (it) { it.action(); setTimeout(function () { zone.setItems(rows(), zone.index); zone.render(); }, 300); },
  });
  zone.setItems(rows());
  zone.focus();
  this.panel = zone;
  this.panelName = 'options';
  this.$('.controls').classList.remove('show');
  this.$('.banner').classList.remove('show');
};

/* ---------------------------------------------------------------- Touches */

PlayerScreen.prototype.back = function () {
  if (this.panel) { this.closePanel(); return true; }
  return false;
};

PlayerScreen.prototype.key = function (code) {
  var self = this;
  if (this.panel) {
    if (Keys.isBack(code)) { this.closePanel(); return true; }
    this.panel.key(code);
    return true;
  }
  if (Keys.isBack(code) || code === Keys.EXIT) return false;
  var live = this.isLive();
  if (live && Keys.isDigit(code)) {
    if (this.number.length < 5) this.number += String(code - 48);
    this.$('.number').textContent = this.number;
    this.$('.number').classList.add('show');
    clearTimeout(this.numberTimer);
    this.numberTimer = setTimeout(function () { self.jumpToNumber(); }, 1500);
    return true;
  }
  if (live) {
    switch (code) {
      case Keys.UP: case Keys.CH_UP: this.zap(1); break;
      case Keys.DOWN: case Keys.CH_DOWN: this.zap(-1); break;
      case Keys.LEFT: this.openList(); break;
      case Keys.RIGHT: case Keys.INFO: this.toggleBanner(); break;
      case Keys.PLAY_PAUSE: case Keys.PLAY: case Keys.PAUSE: Player.toggle(); break;
      case Keys.RED: case Keys.GREEN: case Keys.YELLOW: case Keys.BLUE: this.openOptions(); break;
      default: return true;
    }
    return true;
  }
  switch (code) {
    case Keys.LEFT: case Keys.RW: this.seek(-10000); break;
    case Keys.RIGHT: case Keys.FF: this.seek(30000); break;
    case Keys.UP: this.showControls(); break;
    case Keys.DOWN: case Keys.RED: case Keys.GREEN: case Keys.YELLOW: case Keys.BLUE: this.openOptions(); break;
    case Keys.PLAY_PAUSE: case Keys.PLAY: case Keys.PAUSE: Player.toggle(); this.showControls(); break;
    case Keys.NEXT: if (this.index < this.items.length - 1) this.goTo(this.index + 1); break;
    case Keys.PREV: if (this.index > 0) this.goTo(this.index - 1); break;
    default: return true;
  }
  return true;
};

PlayerScreen.prototype.enter = function () {
  if (this.panel) { this.panel.enter(); return; }
  if (this.error && this.errorFinal) { this.retries = 0; this.start(); return; }
  if (this.isLive()) this.openList();
  else { Player.toggle(); this.showControls(); }
};

PlayerScreen.prototype.long = function () {
  if (this.panel) { this.panel.long(); return; }
  this.openOptions();
};

PlayerScreen.prototype.jumpToNumber = function () {
  var n = parseInt(this.number, 10);
  this.number = '';
  this.$('.number').classList.remove('show');
  if (!n) return;
  for (var i = 0; i < this.items.length; i++) {
    if (this.items[i].number === n) { this.goTo(i); return; }
  }
  var target = App.content && App.content.allLive.filter(function (c) { return c.number === n; })[0];
  if (!target) { toast('Pas de chaîne n° ' + n); return; }
  var cat = App.content.live.filter(function (g) { return g.name === target.group; })[0];
  this.items = cat ? cat.items : [target];
  this.index = -1;
  this.goTo(this.items.indexOf(target));
};
