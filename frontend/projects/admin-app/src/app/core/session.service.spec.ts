import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { isSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authGuard } from './auth.guard';
import { authInterceptor } from './auth.interceptor';
import { SessionService, SKIP_AUTH } from './session.service';

const tokens = (accessToken: string, refreshToken: string) => ({
  accessToken,
  refreshToken,
  tokenType: 'Bearer' as const,
  expiresIn: 300,
});

const router = {
  url: '/users?page=2',
  createUrlTree: vi.fn((_commands: unknown[], options: unknown) => ({ options })),
  navigateByUrl: vi.fn(() => Promise.resolve(true)),
};

describe('SessionService', () => {
  let session: SessionService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    session = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('stores typed access and refresh tokens after login and loads /me', async () => {
    const promise = firstValueFrom(session.login('admin@example.com', 'secret'));
    http.expectOne('/api/admin/auth/login').flush(tokens('access', 'refresh'));
    http
      .expectOne('/api/admin/auth/me')
      .flush({ userId: 'u1', email: 'admin@example.com', generalAdmin: true, projectIds: [] });
    await promise;
    expect(localStorage.getItem('chatbotq.admin.accessToken')).toBe('access');
    expect(localStorage.getItem('chatbotq.admin.refreshToken')).toBe('refresh');
    expect(session.me()?.generalAdmin).toBe(true);
    expect(session.me()?.projectIds).toEqual([]);
  });

  it('keeps tokens out of Angular signals, the DOM and console output', async () => {
    const access = 'access-secret-not-for-signals';
    const refresh = 'refresh-secret-not-for-signals';
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const promise = firstValueFrom(session.login('admin@example.com', 'password-secret'));
    http.expectOne('/api/admin/auth/login').flush(tokens(access, refresh));
    http
      .expectOne('/api/admin/auth/me')
      .flush({ userId: 'u1', email: 'admin@example.com', generalAdmin: true, projectIds: [] });
    await promise;
    const signalValues = Object.values(session as unknown as Record<string, unknown>)
      .filter(isSignal)
      .map((value) => (value as () => unknown)());
    expect(JSON.stringify(signalValues)).not.toContain(access);
    expect(JSON.stringify(signalValues)).not.toContain(refresh);
    expect(document.body.textContent).not.toContain(access);
    expect(document.body.textContent).not.toContain(refresh);
    expect(JSON.stringify([...log.mock.calls, ...error.mock.calls])).not.toContain(access);
    expect(JSON.stringify([...log.mock.calls, ...error.mock.calls])).not.toContain(refresh);
    log.mockRestore();
    error.mockRestore();
  });

  it('clears tokens immediately even when remote logout fails', async () => {
    session.storeTokens(tokens('a', 'r'));
    const promise = firstValueFrom(session.logout());
    expect(session.authenticated()).toBe(false);
    http.expectOne('/api/admin/auth/logout').flush({}, { status: 500, statusText: 'Error' });
    await promise;
    expect(session.authenticated()).toBe(false);
  });

  it('posts the exact password-reset body without authentication context', async () => {
    const promise = firstValueFrom(
      session.completePasswordReset('user@example.com', 'Temporary123', 'Permanent456'),
    );
    const request = http.expectOne('/api/admin/auth/complete-password-reset');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      email: 'user@example.com',
      temporaryPassword: 'Temporary123',
      newPassword: 'Permanent456',
    });
    expect(request.request.context.get(SKIP_AUTH)).toBe(true);
    request.flush(null, { status: 204, statusText: 'No Content' });
    await promise;
  });

  it('does not let an old refresh response restore a logged-out session', async () => {
    session.storeTokens(tokens('old', 'refresh-old'));
    const refresh = firstValueFrom(session.refresh()).catch((error) => error);
    const pendingRefresh = http.expectOne('/api/admin/auth/refresh');

    const logout = firstValueFrom(session.logout());
    expect(session.authenticated()).toBe(false);
    http.expectOne('/api/admin/auth/logout').flush(null);
    await logout;

    pendingRefresh.flush(tokens('stale-access', 'stale-refresh'));
    await refresh;
    expect(session.accessToken).toBeNull();
    expect(session.refreshToken).toBeNull();
  });
});

