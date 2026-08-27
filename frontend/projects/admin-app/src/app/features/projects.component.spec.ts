import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { PageResponse, Project } from '../core/models';
import { SessionService } from '../core/session.service';
import { ProjectsComponent } from './projects.component';

const assignedProjects: Record<string, Project> = {
  p2: project('p2', 'Proyecto dos', 'ACTIVE'),
  p1: project('p1', 'Proyecto uno', 'ACTIVE'),
};

const emptyPage: PageResponse<Project> = {
  items: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

describe('ProjectsComponent', () => {
  async function configure(
    generalAdmin: boolean,
    projectIds: readonly string[],
    overrides: Record<string, unknown> = {},
  ) {
    const api = {
      listProjects: vi.fn(() => of(emptyPage)),
      getProject: vi.fn((id: string) => of(assignedProjects[id])),
      createProject: vi.fn(() => of(assignedProjects['p1'])),
      renameProject: vi.fn(),
      setProjectActive: vi.fn(),
      ...overrides,
    };
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: AdminApiService, useValue: api },
        {
          provide: SessionService,
          useValue: {
            me: signal({ userId: 'u1', email: 'admin@example.com', generalAdmin, projectIds }),
          },
        },
      ],
    }).compileComponents();
    return { api, fixture: TestBed.createComponent(ProjectsComponent) };
  }

  it('loads exactly the assigned IDs and renders real projects in assignment order', async () => {
    const { api, fixture } = await configure(false, ['p2', 'p1']);
    fixture.detectChanges();

    expect(api.listProjects).not.toHaveBeenCalled();
    expect(api.getProject.mock.calls).toEqual([['p2'], ['p1']]);
    const text = fixture.nativeElement.textContent as string;
    expect(text.indexOf('Proyecto dos')).toBeLessThan(text.indexOf('Proyecto uno'));
    expect(text).toContain('Activo');
    expect(fixture.nativeElement.querySelector('.actions')).toBeNull();
  });

  it('shows an empty assigned view without global or detail API calls', async () => {
    const { api, fixture } = await configure(false, []);
    fixture.detectChanges();

    expect(api.listProjects).not.toHaveBeenCalled();
    expect(api.getProject).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('No tienes proyectos asignados');
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('shows one safe error if any assigned project is concurrently revoked', async () => {
    const getProject = vi.fn((id: string) =>
      id === 'p2' ? throwError(() => ({ status: 403 })) : of(assignedProjects[id]),
    );
    const { fixture } = await configure(false, ['p1', 'p2'], { getProject });
    fixture.detectChanges();

    expect(getProject).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain(
      'No se pudieron cargar tus proyectos asignados',
    );
    expect(fixture.nativeElement.textContent).not.toContain('Proyecto uno');
  });

  it('uses the global paginated list only for GENERAL_ADMIN', async () => {
    const { api } = await configure(true, []);
    expect(api.listProjects).toHaveBeenCalledExactlyOnceWith(0);
    expect(api.getProject).not.toHaveBeenCalled();
  });

  it('allows 160 Unicode code points and rejects 161 for GENERAL_ADMIN', async () => {
    const { fixture } = await configure(true, []);
    const name = fixture.componentInstance.createForm.controls.name;
    name.setValue('😀'.repeat(160));
    expect(name.valid).toBe(true);
    name.setValue(`${'😀'.repeat(160)}a`);
    expect(name.invalid).toBe(true);
  });

  it('normalizes whitespace in the create payload and rejects controls or blank names', async () => {
    const createProject = vi.fn(() => of(assignedProjects['p1']));
    const { fixture } = await configure(true, [], { createProject });
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

function project(id: string, name: string, status: Project['status']): Project {
  return {
    id,
    name,
    status,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}
