const DEFAULT_ADMIN_RETURN_URL = '/projects';
const ALLOWED_ADMIN_PATHS = new Set(['/projects', '/users']);

/** Returns only a literal, allowlisted route within the admin SPA. */
export function safeAdminReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//')) {
    return DEFAULT_ADMIN_RETURN_URL;
  }
  if (value.includes('\\') || value.includes('%') || /[\u0000-\u001f\u007f]/u.test(value)) {
    return DEFAULT_ADMIN_RETURN_URL;
  }

  const path = value.split(/[?#]/u, 1)[0];
  return ALLOWED_ADMIN_PATHS.has(path) ? value : DEFAULT_ADMIN_RETURN_URL;
}
