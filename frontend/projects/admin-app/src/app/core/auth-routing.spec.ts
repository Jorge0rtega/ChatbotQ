import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { safeAdminReturnUrl } from './safe-return-url';
import { SessionService } from './session.service';

const router = {
  url: '/users',
  createUrlTree: vi.fn((_commands: unknown[], options: unknown) => ({ options })),
  navigateByUrl: vi.fn(() => Promise.resolve(true)),
};

describe('admin auth request boundary', () => {
  let client: HttpClient;
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
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(SessionService).storeTokens({
      accessToken: 'access-secret',
      refreshToken: 'refresh-secret',
      tokenType: 'Bearer',
      expiresIn: 300,
    });
  });

  afterEach(() => http.verify());

  it.each([
    'https://external.example/api/admin/projects',
    'http://external.example/api/admin/projects',
    '//external.example/api/admin/projects',
    '/api/public/projects',
    '/assets/config.json',
  ])('never adds Bearer to %s', async (url) => {
    const promise = firstValueFrom(client.get(url));
    const request = http.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
    await promise;
  });

  it('adds Bearer to an absolute same-origin admin URL', async () => {
    const url = `${window.location.origin}/api/admin/projects`;
    const promise = firstValueFrom(client.get(url));
    const request = http.expectOne(url);
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-secret');
    request.flush({});
    await promise;
  });

  it.each([
    '/api/admin/auth/login',
    '/api/admin/auth/refresh',
    '/api/admin/auth/logout',
    '/api/admin/auth/complete-password-reset',
  ])('never adds Bearer to public auth endpoint %s', async (url) => {
    const promise = firstValueFrom(client.post(url, {}));
    const request = http.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(null);
    await promise;
  });
});

describe('safeAdminReturnUrl', () => {
  it.each([
    '//evil.example',
    '/\\evil',
    'https://evil.example',
    'javascript:alert(1)',
    '/%2f%2fevil.example',
    '/projects%3Fnext%3Dhttps://evil.example',
    '/unknown',
    '/login',
  ])('rejects unsafe or non-allowlisted return URL %s', (value) => {
    expect(safeAdminReturnUrl(value)).toBe('/projects');
  });

  it.each(['/projects', '/users', '/projects?page=2', '/users#target'])('accepts allowlisted internal URL %s', (value) => {
    expect(safeAdminReturnUrl(value)).toBe(value);
  });
});
