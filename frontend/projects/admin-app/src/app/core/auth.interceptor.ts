import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { SessionService, SKIP_AUTH } from './session.service';

const PUBLIC_AUTH_PATHS = new Set([
  '/api/admin/auth/login',
  '/api/admin/auth/refresh',
  '/api/admin/auth/logout',
  '/api/admin/auth/complete-password-reset',
]);

function protectedAdminPath(pathname: string): boolean {
  return pathname.startsWith('/api/admin/') && !PUBLIC_AUTH_PATHS.has(pathname);
}

export function isProtectedAdminRequest(url: string): boolean {
  if (url.startsWith('//')) return false;
  if (url.startsWith('/')) return protectedAdminPath(url.split(/[?#]/u, 1)[0]);
  if (!/^https?:\/\//iu.test(url)) return false;

  try {
    const parsed = new URL(url);
    return parsed.origin === window.location.origin && protectedAdminPath(parsed.pathname);
  } catch {
    return false;
  }
}

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(SessionService);
  const protectedRequest = !request.context.get(SKIP_AUTH) && isProtectedAdminRequest(request.url);
  if (!protectedRequest) return next(request);

  const requestGeneration = session.generation;
  const authorized = session.accessToken
    ? request.clone({ setHeaders: { Authorization: `Bearer ${session.accessToken}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      if (!session.refreshToken) {
        session.invalidateExpiredSession(requestGeneration);
        return throwError(() => error);
      }

      return session.refresh().pipe(
        switchMap((tokens) =>
          next(request.clone({ setHeaders: { Authorization: `Bearer ${tokens.accessToken}` } })),
        ),
        catchError((authenticationError: unknown) => {
          session.invalidateExpiredSession(requestGeneration);
          return throwError(() => authenticationError);
        }),
      );
    }),
  );
};
