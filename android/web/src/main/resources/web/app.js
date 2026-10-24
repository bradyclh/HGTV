'use strict';
// 紅果TV 網頁版前端：hash 路由的單頁應用，資料來自同一台本機伺服器的 /api 與 /video。

const view = document.getElementById('view');
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const svg = (path) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${path}"/></svg>`;
const ICON = {
  play: svg('M8 5.5v13a.5.5 0 0 0 .77.42l10-6.5a.5.5 0 0 0 0-.84l-10-6.5A.5.5 0 0 0 8 5.5z'),
  pause: svg('M7 5h3.5v14H7zM13.5 5H17v14h-3.5z'),
  next: svg('M6 6.5v11a.5.5 0 0 0 .78.41L14.5 12.4V17.5h2v-11h-2v5.1L6.78 6.09A.5.5 0 0 0 6 6.5z'),
  volume: svg('M4 9.5v5h3.5L12 19V5L7.5 9.5zM15 8.5a5 5 0 0 1 0 7l1.4 1.4a7 7 0 0 0 0-9.8zM17.6 5.9a8.6 8.6 0 0 1 0 12.2L19 19.5a10.6 10.6 0 0 0 0-15z'),
  muted: svg('M4 9.5v5h3.5L12 19V5L7.5 9.5zM15.3 9.7l1.4-1.4 2.3 2.3 2.3-2.3 1.4 1.4-2.3 2.3 2.3 2.3-1.4 1.4-2.3-2.3-2.3 2.3-1.4-1.4 2.3-2.3z'),
  danmaku: svg('M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-7l-4 3v-3H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm2 3v2h8V8zm0 4v2h12v-2z'),
  fullscreen: svg('M4 4h6v2H6v4H4zM14 4h6v6h-2V6h-4zM4 14h2v4h4v2H4zM18 14h2v6h-6v-2h4z'),
  exitFullscreen: svg('M8 4h2v6H4V8h4zM14 4h2v4h4v2h-6zM4 14h6v6H8v-4H4zM14 14h6v2h-4v4h-2z'),
};
const $ = (selector, root = document) => root.querySelector(selector);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtTime = (seconds) => {
  seconds = Math.max(0, Math.floor(seconds || 0));
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
};

// ---------- 本機資料（收藏 / 觀看進度 / 設定），只存在這個瀏覽器 ----------
const store = (() => {
  const KEY = 'hgtv-web';
  let data;
  try { data = JSON.parse(localStorage.getItem(KEY)) || {}; } catch { data = {}; }
  data.favorites ||= [];
  data.progress ||= {};
  data.settings = { quality: 1080, rate: 1, danmaku: 'full', autoNext: true, volume: 1, ...(data.settings || {}) };
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(data)); } catch { /* 隱私模式等情況下不保存 */ } };
  const brief = (s) => ({ id: s.id, title: s.title, cover: s.cover, badge: s.badge });
  return {
    get settings() { return data.settings; },
    setting(name, value) { data.settings[name] = value; save(); },
    isFavorite: (id) => data.favorites.some((s) => s.id === id),
    toggleFavorite(series) {
      const had = this.isFavorite(series.id);
      data.favorites = had ? data.favorites.filter((s) => s.id !== series.id) : [brief(series), ...data.favorites];
      save();
      return !had;
    },
    favorites: () => data.favorites,
    progress: (id) => data.progress[id],
    saveProgress(series, episode, time, duration, total) {
      const old = data.progress[series.id] || { watched: [] };
      const watched = new Set(old.watched);
      if (duration && time / duration > 0.9) watched.add(episode);
      data.progress[series.id] = { series: brief(series), episode, time, duration, total, watched: [...watched].slice(-2000), at: Date.now() };
      save();
    },
    history: () => Object.values(data.progress).sort((a, b) => b.at - a.at),
    removeHistory(id) { delete data.progress[id]; save(); },
    clearHistory() { data.progress = {}; save(); },
  };
})();

// ---------- API ----------
const cache = new Map();
async function api(path, { fresh = false, ttl = 10 * 60 * 1000 } = {}) {
  const hit = cache.get(path);
  if (!fresh && hit && Date.now() - hit.at < ttl) return hit.value;
  const response = await fetch('/api/' + path);
  let body;
  try { body = await response.json(); } catch { throw new Error('伺服器回應異常'); }
  if (!response.ok) throw new Error(body.error || `請求失敗（${response.status}）`);
  cache.set(path, { at: Date.now(), value: body });
  return body;
}

function toast(message) {
  const el = document.getElementById('toast');
  // 全螢幕時只有全螢幕那一層看得到，提示要搬進去。
  (document.fullscreenElement || document.webkitFullscreenElement || document.body).appendChild(el);
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { el.hidden = true; }, 2200);
}

