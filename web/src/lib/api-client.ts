// Typed fetch wrapper cho Auth Service (fullstack-dev skill §5-A + §6).
// - Bearer token tự attach từ in-memory store (localStorage có XSS risk).
// - 401 → thử /auth/refresh 1 lần, re-attempt request; fail 2 lần → redirect login.
// - Error thô từ API → ApiError giữ status + Problem Details body cho UI map lỗi.

const BASE_URL = process.env.NEXT_PUBLIC_AUTH_API_URL ?? 'http://localhost:3001';

let accessToken: string | null = null;
let refreshToken: string | null = null;

export function setTokens(access: string | null, refresh: string | null) {
  accessToken = access;
  refreshToken = refresh;
}

export function getAccessToken() {
  return accessToken;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ProblemDetails | null
  ) {
    super(body?.detail ?? body?.title ?? `API error ${status}`);
  }
}

export interface ProblemDetails {
  type?: string;
  title?: string;
  status: number;
  code?: string;
  detail?: string;
  trace_id?: string;
  timestamp?: string;
  errors?: Array<{ field?: string; message?: string }>;
}

async function rawFetch(path: string, init: RequestInit, withAuth: boolean): Promise<Response> {
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json');
  }
  if (withAuth && accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  return fetch(`${BASE_URL}${path}`, { ...init, headers });
}

async function parseError(res: Response): Promise<ApiError> {
  let body: ProblemDetails | null = null;
  try {
    body = (await res.json()) as ProblemDetails;
  } catch {
    body = null;
  }
  return new ApiError(res.status, body);
}

async function tryRefresh(): Promise<boolean> {
  if (!refreshToken) return false;
  const res = await rawFetch(
    '/auth/refresh',
    { method: 'POST', body: JSON.stringify({ refresh_token: refreshToken }) },
    false
  );
  if (!res.ok) {
    setTokens(null, null);
    return false;
  }
  const pair = (await res.json()) as { access_token: string; refresh_token: string };
  setTokens(pair.access_token, pair.refresh_token);
  return true;
}

async function request<T>(path: string, init: RequestInit = {}, withAuth = true): Promise<T> {
  let res = await rawFetch(path, init, withAuth);
  if (res.status === 401 && withAuth && (await tryRefresh())) {
    res = await rawFetch(path, init, true);
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export const apiClient = {
  get: <T>(path: string, opts?: { public?: boolean }) =>
    request<T>(path, { method: 'GET' }, !opts?.public),
  post: <T>(path: string, body?: unknown, opts?: { public?: boolean }) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }, !opts?.public),
  delete: <T>(path: string, opts?: { public?: boolean }) =>
    request<T>(path, { method: 'DELETE' }, !opts?.public)
};
