import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { AdminUser, PageResponse } from '../core/models';
import { UsersComponent } from './users.component';

const page: PageResponse<AdminUser> = {
  items: [
    {
      id: 'u1',
      email: 'reset@example.com',
      role: 'PROJECT_ADMIN',
      status: 'PASSWORD_RESET_REQUIRED',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('UsersComponent', () => {
  let creation: Subject<AdminUser>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    creation = new Subject<AdminUser>();
    api = {
      listUsers: vi.fn(() => of(page)),
      createUser: vi.fn(() => creation.asObservable()),
      updateUserEmail: vi.fn(() => of(page.items[0])),
      setUserActive: vi.fn(() => of(undefined)),
      resetUserPassword: vi.fn(() => of(undefined)),
    };
    await TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [{ provide: AdminApiService, useValue: api }],
    }).compileComponents();
  });

  function submittedFixture() {
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.form.setValue({
      email: 'new@example.com',
      temporaryPassword: 'Temporary123',
      role: 'PROJECT_ADMIN',
    });
    fixture.componentInstance.create();
    return fixture;
  }

  it('enforces the exact temporary-password contract', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const password = fixture.componentInstance.form.controls.temporaryPassword;
    password.setValue('onlylettersxx');
    expect(password.invalid).toBe(true);
    password.setValue('ValidPass123');
    expect(password.valid).toBe(true);
    password.setValue(`${'a'.repeat(128)}1`);
    expect(password.invalid).toBe(true);
  });

  it('clears temporaryPassword after a create error without clearing email', () => {
    const fixture = submittedFixture();
    creation.error({ status: 500 });
    expect(fixture.componentInstance.form.controls.temporaryPassword.value).toBe('');
    expect(fixture.componentInstance.form.controls.email.value).toBe('new@example.com');
  });

  it('clears temporaryPassword after success and cancellation', () => {
    const fixture = submittedFixture();
    creation.next(page.items[0]);
    creation.complete();
    expect(fixture.componentInstance.form.controls.temporaryPassword.value).toBe('');

    fixture.componentInstance.form.controls.temporaryPassword.setValue('AnotherPass123');
    fixture.componentInstance.closeCreate();
    expect(fixture.componentInstance.form.controls.temporaryPassword.value).toBe('');
  });

  it('normalizes email and cancels pending creation on explicit cancel', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.form.setValue({
      email: ' NEW@Example.COM ',
      temporaryPassword: 'Temporary123',
      role: 'PROJECT_ADMIN',
    });
    fixture.componentInstance.create();
    expect(api['createUser']).toHaveBeenCalledWith('new@example.com', 'Temporary123', 'PROJECT_ADMIN');
    expect(creation.observed).toBe(true);
    fixture.componentInstance.closeCreate();
    expect(creation.observed).toBe(false);
    expect(fixture.componentInstance.form.controls.temporaryPassword.value).toBe('');
  });

  it('cancels pending creation and clears its password when destroyed', () => {
    const fixture = submittedFixture();
    expect(creation.observed).toBe(true);
    fixture.destroy();
    expect(creation.observed).toBe(false);
    expect(fixture.componentInstance.form.controls.temporaryPassword.value).toBe('');
  });

  it('does not submit an email that Java will reject', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('a@@b');

    fixture.componentInstance.changeEmail(page.items[0]);

    expect(api['updateUserEmail']).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toBe('El correo electrónico no es válido.');
    prompt.mockRestore();
  });

  it('renders PASSWORD_RESET_REQUIRED as a differentiated badge', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    const badge = fixture.nativeElement.querySelector('.badge.reset-required') as HTMLElement;
    expect(badge.textContent).toContain('Cambio requerido');
  });
});