// ---------- 共用元件 ----------
function card(series, { rank, sub, href, progress } = {}) {
  const link = href || `#/detail/${series.id}`;
  return `<a class="card" href="${link}">
    <div class="poster">
      ${series.cover ? `<img src="${esc(series.cover)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}
      ${rank ? `<span class="rank">${rank}</span>` : ''}
      ${series.badge ? `<span class="badge">${esc(series.badge)}</span>` : ''}
      ${progress != null ? `<div class="progress"><span style="width:${Math.round(progress * 100)}%"></span></div>` : ''}
    </div>
    <div class="title">${esc(series.title)}</div>
    ${sub ? `<div class="sub">${esc(sub)}</div>` : ''}
  </a>`;
}
const skeletonCards = (n = 12) => Array.from({ length: n }, () => '<div class="card"><div class="poster skeleton"></div></div>').join('');
const errorBox = (error, retry = true) => `<div class="empty error">${esc(error.message || error)}${retry ? '<div style="margin-top:12px"><button class="btn" onclick="route()">重試</button></div>' : ''}</div>`;

/** 「載入更多」分頁清單：loader(page) 回傳 { items, hasMore, ranking? }。 */
function pagedGrid(container, loader, decorate = () => ({})) {
  let page = 0, busy = false;
  const seen = new Set();
  container.innerHTML = `<div class="grid"></div><div class="load-more"><button class="btn" hidden>載入更多</button></div>`;
  const grid = $('.grid', container), button = $('.load-more button', container);
  async function next() {
    if (busy) return;
    busy = true;
    button.disabled = true;
    button.textContent = '載入中…';
    if (page === 0) grid.innerHTML = skeletonCards();
    try {
      const result = await loader(page + 1);
      if (page === 0) grid.innerHTML = '';
      page++;
      const fresh = result.items.filter((s) => !seen.has(s.id) && seen.add(s.id));
      grid.insertAdjacentHTML('beforeend', fresh.map((s) => card(s, decorate(s, result))).join(''));
      if (page === 1 && !fresh.length) grid.insertAdjacentHTML('afterend', '<div class="empty">沒有找到內容</div>');
      button.hidden = !result.hasMore;
    } catch (error) {
      if (page === 0) grid.innerHTML = '';
      grid.insertAdjacentHTML('afterend', errorBox(error, false));
      button.hidden = false;
    } finally {
      busy = false;
      button.disabled = false;
      button.textContent = '載入更多';
    }
  }
  button.addEventListener('click', next);
  // 捲到底自動載入下一頁
  const observer = new IntersectionObserver((entries) => { if (entries[0].isIntersecting && !button.hidden) next(); }, { rootMargin: '400px' });
  observer.observe(button);
  next();
}

// ---------- 頁面 ----------
async function homePage(token) {
  const history = store.history().slice(0, 20);
  view.innerHTML = `
    ${history.length ? `<section class="section"><div class="section-head"><h2>繼續觀看</h2><a class="more" href="#/library">全部</a></div>
      <div class="shelf">${history.map((h) => card(h.series, { href: `#/play/${h.series.id}/${h.episode + 1}`, sub: `看到第 ${h.episode + 1} 集 · ${fmtTime(h.time)}`, progress: h.duration ? h.time / h.duration : null })).join('')}</div></section>` : ''}
    <div id="boards">${['熱播漫劇', '熱播短劇'].map((t) => `<section class="section"><div class="section-head"><h2>${t}</h2></div><div class="shelf">${Array.from({ length: 8 }, () => '<div class="card"><div class="poster skeleton"></div></div>').join('')}</div></section>`).join('')}</div>`;
  try {
    const boards = await api('home');
    if (token !== routeToken) return;
    $('#boards').innerHTML = boards.map((b) => `<section class="section">
      <div class="section-head"><h2>${esc(b.title)}</h2><span class="hint">${esc(b.source)}</span><a class="more" href="#/rank/${b.board}">查看全部 ›</a></div>
      <div class="shelf">${b.items.map((s, i) => card(s, { rank: b.ranking?.positions?.[s.id]?.rank ?? i + 1 })).join('')}</div></section>`).join('');
  } catch (error) {
    if (token === routeToken) $('#boards').innerHTML = errorBox(error);
  }
}

async function rankPage(token, board) {
  const boards = await api('boards', { ttl: Infinity });
  if (token !== routeToken) return;
  view.innerHTML = `<h1>熱播榜</h1>
    <div class="chips">${boards.map((b) => `<a class="chip ${b.key === board ? 'active' : ''}" href="#/rank/${b.key}">${esc(b.label)}</a>`).join('')}</div>
    <p class="hint" id="updated"></p><div id="list"></div>`;
  pagedGrid($('#list'), (page) => api(`rank?board=${board}&page=${page}`), (s, result) => {
    const pos = result.ranking?.positions?.[s.id];
    if (result.ranking?.updated) $('#updated').textContent = result.ranking.updated;
    return { rank: pos?.rank, sub: pos?.heat };
  });
}

async function categoryPage(token, path) {
  const groups = await api('categories', { ttl: Infinity });
  if (token !== routeToken) return;
  const group = groups.find((g) => g.tags.some((t) => t.path === path)) || groups[0];
  if (!group.tags.some((t) => t.path === path)) path = group.path;
  view.innerHTML = `<h1>分類</h1>
    <div class="chips">${groups.map((g) => `<a class="chip ${g === group ? 'active' : ''}" href="#/category/${g.path}">${esc(g.label)}</a>`).join('')}</div>
    <div class="chips">${group.tags.map((t) => `<a class="chip ${t.path === path ? 'active' : ''}" href="#/category/${t.path}">${esc(t.label)}</a>`).join('')}</div>
    <div id="list"></div>`;
  pagedGrid($('#list'), (page) => api(`category?path=${encodeURIComponent(path)}&page=${page}`));
}

async function searchPage(token, keyword) {
  $('#search-input').value = keyword;
  if (!keyword) {
    view.innerHTML = `<h1>搜尋</h1><p class="hint">熱門搜尋</p><div class="chips" id="hot"></div>`;
    try {
      const words = await api('hot');
      if (token === routeToken) $('#hot').innerHTML = words.map((w) => `<a class="chip" href="#/search/${encodeURIComponent(w)}">${esc(w)}</a>`).join('');
    } catch { /* 熱搜詞拿不到就空著 */ }
    return;
  }
  view.innerHTML = `<h1>「${esc(keyword)}」的搜尋結果</h1><div id="list"></div>`;
  // 搜尋結果翻頁有伺服器端游標，每次都要真的發請求。
  pagedGrid($('#list'), (page) => api(`search?q=${encodeURIComponent(keyword)}&page=${page}`, { fresh: true }));
}

async function detailPage(token, id) {
  view.innerHTML = `<div class="detail"><div class="poster skeleton"></div><div><h1 class="skeleton" style="height:32px;width:40%"></h1></div></div>`;
  let detail;
  try { detail = await api(`detail?id=${id}`); } catch (error) { if (token === routeToken) view.innerHTML = errorBox(error); return; }
  if (token !== routeToken) return;
  const s = detail.series, total = detail.episodes.length, progress = store.progress(id);
  const resume = progress && progress.episode < total ? progress : null;
  document.title = `${s.title} · 紅果TV`;
  view.innerHTML = `
    <div class="detail">
      <div class="poster">${s.cover ? `<img src="${esc(s.cover)}" alt="" referrerpolicy="no-referrer">` : ''}</div>
      <div>
        <h1>${esc(s.title)}</h1>
        <p class="meta">${esc([s.badge || `共 ${total} 集`, s.tags].filter(Boolean).join(' · '))}</p>
        <p class="desc">${esc(s.description)}</p>
        <div class="actions">
          <a class="btn primary" href="#/play/${id}/${resume ? resume.episode + 1 : 1}">▶ ${resume ? `繼續看第 ${resume.episode + 1} 集 · ${fmtTime(resume.time)}` : '播放第 1 集'}</a>
          <button class="btn" id="fav">${store.isFavorite(id) ? '★ 已收藏' : '☆ 收藏'}</button>
        </div>
      </div>
    </div>
    <section class="section"><div class="section-head"><h2>選集</h2><span class="hint">共 ${total} 集</span></div><div id="episodes"></div></section>
    <section class="section" id="related"></section>`;
  $('#fav').addEventListener('click', (e) => {
    const added = store.toggleFavorite(s);
    e.currentTarget.textContent = added ? '★ 已收藏' : '☆ 收藏';
    toast(added ? '已加入收藏' : '已取消收藏');
  });
  episodeGrid($('#episodes'), id, total, resume ? resume.episode : -1, (i) => `#/play/${id}/${i + 1}`);
  try {
    const related = await api(`related?id=${id}&title=${encodeURIComponent(s.title)}`);
    if (token !== routeToken || !related.items.length) return;
    $('#related').innerHTML = `<div class="section-head"><h2>相關作品</h2></div><div class="grid">${related.items.slice(0, 24).map((r) => card(r)).join('')}</div>`;
  } catch { /* 相關作品拿不到不影響詳情 */ }
}

/** 選集格：集數多時每 100 集一頁（有的劇上千集）。 */
function episodeGrid(container, id, total, current, href, pageSize = 100) {
  const watched = new Set(store.progress(id)?.watched || []);
  let page = Math.max(0, Math.floor(Math.max(current, 0) / pageSize));
  const pages = Math.ceil(total / pageSize);
  function draw() {
    const start = page * pageSize, end = Math.min(total, start + pageSize);
    container.innerHTML = `${pages > 1 ? `<div class="chips">${Array.from({ length: pages }, (_, p) => `<button class="chip ${p === page ? 'active' : ''}" data-page="${p}">${p * pageSize + 1}-${Math.min(total, (p + 1) * pageSize)}</button>`).join('')}</div>` : ''}
      <div class="episodes">${Array.from({ length: end - start }, (_, k) => {
        const i = start + k;
        return `<a class="ep ${i === current ? 'current' : ''} ${watched.has(i) ? 'watched' : ''}" href="${href(i)}">${i + 1}</a>`;
      }).join('')}</div>`;
    container.querySelectorAll('[data-page]').forEach((b) => b.addEventListener('click', () => { page = +b.dataset.page; draw(); }));
  }
  draw();
}

async function libraryPage() {
  const history = store.history(), favorites = store.favorites();
  view.innerHTML = `
    <section class="section"><div class="section-head"><h2>繼續觀看</h2>${history.length ? '<button class="btn more" id="clear">清除紀錄</button>' : ''}</div>
      ${history.length ? `<div class="grid">${history.map((h) => card(h.series, { href: `#/play/${h.series.id}/${h.episode + 1}`, sub: `第 ${h.episode + 1} 集 · ${fmtTime(h.time)}`, progress: h.duration ? h.time / h.duration : null })).join('')}</div>` : '<div class="empty">還沒有觀看紀錄</div>'}</section>
    <section class="section"><div class="section-head"><h2>我的收藏</h2></div>
      ${favorites.length ? `<div class="grid">${favorites.map((s) => card(s)).join('')}</div>` : '<div class="empty">還沒有收藏，在劇集頁按「收藏」加入</div>'}</section>
    <p class="hint">收藏與觀看紀錄只存在這個瀏覽器。</p>`;
  $('#clear')?.addEventListener('click', () => { if (confirm('清除所有觀看紀錄？')) { store.clearHistory(); libraryPage(); } });
}

// ---------- 播放器 ----------
let player = null; // 目前播放頁的清理函式

async function playPage(token, id, episodeNumber) {
  let detail;
  view.innerHTML = `<div class="player-page"><div><div class="stage"><div class="status">載入中…</div></div></div></div>`;
  try { detail = await api(`detail?id=${id}`); } catch (error) { if (token === routeToken) view.innerHTML = errorBox(error); return; }
  if (token !== routeToken) return;
  const s = detail.series, total = detail.episodes.length;
  const index = Math.min(Math.max(1, episodeNumber), total) - 1;
  const vid = detail.episodes[index];
  const settings = store.settings;
  document.title = `第 ${index + 1} 集 · ${s.title} · 紅果TV`;
  view.innerHTML = `
    <div class="player-page">
      <div>
        <div class="stage" id="stage">
          <video id="video" autoplay playsinline></video>
          <div class="danmaku" id="danmaku"></div>
          <div class="status" id="status">載入中…</div>
          <div class="bar" id="bar">
            <div class="seek" id="seek"><div class="track"><div class="buffered" id="buffered"></div><div class="played" id="played"></div><div class="thumb" id="thumb"></div></div><div class="tip" id="tip" hidden></div></div>
            <div class="row">
              <button class="ic" id="b-play" title="播放 (K)">${ICON.play}</button>
              <button class="ic" id="b-next" title="下一集 (Shift+N)" ${index >= total - 1 ? 'disabled' : ''}>${ICON.next}</button>
              <span class="vol"><button class="ic" id="b-mute" title="靜音 (M)">${ICON.volume}</button><input type="range" id="b-vol" min="0" max="1" step="0.01" aria-label="音量"></span>
              <span class="time" id="b-time">0:00 / 0:00</span>
              <span class="grow"></span>
              <span class="ep-label">第 ${index + 1} / ${total} 集</span>
              <button class="ic text" id="b-rate" title="倍速 (&lt; &gt;)">${settings.rate}×</button>
              <button class="ic" id="b-dm" title="彈幕 (C)">${ICON.danmaku}</button>
              <button class="ic" id="b-fs" title="全螢幕 (F)">${ICON.fullscreen}</button>
            </div>
          </div>
        </div>
        <h1 class="player-title"><a href="#/detail/${id}">${esc(s.title)}</a> · 第 ${index + 1} 集</h1>
        <div class="controls">
          <button class="btn" id="prev" ${index === 0 ? 'disabled' : ''}>‹ 上一集</button>
          <button class="btn" id="next" ${index >= total - 1 ? 'disabled' : ''}>下一集 ›</button>
          <span class="spacer"></span>
          <label>畫質 <select id="quality">${[1080, 720, 480].map((q) => `<option value="${q}" ${q === settings.quality ? 'selected' : ''}>${q}P</option>`).join('')}</select></label>
          <label>倍速 <select id="rate">${RATES.map((r) => `<option value="${r}" ${r === settings.rate ? 'selected' : ''}>${r}×</option>`).join('')}</select></label>
          <label>彈幕 <select id="dm">${[['full', '全螢幕'], ['top', '上方'], ['off', '關閉']].map(([v, t]) => `<option value="${v}" ${v === settings.danmaku ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
          <label><input type="checkbox" id="auto" ${settings.autoNext ? 'checked' : ''}> 自動連播</label>
          <button class="btn" id="fs">⛶ 全螢幕</button>
        </div>
      </div>
      <aside class="side">
        <h2>選集 <span class="hint">共 ${total} 集</span></h2>
        <div id="episodes"></div>
        <div class="keys">
          <kbd>K</kbd>/<kbd>空白</kbd> 播放/暫停　<kbd>M</kbd> 靜音<br>
          <kbd>J</kbd><kbd>L</kbd> 倒退/快轉 10 秒　<kbd>←</kbd><kbd>→</kbd> 5 秒<br>
          <kbd>↑</kbd><kbd>↓</kbd> 音量　<kbd>0</kbd>–<kbd>9</kbd> 跳到 0–90%<br>
          <kbd>F</kbd> 全螢幕　<kbd>C</kbd> 彈幕　<kbd>I</kbd> 子母畫面<br>
          <kbd>&lt;</kbd><kbd>&gt;</kbd> 倍速　<kbd>,</kbd><kbd>.</kbd> 逐格（暫停時）<br>
          <kbd>Shift</kbd>+<kbd>N</kbd>/<kbd>P</kbd> 下一集/上一集
        </div>
      </aside>
    </div>`;
  episodeGrid($('#episodes'), id, total, index, (i) => `#/play/${id}/${i + 1}`);
  const video = $('#video'), stage = $('#stage'), status = $('#status');
  const go = (i) => { if (i >= 0 && i < total) location.replace(`#/play/${id}/${i + 1}`); };
  video.volume = settings.volume;
  video.playbackRate = settings.rate;

  const progress = store.progress(id);
  let resumeAt = progress && progress.episode === index && progress.duration && progress.time < progress.duration - 5 ? progress.time : 0;
  function load(keepTime) {
    if (keepTime) resumeAt = video.currentTime;
    status.hidden = false;
    status.textContent = '載入中…';
    video.src = `/video/${vid}?q=${settings.quality}`;
  }
  video.addEventListener('loadedmetadata', () => {
    if (resumeAt > 0) video.currentTime = resumeAt;
    resumeAt = 0;
    video.playbackRate = settings.rate;
  });
  video.addEventListener('playing', () => { status.hidden = true; });
  video.addEventListener('canplay', () => { status.hidden = true; });
  video.addEventListener('waiting', () => { status.hidden = false; status.textContent = '緩衝中…'; });
  video.addEventListener('error', () => {
    status.hidden = false;
    status.innerHTML = `<div>播放失敗：${video.error?.code === 4 ? '這集的格式瀏覽器無法播放，或播放地址已失效' : '網路或伺服器錯誤'}</div><button class="btn" id="retry">重試</button>`;
    $('#retry').addEventListener('click', () => load(true));
  });
  video.addEventListener('volumechange', () => store.setting('volume', video.volume));
  let lastSave = 0;
  const save = () => { if (video.duration) store.saveProgress(s, index, video.currentTime, video.duration, total); };
  video.addEventListener('timeupdate', () => { if (Date.now() - lastSave > 5000) { lastSave = Date.now(); save(); } });
  video.addEventListener('pause', save);
  video.addEventListener('ended', () => {
    save();
    if (store.settings.autoNext && index < total - 1) go(index + 1);
    else if (index >= total - 1) { status.hidden = false; status.textContent = '全劇終'; }
  });
  // 點畫面播放／暫停（同 YouTube）。控制列是另外一層，點它不會落在 <video> 上；
  // 保險起見仍先記下按下時的狀態，瀏覽器自己已經切換過就不再切換。
  let pausedAtPress = null, pausedBeforeClicks = null;
  video.addEventListener('pointerdown', (e) => {
    pausedAtPress = e.button === 0 ? video.paused : null;
    if (e.detail <= 1) pausedBeforeClicks = pausedAtPress;
  });
  // 雙擊＝全螢幕；兩次單擊的切換排在前面，這裡最後把播放狀態還原成雙擊之前的樣子。
  video.addEventListener('dblclick', (e) => {
    e.preventDefault();
    toggleFullscreen();
    const before = pausedBeforeClicks;
    setTimeout(() => { if (before !== null && video.paused !== before) togglePlay(); }, 0);
  });
  video.addEventListener('click', () => {
    const before = pausedAtPress;
    pausedAtPress = null;
    if (before === null || !video.currentSrc) return;
    setTimeout(() => {
      if (video.paused !== before) return;
      togglePlay();
      flash(before ? ICON.play : ICON.pause);
    }, 0);
  });
  function flash(icon) {
    const el = document.createElement('div');
    el.className = 'flash';
    el.innerHTML = icon;
    stage.appendChild(el);
    el.addEventListener('animationend', () => el.remove());
  }

  // ---- 自訂控制列（取代瀏覽器原生控制列，才能在播放中自動淡出） ----
  const bar = $('#bar'), seek = $('#seek'), tip = $('#tip');
  const playButton = $('#b-play'), muteButton = $('#b-mute'), volumeInput = $('#b-vol'), timeLabel = $('#b-time');
  let dragging = false, dragRatio = 0, idleTimer = 0;
  const ratioAt = (clientX) => { const r = seek.getBoundingClientRect(); return Math.min(1, Math.max(0, (clientX - r.left) / r.width)); };
  function paintProgress() {
    const duration = video.duration || 0;
    const time = dragging ? dragRatio * duration : video.currentTime;
    const ratio = duration ? time / duration : 0;
    $('#played').style.width = `${ratio * 100}%`;
    $('#thumb').style.left = `${ratio * 100}%`;
    let buffered = 0;
    for (let i = 0; i < video.buffered.length; i++) {
      if (video.buffered.start(i) <= video.currentTime + 0.5) buffered = Math.max(buffered, video.buffered.end(i));
    }
    $('#buffered').style.width = `${duration ? (buffered / duration) * 100 : 0}%`;
    timeLabel.textContent = `${fmtTime(time)} / ${fmtTime(duration)}`;
  }
  function paintPlay() {
    playButton.innerHTML = video.paused ? ICON.play : ICON.pause;
    playButton.title = video.paused ? '播放 (K)' : '暫停 (K)';
  }
  function paintVolume() {
    const silent = video.muted || video.volume === 0;
    muteButton.innerHTML = silent ? ICON.muted : ICON.volume;
    volumeInput.value = video.muted ? 0 : video.volume;
  }
  const paintRate = () => { $('#b-rate').textContent = `${video.playbackRate}×`; };
  const paintDanmaku = () => $('#b-dm').classList.toggle('off', store.settings.danmaku === 'off');
  const paintFullscreen = () => {
    const full = !!(document.fullscreenElement || document.webkitFullscreenElement);
    $('#b-fs').innerHTML = full ? ICON.exitFullscreen : ICON.fullscreen;
    $('#b-fs').title = full ? '結束全螢幕 (F)' : '全螢幕 (F)';
  };
  ['timeupdate', 'progress', 'durationchange', 'seeked'].forEach((type) => video.addEventListener(type, paintProgress));
  video.addEventListener('play', () => { paintPlay(); wake(); });
  video.addEventListener('pause', () => { paintPlay(); wake(); });
  video.addEventListener('volumechange', paintVolume);
  video.addEventListener('ratechange', paintRate);
  document.addEventListener('fullscreenchange', paintFullscreen);
  document.addEventListener('webkitfullscreenchange', paintFullscreen);
  paintPlay(); paintVolume(); paintDanmaku(); paintFullscreen();

  seek.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || !video.duration) return;
    dragging = true;
    seek.classList.add('drag');
    seek.setPointerCapture(e.pointerId);
    dragRatio = ratioAt(e.clientX);
    paintProgress();
  });
  seek.addEventListener('pointermove', (e) => {
    const ratio = ratioAt(e.clientX);
    if (video.duration) {
      tip.hidden = false;
      tip.style.left = `${ratio * 100}%`;
      tip.textContent = fmtTime(ratio * video.duration);
    }
    if (dragging) { dragRatio = ratio; paintProgress(); }
  });
  // 放開時才真的跳轉：串流每跳一次就要重新取一段，拖動途中不發請求。
  const endDrag = () => {
    if (!dragging) return;
    dragging = false;
    seek.classList.remove('drag');
    video.currentTime = dragRatio * video.duration;
    paintProgress();
  };
  seek.addEventListener('pointerup', endDrag);
  seek.addEventListener('pointercancel', endDrag);
  seek.addEventListener('pointerleave', () => { if (!dragging) tip.hidden = true; });

  playButton.addEventListener('click', () => togglePlay());
  $('#b-next').addEventListener('click', () => go(index + 1));
  muteButton.addEventListener('click', () => { video.muted = !video.muted; if (!video.muted && video.volume === 0) video.volume = 0.5; });
  volumeInput.addEventListener('input', () => { video.volume = +volumeInput.value; video.muted = +volumeInput.value === 0; });
  $('#b-rate').addEventListener('click', () => {
    const next = RATES[(RATES.indexOf(store.settings.rate) + 1) % RATES.length] ?? 1;
    $('#rate').value = String(next);
    $('#rate').dispatchEvent(new Event('change'));
  });
  $('#b-dm').addEventListener('click', () => toggleDanmaku());
  $('#b-fs').addEventListener('click', toggleFullscreen);

  // 播放中 2.5 秒沒動滑鼠就淡出控制列並隱藏游標；暫停、拖動、游標停在控制列上時保持顯示。
  function wake() {
    stage.classList.remove('idle');
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      if (!video.paused && !dragging && !bar.matches(':hover')) stage.classList.add('idle');
    }, 2500);
  }
  stage.addEventListener('pointermove', wake);
  stage.addEventListener('pointerdown', wake);
  stage.addEventListener('pointerleave', () => { if (!video.paused && !dragging) { clearTimeout(idleTimer); stage.classList.add('idle'); } });
  bar.addEventListener('pointerleave', wake);

  $('#prev').addEventListener('click', () => go(index - 1));
  $('#next').addEventListener('click', () => go(index + 1));
  $('#quality').addEventListener('change', (e) => { store.setting('quality', +e.target.value); load(true); });
  $('#rate').addEventListener('change', (e) => { store.setting('rate', +e.target.value); video.playbackRate = +e.target.value; });
  $('#dm').addEventListener('change', (e) => { store.setting('danmaku', e.target.value); danmaku.mode(e.target.value); paintDanmaku(); });
  $('#auto').addEventListener('change', (e) => store.setting('autoNext', e.target.checked));
  $('#fs').addEventListener('click', toggleFullscreen);
  function toggleFullscreen() {
    if (document.fullscreenElement || document.webkitFullscreenElement) (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    else (stage.requestFullscreen || stage.webkitRequestFullscreen).call(stage);
  }

  // 快捷鍵對齊 YouTube：K/空白、J/L、←/→、↑/↓、M、F、C、I、0–9、Home/End、,/.、</>、Shift+N/P。
  const togglePlay = () => (video.paused ? video.play() : video.pause());
  const seekBy = (seconds) => { video.currentTime = Math.min(Math.max(0, video.currentTime + seconds), video.duration || Infinity); };
  const seekTo = (ratio) => { if (video.duration) video.currentTime = video.duration * ratio; };
  const volumeBy = (delta) => {
    video.muted = false;
    video.volume = Math.round(Math.min(1, Math.max(0, video.volume + delta)) * 100) / 100;
    toast(`音量 ${Math.round(video.volume * 100)}%`);
  };
  const rateBy = (step) => {
    const i = RATES.indexOf(store.settings.rate);
    const next = RATES[Math.min(RATES.length - 1, Math.max(0, (i < 0 ? RATES.indexOf(1) : i) + step))];
    $('#rate').value = String(next);
    $('#rate').dispatchEvent(new Event('change'));
    toast(`倍速 ${next}×`);
  };
  const frameBy = (frames) => { if (!video.paused) video.pause(); seekBy(frames / 30); };
  const toggleDanmaku = () => {
    const modes = ['full', 'top', 'off'];
    $('#dm').value = modes[(modes.indexOf(store.settings.danmaku) + 1) % modes.length];
    $('#dm').dispatchEvent(new Event('change'));
    toast(`彈幕：${$('#dm').selectedOptions[0].text}`);
  };
  const togglePip = () => {
    if (document.pictureInPictureElement) document.exitPictureInPicture();
    else video.requestPictureInPicture?.().catch(() => toast('無法開啟子母畫面'));
  };
  function onKey(e) {
    if (e.target.closest('input, select, textarea') || e.metaKey || e.ctrlKey || e.altKey) return;
    const actions = {
      ' ': togglePlay, k: togglePlay, K: togglePlay,
      j: () => seekBy(-10), J: () => seekBy(-10),
      l: () => seekBy(10), L: () => seekBy(10),
      ArrowLeft: () => seekBy(-5),
      ArrowRight: () => seekBy(5),
      ArrowUp: () => volumeBy(0.05),
      ArrowDown: () => volumeBy(-0.05),
      m: () => { video.muted = !video.muted; toast(video.muted ? '靜音' : '取消靜音'); },
      f: toggleFullscreen,
      c: toggleDanmaku,
      i: togglePip,
      Home: () => seekTo(0),
      End: () => seekTo(0.999),
      ',': () => frameBy(-1),
      '.': () => frameBy(1),
      '<': () => rateBy(-1),
      '>': () => rateBy(1),
      N: () => go(index + 1),
      P: () => go(index - 1),
    };
    for (let n = 0; n <= 9; n++) actions[String(n)] = () => seekTo(n / 10);
    // 大寫鎖定時單鍵字母也照樣能用；Shift+N / Shift+P 才是換集（與 YouTube 相同）。
    const key = e.key.length === 1 && /[a-z]/i.test(e.key) && !e.shiftKey ? e.key.toLowerCase() : e.key;
    const action = actions[key];
    if (!action) return;
    e.preventDefault();
    e.stopPropagation();
    action();
    wake();
  }
  window.addEventListener('keydown', onKey, true);

  const danmaku = createDanmaku($('#danmaku'), video, vid, id, settings.danmaku);
  load(false);
  player = () => {
    save();
    window.removeEventListener('keydown', onKey, true);
    document.removeEventListener('fullscreenchange', paintFullscreen);
    document.removeEventListener('webkitfullscreenchange', paintFullscreen);
    clearTimeout(idleTimer);
    danmaku.stop();
    video.removeAttribute('src');
    video.load();
  };
}

