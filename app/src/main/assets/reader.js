// InkLeaf pager: the chapter is laid out in CSS columns, one column per screen.
(function () {
  var IL = window.IL = { page: 0, count: 1, W: 1, ready: false };

  function measure() {
    IL.W = window.innerWidth || 1;
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
    if (IL.page < IL.count - 1) { IL.go(IL.page + 1); return 'ok'; }
    return 'end';
  };

  IL.prev = function () {
    if (!IL.ready) return 'busy';
    if (IL.page > 0) { IL.go(IL.page - 1); return 'ok'; }
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
