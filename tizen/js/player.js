/*
 * Lecteur vidéo : AVPlay (lecteur matériel Samsung : HLS, MPEG-TS, MP4, MKV…) sur la TV,
 * balise <video> dans un navigateur ordinaire (pour tester l'interface sur PC).
 */
'use strict';

var Player = (function () {
  var av = (window.webapis && window.webapis.avplay) || null;
  var video = null;
  var cb = {};
  var current = null; // { url, live }
  var subtitleTimer = null;

  var ASPECT = {
    fit: 'PLAYER_DISPLAY_MODE_LETTER_BOX',
    fill: 'PLAYER_DISPLAY_MODE_FULL_SCREEN',
    zoom: 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO',
  };
  var aspect = 'fit';

  function emit(name, a, b) { if (cb[name]) cb[name](a, b); }

  function avListener() {
    return {
      onbufferingstart: function () { emit('buffering', true); },
      onbufferingprogress: function () { },
      onbufferingcomplete: function () { emit('buffering', false); emit('state', 'PLAYING'); },
      onstreamcompleted: function () { emit('ended'); },
      oncurrentplaytime: function () { },
      onerror: function (type) { emit('error', String(type)); },
      onevent: function () { },
      onsubtitlechange: function (duration, text) {
        emit('subtitle', text);
        clearTimeout(subtitleTimer);
        subtitleTimer = setTimeout(function () { emit('subtitle', ''); }, Math.max(1000, +duration || 3000));
      },
      ondrmevent: function () { },
    };
  }

  function stopInternal() {
    clearTimeout(subtitleTimer);
    if (av) {
      try { av.stop(); } catch (e) { }
      try { av.close(); } catch (e) { }
    } else if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
  }

  /**
   * Lance un flux. opts : { live, startMs, userAgent }.
   * callbacks : state, buffering, error, ended, subtitle.
   */
  function open(url, opts, callbacks) {
    stopInternal();
    cb = callbacks || {};
    current = { url: url, live: !!opts.live };
    emit('buffering', true);
    if (av) {
      try {
        av.open(url);
        av.setListener(avListener());
        av.setDisplayRect(0, 0, 1920, 1080);
        try { av.setDisplayMethod(ASPECT[aspect]); } catch (e) { }
        if (opts.userAgent) { try { av.setStreamingProperty('USER_AGENT', opts.userAgent); } catch (e) { } }
        if (opts.live) {
          try { av.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', 4); } catch (e) { }
          try { av.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', 5); } catch (e) { }
        }
        av.prepareAsync(function () {
          if (current === null || current.url !== url) return;
          var start = function () {
            try { av.play(); } catch (e) { emit('error', e.name || 'PLAY'); return; }
            emit('buffering', false);
            emit('state', 'PLAYING');
          };
          if (opts.startMs > 0) {
            av.seekTo(opts.startMs, start, start);
          } else {
            start();
          }
        }, function (e) {
          emit('error', (e && e.name) || 'PREPARE');
        });
      } catch (e) {
        emit('error', e.name || String(e));
      }
    } else {
      video = video || document.getElementById('html5-video');
      video.onwaiting = function () { emit('buffering', true); };
      video.onplaying = function () { emit('buffering', false); emit('state', 'PLAYING'); };
      video.onpause = function () { emit('state', 'PAUSED'); };
      video.onended = function () { emit('ended'); };
      video.onerror = function () { emit('error', 'MEDIA_ERR_' + (video.error ? video.error.code : '?')); };
      video.style.objectFit = aspect === 'fill' ? 'fill' : aspect === 'zoom' ? 'cover' : 'contain';
      video.src = url;
      if (opts.startMs > 0) video.currentTime = opts.startMs / 1000;
      var p = video.play();
      if (p && p.catch) p.catch(function () { });
    }
  }

  function stop() {
    stopInternal();
    current = null;
    cb = {};
  }

  function state() {
    if (av) { try { return av.getState(); } catch (e) { return 'NONE'; } }
    if (!video || !current) return 'NONE';
    return video.paused ? 'PAUSED' : 'PLAYING';
  }

  function isPlaying() { return state() === 'PLAYING'; }

  function toggle() {
    if (av) {
      var s = state();
      try { if (s === 'PLAYING') { av.pause(); emit('state', 'PAUSED'); } else if (s === 'PAUSED' || s === 'READY') { av.play(); emit('state', 'PLAYING'); } } catch (e) { }
    } else if (video) {
      if (video.paused) video.play(); else video.pause();
    }
  }

  function pause() {
    if (av) { try { if (state() === 'PLAYING') av.pause(); } catch (e) { } }
    else if (video) video.pause();
  }

  function time() {
    if (av) { try { return av.getCurrentTime() || 0; } catch (e) { return 0; } }
    return video ? Math.floor(video.currentTime * 1000) : 0;
  }

  function duration() {
    if (av) { try { var d = av.getDuration(); return d > 0 ? d : 0; } catch (e) { return 0; } }
    return video && isFinite(video.duration) ? Math.floor(video.duration * 1000) : 0;
  }

  function seekTo(ms) {
    var d = duration();
    var target = Math.max(0, d > 0 ? Math.min(ms, d - 1000) : ms);
    if (av) { try { av.seekTo(target, function () { }, function () { }); } catch (e) { } }
    else if (video) video.currentTime = target / 1000;
  }

  function setAspect(mode) {
    aspect = mode;
    if (av) { try { av.setDisplayMethod(ASPECT[mode]); } catch (e) { } }
    else if (video) video.style.objectFit = mode === 'fill' ? 'fill' : mode === 'zoom' ? 'cover' : 'contain';
  }

  /** Pistes audio / sous-titres : [{ index, label, selected }]. */
  function tracks(type) {
    if (!av) return [];
    var info, currentInfo;
    try { info = av.getTotalTrackInfo() || []; } catch (e) { return []; }
    try { currentInfo = av.getCurrentStreamInfo() || []; } catch (e) { currentInfo = []; }
    var selected = currentInfo.filter(function (t) { return t.type === type; }).map(function (t) { return +t.index; });
    return info.filter(function (t) { return t.type === type; }).map(function (t, i) {
      var label = 'Piste ' + (i + 1);
      try {
        var extra = JSON.parse(t.extra_info || '{}');
        var lang = extra.language || extra.track_lang || '';
        if (lang) label = languageName(lang) + (extra.channels ? ' (' + extra.channels + ' can.)' : '');
      } catch (e) { }
      return { index: +t.index, label: label, selected: selected.indexOf(+t.index) >= 0 };
    });
  }

  function languageName(code) {
    var names = { fr: 'Français', fre: 'Français', fra: 'Français', en: 'Anglais', eng: 'Anglais', es: 'Espagnol', spa: 'Espagnol', de: 'Allemand', ger: 'Allemand', deu: 'Allemand', it: 'Italien', ita: 'Italien', ar: 'Arabe', ara: 'Arabe', pt: 'Portugais', por: 'Portugais' };
    return names[String(code).toLowerCase()] || String(code).toUpperCase();
  }

  function selectTrack(type, index) {
    if (!av) return;
    try { av.setSelectTrack(type, index); } catch (e) { console.warn('setSelectTrack', e); }
  }

  var subtitlesOn = false;
  function setSubtitles(index) {
    if (!av) return;
    if (index == null) {
      subtitlesOn = false;
      try { av.setSilentSubtitle(true); } catch (e) { }
      emit('subtitle', '');
    } else {
      subtitlesOn = true;
      try { av.setSilentSubtitle(false); } catch (e) { }
      selectTrack('TEXT', index);
    }
  }

  // Appli mise en arrière-plan (bouton Home) : on suspend, puis on reprend.
  document.addEventListener('visibilitychange', function () {
    if (!current) return;
    if (document.hidden) {
      if (av) { try { av.suspend(); } catch (e) { pause(); } } else pause();
    } else if (av) {
      try { av.restore(); } catch (e) { emit('error', 'RESTORE'); }
    }
  });

  return {
    open: open, stop: stop, toggle: toggle, pause: pause, isPlaying: isPlaying, state: state,
    time: time, duration: duration, seekTo: seekTo, setAspect: setAspect,
    tracks: tracks, selectTrack: selectTrack, setSubtitles: setSubtitles,
    subtitlesOn: function () { return subtitlesOn; },
    isNative: function () { return !!av; },
  };
})();
