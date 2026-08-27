import { Component, DestroyRef, ElementRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin, Subject, takeUntil } from 'rxjs';
import {
  javaEmailValidator,
  javaPasswordValidator,
  normalizeJavaEmail,
} from '../core/admin-validators';
import { AdminApiService } from '../core/admin-api.service';
import { httpErrorMessage } from '../core/http-error';
import { AdminRole, AdminUser, AdminUserStatus, PageResponse, Project } from '../core/models';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Administración global</p>
          <h1>Usuarios</h1>
          <p class="muted">Gestiona el acceso administrativo.</p>
        </div>
        <button class="primary" (click)="showCreate.set(!showCreate())">Nuevo usuario</button>
      </header>

      @if (showCreate()) {
        <form class="panel form-grid" [formGroup]="form" (ngSubmit)="create()" autocomplete="off">
          <h2>Crear usuario</h2>
          <label for="user-email">Correo</label>
          <input id="user-email" type="email" formControlName="email" autocomplete="off" />
          <label for="user-role">Rol</label>
          <select id="user-role" formControlName="role">
            <option value="PROJECT_ADMIN">Administrador de proyecto</option>
            <option value="GENERAL_ADMIN">Administrador general</option>
          </select>
          <label for="temp-password">Contraseña temporal</label>
          <input
            id="temp-password"
            type="password"
            formControlName="temporaryPassword"
            autocomplete="new-password"
          />
          <p class="hint">12–128 caracteres, con letra y dígito. No se almacenará.</p>
          <div>
            <button class="primary" [disabled]="saving()">
              {{ saving() ? 'Creando…' : 'Crear usuario' }}
            </button>
            <button type="button" class="ghost" (click)="closeCreate()">Cancelar</button>
          </div>
        </form>
      }

      <p class="alert" aria-live="polite">{{ error() }}</p>
      @if (loading()) {
        <p class="state" aria-live="polite">Cargando usuarios…</p>
      } @else if (!data()?.items?.length) {
        <div class="empty">
          <h2>No hay usuarios</h2>
          <p>Crea una cuenta administrativa.</p>
        </div>
      } @else {
        <div class="table-wrap">
          <table>
            <caption class="sr-only">
              Listado global de usuarios
            </caption>
            <thead>
              <tr>
                <th>Usuario</th>
                <th>Rol</th>
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              @for (user of data()!.items; track user.id) {
                <tr>
                  <td>
                    <strong>{{ user.email }}</strong
                    ><small>{{ user.id }}</small>
                  </td>
                  <td>{{ roleLabel(user.role) }}</td>
                  <td>
                    <span
                      class="badge"
                      [class.inactive]="user.status === 'DISABLED'"
                      [class.reset-required]="user.status === 'PASSWORD_RESET_REQUIRED'"
                      >{{ statusLabel(user.status) }}</span
                    >
                  </td>
                  <td class="actions">
                    <button class="ghost" (click)="changeEmail(user)">Cambiar correo</button>
                    <button class="ghost" (click)="resetPassword(user)">Reset temporal</button>
                    @if (user.role === 'PROJECT_ADMIN') {
                      <button
                        #assignmentTrigger
                        class="ghost"
                        [disabled]="assignmentLoading() || assignmentSaving()"
                        (click)="openAssignments(user, assignmentTrigger)"
                      >
                        Asignar proyectos
                      </button>
                    }
                    @if (user.status !== 'PASSWORD_RESET_REQUIRED') {
                      <button class="ghost danger" (click)="toggle(user)">
                        {{ user.status === 'ACTIVE' ? 'Desactivar' : 'Activar' }}
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <nav class="pagination" aria-label="Paginación">
          <button class="ghost" [disabled]="data()!.page === 0" (click)="load(data()!.page - 1)">
            Anterior
          </button>
          <span>Página {{ data()!.page + 1 }} de {{ data()!.totalPages || 1 }}</span>
          <button
            class="ghost"
            [disabled]="data()!.page + 1 >= data()!.totalPages"
            (click)="load(data()!.page + 1)"
          >
            Siguiente
          </button>
        </nav>
      }

      @if (assignmentUser(); as user) {
        <section class="panel" aria-labelledby="assignment-heading">
          <h2 #assignmentHeading id="assignment-heading" tabindex="-1">
            Asignar proyectos a {{ user.email }}
          </h2>
          <p class="alert" aria-live="polite">{{ assignmentError() }}</p>
          <fieldset [disabled]="assignmentLoading() || assignmentSaving()">
            <legend>Proyectos disponibles</legend>
            @if (assignmentLoading()) {
              <p class="state" aria-live="polite">Cargando asignaciones…</p>
            } @else {
              @for (project of assignmentProjects(); track project.id) {
                <label>
                  <input
                    type="checkbox"
                    [checked]="assignmentSelection().has(project.id)"
                    (change)="toggleAssignment(project.id)"
                  />
                  <span>{{ project.name }}</span>
                  <span class="badge" [class.inactive]="project.status === 'DISABLED'">{{
                    projectStatusLabel(project.status)
                  }}</span>
                </label>
              } @empty {
                <p>
                  No hay proyectos disponibles. Puedes guardar una selección vacía para revocar
                  todas las asignaciones.
                </p>
              }
            }
          </fieldset>
          <div class="actions">
            <button
              class="primary"
              [disabled]="assignmentLoading() || assignmentSaving()"
              (click)="saveAssignments()"
            >
              {{ assignmentSaving() ? 'Guardando…' : 'Guardar asignaciones' }}
            </button>
            <button
              type="button"
              class="ghost"
              [disabled]="assignmentSaving()"
              (click)="cancelAssignments()"
            >
              Cancelar
            </button>
          </div>
        </section>
      }
      <p class="state" aria-live="polite">{{ feedback() }}</p>
    </section>
  `,
})
export class UsersComponent {
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly createCancelled = new Subject<void>();
  private readonly assignmentsCancelled = new Subject<void>();
  private readonly assignmentHeading = viewChild<ElementRef<HTMLElement>>('assignmentHeading');
  private assignmentTrigger: HTMLElement | null = null;
  readonly data = signal<PageResponse<AdminUser> | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly showCreate = signal(false);
  readonly assignmentUser = signal<AdminUser | null>(null);
  readonly assignmentProjects = signal<readonly Project[]>([]);
  readonly assignmentSelection = signal<ReadonlySet<string>>(new Set());
  readonly assignmentLoading = signal(false);
  readonly assignmentSaving = signal(false);
  readonly assignmentError = signal('');
  readonly feedback = signal('');
  readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaEmailValidator],
    }),
    temporaryPassword: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaPasswordValidator],
    }),
    role: new FormControl<AdminRole>('PROJECT_ADMIN', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });

  constructor() {
    this.load();
  }

  load(page = 0): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .listUsers(page)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (data) => this.data.set(data),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  create(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const { email, temporaryPassword, role } = this.form.getRawValue();
    this.api
      .createUser(normalizeJavaEmail(email), temporaryPassword, role)
      .pipe(
        takeUntil(this.createCancelled),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.saving.set(false);
          this.form.controls.temporaryPassword.reset('');
        }),
      )
      .subscribe({
        next: () => {
          this.closeCreate();
          this.load(0);
        },
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  closeCreate(): void {
    this.createCancelled.next();
    this.form.reset({ email: '', temporaryPassword: '', role: 'PROJECT_ADMIN' });
    this.showCreate.set(false);
  }

  openAssignments(user: AdminUser, trigger?: HTMLElement): void {
    if (user.role !== 'PROJECT_ADMIN') return;
    this.assignmentsCancelled.next();
    this.assignmentTrigger = trigger ?? null;
    this.assignmentUser.set(user);
    this.assignmentProjects.set([]);
    this.assignmentSelection.set(new Set());
    this.assignmentError.set('');
    this.feedback.set('');
    this.assignmentLoading.set(true);
    queueMicrotask(() => this.assignmentHeading()?.nativeElement.focus());
    forkJoin({ current: this.api.getUserProjectIds(user.id), projects: this.api.listAllProjects() })
      .pipe(
        takeUntil(this.assignmentsCancelled),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.assignmentLoading.set(false)),
      )
      .subscribe({
        next: ({ current, projects }) => {
          this.assignmentProjects.set(projects);
          this.assignmentSelection.set(new Set(current.projectIds));
        },
        error: (error) => this.assignmentError.set(httpErrorMessage(error)),
      });
  }

  toggleAssignment(projectId: string): void {
    const selection = new Set(this.assignmentSelection());
    if (selection.has(projectId)) selection.delete(projectId);
    else selection.add(projectId);
    this.assignmentSelection.set(selection);
  }

  saveAssignments(): void {
    const user = this.assignmentUser();
    if (!user || this.assignmentLoading() || this.assignmentSaving()) return;
    const projectIds = [...this.assignmentSelection()].sort((left, right) =>
      left.localeCompare(right),
    );
    this.assignmentSaving.set(true);
    this.assignmentError.set('');
    this.api
      .replaceUserProjectIds(user.id, projectIds)
      .pipe(
        takeUntil(this.assignmentsCancelled),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.assignmentSaving.set(false)),
      )
      .subscribe({
        next: () => {
          this.clearAssignmentState(true);
          this.feedback.set('Asignaciones actualizadas.');
        },
        error: (error) => this.assignmentError.set(httpErrorMessage(error)),
      });
  }

  cancelAssignments(): void {
    this.assignmentsCancelled.next();
    this.clearAssignmentState(true);
  }

  private clearAssignmentState(restoreFocus = false): void {
    const trigger = restoreFocus ? this.assignmentTrigger : null;
    this.assignmentTrigger = null;
    this.assignmentUser.set(null);
    this.assignmentProjects.set([]);
    this.assignmentSelection.set(new Set());
    this.assignmentError.set('');
    this.assignmentLoading.set(false);
    this.assignmentSaving.set(false);
    if (trigger) queueMicrotask(() => trigger.isConnected && trigger.focus());
  }

  changeEmail(user: AdminUser): void {
    const rawEmail = window.prompt('Nuevo correo electrónico', user.email);
    if (rawEmail === null) return;
    if (javaEmailValidator(new FormControl(rawEmail))) {
      this.error.set('El correo electrónico no es válido.');
      return;
    }
    const email = normalizeJavaEmail(rawEmail);
    if (email !== user.email)
      this.api
        .updateUserEmail(user.id, email)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => this.load(this.data()?.page),
          error: (error) => this.error.set(httpErrorMessage(error)),
        });
  }

  toggle(user: AdminUser): void {
    const active = user.status === 'DISABLED';
    if (!window.confirm(`¿${active ? 'Activar' : 'Desactivar'} a ${user.email}?`)) return;
    this.api
      .setUserActive(user.id, active)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(this.data()?.page),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  resetPassword(user: AdminUser): void {
    if (!window.confirm(`¿Generar una contraseña temporal para ${user.email}?`)) return;
    const temporaryPassword = window.prompt('Contraseña temporal (no se almacenará)') ?? '';
    if (javaPasswordValidator(new FormControl(temporaryPassword))) {
      if (temporaryPassword) this.error.set('La contraseña temporal no cumple los requisitos.');
      return;
    }
    this.api
      .resetUserPassword(user.id, temporaryPassword)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.error.set('Contraseña temporal actualizada.'),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  roleLabel(role: AdminRole): string {
    return role === 'GENERAL_ADMIN' ? 'Administrador general' : 'Administrador de proyecto';
  }

  statusLabel(status: AdminUserStatus): string {
    return {
      ACTIVE: 'Activo',
      DISABLED: 'Deshabilitado',
      PASSWORD_RESET_REQUIRED: 'Cambio requerido',
    }[status];
  }

  projectStatusLabel(status: Project['status']): string {
    return status === 'ACTIVE' ? 'Activo' : 'Deshabilitado';
  }
}
