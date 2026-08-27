import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { javaEmailValidator, javaPasswordValidator, normalizeJavaEmail } from '../core/admin-validators';
import { SessionService } from '../core/session.service';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <section class="auth-card" aria-labelledby="reset-title">
        <p class="eyebrow">ChatbotQ · Administración</p>
        <h1 id="reset-title">Completa el cambio de contraseña</h1>
        <p class="muted">Usa la contraseña temporal que te proporcionó un administrador.</p>
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <label for="reset-email">Correo electrónico</label>
          <input id="reset-email" type="email" autocomplete="username" formControlName="email" />

          <label for="reset-temporary-password">Contraseña temporal</label>
          <input
            id="reset-temporary-password"
            type="password"
            autocomplete="current-password"
            formControlName="temporaryPassword"
          />

          <label for="reset-new-password">Nueva contraseña</label>
          <input
            id="reset-new-password"
            type="password"
            autocomplete="new-password"
            formControlName="newPassword"
          />
          <p class="hint">12–128 caracteres, con al menos una letra y un dígito.</p>

          <label for="reset-confirm-password">Confirma la nueva contraseña</label>
          <input
            id="reset-confirm-password"
            type="password"
            autocomplete="new-password"
            formControlName="confirmNewPassword"
          />

          @if (error()) {
            <p class="alert" role="alert" aria-live="assertive">{{ error() }}</p>
          }
          @if (success()) {
            <p class="state" role="status" aria-live="polite">{{ success() }}</p>
          }
          <button class="primary" type="submit" [disabled]="loading()">
            {{ loading() ? 'Actualizando…' : 'Cambiar contraseña' }}
          </button>
          <a routerLink="/login">Volver al inicio de sesión</a>
        </form>
      </section>
    </main>
  `,
})
export class CompletePasswordResetComponent {
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly success = signal('');
  readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaEmailValidator],
    }),
    temporaryPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    newPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaPasswordValidator],
    }),
    confirmNewPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });

  submit(): void {
    const values = this.form.getRawValue();
    const passwordsMismatch = values.newPassword !== values.confirmNewPassword;
    const passwordReused = values.newPassword === values.temporaryPassword;
    if (this.form.invalid || passwordsMismatch || passwordReused) {
      this.form.markAllAsTouched();
      this.error.set(
        passwordsMismatch
          ? 'Las contraseñas nuevas deben coincidir.'
          : passwordReused
            ? 'La nueva contraseña debe ser distinta de la temporal.'
            : 'Revisa los datos y los requisitos de contraseña.',
      );
      return;
    }

    this.loading.set(true);
    this.error.set('');
    this.success.set('');
    this.session
      .completePasswordReset(normalizeJavaEmail(values.email), values.temporaryPassword, values.newPassword)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading.set(false);
          this.clearFields();
        }),
      )
      .subscribe({
        next: () => this.success.set('Contraseña actualizada. Ya puedes iniciar sesión.'),
        error: () => this.error.set('No se pudo completar el cambio de contraseña.'),
      });
  }

  private clearFields(): void {
    this.form.reset({
      email: '',
      temporaryPassword: '',
      newPassword: '',
      confirmNewPassword: '',
    });
  }
}
