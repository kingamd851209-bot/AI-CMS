import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios';

export interface AdminApiResponse<T> {
  code: number | string;
  data: T;
  message?: string;
  msg?: string;
  requestId?: string;
  details?: Record<string, unknown>;
}

interface AdminHttpErrorOptions {
  status?: number;
  code?: unknown;
  requestId?: string;
  details?: Record<string, unknown>;
}

function trimTrailingSlash(value: string): string {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

function envValue(name: 'VITE_API_BASE_URL' | 'VITE_ADMIN_LOGIN_URL'): string {
  return String(import.meta.env[name] ?? '').trim();
}

export function resolveAdminApiBaseUrl(): string {
  const configured = envValue('VITE_API_BASE_URL');
  // 相对根路径随当前页面的协议、域名和端口解析；开发环境由 Vite 代理。
  return configured ? trimTrailingSlash(configured) : '/';
}

export const httpClient: AxiosInstance = axios.create({
  baseURL: resolveAdminApiBaseUrl(),
  timeout: 15000,
  withCredentials: true,
  headers: {
    'X-Requested-With': 'XMLHttpRequest'
  }
});

type UnauthorizedRedirectHandler = (loginUrl: string) => void;
type UnauthorizedSessionResetHandler = () => void;

const defaultUnauthorizedRedirectHandler: UnauthorizedRedirectHandler = (loginUrl) => {
  const login = new URL(loginUrl, window.location.origin);
  if (login.origin === window.location.origin && window.location.pathname === '/admin/v2/site-build') {
    login.searchParams.set('returnTo', window.location.pathname + window.location.search);
  }
  window.location.href = login.href;
};

let unauthorizedRedirectHandler: UnauthorizedRedirectHandler = defaultUnauthorizedRedirectHandler;
let unauthorizedSessionResetHandler: UnauthorizedSessionResetHandler = () => undefined;
let unauthorizedRedirecting = false;

function responseMessage<T>(response: AdminApiResponse<T>, status?: number): string {
  if (response.message || response.msg) {
    return response.message || response.msg || '';
  }
  if (status === 401 || isUnauthorizedCode(response.code)) return '未登录或登录已过期';
  if (status === 403) return '无权执行当前操作';
  if (status === 409) return '数据已被其他操作修改，请刷新后重试';
  return status ? `请求失败（HTTP ${status}）` : '后台接口无响应';
}

function isUnauthorizedCode(code: unknown): boolean {
  return code === 401 || code === '401';
}

export class AdminHttpError extends Error {
  readonly status?: number;
  readonly code?: unknown;
  readonly authenticationError: boolean;
  readonly authorizationError: boolean;
  readonly conflictError: boolean;
  readonly requestId?: string;
  readonly details: Record<string, unknown>;

  constructor(message: string, options: AdminHttpErrorOptions = {}) {
    super(message);
    this.name = 'AdminHttpError';
    this.status = options.status;
    this.code = options.code;
    this.authenticationError = this.status === 401 || isUnauthorizedCode(this.code);
    this.authorizationError = this.status === 403;
    this.conflictError = this.status === 409;
    this.requestId = options.requestId;
    this.details = options.details ?? {};
  }
}

export function isAuthenticationError(error: unknown): boolean {
  if (error instanceof AdminHttpError) {
    return error.authenticationError;
  }

  if (!error || typeof error !== 'object') {
    return false;
  }

  const candidate = error as {
    authenticationError?: unknown;
    status?: unknown;
    code?: unknown;
    response?: {
      status?: unknown;
      data?: {
        code?: unknown;
      };
    };
  };

  return candidate.authenticationError === true
    || candidate.status === 401
    || isUnauthorizedCode(candidate.code)
    || candidate.response?.status === 401
    || isUnauthorizedCode(candidate.response?.data?.code);
}

export function buildAdminLoginUrl(): string {
  const configured = envValue('VITE_ADMIN_LOGIN_URL');
  if (configured) {
    return configured;
  }
  return new URL('/admin/login', window.location.origin).toString();
}

export function setUnauthorizedRedirectHandler(handler: UnauthorizedRedirectHandler): void {
  unauthorizedRedirectHandler = handler;
}

export function resetUnauthorizedRedirectHandler(): void {
  unauthorizedRedirectHandler = defaultUnauthorizedRedirectHandler;
  resetUnauthorizedRedirectState();
}

export function setUnauthorizedSessionResetHandler(handler: UnauthorizedSessionResetHandler): void {
  unauthorizedSessionResetHandler = handler;
}

export function resetUnauthorizedSessionResetHandler(): void {
  unauthorizedSessionResetHandler = () => undefined;
  resetUnauthorizedRedirectState();
}

export function resetUnauthorizedRedirectState(): void {
  unauthorizedRedirecting = false;
}

function redirectToLogin(): void {
  const loginUrl = buildAdminLoginUrl();
  if (unauthorizedRedirecting) {
    return;
  }
  unauthorizedRedirecting = true;
  unauthorizedSessionResetHandler();
  if (window.location.href === loginUrl) {
    return;
  }
  unauthorizedRedirectHandler(loginUrl);
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const { data: response } = await httpClient.request<AdminApiResponse<T>>(config);
    if (!response || response.code !== 0) {
      if (response && isUnauthorizedCode(response.code)) {
        redirectToLogin();
      }
      throw new AdminHttpError(response ? responseMessage(response) : '后台接口无响应', {
        code: response?.code,
        requestId: response?.requestId,
        details: response?.details
      });
    }
    return response.data;
  } catch (error) {
    if (axios.isAxiosError<AdminApiResponse<unknown>>(error)) {
      if (error.response?.status === 401 || isUnauthorizedCode(error.response?.data?.code)) {
        redirectToLogin();
      }
      throw new AdminHttpError(error.response?.data
        ? responseMessage(error.response.data, error.response.status)
        : '网络请求失败', {
        status: error.response?.status,
        code: error.response?.data?.code,
        requestId: error.response?.data?.requestId,
        details: error.response?.data?.details
      });
    }
    if (error instanceof Error) {
      throw error;
    }
    throw new AdminHttpError('网络请求失败');
  }
}

export const http = {
  get<T>(url: string, config?: AxiosRequestConfig) {
    return request<T>({ ...config, method: 'GET', url });
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, method: 'POST', url, data });
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, method: 'PATCH', url, data });
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, method: 'PUT', url, data });
  },
  delete<T>(url: string, config?: AxiosRequestConfig) {
    return request<T>({ ...config, method: 'DELETE', url });
  }
};
