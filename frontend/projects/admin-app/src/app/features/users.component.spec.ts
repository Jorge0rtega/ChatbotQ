import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { AdminUser, PageResponse, Project, ProjectIdsResponse } from '../core/models';
import { UsersComponent } from './users.component';

const projectAdmin: AdminUser = {
  id: 'u1',
  email: 'project@example.com',
  role: 'PROJECT_ADMIN',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};
const generalAdmin: AdminUser = {
  ...projectAdmin,
  id: 'u2',
  email: 'general@example.com',
  role: 'GENERAL_ADMIN',
};
const page: PageResponse<AdminUser> = {
  items: [projectAdmin, generalAdmin],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
};
const projects: readonly Project[] = [
  project('p2', 'Disabled project', 'DISABLED'),
  project('p1', 'Active project', 'ACTIVE'),
];

describe('UsersComponent', () => {
  let creation: Subject<AdminUser>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    creation = new Subject<AdminUser>();
    api = {
      listUsers: vi.fn(() => of(page)),
      createUser: vi.fn(() => creation.asObservable()),
      updateUserEmail: vi.fn(() => of(projectAdmin)),
      setUserActive: vi.fn(() => of(undefined)),
      resetUserPassword: vi.fn(() => of(undefined)),
      getUserProjectIds: vi.fn(() => of({ projectIds: [] } satisfies ProjectIdsResponse)),
      listAllProjects: vi.fn(() => of(projects)),
      replaceUserProjectIds: vi.fn(() => of({ projectIds: [] } satisfies ProjectIdsResponse)),
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

  it('loads assignments and all ACTIVE/DISABLED projects in parallel for a project admin', () => {
    const ids = new Subject<ProjectIdsResponse>();
    const all = new Subject<readonly Project[]>();
    api['getUserProjectIds'].mockReturnValue(ids);
    api['listAllProjects'].mockReturnValue(all);
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.includes('Asignar proyectos'))!.click();
    fixture.detectChanges();

    expect(api['getUserProjectIds']).toHaveBeenCalledExactlyOnceWith('u1');
    expect(api['listAllProjects']).toHaveBeenCalledOnce();
    expect(ids.observed).toBe(true);
    expect(all.observed).toBe(true);
    expect(fixture.nativeElement.querySelector('fieldset')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Cargando asignaciones');

    ids.next({ projectIds: ['p2'] });
    ids.complete();
    all.next(projects);
    all.complete();
    fixture.detectChanges();

    const checkboxes = [
      ...fixture.nativeElement.querySelectorAll('input[type=checkbox]'),
    ] as HTMLInputElement[];
    expect(checkboxes).toHaveLength(2);
    expect(checkboxes[0].checked).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Deshabilitado');
    expect(fixture.nativeElement.textContent).toContain('Activo');
  });

  it('toggles immutably and saves sorted project IDs before closing with feedback', () => {
    api['getUserProjectIds'].mockReturnValue(of({ projectIds: ['p2'] }));
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.openAssignments(projectAdmin);
    const original = fixture.componentInstance.assignmentSelection();

    fixture.componentInstance.toggleAssignment('p1');
    expect(fixture.componentInstance.assignmentSelection()).not.toBe(original);
    fixture.componentInstance.saveAssignments();

    expect(api['replaceUserProjectIds']).toHaveBeenCalledExactlyOnceWith('u1', ['p1', 'p2']);
    expect(fixture.componentInstance.assignmentUser()).toBeNull();
    expect(fixture.componentInstance.feedback()).toContain('Asignaciones actualizadas');
  });

  it('allows saving an empty list to revoke every assignment when no projects exist', () => {
    api['listAllProjects'].mockReturnValue(of([]));
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.openAssignments(projectAdmin);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No hay proyectos disponibles');
    fixture.componentInstance.saveAssignments();
    expect(api['replaceUserProjectIds']).toHaveBeenCalledExactlyOnceWith('u1', []);
  });

  it('cancels pending assignment requests without PUT and clears panel state', () => {
    const ids = new Subject<ProjectIdsResponse>();
    const all = new Subject<readonly Project[]>();
    api['getUserProjectIds'].mockReturnValue(ids);
    api['listAllProjects'].mockReturnValue(all);
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.openAssignments(projectAdmin);

    fixture.componentInstance.cancelAssignments();

    expect(ids.observed).toBe(false);
    expect(all.observed).toBe(false);
    expect(api['replaceUserProjectIds']).not.toHaveBeenCalled();
    expect(fixture.componentInstance.assignmentUser()).toBeNull();
    expect(fixture.componentInstance.assignmentProjects()).toEqual([]);
  });

  it('moves focus into the assignment editor and restores it on cancel', async () => {
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    const trigger = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.includes('Asignar proyectos'),
    ) as HTMLButtonElement;
    trigger.focus();

    trigger.click();
    fixture.detectChanges();
    await Promise.resolve();
    const heading = fixture.nativeElement.querySelector('#assignment-heading') as HTMLElement;
    expect(document.activeElement).toBe(heading);

    const cancel = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Cancelar',
    ) as HTMLButtonElement;
    cancel.click();
    fixture.detectChanges();
    await Promise.resolve();
    expect(document.activeElement).toBe(trigger);
  });

  it('cancels previous assignment requests when switching users and on destroy', () => {
    const firstIds = new Subject<ProjectIdsResponse>();
    const firstAll = new Subject<readonly Project[]>();
    const secondIds = new Subject<ProjectIdsResponse>();
    const secondAll = new Subject<readonly Project[]>();
    api['getUserProjectIds'].mockReturnValueOnce(firstIds).mockReturnValueOnce(secondIds);
    api['listAllProjects'].mockReturnValueOnce(firstAll).mockReturnValueOnce(secondAll);
    const fixture = TestBed.createComponent(UsersComponent);

    fixture.componentInstance.openAssignments(projectAdmin);
    fixture.componentInstance.openAssignments({ ...projectAdmin, id: 'u3' });
    expect(firstIds.observed).toBe(false);
    expect(firstAll.observed).toBe(false);
    expect(secondIds.observed).toBe(true);
    expect(secondAll.observed).toBe(true);

    fixture.destroy();
    expect(secondIds.observed).toBe(false);
    expect(secondAll.observed).toBe(false);
  });

  it('does not offer project assignments for a general-admin target', () => {
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    const generalRow = [...fixture.nativeElement.querySelectorAll('tr')].find(
      (row: HTMLTableRowElement) => row.textContent?.includes('general@example.com'),
    ) as HTMLTableRowElement;
    expect(generalRow.textContent).not.toContain('Asignar proyectos');
  });

  it('keeps the panel and selection open when loading or saving fails', () => {
    api['getUserProjectIds'].mockReturnValue(of({ projectIds: ['p2'] }));
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.componentInstance.openAssignments(projectAdmin);
    expect(fixture.componentInstance.assignmentSelection()).toEqual(new Set(['p2']));

    api['replaceUserProjectIds'].mockReturnValue(throwError(() => ({ status: 500 })));
    fixture.componentInstance.saveAssignments();

    expect(fixture.componentInstance.assignmentUser()?.id).toBe('u1');
    expect(fixture.componentInstance.assignmentSelection()).toEqual(new Set(['p2']));
    expect(fixture.componentInstance.assignmentError()).not.toBe('');
  });

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
    creation.next(projectAdmin);
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
    expect(api['createUser']).toHaveBeenCalledWith(
      'new@example.com',
      'Temporary123',
      'PROJECT_ADMIN',
    );
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
    fixture.componentInstance.changeEmail(projectAdmin);
    expect(api['updateUserEmail']).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toBe('El correo electrónico no es válido.');
    prompt.mockRestore();
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
