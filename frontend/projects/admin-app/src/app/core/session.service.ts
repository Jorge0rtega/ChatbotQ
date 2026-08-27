import { HttpClient, HttpContext, HttpContextToken } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  catchError,
  finalize,
  Observable,
  of,
  shareReplay,
  switchMap,
  tap,
  throwError,
} from 'rxjs';
import { MeResponse, TokenResponse } from './models';
import { safeAdminReturnUrl } from './safe-return-url';

export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);
const ACCESS_KEY = 'chatbotq.admin.accessToken';
const REFRESH_KEY = 'chatbotq.admin.refreshToken';

interface RefreshFlight {
  epoch: number;
  observable: Observable<TokenResponse>;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly meState = signal<MeResponse | null>(null);
  private accessValue = localStorage.getItem(ACCESS_KEY);
  private refreshValue = localStorage.getItem(REFRESH_KEY);
  private readonly authenticatedState = signal(Boolean(this.accessValue));
  private epoch = 0;
  private refreshInFlight: RefreshFlight | null = null;
  private redirectingToLogin = false;

  readonly me = this.meState.asReadonly();
  readonly authenticated = computed(() => this.authenticatedState());

  get accessToken(): string | null {
    return this.accessValue;
  }

  get refreshToken(): string | null {
    return this.refreshValue;
  }

  get generation(): number {
    return this.epoch;
  }

  login(email: string, password: string): Observable<MeResponse> {
    const loginEpoch = this.beginSessionTransition();
    return this.http
      .post<TokenResponse>(
        '/api/admin/auth/login',
        { email, password },
        { context: new HttpContext().set(SKIP_AUTH, true) },
      )
      .pipe(
        tap((tokens) => this.storeTokensForEpoch(tokens, loginEpoch)),
        switchMap(() => this.loadMe()),
        catchError((error: unknown) => {
          if (this.epoch === loginEpoch) this.clear();
          return throwError(() => error);
        }),
      );
  }

  loadMe(): Observable<MeResponse> {
    const requestEpoch = this.epoch;
    return this.http.get<MeResponse>('/api/admin/auth/me').pipe(
      tap((me) => {
        if (this.epoch === requestEpoch) this.meState.set(me);
      }),
    );
  }

  restore(): Observable<MeResponse | null> {
    return this.accessToken ? this.loadMe().pipe(catchError(() => of(null))) : of(null);
  }

  completePasswordReset(
    email: string,
    temporaryPassword: string,
    newPassword: string,
  ): Observable<void> {
    return this.http.post<void>(
      '/api/admin/auth/complete-password-reset',
      { email, temporaryPassword, newPassword },
      { context: new HttpContext().set(SKIP_AUTH, true) },
    );
  }

  /** Single-flight refresh scoped to the current session generation. */
  refresh(): Observable<TokenResponse> {
    const refreshToken = this.refreshToken;
    if (!refreshToken) return throwError(() => new Error('No refresh token'));

    const refreshEpoch = this.epoch;
    if (this.refreshInFlight?.epoch === refreshEpoch) return this.refreshInFlight.observable;

    let observable!: Observable<TokenResponse>;
    observable = this.http
      .post<TokenResponse>(
        '/api/admin/auth/refresh',
        { refreshToken },
        { context: new HttpContext().set(SKIP_AUTH, true) },
      )
      .pipe(
        tap((tokens) => this.storeTokensForEpoch(tokens, refreshEpoch, refreshToken)),
        finalize(() => {
          if (this.refreshInFlight?.observable === observable) this.refreshInFlight = null;
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    this.refreshInFlight = { epoch: refreshEpoch, observable };
    return observable;
  }

  logout(): Observable<void> {
    const refreshToken = this.refreshToken;
    this.clear();
    if (!refreshToken) return of(undefined);

    return this.http
      .post<void>(
        '/api/admin/auth/logout',
        { refreshToken },
        { context: new HttpContext().set(SKIP_AUTH, true) },
      )
      .pipe(catchError(() => of(undefined)));
  }

  storeTokens(tokens: TokenResponse): void {
    this.epoch += 1;
    this.redirectingToLogin = false;
    this.persistTokens(tokens);
  }

  clear(): void {
    this.epoch += 1;
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
    this.accessValue = null;
    this.refreshValue = null;
    this.authenticatedState.set(false);
    this.meState.set(null);
  }

  invalidateExpiredSession(expectedGeneration = this.epoch): void {
    if (expectedGeneration !== this.epoch || this.redirectingToLogin) return;
    const returnUrl = safeAdminReturnUrl(this.router.url);
    this.clear();
    this.redirectingToLogin = true;
    const login = this.router.createUrlTree(['/login'], { queryParams: { returnUrl } });
    void this.router.navigateByUrl(login);
  }

  private beginSessionTransition(): number {
    this.clear();
    this.redirectingToLogin = false;
    return this.epoch;
  }

  private storeTokensForEpoch(
    tokens: TokenResponse,
    expectedEpoch: number,
    expectedRefreshToken?: string,
  ): void {
    if (
      this.epoch !== expectedEpoch ||
      (expectedRefreshToken !== undefined && this.refreshToken !== expectedRefreshToken)
    ) {
      throw new Error('Session changed while authentication request was in flight');
    }
    this.persistTokens(tokens);
  }

  private persistTokens(tokens: TokenResponse): void {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
    this.accessValue = tokens.accessToken;
    this.refreshValue = tokens.refreshToken;
    this.authenticatedState.set(true);
  }
}