/** 彈幕層：背景輪詢 /api/danmaku（伺服器分窗口抓取），依影片時間從右往左捲動。 */
function createDanmaku(layer, video, vid, seriesId, initialMode) {
  let items = [], next = 0, mode = initialMode, stopped = false, frame = 0, pollTimer = 0;
  const lanes = [], DURATION = 8000, LINE = 30;
  const lowerBound = (ms) => { let lo = 0, hi = items.length; while (lo < hi) { const mid = (lo + hi) >> 1; if (items[mid][0] < ms) lo = mid + 1; else hi = mid; } return lo; };
  const clear = () => { layer.replaceChildren(); lanes.length = 0; };
  function applyMode() { layer.hidden = mode === 'off'; layer.classList.toggle('top', mode === 'top'); clear(); }
  applyMode();

  async function poll() {
    if (stopped) return;
    try {
      const result = await api(`danmaku?vid=${vid}&series=${seriesId}`, { fresh: true });
      if (stopped) return;
      items = result.items;
      next = lowerBound(video.currentTime * 1000);
      if (!result.done) pollTimer = setTimeout(poll, 3000);
    } catch { pollTimer = setTimeout(poll, 10000); }
  }
  poll();

  function spawn(text) {
    const width = layer.clientWidth, laneCount = Math.max(1, Math.floor(layer.clientHeight / LINE) - (mode === 'top' ? 0 : 1));
    const right = layer.getBoundingClientRect().right;
    let lane = -1;
    for (let i = 0; i < laneCount; i++) {
      const last = lanes[i];
      if (!last || !last.isConnected || last.getBoundingClientRect().right < right - 24) { lane = i; break; }
    }
    if (lane < 0 || layer.childElementCount > 120) return;
    const el = document.createElement('span');
    el.textContent = text;
    el.style.top = `${lane * LINE + 4}px`;
    layer.appendChild(el);
    lanes[lane] = el;
    const distance = width + el.offsetWidth;
    const animation = el.animate([{ transform: 'translateX(0)' }, { transform: `translateX(${-distance}px)` }], { duration: DURATION * (distance / Math.max(width, 1)) * 0.8, easing: 'linear' });
    animation.playbackRate = video.playbackRate;
    if (video.paused) animation.pause();
    animation.onfinish = () => el.remove();
  }

  function tick() {
    if (stopped) return;
    frame = requestAnimationFrame(tick);
    if (mode === 'off' || video.paused || video.seeking) return;
    const now = video.currentTime * 1000;
    while (next < items.length && items[next][0] <= now) {
      if (items[next][0] >= now - 1000) spawn(items[next][1]);
      next++;
    }
  }
  tick();

  const animations = () => layer.getAnimations ? layer.getAnimations({ subtree: true }) : [];
  const onPause = () => animations().forEach((a) => a.pause());
  const onPlay = () => animations().forEach((a) => a.play());
  const onSeek = () => { clear(); next = lowerBound(video.currentTime * 1000); };
  const onRate = () => animations().forEach((a) => { a.playbackRate = video.playbackRate; });
  video.addEventListener('pause', onPause);
  video.addEventListener('play', onPlay);
  video.addEventListener('seeked', onSeek);
  video.addEventListener('ratechange', onRate);
  return {
    mode(value) { mode = value; applyMode(); },
    stop() {
      stopped = true;
      cancelAnimationFrame(frame);
      clearTimeout(pollTimer);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('seeked', onSeek);
      video.removeEventListener('ratechange', onRate);
      clear();
    },
  };
}

