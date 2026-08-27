import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { SessionService } from '../core/session.service';
import { CompletePasswordResetComponent } from './complete-password-reset.component';

describe('CompletePasswordResetComponent', () => {
  let completion: Subject<void>;
  let completePasswordReset: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    completion = new Subject<void>();
    completePasswordReset = vi.fn(() => completion.asObservable());
    await TestBed.configureTestingModule({
      imports: [CompletePasswordResetComponent],
      providers: [provideRouter([]), { provide: SessionService, useValue: { completePasswordReset } }],
    }).compileComponents();
  });

  function validFixture() {
    const fixture = TestBed.createComponent(CompletePasswordResetComponent);
    fixture.componentInstance.form.setValue({
      email: 'user@example.com',
      temporaryPassword: 'Temporary123',
      newPassword: 'Permanent456',
      confirmNewPassword: 'Permanent456',
    });
    return fixture;
  }

  it('submits the exact three-field contract and clears every field on 204 success', () => {
    const fixture = validFixture();
    fixture.componentInstance.submit();
    expect(completePasswordReset).toHaveBeenCalledWith(
      'user@example.com',
      'Temporary123',
      'Permanent456',
    );
    completion.next();
    completion.complete();
    expect(fixture.componentInstance.form.getRawValue()).toEqual({
      email: '',
      temporaryPassword: '',
      newPassword: '',
      confirmNewPassword: '',
    });
  });

  it('uses a generic unauthorized response and clears every field on error', () => {
    const fixture = validFixture();
    fixture.componentInstance.submit();
    completion.error({ status: 401 });
    expect(fixture.componentInstance.error()).toBe('No se pudo completar el cambio de contraseña.');
    expect(fixture.componentInstance.form.getRawValue()).toEqual({
      email: '',
      temporaryPassword: '',
      newPassword: '',
      confirmNewPassword: '',
    });
  });

  it('rejects reusing the temporary password as the new password', () => {
    const fixture = validFixture();
    fixture.componentInstance.form.patchValue({
      temporaryPassword: 'Temporary123',
      newPassword: 'Temporary123',
      confirmNewPassword: 'Temporary123',
    });
    fixture.componentInstance.submit();
    expect(completePasswordReset).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toContain('debe ser distinta');
  });

  it('rejects mismatched confirmation and renders accessible labelled controls', () => {
    const fixture = validFixture();
    fixture.componentInstance.form.controls.confirmNewPassword.setValue('Different7890');
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(completePasswordReset).not.toHaveBeenCalled();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('main form')).not.toBeNull();
    expect(root.querySelector('label[for=reset-temporary-password]')).not.toBeNull();
    expect(root.textContent).toContain('Las contraseñas nuevas deben coincidir');
  });

  it('normalizes email and cancels pending reset while clearing every field on destroy', () => {
    const fixture = validFixture();
    fixture.componentInstance.form.controls.email.setValue(' USER@Example.COM ');
    fixture.componentInstance.submit();
    expect(completePasswordReset).toHaveBeenCalledWith(
      'user@example.com',
      'Temporary123',
      'Permanent456',
    );
    expect(completion.observed).toBe(true);
    fixture.destroy();
    expect(completion.observed).toBe(false);
    expect(fixture.componentInstance.form.getRawValue()).toEqual({
      email: '',
      temporaryPassword: '',
      newPassword: '',
      confirmNewPassword: '',
    });
  });
});
