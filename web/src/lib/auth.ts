// Typed wrappers cho Auth Service endpoints.
//
// Shape khớp shared-contracts/openapi/auth.v1.yaml. Run `pnpm gen:auth-types` để sinh
// src/generated/auth-api.d.ts từ OpenAPI (single source of truth) rồi import `components["schemas"]`
// thay thế inline types dưới đây khi muốn strict sync với contract.

import { apiClient, setTokens } from './api-client';

// ---- Request/response types (khớp auth.v1.yaml §components.schemas) ---------

export interface RegisterRequest {
  email: string;
  password: string;
  full_name: string;
}

export interface RegisterResponse {
  user_id: string;
  email_verification_sent: boolean;
  /** Chỉ có khi backend bật auth.dev.expose-verification-token — dev only. */
  verification_token_dev?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
  device_name?: string;
}

export interface TokenPair {
  access_token: string;
  refresh_token: string;
  token_type: 'Bearer';
  expires_in: number;
}

export interface MfaRequired {
  mfa_required: true;
  mfa_token: string;
}

export interface Me {
  user: {
    id: string;
    email: string;
    full_name: string;
    locale?: string;
    timezone?: string;
    email_verified: boolean;
    created_at: string;
    last_login_at: string | null;
  };
  orgs: Array<{
    id: string;
    name?: string;
    role_code: string;
    joined_at: string;
    active: boolean;
  }>;
  active_org_id?: string | null;
  mfa_enabled: boolean;
  platform_role?: 'super_admin' | null;
}

// ---- API functions ----------------------------------------------------------

export async function register(req: RegisterRequest): Promise<RegisterResponse> {
  return apiClient.post<RegisterResponse>('/auth/register', req, { public: true });
}

export async function verifyEmail(token: string): Promise<void> {
  await apiClient.post<void>('/auth/email/verify', { token }, { public: true });
}

/** Login. Nếu MFA enabled → trả {@link MfaRequired}; nếu không → trả {@link TokenPair}. */
export async function login(req: LoginRequest): Promise<TokenPair | MfaRequired> {
  const res = await apiClient.post<TokenPair | MfaRequired>('/auth/login', req, { public: true });
  if ('access_token' in res) {
    setTokens(res.access_token, res.refresh_token);
  }
  return res;
}

export async function logout(): Promise<void> {
  try {
    await apiClient.post<void>('/auth/logout');
  } finally {
    setTokens(null, null);
  }
}

export async function logoutAll(): Promise<void> {
  try {
    await apiClient.post<void>('/auth/logout-all');
  } finally {
    setTokens(null, null);
  }
}

export async function me(): Promise<Me> {
  return apiClient.get<Me>('/auth/me');
}

export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post<void>('/auth/password/forgot', { email }, { public: true });
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await apiClient.post<void>(
    '/auth/password/reset',
    { token, new_password: newPassword },
    { public: true }
  );
}

export async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  await apiClient.post<void>('/auth/password/change', {
    old_password: oldPassword,
    new_password: newPassword
  });
}
