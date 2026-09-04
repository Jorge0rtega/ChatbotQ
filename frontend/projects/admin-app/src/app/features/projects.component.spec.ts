import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
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
      getProjectSiteKey: vi.fn(() => of({ siteKey: 'public-key', version: 1, rotatedAt: '2026-01-01T00:00:00Z' })),
      rotateProjectSiteKey: vi.fn(),
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
    expect(text).toContain('Ver siteKey');
    expect(text).not.toContain('Desactivar');
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

  it('reads an assigned project siteKey without exposing rotation to PROJECT_ADMIN', async () => {
    const { api, fixture } = await configure(false, ['p1']);
    fixture.detectChanges();

    const read = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === 'Ver siteKey',
    )!;
    read.click();
    fixture.detectChanges();

    expect(api.getProjectSiteKey).toHaveBeenCalledWith('p1');
    expect(fixture.nativeElement.textContent).toContain('public-key');
    expect(fixture.nativeElement.textContent).not.toContain('Rotar siteKey');
  });

  it('discards a late siteKey response after closing the panel', async () => {
    const pending = new Subject<{ siteKey: string; version: number; rotatedAt: string }>();
    const { fixture } = await configure(false, ['p1'], { getProjectSiteKey: vi.fn(() => pending) });
    fixture.detectChanges();

    const read = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === 'Ver siteKey',
    )!;
    read.click();
    fixture.detectChanges();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Cerrar')!
      .click();
    pending.next({ siteKey: 'late-public-key', version: 1, rotatedAt: '2026-01-01T00:00:00Z' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('late-public-key');
    expect(fixture.nativeElement.textContent).not.toContain('siteKey de');
  });

  it('announces a successful siteKey rotation to assistive technology', async () => {
    const listProjects = vi.fn(() =>
      of({ ...emptyPage, items: [assignedProjects['p1']], totalElements: 1, totalPages: 1 }),
    );
    const rotateProjectSiteKey = vi.fn(() =>
      of({ siteKey: 'rotated-public-key', version: 2, rotatedAt: '2026-02-01T00:00:00Z' }),
    );
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture } = await configure(true, [], { listProjects, rotateProjectSiteKey });
    fixture.detectChanges();

    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver siteKey')!
      .click();
    fixture.detectChanges();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Rotar siteKey')!
      .click();
    fixture.detectChanges();

    const announcements = Array.from(fixture.nativeElement.querySelectorAll('[aria-live]')) as HTMLElement[];
    expect(announcements.some((element) => element.textContent?.includes('siteKey rotada correctamente'))).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('rotated-public-key');
  });

  it('clears the displayed siteKey after a rotation version conflict', async () => {
    const rotateProjectSiteKey = vi.fn(() => throwError(() => ({ status: 409 })));
    const listProjects = vi.fn(() =>
      of({ ...emptyPage, items: [assignedProjects['p1']], totalElements: 1, totalPages: 1 }),
    );
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    confirm.mockClear();
    const { api, fixture } = await configure(true, [], { listProjects, rotateProjectSiteKey });
    fixture.detectChanges();

    const read = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === 'Ver siteKey',
    )!;
    read.click();
    fixture.detectChanges();
    const rotate = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === 'Rotar siteKey',
    )!;
    rotate.click();
    fixture.detectChanges();

    expect(confirm).toHaveBeenCalledExactlyOnceWith(
      '¿Rotar la siteKey de “Proyecto uno”? La clave anterior dejará de funcionar inmediatamente.',
    );
    expect(api.rotateProjectSiteKey).toHaveBeenCalledWith('p1', 1);
    expect(fixture.nativeElement.textContent).not.toContain('public-key');
    expect(fixture.nativeElement.textContent).toContain('La siteKey cambió en otra sesión');
  });

  it('cancels a pending rotation and discards its late response when the panel closes', async () => {
    const pendingRotation = new Subject<{ siteKey: string; version: number; rotatedAt: string }>();
    const listProjects = vi.fn(() =>
      of({ ...emptyPage, items: [assignedProjects['p1']], totalElements: 1, totalPages: 1 }),
    );
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture } = await configure(true, [], {
      listProjects,
      rotateProjectSiteKey: vi.fn(() => pendingRotation),
    });
    fixture.detectChanges();

    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver siteKey')!
      .click();
    fixture.detectChanges();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Rotar siteKey')!
      .click();
    fixture.detectChanges();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Cerrar')!
      .click();
    pendingRotation.next({ siteKey: 'late-rotated-key', version: 2, rotatedAt: '2026-02-01T00:00:00Z' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('late-rotated-key');
    expect(fixture.nativeElement.textContent).not.toContain('siteKey de');
  });

  it('restores focus to the siteKey trigger after closing the panel', async () => {
    const { fixture } = await configure(false, ['p1']);
    fixture.detectChanges();
    const read = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.trim() === 'Ver siteKey',
    )!;
    read.click();
    fixture.detectChanges();
    await Promise.resolve();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Cerrar')!
      .click();
    fixture.detectChanges();
    await Promise.resolve();

    expect(document.activeElement).toBe(read);
  });

  it('keeps the panel clear when siteKey reading is forbidden', async () => {
    const { fixture } = await configure(false, ['p1'], {
      getProjectSiteKey: vi.fn(() => throwError(() => ({ status: 403 }))),
    });
    fixture.detectChanges();

    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver siteKey')!
      .click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('public-key');
    expect(fixture.nativeElement.textContent).toContain('Ocurrió un error inesperado');
  });

  it('clears the displayed siteKey after an unauthenticated rotation', async () => {
    const listProjects = vi.fn(() =>
      of({ ...emptyPage, items: [assignedProjects['p1']], totalElements: 1, totalPages: 1 }),
    );
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture } = await configure(true, [], {
      listProjects,
      rotateProjectSiteKey: vi.fn(() => throwError(() => ({ status: 401 }))),
    });
    fixture.detectChanges();

    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver siteKey')!
      .click();
    fixture.detectChanges();
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Rotar siteKey')!
      .click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('public-key');
    expect(fixture.nativeElement.textContent).toContain('Ocurrió un error inesperado');
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
