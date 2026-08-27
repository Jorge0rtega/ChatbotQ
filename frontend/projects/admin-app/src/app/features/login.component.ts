import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { javaEmailValidator, normalizeJavaEmail } from '../core/admin-validators';
import { httpErrorMessage } from '../core/http-error';
import { safeAdminReturnUrl } from '../core/safe-return-url';
import { SessionService } from '../core/session.service';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <section class="auth-card" aria-labelledby="login-title">
        <p class="eyebrow">ChatbotQ · Administración</p>
        <h1 id="login-title">Inicia sesión</h1>
        <p class="muted">Accede al espacio de gestión.</p>
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <label for="email">Correo electrónico</label>
          <input
            id="email"
            type="email"
            autocomplete="username"
            formControlName="email"
            [attr.aria-invalid]="form.controls.email.invalid && form.controls.email.touched"
          />
          <label for="password">Contraseña</label>
          <input
            id="password"
            type="password"
            autocomplete="current-password"
            formControlName="password"
            [attr.aria-invalid]="form.controls.password.invalid && form.controls.password.touched"
          />
          @if (error()) {
            <p class="alert" role="alert" aria-live="assertive">{{ error() }}</p>
          }
          <button class="primary" type="submit" [disabled]="loading()">
            {{ loading() ? 'Accediendo…' : 'Continuar' }}
          </button>
          <a routerLink="/complete-password-reset">Tengo una contraseña temporal</a>
          <span class="sr-only" aria-live="polite">
            {{ loading() ? 'Verificando credenciales' : '' }}
          </span>
        </form>
      </section>
    </main>
  `,
})
export class LoginComponent {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaEmailValidator],
    }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Revisa el correo y la contraseña.');
      return;
    }

    this.loading.set(true);
    this.error.set('');
    const { email, password } = this.form.getRawValue();
    this.session
      .login(normalizeJavaEmail(email), password)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading.set(false);
          this.form.controls.password.reset('');
        }),
      )
      .subscribe({
        next: () => {
          const requested = this.route.snapshot.queryParamMap.get('returnUrl');
          void this.router.navigateByUrl(safeAdminReturnUrl(requested));
        },
        error: (error: unknown) => this.error.set(httpErrorMessage(error)),
      });
  }
}
