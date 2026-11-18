// InkLeaf pager: the chapter is laid out in CSS columns, one column per screen.
(function () {
  var IL = window.IL = { page: 0, count: 1, W: 1, ready: false };

  function measure() {
    // Layout-viewport width: unaffected by any zoom the WebView applies.
    IL.W = document.documentElement.clientWidth || window.innerWidth || 1;
    var sw = Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0);
    IL.count = Math.max(1, Math.ceil((sw - 2) / IL.W));
  }

  function report() {
    try { InkLeaf.onPage(IL.page, IL.count); } catch (e) {}
  }

  IL.go = function (p) {
    measure();
    if (p > IL.count - 1) p = IL.count - 1;
    if (p < 0) p = 0;
    IL.page = p;
    window.scrollTo(p * IL.W, 0);
    report();
  };

  IL.next = function () {
    if (!IL.ready) return 'busy';
    measure();
    if (IL.page < IL.count - 1) { IL.clearMark(); IL.go(IL.page + 1); return 'ok'; }
    return 'end';
  };

  IL.prev = function () {
    if (!IL.ready) return 'busy';
    if (IL.page > 0) { IL.clearMark(); IL.go(IL.page - 1); return 'ok'; }
    return 'start';
  };

  IL.anchor = function (id) {
    var el = document.getElementById(id) || document.getElementsByName(id)[0];
    if (!el) { IL.go(0); return; }
    measure();
    var x = el.getBoundingClientRect().left + window.scrollX;
    IL.go(Math.floor((x + 1) / IL.W));
  };

  // x, y arrive in device pixels.
  IL.linkAt = function (x, y) {
    var r = window.devicePixelRatio || 1;
    var el = document.elementFromPoint(x / r, y / r);
    while (el && !(el.tagName && el.tagName.toLowerCase() === 'a')) el = el.parentElement;
    if (!el) return '';
    var h = el.getAttribute('href') || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || '';
    if (!h) return '';
    try { return new URL(h, document.baseURI).href; } catch (e) { return ''; }
  };


  // ---- marks (dictionary word / search match) ----
  IL.clearMark = function () {
    var marks = document.querySelectorAll('span.ilmark');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], parent = m.parentNode;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    }
  };

  function wrap(node, start, end) {
    if (end <= start) return null;
    var r = document.createRange();
    r.setStart(node, start);
    r.setEnd(node, end);
    var sp = document.createElement('span');
    sp.className = 'ilmark';
    try { r.surroundContents(sp); return sp; } catch (e) { return null; }
  }

  var WORD = /[\p{L}\p{N}\p{M}'’­-]/u;

  // Word under a long-press (device pixels). Marks it and returns it.
  IL.wordAt = function (x, y) {
    var r = window.devicePixelRatio || 1;
    x /= r; y /= r;
    var range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (!range || range.startContainer.nodeType !== 3) return '';
    var node = range.startContainer, t = node.nodeValue, i = range.startOffset;
    if (i < t.length && !WORD.test(t.charAt(i)) && i > 0 && WORD.test(t.charAt(i - 1))) i--;
    if (i >= t.length || !WORD.test(t.charAt(i))) return '';
    var s = i, e = i;
    while (s > 0 && WORD.test(t.charAt(s - 1))) s--;
    while (e < t.length && WORD.test(t.charAt(e))) e++;
    while (s < e && /['’\-]/.test(t.charAt(s))) s++;
    while (e > s && /['’\-]/.test(t.charAt(e - 1))) e--;
    var w = t.substring(s, e).replace(/­/g, '');
    if (!w) return '';
    IL.clearMark();
    wrap(node, s, e);
    return w;
  };

  // Jump to the nth (0-based) match of a search, matching BookSearch.java's numbering.
  IL.find = function (n, query) {
    var words = query.trim().split(/\s+/).map(function (w) { return w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); });
    var re = new RegExp(words.join('[\\s\\u00A0]+'), 'giu');
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var nodes = [], mapNode = [], mapOff = [], text = '', nd;
    while ((nd = walker.nextNode())) {
      if (nd.parentNode && /^(script|style)$/i.test(nd.parentNode.nodeName)) continue;
      var v = nd.nodeValue, idx = nodes.length;
      nodes.push(nd);
      for (var k = 0; k < v.length; k++) {
        if (v.charCodeAt(k) === 0xAD) continue;
        text += v.charAt(k); mapNode.push(idx); mapOff.push(k);
      }
    }
    var m, count = 0, hit = null;
    while ((m = re.exec(text))) {
      if (m[0].length === 0) { re.lastIndex++; continue; }
      hit = m;
      if (count++ === n) break;
    }
    if (!hit) { IL.go(0); return; }
    var a = hit.index, b = hit.index + hit[0].length - 1;
    IL.clearMark();
    // Wrap each text node piece of the match, last first so offsets stay valid.
    var pieces = [];
    for (var j = a; j <= b; j++) {
      var last = pieces[pieces.length - 1];
      if (last && last.node === mapNode[j]) last.end = mapOff[j] + 1;
      else pieces.push({ node: mapNode[j], start: mapOff[j], end: mapOff[j] + 1 });
    }
    var first = null;
    for (var q = pieces.length - 1; q >= 0; q--) {
      var sp = wrap(nodes[pieces[q].node], pieces[q].start, pieces[q].end);
      if (sp) first = sp;
    }
    measure();
    if (first) {
      var x = first.getBoundingClientRect().left + window.scrollX;
      IL.go(Math.floor((x + 1) / IL.W));
    } else IL.go(0);
  };

  // Opening words of the current page, for bookmark labels.
  IL.pageText = function () {
    var cs = getComputedStyle(document.body);
    var x = (parseFloat(cs.paddingLeft) || 0) + 6, y = (parseFloat(cs.paddingTop) || 0) + 10;
    var out = '';
    var range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (range && range.startContainer.nodeType === 3) {
      out = range.startContainer.nodeValue.substring(range.startOffset);
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      walker.currentNode = range.startContainer;
      var nd;
      while (out.length < 120 && (nd = walker.nextNode())) out += ' ' + nd.nodeValue;
    }
    out = out.replace(/­/g, '').replace(/\s+/g, ' ').trim();
    if (out.length > 70) out = out.substring(0, 70).replace(/\s+\S*$/, '') + '…';
    return out;
  };

  // Turn justified text into left-aligned text, but leave centred/right-aligned text alone.
  function fixAlignment() {
    if (!InkLeaf.leftAlign()) return;
    var all = document.body.getElementsByTagName('*');
    var justified = [];
    for (var i = 0; i < all.length; i++) {
      var a = getComputedStyle(all[i]).textAlign;
      if (a === 'justify') justified.push(all[i]);
    }
    for (var j = 0; j < justified.length; j++) justified[j].style.setProperty('text-align', 'left', 'important');
  }

  function start() {
    fixAlignment();
    measure();
    var s = InkLeaf.start();
    IL.ready = true;
    if (s === 'last') IL.go(IL.count - 1);
    else if (s.charAt(0) === '#') IL.anchor(s.substring(1));
    else if (s.indexOf('find:') === 0) {
      var rest = s.substring(5), c = rest.indexOf(':');
      IL.find(parseInt(rest.substring(0, c), 10) || 0, rest.substring(c + 1));
    }
    else IL.go(Math.floor((parseFloat(s) || 0) * IL.count + 0.0001));
  }

  window.addEventListener('load', function () {
    var go = function () { setTimeout(start, 30); };
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(go, go); else go();
  });

  window.addEventListener('resize', function () {
    if (!IL.ready) return;
    var f = IL.page / IL.count;
    setTimeout(function () { measure(); IL.go(Math.floor(f * IL.count + 0.0001)); }, 50);
  });
})();
