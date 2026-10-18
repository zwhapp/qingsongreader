package com.qingsongreader.reader

/**
 * JavaScript injected into the reader WebView.
 *
 * The paged layout uses CSS multi-column: every column is a page, so paging is
 * just horizontal scrolling by (column width + gap). Highlights are addressed
 * by character offsets measured over the body's text nodes, which is stable
 * across re-renders because wrapping text in a <span> never changes the total
 * character count.
 */
object ReaderScripts {

    const val BRIDGE = "orBridge"

    val RUNTIME: String = """
(function () {
  if (window.__OR_READY__) return;
  window.__OR_READY__ = true;

  function textNodes() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentElement;
        if (!p) return NodeFilter.FILTER_REJECT;
        var tag = p.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE') return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var list = [], n;
    while ((n = walker.nextNode())) list.push(n);
    return list;
  }

  function textLength() {
    var list = textNodes(), total = 0;
    for (var i = 0; i < list.length; i++) total += list[i].nodeValue.length;
    return total;
  }

  function globalOffset(node, offset) {
    try {
      var r = document.createRange();
      r.selectNodeContents(document.body);
      r.setEnd(node, offset);
      return r.toString().length;
    } catch (e) {
      return 0;
    }
  }

  function locate(offset) {
    var list = textNodes(), acc = 0;
    for (var i = 0; i < list.length; i++) {
      var len = list[i].nodeValue.length;
      if (offset <= acc + len) return { node: list[i], off: offset - acc };
      acc += len;
    }
    var last = list[list.length - 1];
    return last ? { node: last, off: last.nodeValue.length } : null;
  }

  function metrics() {
    var cs = getComputedStyle(document.body);
    var colW = parseFloat(cs.columnWidth);
    if (!colW || isNaN(colW)) colW = document.documentElement.clientWidth;
    var gap = parseFloat(cs.columnGap);
    if (!gap || isNaN(gap)) gap = 0;
    return { colW: colW, gap: gap };
  }

  function isPaged() {
    return document.body && document.body.classList.contains('paged');
  }

  window.OR = {
    pageCount: function () {
      if (!isPaged()) return 1;
      var m = metrics();
      var el = document.scrollingElement;
      var total = el.scrollWidth + m.gap;
      return Math.max(1, Math.round(total / (m.colW + m.gap)));
    },
    currentPage: function () {
      if (!isPaged()) return 0;
      var m = metrics();
      return Math.max(0, Math.round(document.scrollingElement.scrollLeft / (m.colW + m.gap)));
    },
    goToPage: function (i) {
      if (!isPaged()) return;
      var m = metrics();
      document.scrollingElement.scrollLeft = i * (m.colW + m.gap);
    },
    progress: function () {
      if (isPaged()) {
        var pages = window.OR.pageCount();
        return pages <= 1 ? 1 : window.OR.currentPage() / (pages - 1);
      }
      var el = document.scrollingElement;
      var max = el.scrollHeight - el.clientHeight;
      return max <= 0 ? 1 : el.scrollTop / max;
    },
    goToProgress: function (p) {
      if (isPaged()) {
        var pages = window.OR.pageCount();
        window.OR.goToPage(Math.round(p * (pages - 1)));
      } else {
        var el = document.scrollingElement;
        var max = el.scrollHeight - el.clientHeight;
        el.scrollTop = p * max;
      }
    },
    setPaged: function (on) {
      if (!document.body) return;
      document.body.classList.toggle('paged', !!on);
      document.body.classList.toggle('scrolled', !on);
      document.scrollingElement.scrollLeft = 0;
      document.scrollingElement.scrollTop = 0;
    },
    textLength: textLength,
    chapterText: function () {
      return document.body ? document.body.innerText : '';
    },
    pageText: function () {
      // Best effort: everything from the current page onwards.
      var full = document.body ? document.body.innerText : '';
      if (!isPaged()) return full;
      var pages = window.OR.pageCount();
      if (pages <= 1) return full;
      var from = Math.floor(full.length * (window.OR.currentPage() / pages));
      return full.substring(from);
    },
    selectedRange: function () {
      var sel = window.getSelection();
      if (!sel || sel.isCollapsed || sel.rangeCount === 0) return null;
      var r = sel.getRangeAt(0);
      var start = globalOffset(r.startContainer, r.startOffset);
      var end = globalOffset(r.endContainer, r.endOffset);
      if (end < start) { var t = start; start = end; end = t; }
      return { start: start, end: end, text: sel.toString() };
    },
    clearSelection: function () {
      var sel = window.getSelection();
      if (sel) sel.removeAllRanges();
    },
    highlight: function (start, end, color, id) {
      var list = textNodes(), acc = 0, targets = [];
      for (var i = 0; i < list.length; i++) {
        var node = list[i];
        var len = node.nodeValue.length;
        var s = Math.max(start - acc, 0);
        var e = Math.min(end - acc, len);
        if (s < e) targets.push({ node: node, s: s, e: e });
        acc += len;
      }
      targets.forEach(function (t) {
        var r = document.createRange();
        r.setStart(t.node, t.s);
        r.setEnd(t.node, t.e);
        var span = document.createElement('span');
        span.className = 'or-hl';
        span.setAttribute('data-hl', id);
        span.style.backgroundColor = color;
        try {
          r.surroundContents(span);
        } catch (err) {
          var txt = r.toString();
          var frag = r.extractContents();
          span.textContent = txt;
          frag && null;
        }
      });
    },
    removeHighlight: function (id) {
      var spans = document.querySelectorAll('span[data-hl="' + id + '"]');
      for (var i = 0; i < spans.length; i++) {
        var span = spans[i];
        var parent = span.parentNode;
        while (span.firstChild) parent.insertBefore(span.firstChild, span);
        parent.removeChild(span);
      }
      if (document.body) document.body.normalize();
    },
    clearSearch: function () {
      var spans = document.querySelectorAll('span.or-search');
      for (var i = 0; i < spans.length; i++) {
        var span = spans[i];
        var parent = span.parentNode;
        while (span.firstChild) parent.insertBefore(span.firstChild, span);
        parent.removeChild(span);
      }
      if (document.body) document.body.normalize();
    },
    findNext: function (kw) {
      window.getSelection().removeAllRanges();
      return window.find(kw, false, false, true);
    },
    findPrev: function (kw) {
      window.getSelection().removeAllRanges();
      return window.find(kw, false, true, true);
    },
    markSearch: function (kw) {
      window.OR.clearSearch();
      if (!kw) return 0;
      var text = document.body.innerText, count = 0, from = 0;
      var list = textNodes();
      var flat = [];
      for (var i = 0; i < list.length; i++) flat.push(list[i]);
      // Simple pass: walk nodes and mark every occurrence of kw.
      for (var j = 0; j < flat.length; j++) {
        var node = flat[j];
        var value = node.nodeValue;
        var idx = value.indexOf(kw);
        while (idx >= 0) {
          var r = document.createRange();
          r.setStart(node, idx);
          r.setEnd(node, idx + kw.length);
          var span = document.createElement('span');
          span.className = 'or-search';
          try { r.surroundContents(span); } catch (e) { }
          count++;
          idx = value.indexOf(kw, idx + kw.length);
        }
      }
      return count;
    },
    zoom: function (scale) {
      document.body.style.zoom = scale;
    }
  };

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a) {
      var href = a.getAttribute('href') || '';
      if (href.indexOf('#') === 0) return; // in-document anchor, let WebView handle it
      e.preventDefault();
      if (window.orBridge && window.orBridge.onLink) window.orBridge.onLink(href);
    }
  });

  document.addEventListener('mouseup', function () {
    var sel = window.getSelection();
    var text = sel ? sel.toString() : '';
    if (window.orBridge && window.orBridge.onSelection) window.orBridge.onSelection(text);
  });

  document.addEventListener('touchend', function () {
    var sel = window.getSelection();
    var text = sel ? sel.toString() : '';
    if (window.orBridge && window.orBridge.onSelection) window.orBridge.onSelection(text);
  });
})();
    """.trimIndent()
}
