/* Outils communs : touches de la télécommande, stockage, formatage, notifications. */
'use strict';

var Keys = {
  LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
  BACK: 10009, ESC: 27, BACKSPACE: 8, EXIT: 10182,
  PAGE_UP: 33, PAGE_DOWN: 34,
  CH_UP: 427, CH_DOWN: 428,
  PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412, NEXT: 10233, PREV: 10232,
  RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457,
  IME_DONE: 65376, IME_CANCEL: 65385,
  isDigit: function (c) { return c >= 48 && c <= 57; },
  isBack: function (c) { return c === 10009 || c === 27 || c === 8; },
};

/** Déclare les touches « spéciales » pour les recevoir (sinon la TV les garde pour elle). */
function registerKeys() {
  var names = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
    'MediaTrackPrevious', 'MediaTrackNext', 'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green',
    'ColorF2Yellow', 'ColorF3Blue', 'Info', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
  try {
    if (window.tizen && tizen.tvinputdevice) {
      if (tizen.tvinputdevice.registerKeyBatch) tizen.tvinputdevice.registerKeyBatch(names);
      else names.forEach(function (n) { try { tizen.tvinputdevice.registerKey(n); } catch (e) { } });
    }
  } catch (e) { console.warn('registerKeys', e); }
}

function isTizen() { return !!(window.tizen && window.webapis && window.webapis.avplay); }

function exitApp() {
  try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.close(); }
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function pad2(n) { return (n < 10 ? '0' : '') + n; }

function fmtTime(ms) {
  var d = new Date(ms);
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

function fmtDuration(ms) {
  var t = Math.max(0, Math.floor(ms / 1000));
  var h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  return (h > 0 ? h + ':' + pad2(m) : m) + ':' + pad2(s);
}

function ago(time) {
  if (!time) return 'jamais';
  var minutes = Math.floor((Date.now() - time) / 60000);
  if (minutes < 1) return "à l'instant";
  if (minutes < 60) return 'il y a ' + minutes + ' min';
  if (minutes < 48 * 60) return 'il y a ' + Math.floor(minutes / 60) + ' h';
  return 'il y a ' + Math.floor(minutes / 1440) + ' j';
}

function formatSize(bytes) {
  return bytes > 1048576 ? (bytes / 1048576).toFixed(1).replace('.', ',') + ' Mo' : Math.round(bytes / 1024) + ' Ko';
}

function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 8); }

function initials(name) {
  var clean = String(name || '').replace(/^[A-Za-z]{2,3}\s*[:|\-]\s*/, '').trim();
  var words = clean.split(/[\s\-_.]+/).filter(function (w) { return w && !/^(hd|fhd|uhd|sd|4k|hevc)$/i.test(w); });
  if (!words.length) return '?';
  if (words[0].length <= 4 || words.length === 1) return words[0].slice(0, 4).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

/** Image (logo, affiche) avec les initiales en dessous si elle ne charge pas. */
function artwork(url, name, cls) {
  var img = url ? '<img src="' + esc(url) + '" loading="lazy" onerror="this.remove()">' : '';
  return '<div class="art ' + (cls || '') + '"><span>' + esc(initials(name)) + '</span>' + img + '</div>';
}

function progressBar(fraction, cls) {
  var p = Math.max(0, Math.min(1, fraction || 0)) * 100;
  return '<div class="bar ' + (cls || '') + '"><div style="width:' + p.toFixed(1) + '%"></div></div>';
}

function hostOf(url) {
  var m = /^(https?:\/\/[^\/?]+)/i.exec(String(url || '').trim());
  return m ? m[1] + '/…' : url;
}

/** Réglages et petites données : localStorage (JSON). */
var Store = {
  get: function (key, fallback) {
    try {
      var v = localStorage.getItem(key);
      return v == null ? fallback : JSON.parse(v);
    } catch (e) { return fallback; }
  },
  set: function (key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { console.warn('Store.set', key, e); }
  },
};

/** Gros contenus (playlist, guide TV) : IndexedDB, clé → valeur. */
var DB = (function () {
  var dbPromise = null;
  function open() {
    if (!dbPromise) {
      dbPromise = new Promise(function (resolve, reject) {
        var req = indexedDB.open('tviptv', 1);
        req.onupgradeneeded = function () { req.result.createObjectStore('kv'); };
        req.onsuccess = function () { resolve(req.result); };
        req.onerror = function () { reject(req.error); };
      });
    }
    return dbPromise;
  }
  function run(mode, fn) {
    return open().then(function (db) {
      return new Promise(function (resolve, reject) {
        var tx = db.transaction('kv', mode);
        var req = fn(tx.objectStore('kv'));
        tx.oncomplete = function () { resolve(req && req.result); };
        tx.onerror = function () { reject(tx.error); };
        tx.onabort = function () { reject(tx.error); };
      });
    });
  }
  return {
    get: function (key) { return run('readonly', function (s) { return s.get(key); }).catch(function () { return undefined; }); },
    set: function (key, value) { return run('readwrite', function (s) { return s.put(value, key); }); },
    del: function (key) { return run('readwrite', function (s) { return s.delete(key); }).catch(function () { }); },
  };
})();

var toastTimer = null;
function toast(message) {
  var el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { el.classList.remove('show'); }, 3500);
}

function nextFrame() {
  return new Promise(function (r) { requestAnimationFrame(function () { setTimeout(r, 0); }); });
}
