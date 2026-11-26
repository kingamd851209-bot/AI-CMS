(() => {
  const $ = window.jQuery;
  if (!$ || !$.ajaxPrefilter) return;
  const localUrl = value => {
    if (!value) return null;
    try {
      const url = new URL(value, document.baseURI);
      return url.origin === location.origin ? url : null;
    } catch {
      return null;
    }
  };
  const allowedParams = params => [...params.keys()].every(key => key === 'tm' || key === '_');
  const counts = new Map();
  const groups = [];
  const paths = new Map();
  for (const group of document.querySelectorAll('[data-cms-fragment-tabs]')) {
    const tabs = [...group.children];
    const entries = tabs.map(tab => {
      const path = localUrl(tab.getAttribute('data-src'))?.pathname;
      if (path) counts.set(path, (counts.get(path) || 0) + 1);
      const target = localUrl(tab.getAttribute('data-cms-fragment-path'));
      return {path, tab, target};
    });
    groups.push({group, tabs, entries});
  }
  for (const {group, tabs, entries} of groups) {
    if (entries.some(({path, target}) => !target || counts.get(path) !== 1)) continue;
    const activeTabs = tabs.filter(tab => tab.matches('.active,.on'));
    const last = activeTabs.length === 1 ? activeTabs[0] : tabs[0];
    const state = {group, tabs, last, pending: null, serial: 0, status: null};
    for (const {path, tab, target} of entries) paths.set(path, {state, tab, target});
  }
  if (!paths.size) return;
  // 初始片段替换可能改变上方内容高度。访客开始操作后，滚动由访客控制。
  const initialHash = location.hash;
  let landing = initialHash && performance.getEntriesByType('navigation')[0]?.type === 'navigate'
    ? document.getElementById(initialHash.slice(1)) : null;
  const landingEvents = new AbortController();
  let landingObserver;
  function releaseLanding() {
    landing = null;
    landingObserver?.disconnect();
    landingEvents.abort();
  }
  if (landing && window.ResizeObserver) {
    for (const event of ['pointerdown', 'wheel', 'touchstart', 'keydown', 'hashchange', 'pagehide'])
      window.addEventListener(event, releaseLanding, {capture: true, passive: true, signal: landingEvents.signal});
    // 图片比例计算可能晚于 Ajax 成功回调；只观察位于锚点之前的片段区。
    landingObserver = new ResizeObserver(() => {
      if (landing && location.hash === initialHash && document.readyState === 'complete'
          && landing.getBoundingClientRect().top < -1) landing.scrollIntoView({block:'start', behavior:'instant'});
    });
    window.addEventListener('load', () => setTimeout(releaseLanding, 8000), {once:true});
  }
  let syntheticTab;
  const clickHooks = $.event?.special?.click;
  if (clickHooks) {
    const previous = clickHooks.handle;
    clickHooks.handle = function (event, ...args) {
      const handler = previous || event.handleObj.handler;
      if (!event.isTrigger) return handler.call(this, event, ...args);
      const previousTab = syntheticTab;
      syntheticTab = event.target === this ? this : null;
      try {
        return handler.call(this, event, ...args);
      } finally {
        syntheticTab = previousTab;
      }
    };
  }
  $.ajaxPrefilter((options, original, xhr) => {
    if ((options.type || 'GET').toUpperCase() !== 'GET') return;
    const url = localUrl(options.url);
    if (!url || !allowedParams(url.searchParams)) return;
    const match = paths.get(url.pathname);
    if (!match) return;
    const click = window.event;
    const fromTab = syntheticTab === undefined
      ? click?.type === 'click' && click.eventPhase && click.composedPath().includes(match.tab)
      : syntheticTab === match.tab;
    if (!fromTab || (options.data && (typeof options.data !== 'string'
        || !allowedParams(new URLSearchParams(options.data))))) return;
    const {state, tab, target} = match;
    const serial = ++state.serial;
    if (landing && landingObserver && serial === 1 && syntheticTab === tab && tab === state.last
        && (state.group.compareDocumentPosition(landing) & Node.DOCUMENT_POSITION_FOLLOWING)) {
      let section = state.group;
      while (section.parentElement && !section.parentElement.contains(landing)) section = section.parentElement;
      landingObserver.observe(section);
    }
    if (state.pending) state.pending.abort();
    state.pending = xhr;
    url.pathname = target.pathname;
    options.url = url.href;
    state.group.setAttribute('aria-busy','true');
    if (state.status) state.status.textContent = '';
    xhr.done(() => { if (serial === state.serial) state.last = tab; });
    xhr.fail((response, reason) => {
      if (serial !== state.serial || reason === 'abort') return;
      if (!state.status) {
        state.status = document.createElement('p');
        state.status.setAttribute('role','status');
        state.group.parentElement.insertAdjacentElement('afterend',state.status);
      }
      state.status.textContent = '内容加载失败，请再次选择此项重试。';
      if (state.last) {
        for (const cls of ['active','on'])
          if (tab.classList.contains(cls)) state.tabs.forEach(t => t.classList.toggle(cls,t === state.last));
      }
    });
    xhr.always(() => {
      if (serial === state.serial) {
        state.pending = null;
        state.group.removeAttribute('aria-busy');
      }
    });
  });
})();
