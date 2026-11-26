import { fileURLToPath, URL } from 'node:url';
import { TLSSocket } from 'node:tls';
import type { ClientRequest, IncomingMessage } from 'node:http';
import { defineConfig } from 'vite';
import type { ProxyOptions } from 'vite';
import { loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

export function shouldServeAdminSpaRoute(req: Pick<IncomingMessage, 'headers' | 'method'>): boolean {
  const accept = req.headers.accept;
  const acceptsHtml = Array.isArray(accept)
    ? accept.some((value) => value.includes('text/html'))
    : String(accept ?? '').includes('text/html');
  return req.method === 'GET' && acceptsHtml;
}

function proxyBackendOrigin(apiTarget: string) {
  return new URL(apiTarget).origin;
}

function withBackendOriginRewrite(apiTarget: string): ProxyOptions {
  const backendOrigin = proxyBackendOrigin(apiTarget);
  return {
    target: apiTarget,
    changeOrigin: true,
    bypass(req) {
      if (req.headers.origin === undefined) return;
      const scheme = req.socket instanceof TLSSocket && req.socket.encrypted ? 'https' : 'http';
      if (req.headers.origin !== `${scheme}://${req.headers.host}`) return false;
    },
    configure(proxy: Parameters<NonNullable<ProxyOptions['configure']>>[0]) {
      proxy.on('proxyReq', (proxyReq: ClientRequest, req: IncomingMessage) => {
        if (req.headers.origin) {
          proxyReq.setHeader('Origin', backendOrigin);
        }
      });
    }
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const developerWorkbench = mode === 'workbench';
  const apiTarget = process.env.VITE_DEV_PROXY_TARGET || env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:7088';
  const base = process.env.VITE_ADMIN_ASSET_BASE || env.VITE_ADMIN_ASSET_BASE || '/';

  return {
    base,
    define: {
      'import.meta.env.VITE_DEVELOPER_WORKBENCH': JSON.stringify(String(developerWorkbench))
    },
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    optimizeDeps: {
      include: [
        'vue-codemirror',
        '@codemirror/lang-html',
        '@codemirror/theme-one-dark'
      ]
    },
    server: {
      host: '127.0.0.1',
      proxy: {
        '/api': withBackendOriginRewrite(apiTarget),
        '/ws': {
          target: apiTarget,
          changeOrigin: true,
          ws: true
        },
        '/assets/global': withBackendOriginRewrite(apiTarget),
        '/admin': {
          target: apiTarget,
          changeOrigin: true,
          bypass: (req) => shouldServeAdminSpaRoute(req) ? '/index.html' : undefined
        }
      }
    },
    build: {
      outDir: developerWorkbench ? 'dist-workbench' : 'dist',
      emptyOutDir: true,
      assetsDir: 'assets'
    }
  };
});
