import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { MeResponse } from '../core/models';
import { Subject } from 'rxjs';
import { SessionService } from '../core/session.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let loginResult: Subject<MeResponse>;
  let login: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    loginResult = new Subject<MeResponse>();
    login = vi.fn(() => loginResult.asObservable());
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => '//evil.example' } } },
        },
        { provide: SessionService, useValue: { login } },
      ],
    }).compileComponents();
  });

  function submittedFixture() {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'Secret123456' });
    fixture.componentInstance.submit();
    return fixture;
  }

  it('renders semantic fields, a loading live region and the reset link', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('main form')).not.toBeNull();
    expect(root.querySelector('label[for=email]')).not.toBeNull();
    expect(root.querySelector('[aria-live=polite]')).not.toBeNull();
    expect(root.querySelector('a[href="/complete-password-reset"]')).not.toBeNull();
  });

  it('shows validation without calling the API', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Revisa el correo y la contraseña');
  });

  it('clears only the password after an authentication error', () => {
    const fixture = submittedFixture();
    loginResult.error({ status: 401 });
    expect(fixture.componentInstance.form.controls.password.value).toBe('');
    expect(fixture.componentInstance.form.controls.email.value).toBe('admin@example.com');
  });

  it('clears the password after success and rejects an unsafe returnUrl', () => {
    const fixture = submittedFixture();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl');
    loginResult.next({ userId: '1', email: 'admin@example.com', generalAdmin: true });
    loginResult.complete();
    expect(fixture.componentInstance.form.controls.password.value).toBe('');
    expect(navigate).toHaveBeenCalledWith('/projects');
  });

  it('normalizes email and cancels a pending login while clearing password on destroy', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: '  ADMIN@Example.COM ', password: 'Secret123456' });
    fixture.componentInstance.submit();
    expect(login).toHaveBeenCalledWith('admin@example.com', 'Secret123456');
    expect(loginResult.observed).toBe(true);
    fixture.destroy();
    expect(loginResult.observed).toBe(false);
    expect(fixture.componentInstance.form.controls.password.value).toBe('');
  });

  it('accepts a Java-compatible 320-character email', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: `${'a'.repeat(318)}@b`, password: 'Secret123456' });
    fixture.componentInstance.submit();
    expect(login).toHaveBeenCalledOnce();
  });
});
