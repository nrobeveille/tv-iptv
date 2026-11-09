/*
 * Navigation à la télécommande par « zones » : une zone (liste, grille, rangée de boutons)
 * gère elle-même les flèches ; quand on sort par un bord, l'écran passe à la zone voisine.
 * Les listes et grilles sont virtualisées : seules les lignes visibles existent dans le DOM,
 * même avec des dizaines de milliers de chaînes ou de films.
 */
'use strict';

function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }

/* ------------------------------------------------------------------ Liste verticale */

function ListZone(el, opts) {
  this.el = el;
  this.o = opts;
  this.items = [];
  this.index = 0;
  this.top = 0;
  this.active = false;
  this.rendered = null; // { top, items } du dernier rendu complet
  el.classList.add('vlist');
  el.innerHTML = '';
  this.inner = document.createElement('div');
  this.inner.className = 'vlist-inner';
  el.appendChild(this.inner);
}

ListZone.prototype.rowH = function () { return this.o.rowHeight; };
ListZone.prototype.visible = function () { return Math.max(1, Math.floor((this.el.clientHeight || 600) / this.rowH())); };
ListZone.prototype.skippable = function (i) { return this.o.skip ? this.o.skip(this.items[i], i) : false; };

ListZone.prototype.setItems = function (items, index) {
  this.items = items || [];
  this.index = clamp(index || 0, 0, Math.max(0, this.items.length - 1));
  while (this.index < this.items.length - 1 && this.skippable(this.index)) this.index++;
  this.top = 0;
  this.rendered = null;
  this.render();
};

ListZone.prototype.current = function () { return this.items[this.index]; };

ListZone.prototype.ensureVisible = function () {
  var n = this.visible();
  var margin = n > 4 ? 1 : 0;
  if (this.index < this.top + margin) this.top = Math.max(0, this.index - margin);
  if (this.index > this.top + n - 1 - margin) this.top = this.index - n + 1 + margin;
  this.top = clamp(this.top, 0, Math.max(0, this.items.length - n));
};

ListZone.prototype.render = function () {
  this.ensureVisible();
  var n = this.visible();
  var h = this.rowH();
  if (!this.rendered || this.rendered.top !== this.top || this.rendered.items !== this.items || this.rendered.n !== n) {
    var html = [];
    var end = Math.min(this.items.length, this.top + n + 1);
    for (var i = this.top; i < end; i++) {
      html.push('<div class="row" data-i="' + i + '" style="top:' + ((i - this.top) * h) + 'px;height:' + h + 'px">' +
        this.o.render(this.items[i], i) + '</div>');
    }
    if (!this.items.length && this.o.empty) html.push('<div class="empty">' + this.o.empty + '</div>');
    this.inner.innerHTML = html.join('');
    this.rendered = { top: this.top, items: this.items, n: n };
  }
  var rows = this.inner.children;
  for (var j = 0; j < rows.length; j++) {
    var idx = +rows[j].getAttribute('data-i');
    rows[j].classList.toggle('focused', this.active && idx === this.index);
    rows[j].classList.toggle('selected', !!(this.o.isSelected && this.o.isSelected(this.items[idx], idx)));
  }
  if (this.o.scrollbar !== false) this.renderScrollbar(n);
};

ListZone.prototype.renderScrollbar = function (n) {
  var bar = this.el.querySelector('.scroll');
  if (this.items.length <= n) { if (bar) bar.remove(); return; }
  if (!bar) {
    bar = document.createElement('div');
    bar.className = 'scroll';
    bar.innerHTML = '<div></div>';
    this.el.appendChild(bar);
  }
  var thumb = bar.firstChild;
  thumb.style.height = Math.max(4, (n / this.items.length) * 100) + '%';
  thumb.style.top = (this.top / this.items.length) * 100 + '%';
};

/** Rafraîchit le contenu des lignes visibles (favori ajouté, programme en cours…). */
ListZone.prototype.refresh = function () { this.rendered = null; this.render(); };

