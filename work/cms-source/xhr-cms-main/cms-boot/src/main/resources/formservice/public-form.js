/* 同站点留言适配器；显式映射控件，不含查询密钥，不自动提交。 */
(() => {
  'use strict';
  const base = '/api/form/public/';
  const request = async (path, options) => {
    const response = await fetch(base + path, {credentials: 'omit', ...options});
    let body;
    try { body = await response.json(); } catch { throw new Error('留言服务返回无效响应'); }
    if (!response.ok || body.code !== 0) {
      const details = body.data && Array.isArray(body.data.errors) ? body.data.errors.map(e => e.message).join('；') : '';
      throw new Error(details || body.message || '留言服务暂不可用');
    }
    return body.data;
  };
  function prefill(form) {
    const controls = form.querySelectorAll('[data-cms-form-prefill]');
    if (controls.length !== 1) return;
    const control = controls[0];
    const key = control.dataset.cmsFormPrefill;
    if (control.form !== form || !control.matches('input[data-cms-field]') || control.type !== 'text'
        || control.readOnly || control.matches(':disabled') || control.value !== ''
        || !/^[A-Za-z][A-Za-z0-9_]{0,62}$/.test(key) || control.id !== key) return;
    const values = new URLSearchParams(window.location.search).getAll(key);
    if (values.length !== 1) return;
    const value = values[0];
    // Character.isWhitespace 与 Java String.isBlank 一致，且单独拒绝 ISO 控制字符。
    if (value.length > 200 || /[\u0000-\u001f\u007f-\u009f]/.test(value)
        || /^[\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(value)) return;
    control.value = value;
  }
  function layoutGeneratedCaptcha(form, code, image, refresh) {
    const label = code?.parentElement;
    const item = label?.parentElement;
    const row = item?.parentElement;
    // 只调整编译器追加的验证码行，保留原页作者自行设计的控件。
    if (!label?.matches('label') || !item?.matches('div.item') || !row?.matches('div.group')
        || row.parentElement !== form || row.children.length !== 1 || item.children.length !== 3
        || image?.parentElement !== item || refresh?.parentElement !== item) return;
    row.classList.remove('group');
    item.classList.remove('item');
    row.setAttribute('data-cms-captcha-row', '');
    code.placeholder = '输入图中字符';
    if (document.querySelector('style[data-cms-captcha-style]')) return;
    const style = document.createElement('style');
    style.setAttribute('data-cms-captcha-style', '');
    style.textContent = `
      form[data-cms-form-category] [data-cms-captcha-row] {display:block;width:100%;margin:16px 0;box-sizing:border-box}
      [data-cms-captcha-row] > div {display:flex;flex-wrap:wrap;align-items:flex-end;gap:10px;max-width:100%}
      [data-cms-captcha-row] label {display:grid;gap:6px;max-width:100%;position:static;color:#30343a;font:500 15px/1.4 sans-serif}
      [data-cms-captcha-row] input[data-cms-captcha-code] {box-sizing:border-box;display:block;position:static;width:160px;max-width:100%;height:44px;margin:0;padding:8px 12px;border:1px solid #737982;border-radius:4px;background:white;color:#222;font:16px/1.4 sans-serif}
      [data-cms-captcha-row] input:focus-visible {outline:2px solid #21659c;outline-offset:1px}
      [data-cms-captcha-row] img[data-cms-captcha-image] {position:static;display:block;width:auto;max-width:100%;height:44px;object-fit:contain}
      [data-cms-captcha-row] button,[data-cms-captcha-row] ~ button[data-cms-form-submit] {box-sizing:border-box;min-height:44px;padding:8px 16px;border:1px solid #737982;border-radius:4px;background:white;color:#30343a;font:15px/1.4 sans-serif;cursor:pointer}
      [data-cms-captcha-row] ~ button[data-cms-form-submit]:disabled {opacity:.6;cursor:default}
    `;
    document.head.appendChild(style);
  }
  async function bind(form) {
    form.removeAttribute('action'); form.removeAttribute('onsubmit');
    prefill(form);
    let status = form.querySelector('[data-cms-form-status]');
    if (!status) {
      status = document.createElement('p');
      status.setAttribute('data-cms-form-status', '');
      status.setAttribute('role', 'status');
      form.appendChild(status);
    }
    const image = form.querySelector('[data-cms-captcha-image]');
    const code = form.querySelector('[data-cms-captcha-code]');
    const refresh = form.querySelector('[data-cms-captcha-refresh]');
    layoutGeneratedCaptcha(form, code, image, refresh);
    const submit = form.querySelector('[data-cms-form-submit]');
    const setReady = ready => { if (submit) submit.disabled = !ready; };
    const categoryId = Number(form.dataset.cmsFormCategory);
    let definition, captchaKey = '', busy = false;
    const message = text => { if (status) status.textContent = text; };
    if (status) status.setAttribute('aria-live', 'polite');
    // 接管已有form，阻止残留action向真实第三方提交。
    form.addEventListener('submit', async event => {
      event.preventDefault(); event.stopImmediatePropagation();
      if (busy) return;
      if (!definition || !captchaKey) { message('留言尚未就绪，请刷新验证码或检查配置'); return; }
      if (!form.reportValidity()) return;
      try {
        const fields = {};
        for (const field of definition.fields) {
          const controls = [...form.querySelectorAll('[data-cms-field]')].filter(e => e.dataset.cmsField === field.fieldName);
          if (!controls.length) continue;
          const values = controls.flatMap(e => {
            if ((e.type === 'checkbox' || e.type === 'radio') && !e.checked) return [];
            return e.multiple ? [...e.selectedOptions].map(o => o.value) : [String(e.value ?? '')];
          });
          if (field.required && !values.some(value => value.trim())) throw new Error(field.label + ' 为必填项');
          const value = field.multiple ? (values.length ? ',' + values.join(',') + ',' : '') : (values[0] || '');
          if (field.storageMaxLength > 0 && Array.from(value).length > field.storageMaxLength)
            throw new Error(field.label + ' 长度不能超过 ' + field.storageMaxLength);
          fields[field.fieldName] = value;
        }
        busy = true; setReady(false); message('正在提交…');
        await request('submit', {method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({categoryId,fields,captchaKey,captchaCode:code.value})});
        message('提交成功');
      } catch (error) { message(error.message || '提交失败，请稍后重试'); }
      finally { if (busy) { busy = false; captchaKey = ''; code.value = ''; } }
    }, true);
    if (!image || !code || !refresh || !Number.isSafeInteger(categoryId) || categoryId <= 0) {
      message('留言控件配置不完整：需要状态、验证码图片、验证码输入和刷新按钮'); return;
    }
    const loadCaptcha = async () => {
      if (busy) return;
      captchaKey = ''; setReady(false);
      try {
        const result = await request('captcha?categoryId=' + categoryId);
        captchaKey = result.captchaKey;
        image.src = result.imageBase64.startsWith('data:image/') ? result.imageBase64 : 'data:image/png;base64,' + result.imageBase64;
        code.value = ''; setReady(true); message('请填写表单并输入验证码');
      } catch (error) { captchaKey = ''; message(error.message || '验证码加载失败'); }
    };
    refresh.type = 'button'; refresh.addEventListener('click', loadCaptcha);
    try {
      const result = await request('definition?categoryId=' + categoryId);
      const allowed = new Map(result.fields.map(f => [f.fieldName, f]));
      for (const control of form.querySelectorAll('[data-cms-field]')) {
        const field = allowed.get(control.dataset.cmsField);
        if (!field) throw new Error('控件映射了未开放字段：' + control.dataset.cmsField);
        control.required = Boolean(field.required) && !['checkbox','radio'].includes(control.type);
        const browserLimit = field.storageMaxLength > 0 ? field.configuredMaxLength : field.maxLength;
        if (browserLimit > 0 && ['text','email','tel','url','search','password','textarea'].includes(control.type))
          control.maxLength = browserLimit;
      }
      for (const field of result.fields) {
        if (field.required && ![...form.querySelectorAll('[data-cms-field]')].some(e => e.dataset.cmsField === field.fieldName)) throw new Error('缺少必填字段映射：' + field.label);
      }
      definition = result; code.required = true; await loadCaptcha();
    } catch (error) { definition = null; message(error.message || '留言配置加载失败'); }
  }
  const start = () => document.querySelectorAll('form[data-cms-form-category]').forEach(form => { void bind(form); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
