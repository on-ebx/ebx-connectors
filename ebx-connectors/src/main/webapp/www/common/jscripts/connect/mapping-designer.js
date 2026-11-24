/*
 * Mapping designer for the EBX connectors module.
 * Input tree on the left, output tree on the right, a link per rule drawn on the canvas in the
 * middle; a rule with a transformation pipeline (or a constant) is a node on the canvas.
 * Expects a global `ftmdModel` (written by MappingDesignerUserService) and the hidden inputs
 * ftmdRulesInput / ftmdDirectionInput / ftmdRootInput / ftmdChild* that travel with the form.
 */
(function () {
	'use strict';
	var M = window.ftmdModel;
	var root = document.getElementById('ftmd');
	if (!M || !root) { return; }
	var L = {};
	for (var li = 0; li < M.labels.length; li++) { L[M.labels[li].key] = M.labels[li].text; }
	function t(key, a, b) { var s = L[key] || key; return s.replace('{0}', a === undefined ? '' : a).replace('{1}', b === undefined ? '' : b); }

	// ------------------------------------------------------------ state
	var inbound = M.direction !== 'OUTBOUND';
	var rules = M.rules.slice();                 // {id, source, target, transformation, policy, comment}
	var selected = -1;                           // index of the selected rule
	var focusField = null;                       // {side, path} highlighted field
	var hideUnmapped = false;
	var tableMode = false;
	var armedConstant = false;                   // "+ constant" pressed: next output click makes a rule
	var dirty = false;
	var trees = { L: null, R: null };
	var expanded = { L: {}, R: {} };
	var filters = { L: '', R: '' };
	var tabKey = { L: null, R: null };           // the one tree row in the tab order of each side (roving tabindex)
	var canvasRule = -1;                         // the one rule of the canvas in the tab order
	var inputs = (M.inputs || []).map(function (i) { return { name: i.name, expression: i.expression || '', required: !!i.required }; });
	// audit C-35: on a feed the write policy of the rules added is chosen, never met by surprise; the key fields stay IF_EMPTY
	var newPolicy = M.defaultPolicy || '';
	var keyTargets = M.keyTargets || [];
	function normalTarget(p) { return './' + String(p || '').replace(/^\.?\//, ''); }
	function isKeyTarget(p) { return keyTargets.indexOf(normalTarget(p)) >= 0; }
	function policyFor(target) { return isKeyTarget(target) ? 'IF_EMPTY' : (newPolicy || 'IF_EMPTY'); }
	function needsPolicy() { return inbound && !!M.policyChoice && !newPolicy; }

	function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
	function el(id) { return document.getElementById(id); }
	// ------------------------------------------------------------ keyboard and screen reader helpers (audit C-42)
	// A polite live region says what a keyboard user cannot see happen: a field picked, a link made, a rule removed.
	function announce(text) { var live = el('ftmdLive'); if (live) { live.textContent = ''; window.setTimeout(function () { live.textContent = text; }, 30); } }
	// An in-page dialog replaces the browser prompt and confirm boxes: role=dialog, a focus trap, Esc cancels, Enter in the field confirms,
	// and the focus goes back to what opened it. done(result): the text (or true for a confirmation), null when cancelled.
	var dialogOpen = false;
	function openDialog(message, withInput, initial, done) {
		if (dialogOpen) { return; }
		dialogOpen = true;
		var previous = document.activeElement;
		var back = document.createElement('div'); back.className = 'ftmdDialogBack';
		var h = '<div class="ftmdDialog" role="dialog" aria-modal="true" aria-labelledby="ftmdDialogText"><div class="ftmdDialogText" id="ftmdDialogText">' + esc(message) + '</div>';
		if (withInput) { h += '<input type="text" class="ftmdDialogInput" id="ftmdDialogInput" aria-labelledby="ftmdDialogText" value="' + esc(initial || '') + '"/>'; }
		h += '<div class="ftmdDialogActions"><button type="button" class="ftmdBtn ftmdPrimary" id="ftmdDialogOk">' + esc(t('designer.dialog.ok')) + '</button>'
			+ '<button type="button" class="ftmdBtn" id="ftmdDialogCancel">' + esc(t('designer.dialog.cancel')) + '</button></div></div>';
		back.innerHTML = h; root.appendChild(back);
		var input = el('ftmdDialogInput'), ok = el('ftmdDialogOk'), cancel = el('ftmdDialogCancel');
		function close(result) {
			document.removeEventListener('keydown', onKey, true); dialogOpen = false;
			if (back.parentNode) { back.parentNode.removeChild(back); }
			if (previous && previous.focus && document.body.contains(previous)) { previous.focus(); }
			done(result);
		}
		function accept() { if (withInput) { var v = input.value.replace(/^\s+|\s+$/g, ''); if (!v) { input.focus(); return; } close(v); } else { close(true); } }
		function onKey(e) {
			if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(null); return; }
			if (e.key === 'Enter' && e.target === input) { e.preventDefault(); accept(); return; }
			if (e.key === 'Tab') {
				var order = withInput ? [input, ok, cancel] : [ok, cancel]; var at = order.indexOf(document.activeElement);
				var next = order[(at + (e.shiftKey ? order.length - 1 : 1)) % order.length];
				e.preventDefault(); next.focus();
			}
		}
		ok.onclick = accept; cancel.onclick = function () { close(null); };
		document.addEventListener('keydown', onKey, true);
		(input || ok).focus(); if (input) { input.select(); }
	}
	function askConfirm(message, done) { openDialog(message, false, '', function (r) { done(r === true); }); }
	function askText(message, done) { openDialog(message, true, '', done); }
	// roving tabindex: one stop of the list is in the tab order, the arrows move inside it
	function setRoving(items, current) { for (var i = 0; i < items.length; i++) { items[i].setAttribute('tabindex', items[i] === current ? '0' : '-1'); } }
	function rovingKey(e, items, index) {
		var n = items.length; if (!n) { return -1; }
		if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { return Math.min(n - 1, index + 1); }
		if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { return Math.max(0, index - 1); }
		if (e.key === 'Home') { return 0; }
		if (e.key === 'End') { return n - 1; }
		return -1;
	}
	function isActivate(e) { return e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar'; }
	function sync(markDirty) {
		if (markDirty) { dirty = true; }
		el('ftmdRulesInput').value = JSON.stringify(rules);
		var ii = el('ftmdInputsInput'); if (ii) { ii.value = JSON.stringify(inputs); }
		el('ftmdDirectionInput').value = inbound ? 'INBOUND' : 'OUTBOUND';
		var r = el('ftmdRootField'); if (r) { el('ftmdRootInput').value = r.value; }
		var d = el('ftmdDirty'); if (d) { d.className = 'ftmdDirty' + (dirty ? ' ftmdOn' : ''); }
	}
	function fieldsOf(side) { return (side === 'L') === inbound ? M.externalFields : M.ebxFields; }
	function isEbx(side) { return (side === 'L') !== inbound; }

	// ------------------------------------------------------------ type badges
	function badge(type) {
		var s = String(type || '').toLowerCase();
		if (!s) { return ['?', '']; }
		if (s.indexOf('list of') === 0 || s === 'array') { return ['LIST', 'ftmdTList']; }
		if (s.indexOf('fk') === 0) { return ['FK', 'ftmdTFk']; }
		if (s === 'object') { return ['OBJ', 'ftmdTObj']; }
		if (/int|decimal|number|double|float|long|numeric/.test(s)) { return ['NUM', 'ftmdTNum']; }
		if (/datetime|timestamp/.test(s)) { return ['DT', 'ftmdTDate']; }
		if (/date|time/.test(s)) { return ['DATE', 'ftmdTDate']; }
		if (/bool/.test(s)) { return ['BOOL', 'ftmdTBool']; }
		if (/string|text|char|varchar|null/.test(s)) { return ['STR', '']; }
		return [s.substring(0, 4).toUpperCase(), ''];
	}

	// ------------------------------------------------------------ trees
	// A tree node: {key, name, label, type, path (leaf), children[], field}
	function buildTree(side) {
		var fields = fieldsOf(side); var ebx = isEbx(side);
		var rootNode = { key: '', children: [], byName: {} };
		for (var i = 0; i < fields.length; i++) {
			var f = fields[i]; var segs, labels = [];
			if (ebx) {
				segs = f.path.replace(/^\.\//, '').split('/');
				labels = (f.groupPath || '').length ? f.groupPath.split(' / ') : [];
			} else {
				segs = f.path.replace(/^\./, '').split('.');
			}
			var node = rootNode, key = '';
			for (var s = 0; s < segs.length; s++) {
				var seg = segs[s]; if (seg === '') { continue; }
				key = key ? key + (ebx ? '/' : '.') + seg : seg;
				var last = s === segs.length - 1;
				var child = node.byName[seg];
				if (!child) {
					child = { key: key, name: seg, label: last ? (f.label || seg) : (ebx && labels[s] ? labels[s] : seg.replace(/\[\d*\*?\]$/, '')), children: [], byName: {},
						isList: /\[\d*\*?\]$/.test(seg), type: last ? f.type : (/\[\d*\*?\]$/.test(seg) ? 'array' : 'object') };
					node.children.push(child); node.byName[seg] = child;
				}
				if (last) { child.path = f.path; child.field = f; child.type = f.type; child.label = f.label || seg; }
				node = child;
			}
		}
		return rootNode;
	}
	function mappedPaths(side) {
		var m = {}; var out = (side === 'R');
		for (var i = 0; i < rules.length; i++) { var p = out ? rules[i].target : rules[i].source; if (p) { m[p] = true; } }
		return m;
	}
	function renderTree(side) {
		var tree = trees[side] = trees[side] || buildTree(side);
		var container = el(side === 'L' ? 'ftmdTreeL' : 'ftmdTreeR');
		var filter = filters[side].toLowerCase(); var mapped = mappedPaths(side); var h = [];
		// the tree is rebuilt on every change: keep the keyboard focus on the same row
		var hadFocus = document.activeElement && container.contains(document.activeElement) && document.activeElement.getAttribute ? document.activeElement.getAttribute('data-key') : null;
		if (!tree.children.length) {
			container.innerHTML = '<div class="ftmdEmpty">' + esc(isEbx(side) ? t('designer.noExternalFields') : t('designer.noExternalFields')) + '</div>';
			return;
		}
		var count = 0;
		function matches(n) {
			if (!filter) { return true; }
			if ((n.label + ' ' + n.name + ' ' + (n.path || '')).toLowerCase().indexOf(filter) >= 0) { return true; }
			for (var c = 0; c < n.children.length; c++) { if (matches(n.children[c])) { return true; } }
			return false;
		}
		function anyMapped(n) {
			if (n.path && mapped[n.path]) { return true; }
			for (var c = 0; c < n.children.length; c++) { if (anyMapped(n.children[c])) { return true; } }
			return false;
		}
		function walk(n, depth) {
			for (var c = 0; c < n.children.length; c++) {
				var x = n.children[c]; var leaf = !x.children.length;
				if (!matches(x)) { continue; }
				if (hideUnmapped && !anyMapped(x)) { continue; }
				var open = filter ? true : expanded[side][x.key] !== false;
				var b = badge(x.type); var isMapped = leaf && mapped[x.path];
				if (leaf) { count++; }
				var cls = 'ftmdRow' + (leaf ? '' : ' ftmdGroupRow') + (isMapped ? ' ftmdMapped' : '') + (x.field && x.field.pk ? ' ftmdPk' : '')
					+ (focusField && focusField.side === side && focusField.path === x.path ? ' ftmdSel' : '');
				var title = leaf ? x.path + (x.field && x.field.sample ? ' = ' + x.field.sample : '') + (x.field && x.field.pk ? ' (' + t('designer.pk') + ')' : '') : x.key;
				var isSel = !!(focusField && focusField.side === side && focusField.path === x.path);
				var spoken = x.label + ', ' + (x.type || '') + (x.field && x.field.pk ? ', ' + t('designer.a11y.pk') : '') + (isMapped ? ', ' + t('designer.a11y.mapped') : '');
				h.push('<div class="' + cls + '" role="treeitem" tabindex="' + (x.key === tabKey[side] ? '0' : '-1') + '" aria-level="' + (depth + 1) + '"' + (leaf ? '' : ' aria-expanded="' + (open ? 'true' : 'false') + '"')
					+ ' aria-selected="' + isSel + '" aria-label="' + esc(spoken) + '" data-side="' + side + '" data-key="' + esc(x.key) + '"' + (leaf ? ' data-path="' + esc(x.path) + '"' : '') + ' title="' + esc(title) + '">');
				h.push('<span class="ftmdIndent" style="width:' + (depth * 14 + 4) + 'px"></span>');
				h.push('<span class="ftmdCaret' + (leaf ? ' ftmdLeaf' : '') + '" data-toggle="' + esc(x.key) + '">' + (leaf ? '' : (open ? '&#9662;' : '&#9656;')) + '</span>');
				h.push('<span class="ftmdType ' + b[1] + '">' + esc(b[0]) + '</span>');
				h.push('<span class="ftmdName">' + esc(x.label) + '</span>');
				if (leaf && x.field && x.field.sample) { h.push('<span class="ftmdSampleV">' + esc(x.field.sample) + '</span>'); }
				if (leaf) { h.push('<span class="ftmdPort" data-port="' + side + '" data-path="' + esc(x.path) + '"></span>'); }
				h.push('</div>');
				if (!leaf && open) { walk(x, depth + 1); }
			}
		}
		walk(tree, 0);
		container.innerHTML = h.join('');
		var rowsNow = container.querySelectorAll('.ftmdRow');
		if (rowsNow.length && !container.querySelector('.ftmdRow[tabindex="0"]')) { rowsNow[0].setAttribute('tabindex', '0'); }
		if (hadFocus !== null) { for (var rf = 0; rf < rowsNow.length; rf++) { if (rowsNow[rf].getAttribute('data-key') === hadFocus) { rowsNow[rf].focus(); break; } } }
		var cnt = el(side === 'L' ? 'ftmdCountL' : 'ftmdCountR');
		if (cnt) { var m = 0; for (var k in mapped) { if (mapped.hasOwnProperty(k)) { m++; } } cnt.textContent = t('designer.mappedCount', m) + ' / ' + count; }
	}
	function setAll(side, open) {
		function walk(n) { for (var c = 0; c < n.children.length; c++) { if (n.children[c].children.length) { expanded[side][n.children[c].key] = open; walk(n.children[c]); } } }
		walk(trees[side] || buildTree(side)); renderTree(side); drawAll();
	}

	// ------------------------------------------------------------ geometry
	function portOf(side, path) {
		var pane = el(side === 'L' ? 'ftmdTreeL' : 'ftmdTreeR');
		var ports = pane.querySelectorAll('.ftmdPort');
		for (var i = 0; i < ports.length; i++) { if (ports[i].getAttribute('data-path') === path) { return ports[i]; } }
		return null;
	}
	function anchor(side, path) {
		var work = el('ftmdWork').getBoundingClientRect();
		var pane = el(side === 'L' ? 'ftmdTreeL' : 'ftmdTreeR').getBoundingClientRect();
		var port = portOf(side, path);
		if (port) {
			var r = port.getBoundingClientRect();
			var y = r.top + r.height / 2 - work.top;
			var clamped = Math.max(pane.top - work.top + 2, Math.min(pane.bottom - work.top - 2, y));
			return { x: (side === 'L' ? r.right : r.left) - work.left, y: clamped, visible: y >= pane.top - work.top && y <= pane.bottom - work.top };
		}
		// hidden by a collapsed group: aim at the closest visible ancestor row
		var key = path; var rows = el(side === 'L' ? 'ftmdTreeL' : 'ftmdTreeR').querySelectorAll('.ftmdRow');
		var best = null;
		for (var i = 0; i < rows.length; i++) { var k = rows[i].getAttribute('data-key'); if (k && path.indexOf(k) === 0 && (!best || k.length > best.getAttribute('data-key').length)) { best = rows[i]; } }
		var edge = side === 'L' ? pane.right - 7 : pane.left + 7;
		if (best) { var br = best.getBoundingClientRect(); var by = Math.max(pane.top - work.top + 2, Math.min(pane.bottom - work.top - 2, br.top + br.height / 2 - work.top)); return { x: edge - work.left, y: by, visible: false, folded: true }; }
		return { x: edge - work.left, y: pane.top - work.top + 12, visible: false, folded: true };
	}
	function curve(x1, y1, x2, y2) { var mx = (x1 + x2) / 2; return 'M' + x1 + ',' + y1 + ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2; }

	// ------------------------------------------------------------ canvas: links and nodes
	function ruleLabel(k) {
		var r = rules[k]; return t('designer.a11y.rule', k + 1, (r.source || t('designer.rule.constant')) + ' → ' + r.target + (r.transformation ? ' (' + r.transformation + ')' : ''));
	}
	function hasClass(node, name) { return !!(node && node.getAttribute && (' ' + (node.getAttribute('class') || '') + ' ').indexOf(' ' + name + ' ') >= 0); }
	// the focusable stop of a rule on the canvas (its node, else its link), and the reverse
	function canvasItem(k) {
		return el('ftmdNodes').querySelector('.ftmdNode[data-rule="' + k + '"]') || el('ftmdSvg').querySelector('path[role="button"][data-rule="' + k + '"]');
	}
	function canvasItemOf(node) { return (hasClass(node, 'ftmdNode') || (node.tagName && node.tagName.toLowerCase() === 'path' && node.getAttribute('role') === 'button')) && node.getAttribute('data-rule') !== null; }
	function canvasItems() { return Array.prototype.slice.call(el('ftmdNodes').querySelectorAll('.ftmdNode')).concat(Array.prototype.slice.call(el('ftmdSvg').querySelectorAll('path[role="button"]'))); }
	function hasNode(r) { return !!(r.transformation && r.transformation.trim()) || !r.source; }
	function drawAll() {
		var svg = el('ftmdSvg'); var canvas = el('ftmdCanvas'); var work = el('ftmdWork').getBoundingClientRect();
		var hadRule = document.activeElement && document.activeElement.getAttribute && canvasItemOf(document.activeElement) ? document.activeElement.getAttribute('data-rule') : null;
		var roving = canvasRule >= 0 && canvasRule < rules.length ? canvasRule : (selected >= 0 ? selected : 0);
		var cRect = canvas.getBoundingClientRect(); var cx0 = cRect.left - work.left, cx1 = cRect.right - work.left;
		var paths = []; var nodes = [];
		var hot = {}; // rule indexes touching the focused field
		if (focusField) { for (var i = 0; i < rules.length; i++) { if ((focusField.side === 'L' ? rules[i].source : rules[i].target) === focusField.path) { hot[i] = true; } } }
		var anyHot = focusField !== null || selected >= 0;
		// place nodes first (auto layout, collisions pushed down)
		var placed = [];
		for (var n = 0; n < rules.length; n++) {
			var r = rules[n]; if (!hasNode(r)) { continue; }
			var a = r.source ? anchor('L', r.source) : null; var b = anchor('R', r.target);
			var y = a ? (a.y + b.y) / 2 : b.y;
			placed.push({ i: n, y: y });
		}
		placed.sort(function (p, q) { return p.y - q.y; });
		var NH = 44, GAP = 6, lastBottom = -1e9, top = 0, bottom = work.height;
		for (var p = 0; p < placed.length; p++) {
			var yy = Math.max(placed[p].y - NH / 2, lastBottom + GAP, top + 4);
			if (yy + NH > bottom - 4) { yy = Math.max(top + 4, bottom - 4 - NH); }
			placed[p].top = yy; lastBottom = yy + NH;
		}
		var nodeW = Math.min(210, Math.max(150, (cx1 - cx0) - 40)); var nodeX = cx0 + ((cx1 - cx0) - nodeW) / 2;
		for (var q = 0; q < placed.length; q++) {
			var idx = placed[q].i; var rr = rules[idx]; var cls = 'ftmdNode' + (!rr.source ? ' ftmdConst' : '') + (idx === selected ? ' ftmdSel' : '') + (anyHot && !hot[idx] && idx !== selected ? ' ftmdOff' : '');
			var head = !rr.source ? t('designer.rule.constant') : t('designer.rule.transformation');
			nodes.push('<div class="' + cls + '" role="button" tabindex="' + (idx === roving ? '0' : '-1') + '" aria-pressed="' + (idx === selected) + '" aria-label="' + esc(ruleLabel(idx)) + '" data-rule="' + idx + '" style="left:' + nodeX + 'px;top:' + placed[q].top + 'px;width:' + nodeW + 'px" title="' + esc((rr.source || '') + ' → ' + rr.target + '\n' + rr.transformation) + '">'
				+ (rr.source ? '<span class="ftmdNodePort ftmdIn"></span>' : '') + '<span class="ftmdNodePort ftmdOut" data-nodeport="' + idx + '"></span>'
				+ '<div class="ftmdNodeHead"><span class="ftmdGear">&#9881;</span>' + esc(head) + '</div><div class="ftmdNodeBody">' + esc(rr.transformation || '') + '</div></div>');
			placed[q].cy = placed[q].top + NH / 2;
		}
		var byRule = {}; for (var z = 0; z < placed.length; z++) { byRule[placed[z].i] = placed[z]; }
		for (var k = 0; k < rules.length; k++) {
			var ru = rules[k]; var pa = ru.source ? anchor('L', ru.source) : null; var pb = anchor('R', ru.target);
			var cls2 = (k === selected ? ' ftmdSel' : '') + (hot[k] ? ' ftmdHot' : '') + (anyHot && !hot[k] && k !== selected ? ' ftmdOff' : '');
			var tip = '<title>' + esc((ru.source || t('designer.rule.constant')) + ' → ' + ru.target + (ru.transformation ? '\n' + ru.transformation : '')) + '</title>';
			// one stop per rule: the node when the rule has one, else its link; the other half of a link through a node is only decoration
			var pathRole = byRule[k] ? ' aria-hidden="true"' : ' role="button" tabindex="' + (k === roving ? '0' : '-1') + '" aria-pressed="' + (k === selected) + '" aria-label="' + esc(ruleLabel(k)) + '"';
			if (byRule[k]) {
				var node = byRule[k]; var nx0 = nodeX - 6, nx1 = nodeX + nodeW + 6;
				if (pa) { paths.push('<path data-rule="' + k + '" class="' + cls2 + '"' + pathRole + ' d="' + curve(pa.x, pa.y, nx0, node.cy) + '">' + tip + '</path>'); }
				paths.push('<path data-rule="' + k + '" class="' + cls2 + '"' + pathRole + ' d="' + curve(nx1, node.cy, pb.x, pb.y) + '">' + tip + '</path>');
			} else if (pa) {
				paths.push('<path data-rule="' + k + '" class="' + cls2 + '"' + pathRole + ' d="' + curve(pa.x, pa.y, pb.x, pb.y) + '">' + tip + '</path>');
			}
		}
		svg.innerHTML = paths.join('');
		var layer = el('ftmdNodes'); layer.innerHTML = nodes.join('');
		el('ftmdCanvasHint').style.display = rules.length ? 'none' : 'block';
		el('ftmdRuleCount').textContent = rules.length;
		var ps = svg.getElementsByTagName('path');
		for (var y2 = 0; y2 < ps.length; y2++) { ps[y2].onclick = function () { select(parseInt(this.getAttribute('data-rule'), 10)); }; }
		var ns = layer.getElementsByClassName('ftmdNode');
		for (var y3 = 0; y3 < ns.length; y3++) { ns[y3].onclick = function (e) { if (e.target.getAttribute('data-nodeport')) { return; } select(parseInt(this.getAttribute('data-rule'), 10)); }; }
		if (hadRule !== null) { var again = canvasItem(parseInt(hadRule, 10)); if (again) { again.focus(); } }
	}

	// ------------------------------------------------------------ selection + inspector
	function select(i) { selected = i; canvasRule = i; focusField = null; armedConstant = false; setPressed(el('ftmdConstBtn'), false); refreshAll(); }
	function stepsOf(pipeline) {
		var out = []; var s = pipeline || ''; var cur = '', depth = 0, quote = null;
		for (var i = 0; i < s.length; i++) {
			var c = s.charAt(i);
			if (quote) { cur += c; if (c === quote) { quote = null; } continue; }
			if (c === '\'' || c === '"') { quote = c; cur += c; continue; }
			if (c === '(') { depth++; } if (c === ')') { depth--; }
			if (c === '|' && depth === 0) { out.push(cur.trim()); cur = ''; continue; }
			cur += c;
		}
		if (cur.trim()) { out.push(cur.trim()); }
		var parsed = [];
		for (var j = 0; j < out.length; j++) { var m = /^([A-Za-z_][A-Za-z0-9_]*)\s*(?:\((.*)\))?\s*$/.exec(out[j]); parsed.push(m ? { name: m[1], args: m[2] === undefined ? null : m[2] } : { name: out[j], args: null }); }
		return parsed;
	}
	function joinSteps(steps) { var s = []; for (var i = 0; i < steps.length; i++) { s.push(steps[i].name + (steps[i].args === null ? '' : '(' + steps[i].args + ')')); } return s.join('|'); }
	function hintFor(name) { for (var i = 0; i < M.transformations.length; i++) { if (M.transformations[i].name.toLowerCase() === name.toLowerCase()) { return M.transformations[i].hint; } } return name; }
	function renderInspector() {
		var body = el('ftmdInsBody'); var head = el('ftmdInsTitle');
		if (selected < 0 || selected >= rules.length) {
			head.textContent = t('designer.inspector'); body.innerHTML = '<div class="ftmdInsEmpty">' + esc(t('designer.inspector.empty')) + '</div>'; return;
		}
		var r = rules[selected]; head.textContent = t('designer.inspector') + ' #' + (selected + 1);
		var steps = stepsOf(r.transformation); var h = [];
		h.push('<div class="ftmdFlow"><span class="ftmdChip ftmdSrc"><code>' + esc(r.source || '(' + t('designer.rule.constant') + ')') + '</code></span><span class="ftmdArrow">&#10140;</span>');
		for (var i = 0; i < steps.length; i++) {
			h.push('<span class="ftmdStep" title="' + esc(hintFor(steps[i].name)) + '"><b>' + esc(steps[i].name) + '</b>'
				+ (steps[i].args !== null ? '(<input type="text" data-step="' + i + '" aria-label="' + esc(t('designer.a11y.stepArgs', steps[i].name)) + '" value="' + esc(steps[i].args) + '"/>)' : '')
				+ '<button type="button" class="ftmdXBtn" data-del="' + i + '" title="' + esc(t('designer.step.remove')) + '" aria-label="' + esc(t('designer.a11y.removeStep', steps[i].name)) + '">&#10005;</button></span><span class="ftmdStepArrow">&#8250;</span>');
		}
		var opts = '<option value="">' + esc(t('designer.step.add')) + '…</option>';
		for (var o = 0; o < M.transformations.length; o++) { opts += '<option value="' + esc(M.transformations[o].hint) + '">' + esc(M.transformations[o].hint) + '</option>'; }
		h.push('<select class="ftmdAddStep" id="ftmdAddStep" aria-label="' + esc(t('designer.step.add')) + '">' + opts + '</select>');
		h.push('<span class="ftmdArrow">&#10140;</span><span class="ftmdChip ftmdDst"><code>' + esc(r.target) + '</code></span></div>');
		h.push('<div class="ftmdGrid">');
		if (!r.source) { h.push('<label for="ftmdConstVal">' + esc(t('designer.value')) + '</label><input type="text" id="ftmdConstVal" value="' + esc(constantOf(r.transformation)) + '"/>'); }
		h.push('<label for="ftmdRaw">' + esc(t('designer.pipeline.raw')) + '</label><input type="text" id="ftmdRaw" value="' + esc(r.transformation) + '" placeholder="trim|left(80)"/>');
		h.push('<label for="ftmdPolicy">' + esc(t('designer.rule.policy')) + '</label><select id="ftmdPolicy"><option value="IF_EMPTY"' + (r.policy === 'IF_EMPTY' ? ' selected' : '') + '>' + esc(t('designer.policy.IF_EMPTY')) + '</option><option value="ALWAYS"' + (r.policy === 'ALWAYS' ? ' selected' : '') + '>' + esc(t('designer.policy.ALWAYS')) + '</option><option value="TRUSTED"' + (r.policy === 'TRUSTED' ? ' selected' : '') + '>' + esc(t('designer.policy.TRUSTED')) + '</option></select>');
		h.push('<label for="ftmdComment">' + esc(t('designer.rule.comment')) + '</label><input type="text" id="ftmdComment" value="' + esc(r.comment || '') + '"/>');
		h.push('</div><div class="ftmdHint">' + esc(r.source ? t('designer.pipeline') : t('designer.constantHint')) + '</div>');
		h.push('<div class="ftmdInsFoot"><button type="button" class="ftmdBtn ftmdDanger" id="ftmdDelRule">' + esc(t('designer.rule.delete')) + '</button></div>');
		body.innerHTML = h.join('');
		var inputs = body.querySelectorAll('input[data-step]');
		for (var s = 0; s < inputs.length; s++) {
			inputs[s].oninput = function () { var st = stepsOf(r.transformation); st[parseInt(this.getAttribute('data-step'), 10)].args = this.value; r.transformation = joinSteps(st); el('ftmdRaw').value = r.transformation; sync(true); drawAll(); renderTable(); };
		}
		var dels = body.querySelectorAll('button[data-del]');
		for (var d = 0; d < dels.length; d++) { dels[d].onclick = function () { var st = stepsOf(r.transformation); st.splice(parseInt(this.getAttribute('data-del'), 10), 1); r.transformation = joinSteps(st); sync(true); refreshAll(); }; }
		el('ftmdAddStep').onchange = function () { if (!this.value) { return; } r.transformation = r.transformation ? r.transformation + '|' + this.value : this.value; sync(true); refreshAll(); };
		el('ftmdRaw').oninput = function () { r.transformation = this.value; sync(true); drawAll(); renderTable(); };
		el('ftmdRaw').onchange = function () { refreshAll(); };
		var cv = el('ftmdConstVal'); if (cv) { cv.oninput = function () { r.transformation = withConstant(r.transformation, this.value); el('ftmdRaw').value = r.transformation; sync(true); drawAll(); renderTable(); }; }
		el('ftmdPolicy').onchange = function () { r.policy = this.value; sync(true); renderTable(); };
		el('ftmdComment').oninput = function () { r.comment = this.value; sync(true); };
		el('ftmdDelRule').onclick = function () { removeRule(selected); };
	}
	function constantOf(pipeline) { var m = /constant\((.*?)\)/.exec(pipeline || ''); return m ? m[1] : ''; }
	function withConstant(pipeline, value) { var st = stepsOf(pipeline); var found = false; for (var i = 0; i < st.length; i++) { if (st[i].name === 'constant') { st[i].args = value; found = true; } } if (!found) { st.unshift({ name: 'constant', args: value }); } return joinSteps(st); }

	// ------------------------------------------------------------ table view
	function renderTable() {
		var tb = el('ftmdTable'); var hadRow = document.activeElement && tb.contains(document.activeElement) && document.activeElement.getAttribute ? document.activeElement.getAttribute('data-rule') : null; var h = ['<tr><th>#</th><th>' + esc(t('designer.rule.source')) + '</th><th>' + esc(t('designer.rule.transformation')) + '</th><th>' + esc(t('designer.rule.target')) + '</th><th>' + esc(t('designer.rule.policy')) + '</th><th>' + esc(t('designer.rule.comment')) + '</th></tr>'];
		for (var i = 0; i < rules.length; i++) {
			var r = rules[i];
			h.push('<tr data-rule="' + i + '" tabindex="' + (i === (canvasRule >= 0 && canvasRule < rules.length ? canvasRule : (selected >= 0 ? selected : 0)) ? '0' : '-1') + '"' + (i === selected ? ' aria-current="true"' : '') + ' class="' + (i === selected ? 'ftmdSel' : '') + '"><td>' + (i + 1) + '</td><td><code>' + esc(r.source || '(' + t('designer.rule.constant') + ')') + '</code></td><td><code>' + esc(r.transformation) + '</code></td><td><code>' + esc(r.target) + '</code></td><td>' + esc(t('designer.policy.' + r.policy)) + '</td><td>' + esc(r.comment || '') + '</td></tr>');
		}
		if (!rules.length) { h.push('<tr><td colspan="6" class="ftmdInsEmpty">' + esc(t('designer.noRules')) + '</td></tr>'); }
		tb.innerHTML = h.join('');
		var trs = tb.getElementsByTagName('tr');
		for (var j = 1; j < trs.length; j++) { trs[j].onclick = function () { var v = this.getAttribute('data-rule'); if (v !== null) { select(parseInt(v, 10)); } }; }
		if (hadRow !== null) { var back = tb.querySelector('tr[data-rule="' + hadRow + '"]'); if (back) { back.focus(); } }
	}

	// ------------------------------------------------------------ drag and drop
	var drag = null; // {fromSide:'L', path} or {node: ruleIndex}
	function tempPath(x1, y1, x2, y2) { var svg = el('ftmdSvg'); var old = el('ftmdTempPath'); if (old) { old.parentNode.removeChild(old); } var p = document.createElementNS('http://www.w3.org/2000/svg', 'path'); p.setAttribute('id', 'ftmdTempPath'); p.setAttribute('class', 'ftmdTemp'); p.setAttribute('d', curve(x1, y1, x2, y2)); svg.appendChild(p); }
	function endDrag() { var old = el('ftmdTempPath'); if (old) { old.parentNode.removeChild(old); } var rows = root.querySelectorAll('.ftmdDropOk'); for (var i = 0; i < rows.length; i++) { rows[i].className = rows[i].className.replace(' ftmdDropOk', ''); } drag = null; }
	function addRule(source, target, transformation) {
		// one rule per output field: dropping on an already mapped field re-points that rule
		var existing = -1;
		for (var i = 0; i < rules.length; i++) { if (rules[i].target === target && source) { existing = i; } }
		if (existing < 0 && needsPolicy()) { askPolicy(); return; }
		announce(t('designer.a11y.linked', source || t('designer.rule.constant'), target));
		if (existing >= 0) { rules[existing].source = source; selected = existing; sync(true); invalidateTrees(); refreshAll(); return; }
		rules.push({ id: 0, source: source || '', target: target, transformation: transformation || '', policy: policyFor(target), comment: '' });
		selected = rules.length - 1; sync(true); invalidateTrees(); refreshAll();
	}
	function removeRule(i) { rules.splice(i, 1); selected = -1; canvasRule = -1; sync(true); invalidateTrees(); refreshAll(); announce(t('designer.a11y.removed')); }
	function invalidateTrees() { trees.L = null; trees.R = null; }
	root.addEventListener('mousedown', function (e) {
		var port = e.target; if (!port.getAttribute) { return; }
		if (port.getAttribute('data-port') === 'L') { drag = { fromSide: 'L', path: port.getAttribute('data-path') }; e.preventDefault(); }
		else if (port.getAttribute('data-nodeport') !== null && port.getAttribute('data-nodeport') !== undefined && port.className.indexOf('ftmdOut') >= 0) { drag = { node: parseInt(port.getAttribute('data-nodeport'), 10) }; e.preventDefault(); }
	});
	document.addEventListener('mousemove', function (e) {
		if (!drag) { return; }
		var work = el('ftmdWork').getBoundingClientRect(); var from;
		if (drag.fromSide) { from = anchor('L', drag.path); } else { var nd = el('ftmdNodes').querySelector('.ftmdNode[data-rule="' + drag.node + '"]'); var nr = nd.getBoundingClientRect(); from = { x: nr.right - work.left, y: nr.top + nr.height / 2 - work.top }; }
		tempPath(from.x, from.y, e.clientX - work.left, e.clientY - work.top);
		var rows = root.querySelectorAll('.ftmdDropOk'); for (var i = 0; i < rows.length; i++) { rows[i].className = rows[i].className.replace(' ftmdDropOk', ''); }
		var row = rowAt(e.target, 'R'); if (row) { row.className += ' ftmdDropOk'; }
	});
	document.addEventListener('mouseup', function (e) {
		if (!drag) { return; }
		var row = rowAt(e.target, 'R'); var d = drag; endDrag();
		if (!row) { return; }
		var target = row.getAttribute('data-path'); if (!target) { return; }
		if (d.fromSide) { addRule(d.path, target, ''); }
		else { rules[d.node].target = target; selected = d.node; sync(true); invalidateTrees(); refreshAll(); }
	});
	function rowAt(node, side) { var n = node; while (n && n !== root) { if (n.getAttribute && n.getAttribute('data-side') === side && n.className.indexOf('ftmdRow') >= 0) { return n; } n = n.parentNode; } return null; }

	// ------------------------------------------------------------ clicks in trees
	root.addEventListener('click', function (e) {
		var tg = e.target; if (!tg.getAttribute) { return; }
		var toggle = tg.getAttribute('data-toggle');
		if (toggle !== null && toggle !== undefined && tg.className.indexOf('ftmdLeaf') < 0) { var side = rowAt(tg, 'L') ? 'L' : 'R'; expanded[side][toggle] = expanded[side][toggle] === false; renderTree(side); drawAll(); return; }
		if (tg.getAttribute('data-port')) { return; }
		var row = rowAt(tg, 'L') || rowAt(tg, 'R'); if (!row) { return; }
		activateRow(row);
	});
	// a click or Enter / Space on a tree row: open a group, pick a source, link the picked source to a target, or select the rule of a target
	function activateRow(row) {
		var path = row.getAttribute('data-path'); var sd = row.getAttribute('data-side');
		if (!path) { var key = row.getAttribute('data-key'); expanded[sd][key] = expanded[sd][key] === false; renderTree(sd); drawAll(); return; }
		if (sd === 'R' && armedConstant) { armedConstant = false; setPressed(el('ftmdConstBtn'), false); addRule('', path, 'constant()'); return; }
		if (sd === 'R' && focusField && focusField.side === 'L') { addRule(focusField.path, path, ''); return; }
		if (sd === 'R') { for (var i = 0; i < rules.length; i++) { if (rules[i].target === path) { select(i); return; } } }
		focusField = focusField && focusField.side === sd && focusField.path === path ? null : { side: sd, path: path }; selected = -1;
		if (focusField && sd === 'L') { announce(t('designer.a11y.picked', path)); }
		renderTree('L'); renderTree('R'); drawAll(); renderInspector(); renderTable();
	}
	// a toggle button: the pressed state is said to a screen reader and shown by a check mark, not by the colour alone
	function treeKey(e, row) {
		var side = row.getAttribute('data-side'); var all = Array.prototype.slice.call(el(side === 'L' ? 'ftmdTreeL' : 'ftmdTreeR').querySelectorAll('.ftmdRow'));
		var at = all.indexOf(row); var key = row.getAttribute('data-key'); var path = row.getAttribute('data-path');
		var group = !path; var open = row.getAttribute('aria-expanded') === 'true'; var move = null;
		if (e.key === 'ArrowDown') { move = all[at + 1]; }
		else if (e.key === 'ArrowUp') { move = all[at - 1]; }
		else if (e.key === 'Home') { move = all[0]; }
		else if (e.key === 'End') { move = all[all.length - 1]; }
		else if (e.key === 'ArrowRight') {
			if (!group) { return; }
			if (!open) { e.preventDefault(); expanded[side][key] = true; renderTree(side); drawAll(); return; }
			move = all[at + 1];
		} else if (e.key === 'ArrowLeft') {
			if (group && open) { e.preventDefault(); expanded[side][key] = false; renderTree(side); drawAll(); return; }
			var level = parseInt(row.getAttribute('aria-level'), 10);
			for (var k = at - 1; k >= 0; k--) { if (parseInt(all[k].getAttribute('aria-level'), 10) < level) { move = all[k]; break; } }
		} else if (isActivate(e)) { e.preventDefault(); activateRow(row); return; }
		else if ((e.key === 'Delete' || e.key === 'Backspace') && side === 'R' && path) {
			for (var i = 0; i < rules.length; i++) { if (rules[i].target === path) { e.preventDefault(); askConfirmRemove(i); return; } }
			return;
		} else { return; }
		if (move) { e.preventDefault(); move.focus(); }
	}
	function askConfirmRemove(i) { askConfirm(t('designer.dialog.deleteRule'), function (yes) { if (yes && i < rules.length) { removeRule(i); } }); }
	function canvasKey(e, item) {
		var k = parseInt(item.getAttribute('data-rule'), 10);
		if (isActivate(e)) { e.preventDefault(); select(k); return; }
		if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); askConfirmRemove(k); return; }
		var next = rovingKey(e, rules, k);
		if (next >= 0 && next !== k) { var target = canvasItem(next); if (target) { e.preventDefault(); target.focus(); } }
	}
	function tableKey(e, tr) {
		var k = parseInt(tr.getAttribute('data-rule'), 10);
		if (isActivate(e)) { e.preventDefault(); select(k); return; }
		var next = rovingKey(e, rules, k);
		if (next >= 0 && next !== k) { var target = el('ftmdTable').querySelector('tr[data-rule="' + next + '"]'); if (target) { e.preventDefault(); target.focus(); } }
	}
	root.addEventListener('keydown', function (e) {
		if (dialogOpen || e.altKey || e.ctrlKey || e.metaKey) { return; }
		var tg = e.target; if (!tg || !tg.getAttribute) { return; }
		if (hasClass(tg, 'ftmdRow')) { treeKey(e, tg); }
		else if (canvasItemOf(tg)) { canvasKey(e, tg); }
		else if (tg.tagName === 'TR' && tg.getAttribute('data-rule') !== null) { tableKey(e, tg); }
	});
	// the focused stop becomes the one in the tab order of its list
	root.addEventListener('focusin', function (e) {
		var tg = e.target; if (!tg || !tg.getAttribute) { return; }
		if (hasClass(tg, 'ftmdRow')) { var sd = tg.getAttribute('data-side'); tabKey[sd] = tg.getAttribute('data-key'); setRoving(el(sd === 'L' ? 'ftmdTreeL' : 'ftmdTreeR').querySelectorAll('.ftmdRow'), tg); }
		else if (canvasItemOf(tg)) { canvasRule = parseInt(tg.getAttribute('data-rule'), 10); setRoving(canvasItems(), tg); }
		else if (tg.tagName === 'TR' && tg.getAttribute('data-rule') !== null) { canvasRule = parseInt(tg.getAttribute('data-rule'), 10); setRoving(el('ftmdTable').querySelectorAll('tr[data-rule]'), tg); }
	});
	function setPressed(btn, on) { if (!btn) { return; } btn.setAttribute('aria-pressed', on ? 'true' : 'false'); btn.className = btn.className.replace(/ ?ftmdArmed/g, '') + (on ? ' ftmdArmed' : ''); }
	root.addEventListener('dblclick', function (e) { var row = rowAt(e.target, 'L') || rowAt(e.target, 'R'); if (!row || row.getAttribute('data-path')) { return; } });

	// ------------------------------------------------------------ write policy of the rules added (feed)
	function policySection() {
		var h = ['<div class="ftmdPolicy" role="radiogroup" aria-labelledby="ftmdPolicyTitle" aria-describedby="ftmdPolicyHint"><span class="ftmdPolicyTitle" id="ftmdPolicyTitle">' + esc(t('designer.newPolicy.title')) + '</span>'];
		var options = ['IF_EMPTY', 'ALWAYS', 'TRUSTED'];
		for (var i = 0; i < options.length; i++) {
			h.push('<label><input type="radio" name="ftmdNewPolicy" value="' + options[i] + '"' + (newPolicy === options[i] ? ' checked' : '') + '/>' + esc(t('designer.policy.' + options[i])) + '</label>');
		}
		h.push('<button type="button" class="ftmdBtn" id="ftmdPolicyApply"' + (newPolicy && rules.length ? '' : ' disabled') + '>' + esc(t('designer.newPolicy.apply')) + '</button>');
		h.push('<div class="ftmdPolicyHint" id="ftmdPolicyHint">' + esc(t('designer.newPolicy.hint')) + '</div>');
		h.push('<div class="ftmdMsg ftmdErr" id="ftmdPolicyMsg" role="alert" hidden>' + esc(t('designer.newPolicy.required')) + '</div></div>');
		return h.join('');
	}
	function wirePolicy() {
		var radios = root.querySelectorAll('input[name="ftmdNewPolicy"]');
		for (var i = 0; i < radios.length; i++) {
			radios[i].onchange = function () { newPolicy = this.value; var msg = el('ftmdPolicyMsg'); if (msg) { msg.hidden = true; } var apply = el('ftmdPolicyApply'); if (apply) { apply.disabled = !rules.length; } };
		}
		var applyBtn = el('ftmdPolicyApply');
		if (applyBtn) {
			applyBtn.onclick = function () {
				if (!newPolicy) { askPolicy(); return; }
				// the rules already there take the choice, their key fields excepted
				for (var r = 0; r < rules.length; r++) { if (!isKeyTarget(rules[r].target)) { rules[r].policy = newPolicy; } }
				sync(true); refreshAll(); announce(t('designer.newPolicy.applied', t('designer.policy.' + newPolicy)));
			};
		}
	}
	function askPolicy() {
		var msg = el('ftmdPolicyMsg'); if (msg) { msg.hidden = false; }
		announce(t('designer.newPolicy.required'));
		var first = root.querySelector('input[name="ftmdNewPolicy"]'); if (first) { first.focus(); }
	}

	// ------------------------------------------------------------ page
	function build() {
		var leftName = inbound ? t('designer.inputOf') + ' ' + M.connector + ' / ' + M.operation : 'EBX ' + M.table;
		var rightName = inbound ? 'EBX ' + M.table : t('designer.responseOf') + ' ' + M.connector + ' / ' + M.operation;
		var h = [];
		h.push('<div class="ftmdBar">');
		h.push('<div id="ftmdLive" class="ftmdSr" role="status" aria-live="polite"></div><div id="ftmdKbHint" class="ftmdSr">' + esc(t('designer.a11y.keyboardHint')) + '</div>');
		h.push('<label for="ftmdDir">' + esc(t('designer.direction')) + '</label><select id="ftmdDir"><option value="INBOUND"' + (inbound ? ' selected' : '') + '>' + esc(M.connector) + ' &rarr; EBX</option><option value="OUTBOUND"' + (!inbound ? ' selected' : '') + '>EBX &rarr; ' + esc(M.connector) + '</option></select>');
		h.push('<label for="ftmdRootField">' + esc(t('designer.responseRoot')) + '</label><input type="text" id="ftmdRootField" value="' + esc(M.responseRoot) + '" size="18"/>');
		h.push('<button type="button" class="ftmdBtn" id="ftmdConstBtn" aria-pressed="false">&#65291; ' + esc(t('designer.addConstant')) + '</button>');
		h.push('<span class="ftmdSp"></span>');
		h.push('<span class="ftmdDirty" id="ftmdDirty" role="status">&#9679; ' + esc(t('designer.unsaved')) + '</span>');
		h.push('<label class="ftmdToggle"><input type="checkbox" id="ftmdHide"/>' + esc(t('designer.hideUnmapped')) + '</label>');
		h.push('<span><span class="ftmdBadgeCount" id="ftmdRuleCount">0</span> ' + esc(t('designer.rules')) + '</span>');
		h.push('<span class="ftmdSeg"><button type="button" id="ftmdViewD" class="ftmdOn" aria-pressed="true">' + esc(t('designer.viewDiagram')) + '</button><button type="button" id="ftmdViewT" aria-pressed="false">' + esc(t('designer.viewTable')) + '</button></span>');
		h.push('<span class="ftmdZoom"><button type="button" class="ftmdBtn" id="ftmdZoomOut" title="' + esc(t('designer.zoomOut')) + '" aria-label="' + esc(t('designer.zoomOut')) + '">&#8722;</button><span id="ftmdZoomPct" role="status">100%</span><button type="button" class="ftmdBtn" id="ftmdZoomIn" title="' + esc(t('designer.zoomIn')) + '" aria-label="' + esc(t('designer.zoomIn')) + '">&#43;</button></span>');
		h.push('<button type="button" class="ftmdBtn" id="ftmdFullBtn" aria-pressed="false" title="' + esc(t('designer.fullscreen')) + '" aria-label="' + esc(t('designer.fullscreen')) + '">&#x26F6;</button>');
		h.push('</div>');
		h.push(callSection());
		if (inbound && M.policyChoice) { h.push(policySection()); }
		h.push('<div class="ftmdWork" id="ftmdWork">');
		h.push(pane('L', t('designer.input'), leftName));
		h.push('<div class="ftmdCanvas" id="ftmdCanvas"><div class="ftmdCanvasHint" id="ftmdCanvasHint">' + esc(t('designer.inspector.empty')) + '</div></div>');
		h.push(pane('R', t('designer.output'), rightName));
		h.push('<svg class="ftmdSvg" id="ftmdSvg"></svg><div class="ftmdNodesLayer" id="ftmdNodes"></div>');
		h.push('</div>');
		h.push('<table class="ftmdTable" id="ftmdTable"></table>');
		h.push('<div class="ftmdInspector"><div class="ftmdInsHead"><span id="ftmdInsTitle"></span><span class="ftmdSp"></span></div><div class="ftmdInsBody" id="ftmdInsBody"></div></div>');
		if (!inbound) { h.push(responseSection()); }
		h.push(childrenSection());
		root.innerHTML = h.join('');
		wireCall();
		wirePolicy();
		el('ftmdDir').onchange = function () {
			var sel = this;
			function change() { inbound = sel.value === 'INBOUND'; rules = []; selected = -1; canvasRule = -1; focusField = null; invalidateTrees(); sync(true); build(); var again = el('ftmdDir'); if (again) { again.focus(); } }
			if (!rules.length) { change(); return; }
			askConfirm(t('designer.confirmDirection'), function (yes) { if (yes) { change(); } else { sel.value = inbound ? 'INBOUND' : 'OUTBOUND'; sel.focus(); } });
		};
		el('ftmdRootField').oninput = function () { sync(true); };
		el('ftmdConstBtn').onclick = function () { armedConstant = !armedConstant; setPressed(this, armedConstant); el('ftmdCanvasHint').style.display = armedConstant ? 'block' : (rules.length ? 'none' : 'block'); el('ftmdCanvasHint').textContent = armedConstant ? t('designer.pickTarget') : t('designer.inspector.empty'); };
		el('ftmdHide').onchange = function () { hideUnmapped = this.checked; renderTree('L'); renderTree('R'); drawAll(); };
		el('ftmdZoomIn').onclick = function () { setScale(scale + 0.1); };
		el('ftmdZoomOut').onclick = function () { setScale(scale - 0.1); };
		el('ftmdFullBtn').onclick = function () { toggleFull(); };
		el('ftmdWork').addEventListener('wheel', function (e) { if (e.ctrlKey || e.metaKey) { e.preventDefault(); setScale(scale + (e.deltaY < 0 ? 0.1 : -0.1)); } }, { passive: false });
		el('ftmdViewD').onclick = function () { tableMode = false; viewMode(); };
		el('ftmdViewT').onclick = function () { tableMode = true; viewMode(); };
		el('ftmdSearchL').oninput = function () { filters.L = this.value; renderTree('L'); drawAll(); };
		el('ftmdSearchR').oninput = function () { filters.R = this.value; renderTree('R'); drawAll(); };
		el('ftmdExpL').onclick = function () { setAll('L', true); }; el('ftmdColL').onclick = function () { setAll('L', false); };
		el('ftmdExpR').onclick = function () { setAll('R', true); }; el('ftmdColR').onclick = function () { setAll('R', false); };
		el('ftmdTreeL').addEventListener('scroll', drawAll); el('ftmdTreeR').addEventListener('scroll', drawAll); window.addEventListener('resize', drawAll);
		wireChildren();
		if (window.ResizeObserver) { new ResizeObserver(function () { drawAll(); }).observe(el('ftmdWork')); }
		refreshAll();
	}
	function pane(side, title, name) {
		return '<div class="ftmdPane ' + (side === 'L' ? 'ftmdLeftPane' : 'ftmdRightPane') + '"><div class="ftmdPaneHead"><div class="ftmdPaneTitle" id="ftmdPaneTitle' + side + '">' + esc(title) + '<small title="' + esc(name) + '">' + esc(name) + '</small></div>'
			+ '<div class="ftmdSearch"><input type="text" id="ftmdSearch' + side + '" aria-label="' + esc(t('designer.search') + ' ' + title) + '" placeholder="' + esc(t('designer.search')) + '"/><span aria-hidden="true">&#128269;</span></div>'
			+ '<div class="ftmdTreeTools"><button type="button" class="ftmdLinkBtn" id="ftmdExp' + side + '">' + esc(t('designer.expandAll')) + '</button><button type="button" class="ftmdLinkBtn" id="ftmdCol' + side + '">' + esc(t('designer.collapseAll')) + '</button><span class="ftmdSp" style="flex:1"></span><span id="ftmdCount' + side + '"></span></div></div>'
			+ '<div class="ftmdTree" id="ftmdTree' + side + '" role="tree" aria-labelledby="ftmdPaneTitle' + side + '" aria-describedby="ftmdKbHint"></div></div>';
	}
	var scale = 1;
	function setScale(v) { scale = Math.max(0.6, Math.min(1.8, Math.round(v * 10) / 10)); el('ftmdWork').style.setProperty('--ftmd-scale', scale); el('ftmdZoomPct').textContent = Math.round(scale * 100) + '%'; drawAll(); }
	function toggleFull() {
		var on = root.className.indexOf('ftmdFull') < 0;
		root.className = on ? 'ftmdFull' : '';
		setPressed(el('ftmdFullBtn'), on);
		drawAll();
	}
	document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !dialogOpen && root.className.indexOf('ftmdFull') >= 0) { toggleFull(); } });
	// the work area is resizable by its corner: redraw the links while it changes size
	function viewMode() { el('ftmdWork').className = 'ftmdWork' + (tableMode ? ' ftmdTableMode' : ''); el('ftmdTable').className = 'ftmdTable' + (tableMode ? ' ftmdOn' : ''); el('ftmdViewD').className = tableMode ? '' : 'ftmdOn'; el('ftmdViewT').className = tableMode ? 'ftmdOn' : ''; el('ftmdViewD').setAttribute('aria-pressed', tableMode ? 'false' : 'true'); el('ftmdViewT').setAttribute('aria-pressed', tableMode ? 'true' : 'false'); if (!tableMode) { drawAll(); } }
	function refreshAll() { renderTree('L'); renderTree('R'); renderTable(); renderInspector(); drawAll(); sync(false); }

	// ------------------------------------------------------------ the call: inputs sent to the service
	function ebxOptions() {
		var o = '<option value="">' + esc(t('designer.input.insertField')) + '</option>';
		for (var i = 0; i < M.ebxFields.length; i++) { var f = M.ebxFields[i]; o += '<option value="' + esc('${' + f.path + '}') + '">' + esc((f.groupPath ? f.groupPath + ' / ' : '') + f.label) + '</option>'; }
		return o;
	}
	function callSection() {
		var h = ['<div class="ftmdCall" id="ftmdCall"><div class="ftmdCallHead"><span class="ftmdGear">&#8594;</span>' + (M.connectorLogo ? '<img class="ftmdLogo" src="' + esc(M.connectorLogo) + '" alt=""/>' : '') + esc(t('designer.serviceCall')) + ' <small>' + esc(M.connector + ' / ' + M.operation) + (M.operationDescription ? ' — ' + M.operationDescription : '') + '</small><span class="ftmdSp"></span><button type="button" class="ftmdLinkBtn" id="ftmdCallToggle" aria-expanded="true" aria-controls="ftmdCallBody" aria-label="' + esc(t('designer.serviceCall')) + '">&#9662;</button></div><div class="ftmdCallBody" id="ftmdCallBody">'];
		h.push('<div class="ftmdHint">' + esc(inbound ? t('designer.serviceCall.hint') : t('designer.serviceCall.hintOut')) + '</div>');
		if (M.connectorError) { h.push('<div class="ftmdMsg ftmdErr">' + esc(t('designer.connectorError', M.connectorError)) + '</div>'); }
		if ((M.sourceObjects && M.sourceObjects.length) || M.sourceDiscoveryError) {
			h.push('<div class="ftmdSourcePicker"><label for="ftmdSourceObject">' + esc(t('designer.sourceTable')) + '</label>');
			if (M.sourceObjects && M.sourceObjects.length) {
				h.push('<select id="ftmdSourceObject"><option value="">' + esc(t('designer.sourceTable.placeholder')) + '</option>');
				for (var so = 0; so < M.sourceObjects.length; so++) { var obj = M.sourceObjects[so]; h.push('<option value="' + so + '">' + esc(obj.id + (obj.type ? ' — ' + obj.type : '')) + '</option>'); }
				h.push('</select><small>' + esc(t('designer.sourceTable.hint')) + '</small>');
			} else { h.push('<span class="ftmdSourceUnavailable">' + esc(t('designer.sourceTable.unavailable', M.sourceDiscoveryError || '')) + '</span>'); }
			h.push('</div>');
		}
		h.push('<div id="ftmdInputsRows"></div>');
		h.push('<div class="ftmdInputAdd"><button type="button" class="ftmdBtn" id="ftmdInputAdd">&#65291; ' + esc(t('designer.input.add')) + '</button></div>');
		h.push('</div></div>');
		return h.join('');
	}
	// an input expression is "<text with ${placeholders}> |> <pipeline>": the pipeline transforms the value sent
	function splitInput(expression) { var i = (expression || '').lastIndexOf('|>'); return i < 0 ? { expr: expression || '', pipeline: '' } : { expr: expression.substring(0, i).replace(/\s+$/, ''), pipeline: expression.substring(i + 2).trim() }; }
	function joinInput(expr, pipeline) { return pipeline ? expr + ' |> ' + pipeline : expr; }
	function renderInputs() {
		var box = el('ftmdInputsRows'); if (!box) { return; }
		var h = [];
		for (var i = 0; i < inputs.length; i++) {
			var x = inputs[i]; var parts = splitInput(x.expression); var steps = stepsOf(parts.pipeline);
			h.push('<div class="ftmdInputRow"><span class="ftmdInputName' + (x.required ? ' ftmdReq' : '') + '" title="' + esc(x.required ? t('designer.input.required') : t('designer.input.optional')) + '">' + esc(x.name) + (x.required ? ' *' : '') + '</span>'
				+ '<input type="text" data-input="' + i + '" aria-label="' + esc(x.name) + '" value="' + esc(parts.expr) + '" placeholder="' + esc(t('designer.input.expression')) + '"/>'
				+ '<select data-insert="' + i + '" aria-label="' + esc(t('designer.input.insertField')) + '">' + ebxOptions() + '</select>'
				+ (x.required ? '<span></span>' : '<button type="button" class="ftmdXBtn" data-rminput="' + i + '" title="' + esc(t('designer.step.remove')) + '" aria-label="' + esc(t('designer.step.remove') + ': ' + x.name) + '">&#10005;</button>'));
			h.push('<span></span><div class="ftmdSteps ftmdInputSteps"><span class="ftmdStepArrow">&#8627;</span>');
			for (var st = 0; st < steps.length; st++) {
				h.push('<span class="ftmdStep" title="' + esc(hintFor(steps[st].name)) + '"><b>' + esc(steps[st].name) + '</b>'
					+ (steps[st].args !== null ? '(<input type="text" data-istep="' + i + ':' + st + '" aria-label="' + esc(t('designer.a11y.stepArgs', steps[st].name)) + '" value="' + esc(steps[st].args) + '"/>)' : '')
					+ '<button type="button" class="ftmdXBtn" data-idel="' + i + ':' + st + '" title="' + esc(t('designer.step.remove')) + '" aria-label="' + esc(t('designer.a11y.removeStep', steps[st].name)) + '">&#10005;</button></span><span class="ftmdStepArrow">&#8250;</span>');
			}
			var opts = '<option value="">' + esc(steps.length ? t('designer.step.add') : t('designer.input.transform')) + '…</option>';
			for (var o = 0; o < M.transformations.length; o++) { opts += '<option value="' + esc(M.transformations[o].hint) + '">' + esc(M.transformations[o].hint) + '</option>'; }
			h.push('<select class="ftmdAddStep" data-iadd="' + i + '" aria-label="' + esc(t('designer.step.add')) + ': ' + esc(x.name) + '">' + opts + '</select></div><span></span><span></span></div>');
		}
		box.innerHTML = h.join('');
		function setPipeline(k, pipeline) { var p = splitInput(inputs[k].expression); inputs[k].expression = joinInput(p.expr, pipeline); sync(true); }
		var texts = box.querySelectorAll('input[data-input]');
		for (var a = 0; a < texts.length; a++) { texts[a].oninput = function () { var k = parseInt(this.getAttribute('data-input'), 10); inputs[k].expression = joinInput(this.value, splitInput(inputs[k].expression).pipeline); sync(true); }; }
		var sels = box.querySelectorAll('select[data-insert]');
		for (var b = 0; b < sels.length; b++) { sels[b].onchange = function () { if (!this.value) { return; } var k = parseInt(this.getAttribute('data-insert'), 10); var tx = box.querySelector('input[data-input="' + k + '"]'); tx.value = tx.value ? tx.value + ' ' + this.value : this.value; inputs[k].expression = joinInput(tx.value, splitInput(inputs[k].expression).pipeline); this.value = ''; sync(true); }; }
		var rms = box.querySelectorAll('button[data-rminput]');
		for (var c = 0; c < rms.length; c++) { rms[c].onclick = function () { inputs.splice(parseInt(this.getAttribute('data-rminput'), 10), 1); sync(true); renderInputs(); }; }
		var adds = box.querySelectorAll('select[data-iadd]');
		for (var d = 0; d < adds.length; d++) { adds[d].onchange = function () { if (!this.value) { return; } var k = parseInt(this.getAttribute('data-iadd'), 10); var p = splitInput(inputs[k].expression).pipeline; setPipeline(k, p ? p + '|' + this.value : this.value); renderInputs(); }; }
		var iargs = box.querySelectorAll('input[data-istep]');
		for (var e = 0; e < iargs.length; e++) { iargs[e].oninput = function () { var kv = this.getAttribute('data-istep').split(':'); var k = parseInt(kv[0], 10); var st = stepsOf(splitInput(inputs[k].expression).pipeline); st[parseInt(kv[1], 10)].args = this.value; setPipeline(k, joinSteps(st)); }; }
		var idels = box.querySelectorAll('button[data-idel]');
		for (var f = 0; f < idels.length; f++) { idels[f].onclick = function () { var kv = this.getAttribute('data-idel').split(':'); var k = parseInt(kv[0], 10); var st = stepsOf(splitInput(inputs[k].expression).pipeline); st.splice(parseInt(kv[1], 10), 1); setPipeline(k, joinSteps(st)); renderInputs(); }; }
	}
	function wireCall() {
		renderInputs();
		var source = el('ftmdSourceObject'); if (source) { source.onchange = function () {
			if (this.value === '') { return; }
			var object = M.sourceObjects[parseInt(this.value, 10)]; var sql = -1;
			for (var i = 0; i < inputs.length; i++) { if (inputs[i].name === 'sql') { sql = i; break; } }
			if (!object || sql < 0) { return; }
			var pipeline = splitInput(inputs[sql].expression).pipeline;
			inputs[sql].expression = joinInput(object.query, pipeline);
			sync(true); renderInputs();
			// The selected source changes both the SQL and its response schema. Reuse the existing
			// bounded, read-only discovery action so the input canvas refreshes with the query.
			var loader = document.getElementById('ftmdSample'); if (loader) { loader.click(); }
		}; }
		var tg = el('ftmdCallToggle'); if (tg) { tg.onclick = function () { var b = el('ftmdCallBody'); var off = b.style.display === 'none'; b.style.display = off ? '' : 'none'; this.innerHTML = off ? '&#9662;' : '&#9656;'; this.setAttribute('aria-expanded', off ? 'true' : 'false'); }; }
		var add = el('ftmdInputAdd'); if (add) { add.onclick = function () { askText(t('designer.input.name'), function (n) { if (!n) { add.focus(); return; } inputs.push({ name: n, expression: '', required: false }); sync(true); renderInputs(); var fresh = el('ftmdInputsRows').querySelector('input[data-input="' + (inputs.length - 1) + '"]'); if (fresh) { fresh.focus(); } }); }; }
	}
	function responseSection() {
		var h = ['<div class="ftmdResponse"><div class="ftmdCallHead"><span class="ftmdGear">&#8592;</span>' + esc(t('designer.responseSection')) + '</div><div class="ftmdCallBody">'];
		h.push('<div class="ftmdHint">' + esc(t('designer.responseSection.hint')) + '</div>');
		if (M.responseMapping) { h.push('<div class="ftmdCards"><div class="ftmdCard"><b>#' + M.responseMapping.id + ' ' + esc(M.responseMapping.label) + '</b><small>' + M.responseMapping.rules + ' ' + esc(t('designer.rules')) + ' · ' + esc(t('designer.responseSection.open')) + '</small></div></div>'); }
		else { h.push('<div class="ftmdInsEmpty">' + esc(t('designer.responseSection.none')) + '</div>'); }
		h.push('</div></div>');
		return h.join('');
	}

	// ------------------------------------------------------------ sub-object mappings (kept from the first version)
	function childrenSection() {
		var h = ['<div class="ftmdChildren"><div class="ftmdChildHead">' + esc(t('designer.children')) + (M.isChild ? ' — ' + esc(t('designer.child.of')) + ' <code>' + esc(M.collectionPath) + '</code> (' + esc(M.parentLinkField) + ')' : '') + '</div><div class="ftmdChildBody">'];
		if (M.children.length) {
			h.push('<div class="ftmdCards">');
			for (var i = 0; i < M.children.length; i++) { var c = M.children[i]; h.push('<div class="ftmdCard"><b>#' + c.id + ' ' + esc(c.label) + '</b><code>' + esc(c.collectionPath) + ' &rarr; ' + esc(c.tablePath) + '</code><br/><small>' + c.rules + ' ' + esc(t('designer.rules')) + ' · ' + esc(t('designer.child.open')) + '</small></div>'); }
			h.push('</div>');
		}
		var tables = ''; for (var x = 0; x < M.tables.length; x++) { tables += '<option value="' + esc(M.tables[x]) + '">' + esc(M.tables[x]) + '</option>'; }
		h.push('<div class="ftmdHint">' + esc(t('designer.child.hint')) + '</div>');
		h.push('<div class="ftmdChildForm"><label>' + esc(t('designer.child.collection')) + '</label><input type="text" id="ftmdCc" size="22"/><label>' + esc(t('designer.child.table')) + '</label><select id="ftmdCt">' + tables + '</select><label>' + esc(t('designer.child.link')) + '</label><input type="text" id="ftmdCl" size="16"/><label>' + esc(t('designer.child.keys')) + '</label><input type="text" id="ftmdCk" size="16"/></div>');
		h.push('</div></div>');
		return h.join('');
	}
	function wireChildren() {
		var f = function (src, dst) { var e = el(src); if (!e) { return; } var s2 = function () { el(dst).value = e.value; }; e.onchange = s2; e.oninput = s2; s2(); };
		f('ftmdCc', 'ftmdChildCollection'); f('ftmdCt', 'ftmdChildTable'); f('ftmdCl', 'ftmdChildLink'); f('ftmdCk', 'ftmdChildKeys');
		root.addEventListener('click', function (e) { var row = rowAt(e.target, inbound ? 'L' : 'R'); if (!row) { return; } var key = row.getAttribute('data-key'); var cc = el('ftmdCc'); if (cc && !cc.value && key && /\[\d*\*?\]$/.test(key)) { cc.value = key.replace(/\[\d*\*?\]$/, '[*]'); el('ftmdChildCollection').value = cc.value; } });
	}

	build();
})();