ListZone.prototype.move = function (delta) {
  var i = this.index + delta;
  while (i >= 0 && i < this.items.length && this.skippable(i)) i += delta > 0 ? 1 : -1;
  if (i < 0 || i >= this.items.length) {
    if (Math.abs(delta) > 1) i = clamp(i, 0, this.items.length - 1); else return false;
  }
  if (i === this.index) return Math.abs(delta) > 1;
  this.index = i;
  this.render();
  if (this.o.onFocus) this.o.onFocus(this.items[i], i);
  return true;
};

ListZone.prototype.key = function (code) {
  switch (code) {
    case Keys.UP: return this.move(-1) || 'up';
    case Keys.DOWN: return this.move(1) || 'down';
    case Keys.PAGE_UP: case Keys.CH_UP: return this.move(-this.visible()) || true;
    case Keys.PAGE_DOWN: case Keys.CH_DOWN: return this.move(this.visible()) || true;
    case Keys.LEFT: return 'left';
    case Keys.RIGHT: return 'right';
  }
  return false;
};

ListZone.prototype.enter = function () { if (this.items.length && this.o.onEnter) this.o.onEnter(this.current(), this.index); };
ListZone.prototype.long = function () { if (this.items.length && this.o.onLong) this.o.onLong(this.current(), this.index); };
ListZone.prototype.focus = function () {
  this.active = true;
  this.el.classList.add('zone-active');
  this.render();
  if (this.o.onFocus && this.items.length) this.o.onFocus(this.current(), this.index);
};
ListZone.prototype.blur = function () { this.active = false; this.el.classList.remove('zone-active'); this.render(); };
ListZone.prototype.canFocus = function () { return this.items.length > 0; };

/* ------------------------------------------------------------------ Grille */

function GridZone(el, opts) {
  this.el = el;
  this.o = opts; // { cellWidth, cellHeight, render, onEnter, onLong, onFocus, empty }
  this.items = [];
  this.index = 0;
  this.topRow = 0;
  this.active = false;
  this.rendered = null;
  el.classList.add('vgrid');
  el.innerHTML = '';
  this.inner = document.createElement('div');
  this.inner.className = 'vlist-inner';
  el.appendChild(this.inner);
}

GridZone.prototype.cols = function () { return Math.max(1, Math.floor((this.el.clientWidth || 1200) / this.o.cellWidth)); };
GridZone.prototype.rowsVisible = function () { return Math.max(1, Math.floor((this.el.clientHeight || 800) / this.o.cellHeight)); };
GridZone.prototype.setItems = function (items, index) {
  this.items = items || [];
  this.index = clamp(index || 0, 0, Math.max(0, this.items.length - 1));
  this.topRow = 0;
  this.rendered = null;
  this.render();
};
GridZone.prototype.current = function () { return this.items[this.index]; };

GridZone.prototype.render = function () {
  var cols = this.cols(), n = this.rowsVisible();
  var row = Math.floor(this.index / cols);
  if (row < this.topRow) this.topRow = row;
  if (row > this.topRow + n - 1) this.topRow = row - n + 1;
  var totalRows = Math.ceil(this.items.length / cols);
  this.topRow = clamp(this.topRow, 0, Math.max(0, totalRows - n));
  if (!this.rendered || this.rendered.top !== this.topRow || this.rendered.items !== this.items || this.rendered.cols !== cols) {
    var html = [];
    var start = this.topRow * cols, end = Math.min(this.items.length, (this.topRow + n + 1) * cols);
    for (var i = start; i < end; i++) {
      var r = Math.floor(i / cols) - this.topRow, c = i % cols;
      html.push('<div class="cell" data-i="' + i + '" style="left:' + (c * this.o.cellWidth) + 'px;top:' + (r * this.o.cellHeight) +
        'px;width:' + this.o.cellWidth + 'px;height:' + this.o.cellHeight + 'px">' + this.o.render(this.items[i], i) + '</div>');
    }
    if (!this.items.length && this.o.empty) html.push('<div class="empty">' + this.o.empty + '</div>');
    this.inner.innerHTML = html.join('');
    this.rendered = { top: this.topRow, items: this.items, cols: cols };
  }
  var cells = this.inner.children;
  for (var j = 0; j < cells.length; j++) {
    cells[j].classList.toggle('focused', this.active && +cells[j].getAttribute('data-i') === this.index);
  }
};

