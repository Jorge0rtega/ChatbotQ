import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { javaProjectNameValidator, normalizeJavaProjectName } from '../core/admin-validators';
import { AdminApiService } from '../core/admin-api.service';
import { httpErrorMessage } from '../core/http-error';
import { PageResponse, Project } from '../core/models';
import { SessionService } from '../core/session.service';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Espacio de trabajo</p>
          <h1>Proyectos</h1>
          <p class="muted">Gestiona los proyectos disponibles.</p>
        </div>
        @if (session.me()?.generalAdmin) {
          <button class="primary" (click)="showCreate.set(!showCreate())">Nuevo proyecto</button>
        }
      </header>

      <p class="alert" aria-live="polite">{{ error() }}</p>
      @if (!session.me()?.generalAdmin) {
        @if (loading()) {
          <p class="state" aria-live="polite">Cargando proyectos asignados…</p>
        } @else if (assignedProjects().length === 0 && !error()) {
          <div class="empty" role="status">
            <h2>No tienes proyectos asignados</h2>
            <p>Contacta con un administrador general si necesitas acceso.</p>
          </div>
        } @else if (assignedProjects().length) {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">
                Proyectos asignados
              </caption>
              <thead>
                <tr>
                  <th>Proyecto</th>
                  <th>Estado</th>
                </tr>
              </thead>
              <tbody>
                @for (project of assignedProjects(); track project.id) {
                  <tr>
                    <td>
                      <strong>{{ project.name }}</strong
                      ><small>{{ project.id }}</small>
                    </td>
                    <td>
                      <span class="badge" [class.inactive]="project.status === 'DISABLED'">{{
                        projectStatusLabel(project.status)
                      }}</span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        @if (showCreate()) {
          <form class="panel inline-form" [formGroup]="createForm" (ngSubmit)="create()">
            <label for="project-name">Nombre del proyecto</label>
            <input id="project-name" formControlName="name" aria-describedby="project-name-hint" />
            <p id="project-name-hint" class="hint">Máximo 160 caracteres Unicode.</p>
            <button class="primary" [disabled]="saving()">Crear</button>
            <button type="button" class="ghost" (click)="closeCreate()">Cancelar</button>
          </form>
        }
        @if (loading()) {
          <p class="state" aria-live="polite">Cargando proyectos…</p>
        } @else if (!data()?.items?.length) {
          <div class="empty">
            <h2>Aún no hay proyectos</h2>
            <p>Crea el primero para comenzar.</p>
          </div>
        } @else {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">
                Listado de proyectos
              </caption>
              <thead>
                <tr>
                  <th>Proyecto</th>
                  <th>Estado</th>
                  <th>Actualizado</th>
                  <th>Acciones</th>
                </tr>
              </thead>
              <tbody>
                @for (project of data()!.items; track project.id) {
                  <tr>
                    <td>
                      <strong>{{ project.name }}</strong
                      ><small>{{ project.id }}</small>
                    </td>
                    <td>
                      <span class="badge" [class.inactive]="project.status === 'DISABLED'">{{
                        projectStatusLabel(project.status)
                      }}</span>
                    </td>
                    <td>{{ format(project.updatedAt) }}</td>
                    <td class="actions">
                      <button class="ghost" (click)="rename(project)">Renombrar</button>
                      <button class="ghost danger" (click)="toggle(project)">
                        {{ project.status === 'ACTIVE' ? 'Desactivar' : 'Activar' }}
                      </button>
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
      }
    </section>
  `,
})
export class ProjectsComponent {
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(SessionService);
  readonly assignedProjects = signal<readonly Project[]>([]);
  readonly data = signal<PageResponse<Project> | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly showCreate = signal(false);
  readonly createForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, javaProjectNameValidator],
    }),
  });

  constructor() {
    const me = this.session.me();
    if (me?.generalAdmin) this.load();
    else this.loadAssigned(me?.projectIds ?? []);
  }

  load(page = 0): void {
    if (!this.session.me()?.generalAdmin) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .listProjects(page)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (data) => this.data.set(data),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  private loadAssigned(projectIds: readonly string[]): void {
    this.loading.set(true);
    this.error.set('');
    if (projectIds.length === 0) {
      this.assignedProjects.set([]);
      this.loading.set(false);
      return;
    }
    forkJoin(projectIds.map((id) => this.api.getProject(id)))
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (projects) => this.assignedProjects.set(projects),
        error: () => {
          this.assignedProjects.set([]);
          this.error.set(
            'No se pudieron cargar tus proyectos asignados. Actualiza la página para reintentar.',
          );
        },
      });
  }

  create(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.api
      .createProject(normalizeJavaProjectName(this.createForm.controls.name.value))
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
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
    this.createForm.reset({ name: '' });
    this.showCreate.set(false);
  }

  rename(project: Project): void {
    const rawName = window.prompt('Nuevo nombre del proyecto', project.name);
    if (rawName === null || javaProjectNameValidator(new FormControl(rawName))) return;
    this.api
      .renameProject(project.id, normalizeJavaProjectName(rawName))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(this.data()?.page),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  toggle(project: Project): void {
    const active = project.status === 'DISABLED';
    if (!window.confirm(`¿${active ? 'Activar' : 'Desactivar'} el proyecto “${project.name}”?`))
      return;
    this.api
      .setProjectActive(project.id, active)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(this.data()?.page),
        error: (error) => this.error.set(httpErrorMessage(error)),
      });
  }

  projectStatusLabel(status: Project['status']): string {
    return status === 'ACTIVE' ? 'Activo' : 'Deshabilitado';
  }

  format(value: string): string {
    return new Intl.DateTimeFormat('es', { dateStyle: 'medium' }).format(new Date(value));
  }
}
