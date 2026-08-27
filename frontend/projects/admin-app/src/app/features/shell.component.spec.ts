import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionService } from '../core/session.service';
import { ShellComponent } from './shell.component';

describe('ShellComponent role navigation', () => {
  let fixture: ComponentFixture<ShellComponent>;
  const me = signal({
    userId: '1',
    email: 'project@example.com',
    generalAdmin: false,
    projectIds: [] as readonly string[],
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        {
          provide: SessionService,
          useValue: {
            me,
            authenticated: () => true,
            loadMe: () => of(me()),
            logout: () => of(undefined),
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
  });

  it('hides global user navigation for PROJECT_ADMIN', () => {
    expect(fixture.nativeElement.textContent).not.toContain('Usuarios');
  });

  it('shows global user navigation for GENERAL_ADMIN', () => {
    me.set({ ...me(), generalAdmin: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Usuarios');
  });
});

describe('ShellComponent expired-session handling', () => {
  it('does not duplicate logout or login navigation after the interceptor invalidated the session', async () => {
    const logout = vi.fn(() => of(undefined));
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        {
          provide: SessionService,
          useValue: {
            me: signal(null),
            authenticated: () => false,
            loadMe: () => throwError(() => new Error('expired')),
            logout,
          },
        },
      ],
    }).compileComponents();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl');
    TestBed.createComponent(ShellComponent).detectChanges();
    expect(logout).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});
