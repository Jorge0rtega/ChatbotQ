import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { javaProjectNameValidator, normalizeJavaProjectName } from '../core/admin-validators';
import { AdminApiService } from '../core/admin-api.service';
import { httpErrorMessage } from '../core/http-error';
import {
  assignedProjectsFromMe,
  AssignedProjectSummary,
  PageResponse,
  Project,
} from '../core/models';
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

      @if (!session.me()?.generalAdmin) {
        @if (assignedProjects(); as projects) {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">Proyectos asignados</caption>
              <thead><tr><th>Proyecto</th><th>Estado</th></tr></thead>
              <tbody>
                @for (project of projects; track project.id) {
                  <tr><td><strong>{{ project.name }}</strong><small>{{ project.id }}</small></td><td>{{ projectStatusLabel(project.status) }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty" role="status">
            <h2>Asignaciones pendientes del perfil</h2>
            <p>Tus proyectos asignados se cargarán cuando el perfil /me incluya sus asignaciones.</p>
            <p>Esta vista no realiza el listado global ni ofrece acciones que el servidor no autoriza.</p>
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
        <p class="alert" aria-live="polite">{{ error() }}</p>
        @if (loading()) {
          <p class="state" aria-live="polite">Cargando proyectos…</p>
        } @else if (!data()?.items?.length) {
          <div class="empty"><h2>Aún no hay proyectos</h2><p>Crea el primero para comenzar.</p></div>
        } @else {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">Listado de proyectos</caption>
              <thead><tr><th>Proyecto</th><th>Estado</th><th>Actualizado</th><th>Acciones</th></tr></thead>
              <tbody>
                @for (project of data()!.items; track project.id) {
                  <tr>
                    <td><strong>{{ project.name }}</strong><small>{{ project.id }}</small></td>
                    <td><span class="badge" [class.inactive]="project.status === 'DISABLED'">{{ projectStatusLabel(project.status) }}</span></td>
                    <td>{{ format(project.updatedAt) }}</td>
                    <td class="actions">
                      <button class="ghost" (click)="rename(project)">Renombrar</button>
                      <button class="ghost danger" (click)="toggle(project)">{{ project.status === 'ACTIVE' ? 'Desactivar' : 'Activar' }}</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <nav class="pagination" aria-label="Paginación">
            <button class="ghost" [disabled]="data()!.page === 0" (click)="load(data()!.page - 1)">Anterior</button>
            <span>Página {{ data()!.page + 1 }} de {{ data()!.totalPages || 1 }}</span>
            <button class="ghost" [disabled]="data()!.page + 1 >= data()!.totalPages" (click)="load(data()!.page + 1)">Siguiente</button>
          </nav>
        }
      }
    </section>
  `,
})
export class ProjectsComponent {
  private readonly api = inject(AdminApiService);
  readonly session = inject(SessionService);
  readonly assignedProjects = computed<readonly AssignedProjectSummary[] | null>(() =>
    assignedProjectsFromMe(this.session.me()),
  );
  readonly data = signal<PageResponse<Project> | null>(null);
  readonly loading = signal(false);
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
    if (this.session.me()?.generalAdmin) this.load();
  }

  load(page = 0): void {
    if (!this.session.me()?.generalAdmin) return;
    this.loading.set(true);
    this.error.set('');
    this.api
      .listProjects(page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (data) => this.data.set(data), error: (error) => this.error.set(httpErrorMessage(error)) });
  }

  create(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.api
      .createProject(normalizeJavaProjectName(this.createForm.controls.name.value))
      .pipe(finalize(() => this.saving.set(false)))
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
    const name = normalizeJavaProjectName(rawName);
    this.api.renameProject(project.id, name).subscribe({
      next: () => this.load(this.data()?.page),
      error: (error) => this.error.set(httpErrorMessage(error)),
    });
  }

  toggle(project: Project): void {
    const active = project.status === 'DISABLED';
    if (!window.confirm(`¿${active ? 'Activar' : 'Desactivar'} el proyecto “${project.name}”?`)) return;
    this.api.setProjectActive(project.id, active).subscribe({
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
