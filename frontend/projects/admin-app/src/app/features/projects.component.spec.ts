import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { SessionService } from '../core/session.service';
import { ProjectsComponent } from './projects.component';

describe('ProjectsComponent', () => {
  it('does not call the forbidden global list for PROJECT_ADMIN and explains the backend dependency', async () => {
    const listProjects = vi.fn(() => of({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: AdminApiService, useValue: { listProjects } },
        {
          provide: SessionService,
          useValue: { me: signal({ userId: 'u1', email: 'project@example.com', generalAdmin: false }) },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectsComponent);
    fixture.detectChanges();
    expect(listProjects).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'Tus proyectos asignados se cargarán cuando el perfil /me incluya sus asignaciones',
    );
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('allows 160 Unicode code points and rejects 161 for GENERAL_ADMIN', async () => {
    const listProjects = vi.fn(() => of({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        {
          provide: AdminApiService,
          useValue: {
            listProjects,
            createProject: vi.fn(),
            renameProject: vi.fn(),
            setProjectActive: vi.fn(),
          },
        },
        {
          provide: SessionService,
          useValue: { me: signal({ userId: 'u1', email: 'general@example.com', generalAdmin: true }) },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectsComponent);
    const name = fixture.componentInstance.createForm.controls.name;
    name.setValue('😀'.repeat(160));
    expect(name.valid).toBe(true);
    name.setValue(`${'😀'.repeat(160)}a`);
    expect(name.invalid).toBe(true);
    expect(listProjects).toHaveBeenCalledTimes(1);
  });

  it('normalizes whitespace in the create payload and rejects controls or blank names', async () => {
    const createProject = vi.fn(() => of({}));
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        {
          provide: AdminApiService,
          useValue: {
            listProjects: vi.fn(() => of({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
            createProject,
          },
        },
        {
          provide: SessionService,
          useValue: { me: signal({ userId: 'u1', email: 'general@example.com', generalAdmin: true }) },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectsComponent);
    const control = fixture.componentInstance.createForm.controls.name;
    control.setValue('\u00a0  Proyecto 😀 \u2003');
    fixture.componentInstance.create();
    expect(createProject).toHaveBeenCalledWith('Proyecto 😀');
    control.setValue('bad\nname');
    expect(control.invalid).toBe(true);
    control.setValue('\u00a0\u2003');
    expect(control.invalid).toBe(true);
  });
});