GridZone.prototype.refresh = function () { this.rendered = null; this.render(); };

GridZone.prototype.go = function (i) {
  if (i < 0 || i >= this.items.length || i === this.index) return false;
  this.index = i;
  this.render();
  if (this.o.onFocus) this.o.onFocus(this.current(), i);
  return true;
};

GridZone.prototype.key = function (code) {
  var cols = this.cols();
  var col = this.index % cols;
  switch (code) {
    case Keys.LEFT: return col > 0 ? this.go(this.index - 1) : 'left';
    case Keys.RIGHT: return col < cols - 1 && this.index + 1 < this.items.length ? this.go(this.index + 1) : 'right';
    case Keys.UP: return this.index - cols >= 0 ? this.go(this.index - cols) : 'up';
    case Keys.DOWN:
      if (this.index + cols < this.items.length) return this.go(this.index + cols);
      // Dernière ligne incomplète : on va sur le dernier élément.
      if (Math.floor(this.index / cols) < Math.floor((this.items.length - 1) / cols)) return this.go(this.items.length - 1);
      return 'down';
    case Keys.CH_DOWN: case Keys.PAGE_DOWN: return this.go(Math.min(this.items.length - 1, this.index + cols * this.rowsVisible())) || true;
    case Keys.CH_UP: case Keys.PAGE_UP: return this.go(Math.max(0, this.index - cols * this.rowsVisible())) || true;
  }
  return false;
};

GridZone.prototype.enter = function () { if (this.items.length && this.o.onEnter) this.o.onEnter(this.current(), this.index); };
GridZone.prototype.long = function () { if (this.items.length && this.o.onLong) this.o.onLong(this.current(), this.index); };
GridZone.prototype.focus = function () { this.active = true; this.el.classList.add('zone-active'); this.render(); };
GridZone.prototype.blur = function () { this.active = false; this.el.classList.remove('zone-active'); this.render(); };
GridZone.prototype.canFocus = function () { return this.items.length > 0; };

/* ------------------------------------------------------------------ Rangée horizontale (boutons, saisons) */

function RowZone(el, opts) {
  this.el = el;
  this.o = opts; // { render(item,i), onEnter, onFocus, isSelected }
  this.items = [];
  this.index = 0;
  this.active = false;
  el.classList.add('hrow');
}

RowZone.prototype.setItems = function (items, index) {
  this.items = items || [];
  this.index = clamp(index || 0, 0, Math.max(0, this.items.length - 1));
  this.render();
};
RowZone.prototype.current = function () { return this.items[this.index]; };
RowZone.prototype.render = function () {
  var self = this;
  this.el.innerHTML = this.items.map(function (it, i) {
    var cls = 'chip' + (self.active && i === self.index ? ' focused' : '') + (self.o.isSelected && self.o.isSelected(it, i) ? ' selected' : '');
    return '<div class="' + cls + '">' + self.o.render(it, i) + '</div>';
  }).join('');
  var f = this.el.children[this.index];
  if (f && f.scrollIntoView && this.active) f.scrollIntoView({ block: 'nearest', inline: 'nearest' });
};
RowZone.prototype.refresh = RowZone.prototype.render;
RowZone.prototype.key = function (code) {
  if (code === Keys.LEFT) {
    if (this.index === 0) return 'left';
    this.index--;
  } else if (code === Keys.RIGHT) {
    if (this.index >= this.items.length - 1) return 'right';
    this.index++;
  } else if (code === Keys.UP) return 'up';
  else if (code === Keys.DOWN) return 'down';
  else return false;
  this.render();
  if (this.o.onFocus) this.o.onFocus(this.current(), this.index);
  return true;
};
RowZone.prototype.enter = function () { if (this.items.length && this.o.onEnter) this.o.onEnter(this.current(), this.index); };
RowZone.prototype.long = function () { };
RowZone.prototype.focus = function () { this.active = true; this.render(); if (this.o.onFocus && this.items.length) this.o.onFocus(this.current(), this.index); };
RowZone.prototype.blur = function () { this.active = false; this.render(); };
RowZone.prototype.canFocus = function () { return this.items.length > 0; };