describe('authInterceptor refresh lifecycle', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shares one rotating refresh between concurrent 401 responses', async () => {
    const session = TestBed.inject(SessionService);
    session.storeTokens(tokens('old', 'refresh-old'));
    const first = firstValueFrom(session.loadMe());
    const second = firstValueFrom(session.loadMe());
    http
      .match('/api/admin/auth/me')
      .forEach((request) => request.flush({}, { status: 401, statusText: 'Unauthorized' }));
    http.expectOne('/api/admin/auth/refresh').flush(tokens('new', 'refresh-new'));
    const retried = http.match('/api/admin/auth/me');
    expect(retried).toHaveLength(2);
    retried[0].flush({ userId: 'u', email: 'one@b.co', generalAdmin: false, projectIds: ['p1'] });
    retried[1].flush({ userId: 'u', email: 'two@b.co', generalAdmin: false, projectIds: ['p2'] });
    await Promise.all([first, second]);
  });

  it('does not let a stale refresh response invalidate a newer login', async () => {
    const session = TestBed.inject(SessionService);
    session.storeTokens(tokens('old', 'refresh-old'));
    const oldRequest = firstValueFrom(session.loadMe()).catch((error) => error);
    http.expectOne('/api/admin/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    const oldRefresh = http.expectOne('/api/admin/auth/refresh');

    const newLogin = firstValueFrom(session.login('new@example.com', 'NewPassword123'));
    http.expectOne('/api/admin/auth/login').flush(tokens('new-access', 'new-refresh'));
    const newMe = http.expectOne('/api/admin/auth/me');
    expect(newMe.request.headers.get('Authorization')).toBe('Bearer new-access');
    newMe.flush({ userId: 'new', email: 'new@example.com', generalAdmin: true, projectIds: [] });
    await newLogin;

    oldRefresh.flush(tokens('stale-access', 'stale-refresh'));
    await oldRequest;
    expect(session.accessToken).toBe('new-access');
    expect(session.refreshToken).toBe('new-refresh');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('retries at most once and invalidates and redirects exactly once after a second 401', async () => {
    const session = TestBed.inject(SessionService);
    session.storeTokens(tokens('old', 'refresh-old'));
    const result = firstValueFrom(session.loadMe()).catch((error) => error);
    http.expectOne('/api/admin/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/admin/auth/refresh').flush(tokens('new', 'refresh-new'));
    http.expectOne('/api/admin/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect((await result).status).toBe(401);
    expect(session.authenticated()).toBe(false);
    expect(router.createUrlTree).toHaveBeenCalledTimes(1);
    expect(router.createUrlTree.mock.calls[0][1]).toEqual({
      queryParams: { returnUrl: '/users?page=2' },
    });
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    http.expectNone('/api/admin/auth/refresh');
  });

  it('invalidates and redirects when a 401 has no refresh token', async () => {
    localStorage.setItem('chatbotq.admin.accessToken', 'orphan-access');
    const session = TestBed.inject(SessionService);
    const result = firstValueFrom(session.loadMe()).catch((error) => error);
    http.expectOne('/api/admin/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect((await result).status).toBe(401);
    expect(session.authenticated()).toBe(false);
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    http.expectNone('/api/admin/auth/refresh');
  });

  it('invalidates and redirects once when refresh itself is unauthorized', async () => {
    const session = TestBed.inject(SessionService);
    session.storeTokens(tokens('old', 'bad'));
    const first = firstValueFrom(session.loadMe()).catch((error) => error);
    const second = firstValueFrom(session.loadMe()).catch((error) => error);
    http
      .match('/api/admin/auth/me')
      .forEach((request) => request.flush({}, { status: 401, statusText: 'Unauthorized' }));
    http
      .expectOne('/api/admin/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    await Promise.all([first, second]);
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
  });
});

describe('authGuard', () => {
  it.each(['/users', '//evil.example', '/unknown'])(
    'uses only a safe returnUrl for %s',
    (requested) => {
      localStorage.clear();
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: Router, useValue: router },
        ],
      });
      const result = TestBed.runInInjectionContext(() =>
        authGuard({} as never, { url: requested } as never),
      ) as unknown as { options: { queryParams: { returnUrl: string } } };
      expect(result.options.queryParams.returnUrl).toBe(
        requested === '/users' ? '/users' : '/projects',
      );
    },
  );
});
