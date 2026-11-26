(function () {
  'use strict';
  const entries = /*__CMS_PUBLIC_ENTRIES__*/[];
  for (const entry of entries) {
    const label = entry.kind === 'chat' ? '客服' : '统计';
    const status = document.createElement('p');
    status.setAttribute('role', 'status');
    status.setAttribute('data-cms-integration-status', entry.kind);
    status.textContent = label + '正在加载';
    (document.body || document.documentElement).appendChild(status);
    const script = document.createElement('script');
    script.async = true;
    script.crossOrigin = 'anonymous';
    script.referrerPolicy = 'no-referrer';
    for (const [name, value] of Object.entries(entry.parameters || {})) script.setAttribute(name, value);
    let controller, objectUrl, timedOut = false;
    const release = () => { clearTimeout(timer); if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; } };
    const failed = () => { release(); if (!timedOut) status.textContent = label + '加载失败，请检查接入地址、CORS和网络'; };
    const timer = setTimeout(() => {
      timedOut = true;
      status.textContent = label + '加载超时，请检查接入地址和网络';
      if (controller) controller.abort();
      release();
    }, 8000);
    script.onload = () => { release(); if (!timedOut) status.textContent = label + '脚本已加载，业务功能尚需核验'; };
    script.onerror = failed;
    if (entry.loopbackTest === true) {
      // 本地替身可信；拒绝任何重定向，防止loopback入口转向真实供应商。
      controller = new AbortController();
      fetch(entry.scriptUrl, { credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: controller.signal })
        .then(response => { if (!response.ok) throw new Error('local script rejected'); return response.text(); })
        .then(text => {
          if (timedOut) return;
          objectUrl = URL.createObjectURL(new Blob([text], { type: 'application/javascript' }));
          script.src = objectUrl;
          document.head.appendChild(script);
        }).catch(failed);
    } else {
      script.src = entry.scriptUrl;
      document.head.appendChild(script);
    }
  }
})();