// ---------- 搜尋框與提示詞 ----------
(function setupSearch() {
  const form = $('#search-form'), input = $('#search-input'), list = $('#suggest');
  let timer = 0, rows = [], active = -1, seq = 0;
  const close = () => { list.hidden = true; active = -1; };
  const choose = (row) => {
    close();
    input.blur();
    location.hash = row.series ? `#/detail/${row.series.id}` : `#/search/${encodeURIComponent(row.name)}`;
  };
  const draw = () => {
    list.innerHTML = rows.map((r, i) => `<li data-i="${i}" class="${i === active ? 'active' : ''}"><span>${esc(r.name)}</span>${r.series ? '<small>劇集</small>' : ''}</li>`).join('');
    list.hidden = !rows.length;
  };
  input.addEventListener('input', () => {
    clearTimeout(timer);
    const keyword = input.value.trim();
    if (!keyword) { rows = []; close(); return; }
    timer = setTimeout(async () => {
      const mine = ++seq;
      try { const result = await api(`suggest?q=${encodeURIComponent(keyword)}`); if (mine === seq && input.value.trim() === keyword) { rows = result; active = -1; draw(); } } catch { /* 提示詞失敗不打擾 */ }
    }, 250);
  });
  input.addEventListener('keydown', (e) => {
    if (list.hidden) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      active = (active + (e.key === 'ArrowDown' ? 1 : -1) + rows.length + 1) % (rows.length + 1);
      if (active === rows.length) active = -1;
      draw();
    } else if (e.key === 'Escape') { e.preventDefault(); close(); } // 只關提示詞，不要清空輸入框
  });
  list.addEventListener('mousedown', (e) => { const li = e.target.closest('li'); if (li) { e.preventDefault(); choose(rows[+li.dataset.i]); } });
  input.addEventListener('blur', () => setTimeout(close, 150));
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    if (active >= 0 && rows[active]) return choose(rows[active]);
    const keyword = input.value.trim();
    if (!keyword) return;
    close();
    input.blur();
    location.hash = `#/search/${encodeURIComponent(keyword)}`;
  });
  window.addEventListener('keydown', (e) => {
    if (e.key === '/' && !e.target.closest('input, select, textarea')) { e.preventDefault(); input.focus(); input.select(); }
  });
})();

// ---------- 路由 ----------
let routeToken = 0;
function route() {
  const token = ++routeToken;
  if (player) { player(); player = null; }
  const parts = location.hash.replace(/^#\/?/, '').split('/').map((p) => { try { return decodeURIComponent(p); } catch { return p; } });
  const [name = '', ...rest] = parts;
  document.title = '紅果TV';
  document.querySelectorAll('[data-nav]').forEach((a) => a.classList.toggle('active', a.dataset.nav === (name || 'home')));
  if (name !== 'search') $('#search-input').value = '';
  window.scrollTo(0, 0);
  const pages = {
    '': () => homePage(token),
    rank: () => rankPage(token, rest[0] || 'comic'),
    category: () => categoryPage(token, rest.join('/') || 'comic-drama'),
    search: () => searchPage(token, rest.join('/')),
    detail: () => detailPage(token, rest[0]),
    play: () => playPage(token, rest[0], parseInt(rest[1], 10) || 1),
    library: () => libraryPage(token),
  };
  Promise.resolve((pages[name] || pages[''])()).catch((error) => { if (token === routeToken) view.innerHTML = errorBox(error); });
}
window.addEventListener('hashchange', route);
route();