/* ------------------------------------------------------------------ Fenêtres (menu, saisie) */

var Modal = (function () {
  var stack = [];

  function root() { return document.getElementById('modal'); }

  function close() {
    var m = stack.pop();
    if (m && m.el) m.el.remove();
    if (!stack.length) root().classList.remove('show');
  }

  /** Menu de choix : actions = [{ label, action }]. */
  function menu(title, actions, subtitle) {
    var el = document.createElement('div');
    el.className = 'dialog';
    el.innerHTML = '<h2>' + esc(title) + '</h2>' + (subtitle ? '<p>' + esc(subtitle) + '</p>' : '') + '<div class="dialog-list"></div>';
    root().appendChild(el);
    root().classList.add('show');
    var list = el.querySelector('.dialog-list');
    list.style.height = Math.min(actions.length, 7) * 64 + 'px';
    var zone = new ListZone(list, {
      rowHeight: 64,
      scrollbar: false,
      render: function (a) { return '<div class="menu-item">' + esc(a.label) + '</div>'; },
      onEnter: function (a) { close(); a.action(); },
    });
    zone.setItems(actions);
    zone.focus();
    stack.push({
      el: el,
      key: function (code) {
        if (Keys.isBack(code)) { close(); return; }
        if (code === Keys.ENTER) { zone.enter(); return; }
        zone.key(code);
      },
    });
  }

  /** Saisie de texte : le clavier de la TV s'ouvre. */
  function prompt(title, value, onDone, hint) {
    var el = document.createElement('div');
    el.className = 'dialog wide';
    el.innerHTML = '<h2>' + esc(title) + '</h2>' + (hint ? '<p>' + esc(hint) + '</p>' : '') +
      '<input type="text" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false">' +
      '<p class="hint">Valide avec « Terminé » sur le clavier · Retour pour annuler</p>';
    root().appendChild(el);
    root().classList.add('show');
    var input = el.querySelector('input');
    input.value = value || '';
    var done = false;
    function finish(ok) {
      if (done) return;
      done = true;
      var v = input.value.trim();
      input.blur();
      close();
      if (ok) onDone(v);
    }
    input.addEventListener('keydown', function (e) {
      if (e.keyCode === Keys.ENTER || e.keyCode === Keys.IME_DONE) { e.preventDefault(); e.stopPropagation(); finish(true); }
      else if (e.keyCode === Keys.IME_CANCEL || e.keyCode === Keys.BACK || e.keyCode === Keys.ESC) { e.preventDefault(); e.stopPropagation(); finish(false); }
      else e.stopPropagation();
    });
    // Sur la TV, le clavier virtuel déclenche « blur » quand on le ferme avec Terminé.
    input.addEventListener('blur', function () { setTimeout(function () { if (!done && document.activeElement !== input) finish(true); }, 200); });
    setTimeout(function () { input.focus(); try { input.setSelectionRange(input.value.length, input.value.length); } catch (e) { } }, 50);
    stack.push({ el: el, key: function (code) { if (Keys.isBack(code)) finish(false); else if (code === Keys.ENTER) input.focus(); } });
  }

  return {
    menu: menu,
    prompt: prompt,
    isOpen: function () { return stack.length > 0; },
    key: function (code) { if (stack.length) stack[stack.length - 1].key(code); },
    close: close,
  };
})();
