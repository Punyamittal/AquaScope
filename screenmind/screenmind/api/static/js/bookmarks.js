// ══════════════════════════════════════════════════════════
//  BOOKMARKS VIEW — Starred activities
// ══════════════════════════════════════════════════════════

async function renderBookmarks(el) {
  el.innerHTML = `
    <div style="margin-bottom:20px;display:flex;align-items:center;gap:12px">
      <span style="color:var(--text-muted);font-size:0.85rem" id="bm-count"></span>
    </div>
    <div class="timeline" id="bookmarks-list"><div class="spinner"></div></div>`;
  loadBookmarks();
}

async function loadBookmarks() {
  const list = $('#bookmarks-list');
  try {
    const data = await api('/api/bookmarks?limit=100');
    const bookmarks = data.bookmarks || [];
    const countEl = $('#bm-count');
    if (countEl) countEl.textContent = bookmarks.length + ' bookmark' + (bookmarks.length !== 1 ? 's' : '');

    if (!bookmarks.length) {
      list.innerHTML = `<div class="empty-state">
        <div class="empty-icon">⭐</div>
        <div class="empty-title">No Bookmarks Yet</div>
        <div>Use <strong>⋮ → ☆ Bookmark</strong> on any timeline item, or press <strong>Ctrl+Shift+B</strong> from any app.</div>
      </div>`;
      return;
    }

    list.innerHTML = bookmarks.map(function(a, i) {
      // Reuse timelineCard if available, otherwise build inline
      if (typeof timelineCard === 'function') {
        return timelineCard(a, i);
      }
      var time = formatTime(a.timestamp);
      var cat = a.category || 'other';
      var thumb = a.screenshot_url ? '<img class="thumb" src="' + a.screenshot_url + '" loading="lazy" onclick="openModal(\'' + a.screenshot_url + '\', ' + a.id + ')" alt="">' : '<div class="thumb"></div>';
      return '<div class="timeline-item" style="animation-delay:' + (i * 0.06) + 's">' +
        thumb +
        '<div class="info">' +
          '<div class="top"><span class="time">' + time + '</span><span class="app-name">' + (a.app_name || 'Unknown') + '</span><span class="badge badge-' + cat + '">' + cat + '</span></div>' +
          '<div class="summary">' + (a.summary || 'No analysis') + '</div>' +
        '</div>' +
        '<div class="card-menu-wrap">' +
          '<button class="card-menu-trigger" onclick="event.stopPropagation(); toggleCardMenu(this)" title="Actions">⋮</button>' +
          '<div class="card-menu">' +
            '<button class="menu-item bookmark-item active" onclick="event.stopPropagation(); toggleBookmark(' + a.id + ', this)">' +
              '<span class="menu-icon">★</span> ★ Bookmarked' +
            '</button>' +
            '<button class="menu-item reanalyze-item" onclick="event.stopPropagation(); reanalyzeActivity(' + a.id + ', this)">' +
              '<span class="menu-icon">↻</span> Re-analyze' +
            '</button>' +
            '<div class="menu-divider"></div>' +
            '<button class="menu-item delete-item" onclick="event.stopPropagation(); deleteActivity(' + a.id + ', this)">' +
              '<span class="menu-icon">✕</span> Delete' +
            '</button>' +
          '</div>' +
        '</div>' +
      '</div>';
    }).join('');
  } catch (err) {
    list.innerHTML = '<div class="empty-state"><div class="empty-icon">⚠️</div><div>' + err.message + '</div></div>';
  }
}
