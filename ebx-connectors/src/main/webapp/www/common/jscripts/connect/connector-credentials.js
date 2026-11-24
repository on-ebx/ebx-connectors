/*
 * Credential guide of the connector form (Connection tab). Reads the model written by
 * ConnectorCredentialsPane (window.ftccModel), follows the native "Authentication" widget and
 * annotates the native credential rows: state chip, contextual label, dimmed unused rows, a
 * length meter on the secret, the guide pane and the readiness strip of the selected mode.
 * The native EBX widgets keep their own behaviour; nothing here submits or stores a value.
 */
(function () {
	'use strict';
	var model = window.ftccModel;
	if (!model || document.documentElement.dataset.ftccBound) { return; }
	document.documentElement.dataset.ftccBound = 'true';

	var rows = {};
	var authWidget = null;
	var authSelect = null;
	var applied = null;
	var typed = {};

	function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
	function fmt(pattern, a, b) { return String(pattern).replace('{0}', a).replace('{1}', b); }

	function locateRows() {
		var wrapper = document.getElementById('ftcc-rows');
		var i, key, el, positional = [];
		if (wrapper) {
			var table = wrapper.querySelector('table');
			if (table) {
				var body = table.tBodies.length ? table.tBodies[0] : table;
				for (i = 0; i < body.children.length; i++) { if (body.children[i].tagName === 'TR') { positional.push(body.children[i]); } }
			}
		}
		for (i = 0; i < model.rows.length; i++) {
			key = model.rows[i];
			el = document.getElementById('ftcc_' + key);
			if (!el && positional.length === model.rows.length) { el = positional[i]; }
			if (el) { rows[key] = el; }
		}
	}

	function widgetOf(row) {
		if (!row) { return null; }
		var cells = row.children;
		var scope = cells.length > 1 ? cells[cells.length - 1] : row;
		return scope.querySelector('select, input:not([type=hidden]), textarea') || scope.querySelector('input');
	}

	function labelCellOf(row) {
		if (!row) { return null; }
		return row.children.length > 1 ? row.children[0] : row;
	}

	function currentMode() {
		if (authSelect) { return authSelect.value; }
		if (authWidget) { return authWidget.value; }
		return model.current;
	}

	function valueOf(key) {
		var w = widgetOf(rows[key]);
		return w && typeof w.value === 'string' ? w.value : '';
	}

	function filled(key) {
		if (valueOf(key).trim().length > 0) { return true; }
		return !!(model.stored && model.stored[key]) && !typed[key];
	}

	function chip(state) {
		return '<span class="ftcc-state ' + state + '">' + esc(model.text[state]) + '</span>';
	}

	function annotate(key, state, label) {
		var row = rows[key];
		if (!row) { return; }
		var cell = labelCellOf(row);
		var box = cell.querySelector('.ftcc-annot');
		if (!box) { box = document.createElement('div'); box.className = 'ftcc-annot'; cell.appendChild(box); }
		box.innerHTML = chip(state) + (label ? '<span class="ftcc-as">' + esc(label) + '</span>' : '');
		if (state === 'unused') { row.classList.add('ftcc-off'); } else { row.classList.remove('ftcc-off'); }
		var w = widgetOf(row);
		if (w && (w.type === 'text' || w.type === 'password')) {
			if (state === 'unused') { if (!w.dataset.ftccPh) { w.dataset.ftccPh = w.placeholder || ''; } w.placeholder = model.text.unusedPlaceholder; }
			else if (w.dataset.ftccPh !== undefined) { w.placeholder = w.dataset.ftccPh; }
		}
	}

	function showPanes(mode) {
		var panes = document.querySelectorAll('[data-ftcc-mode]');
		for (var i = 0; i < panes.length; i++) { panes[i].hidden = panes[i].getAttribute('data-ftcc-mode') !== mode; }
		var cards = document.querySelectorAll('.ftcc-mode');
		for (var j = 0; j < cards.length; j++) {
			if (cards[j].getAttribute('data-mode') === mode) { cards[j].classList.add('on'); } else { cards[j].classList.remove('on'); }
		}
	}

	function meter() {
		var row = rows.apiKey;
		var box = document.getElementById('ftcc-meter');
		if (!row || !box) { return; }
		var w = widgetOf(row);
		var n = w && typeof w.value === 'string' ? w.value.length : 0;
		var pasted = box.dataset.pasted ? parseInt(box.dataset.pasted, 10) : 0;
		var shown = Math.max(n, pasted);
		var crit = shown > model.limit;
		if (shown === 0 && !crit) { box.hidden = true; return; }
		box.hidden = false;
		box.className = 'ftcc-meter' + (crit ? ' crit' : '');
		box.innerHTML = '<span>' + esc(crit ? model.text.tooLong : model.text.encrypted) + '</span><span>' + fmt(esc(model.text.counter), shown, model.limit) + '</span>'
			+ '<i class="ftcc-bar"><b style="width:' + Math.min(100, shown / model.limit * 100) + '%"></b></i>';
	}

	function readiness(mode) {
		var box = document.getElementById('ftcc-ready');
		var spec = model.modes[mode];
		if (!box || !spec) { return; }
		var missing = [], key, i, tooLong = false, url = valueOf('baseUrl').trim().length > 0 || !!(model.stored && model.stored.baseUrl);
		for (key in spec.fields) {
			if (spec.fields[key] === 'required' && !filled(key)) { missing.push(spec.labels[key] || model.fieldLabels[key] || key); }
		}
		for (i = 0; i < spec.props.length; i++) {
			if (spec.props[i].state === 'required' && !spec.props[i].configured) { missing.push(spec.props[i].name); }
		}
		if (spec.fields.apiKey !== 'unused') {
			var w = widgetOf(rows.apiKey);
			var meterBox = document.getElementById('ftcc-meter');
			var pasted = meterBox && meterBox.dataset.pasted ? parseInt(meterBox.dataset.pasted, 10) : 0;
			tooLong = (w && typeof w.value === 'string' && w.value.length > model.limit) || pasted > model.limit;
		}
		var noCredential = true;
		for (key in spec.fields) { if (spec.fields[key] !== 'unused') { noCredential = false; } }
		var html = '<span class="' + (url ? 'ok' : 'ko') + '">' + (url ? '&#10003; ' : '! ') + esc(model.text.endpoint) + '</span>';
		if (noCredential && !spec.props.length) { html += '<span class="ok">&#10003; ' + esc(model.text.none) + '</span>'; }
		else {
			var bad = missing.length > 0 || tooLong;
			html += '<span class="' + (bad ? 'ko' : 'ok') + '">' + (bad ? '! ' : '&#10003; ')
				+ (missing.length ? fmt(esc(model.text.missing), esc(missing.join(', '))) : esc(model.text.credentials))
				+ (tooLong ? ' &middot; ' + esc(model.text.tooLong) : '') + '</span>';
		}
		box.innerHTML = html;
	}

	function apply(force) {
		var mode = currentMode();
		if (!model.modes[mode]) { mode = model.current; }
		if (!force && mode === applied) { readiness(mode); return; }
		applied = mode;
		var spec = model.modes[mode];
		var key;
		for (key in spec.fields) { annotate(key, spec.fields[key], spec.labels[key]); }
		showPanes(mode);
		meter();
		readiness(mode);
	}

	function bindMode() {
		var row = rows.authType;
		if (!row) { return; }
		authSelect = row.querySelector('select');
		authWidget = authSelect || row.querySelector('input');
		if (authSelect) { authSelect.addEventListener('change', function () { apply(false); }); }
		// Custom EBX drop-downs keep their value in a hidden input without a change event: poll.
		setInterval(function () { apply(false); }, 500);
	}

	function bindCards() {
		var cards = document.querySelectorAll('.ftcc-mode');
		for (var i = 0; i < cards.length; i++) {
			cards[i].addEventListener('click', function (event) {
				var mode = event.currentTarget.getAttribute('data-mode');
				if (event.currentTarget.disabled || !model.modes[mode]) { return; }
				if (authSelect) {
					authSelect.value = mode;
					authSelect.dispatchEvent(new Event('change', { bubbles: true }));
				} else if (authWidget) {
					// Not a plain select: let the native widget do the change, only bring it into view.
					authWidget.focus();
					if (authWidget.click) { authWidget.click(); }
				}
				apply(false);
			});
		}
	}

	function bindInputs() {
		var i, key, w;
		for (i = 0; i < model.rows.length; i++) {
			key = model.rows[i];
			w = widgetOf(rows[key]);
			if (!w) { continue; }
			(function (k, widget) {
				widget.addEventListener('input', function () {
					typed[k] = widget.value.length === 0;
					if (k === 'apiKey') { var box = document.getElementById('ftcc-meter'); if (box) { delete box.dataset.pasted; } meter(); }
					readiness(applied || model.current);
				});
				if (k === 'apiKey') {
					widget.addEventListener('paste', function (event) {
						var text = event.clipboardData ? event.clipboardData.getData('text') : '';
						var box = document.getElementById('ftcc-meter');
						if (box && text) { box.dataset.pasted = String(text.length); }
						setTimeout(function () { meter(); readiness(applied || model.current); }, 0);
					});
				}
			})(key, w);
		}
	}

	function start() {
		locateRows();
		if (!rows.authType) { return; }
		bindMode();
		bindCards();
		bindInputs();
		apply(true);
	}

	if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();
