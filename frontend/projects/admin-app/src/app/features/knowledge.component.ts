import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { finalize, forkJoin, takeUntil, Subject } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { httpErrorMessage } from '../core/http-error';
import { KnowledgeEmbeddingStatus, KnowledgeEntry, PageResponse, Project } from '../core/models';
import { SessionService } from '../core/session.service';

@Component({
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Base de conocimiento</p>
          <h1>Conocimiento</h1>
          <p class="muted">Consulta el estado de las respuestas disponibles por proyecto.</p>
        </div>
      </header>

      @if (projectsLoading()) {
        <p class="state" aria-live="polite">Cargando proyectos…</p>
      } @else if (projectsError()) {
        <div class="empty" role="alert" aria-live="assertive">
          <h2>No se pudieron cargar los proyectos</h2>
          <p>{{ projectsError() }}</p>
          <button class="ghost" type="button" (click)="retryProjects()">Reintentar</button>
        </div>
      } @else if (projects().length === 0 && !error()) {
        <div class="empty" role="status">
          <h2>No hay proyectos disponibles</h2>
          <p>No puedes consultar conocimiento hasta que tengas un proyecto activo asignado.</p>
        </div>
      } @else {
        <div class="panel inline-form">
          <label for="knowledge-project">Proyecto</label>
          <select id="knowledge-project" [value]="selectedProjectId()" (change)="selectProject($any($event.target).value)">
            @for (project of projects(); track project.id) {
              <option [value]="project.id">{{ project.name }}</option>
            }
          </select>
          <label for="knowledge-search">Buscar</label>
          <input id="knowledge-search" [formControl]="search" (keyup.enter)="searchKnowledge()" placeholder="Pregunta o ID externo" />
          <button class="ghost" type="button" (click)="searchKnowledge()">Buscar</button>
        </div>

        @if (loading()) {
          <p class="state" aria-live="polite">Cargando conocimiento…</p>
        } @else if (error()) {
          <div class="empty" role="alert" aria-live="assertive">
            <h2>No se pudo cargar el conocimiento</h2>
            <p>{{ error() }}</p>
            <button class="ghost" type="button" (click)="retryKnowledge()">Reintentar</button>
          </div>
        } @else if (!data()?.items?.length && !error()) {
          <div class="empty" role="status">
            <h2>No hay entradas de conocimiento</h2>
            <p>Prueba otra búsqueda o crea la primera entrada en el siguiente corte.</p>
          </div>
        } @else if (data()?.items?.length) {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">Listado de conocimiento</caption>
              <thead>
                <tr>
                  <th>Pregunta</th>
                  <th>Estado del embedding</th>
                  <th>Activo</th>
                  <th>Actualizado</th>
                </tr>
              </thead>
              <tbody>
                @for (entry of data()!.items; track entry.id) {
                  <tr>
                    <td><strong>{{ entry.question }}</strong></td>
                    <td><span class="badge" [class.inactive]="entry.embeddingStatus === 'FAILED'">{{ embeddingStatusLabel(entry.embeddingStatus) }}</span></td>
                    <td>{{ entry.active ? 'Sí' : 'No' }}</td>
                    <td>{{ format(entry.updatedAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <nav class="pagination" aria-label="Paginación de conocimiento">
            <button class="ghost" [disabled]="data()!.page === 0" (click)="load(data()!.page - 1)">Anterior</button>
            <span>Página {{ data()!.page + 1 }} de {{ data()!.totalPages || 1 }}</span>
            <button class="ghost" [disabled]="data()!.page + 1 >= data()!.totalPages" (click)="load(data()!.page + 1)">Siguiente</button>
          </nav>
        }
      }
    </section>
  `,
})
export class KnowledgeComponent {
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requestsCancelled = new Subject<void>();
  private readonly projectsRequestsCancelled = new Subject<void>();
  private requestEpoch = 0;
  private projectsRequestEpoch = 0;
  private loadedForUserId: string | null = null;
  readonly session = inject(SessionService);
  readonly projects = signal<readonly Project[]>([]);
  readonly selectedProjectId = signal('');
  readonly projectsLoading = signal(true);
  readonly projectsError = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly data = signal<PageResponse<KnowledgeEntry> | null>(null);
  readonly search = new FormControl('', { nonNullable: true });

  constructor() {
    effect(() => {
      const me = this.session.me();
      if (me && this.loadedForUserId !== me.userId) {
        this.loadedForUserId = me.userId;
        this.loadProjects(me);
      }
    });
  }

  selectProject(projectId: string): void {
    if (!this.projects().some((project) => project.id === projectId) || projectId === this.selectedProjectId()) return;
    this.selectedProjectId.set(projectId);
    this.load(0);
  }

  searchKnowledge(): void {
    if (this.selectedProjectId()) this.load(0);
  }

  retryProjects(): void {
    this.loadProjects();
  }

  retryKnowledge(): void {
    this.load();
  }

  load(page = 0): void {
    const projectId = this.selectedProjectId();
    if (!projectId) return;
    this.requestsCancelled.next();
    const requestEpoch = ++this.requestEpoch;
    this.loading.set(true);
    this.error.set('');
    this.data.set(null);
    const query = this.normalizedQuery();
    this.api
      .listKnowledge(projectId, page, 20, query || undefined)
      .pipe(
        takeUntil(this.requestsCancelled),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (requestEpoch === this.requestEpoch) this.loading.set(false);
        }),
      )
      .subscribe({
        next: (data) => {
          if (requestEpoch === this.requestEpoch) this.data.set(data);
        },
        error: (error) => {
          if (requestEpoch !== this.requestEpoch) return;
          this.data.set(null);
          this.error.set(httpErrorMessage(error));
        },
      });
  }

  embeddingStatusLabel(status: KnowledgeEmbeddingStatus): string {
    return ({ PENDING: 'Pendiente', PROCESSING: 'Procesando', READY: 'Listo', FAILED: 'Falló' })[status];
  }

  format(value: string): string {
    return new Intl.DateTimeFormat('es', { dateStyle: 'medium' }).format(new Date(value));
  }

  private loadProjects(me = this.session.me()): void {
    this.projectsRequestsCancelled.next();
    const requestEpoch = ++this.projectsRequestEpoch;
    this.requestsCancelled.next();
    ++this.requestEpoch;
    this.projects.set([]);
    this.selectedProjectId.set('');
    this.data.set(null);
    this.projectsLoading.set(true);
    this.projectsError.set('');
    this.error.set('');
    if (!me) {
      this.projectsLoading.set(false);
      return;
    }
    const source = me.generalAdmin
      ? this.api.listAllProjects()
      : me.projectIds.length === 0
        ? undefined
        : forkJoin(me.projectIds.map((id) => this.api.getProject(id)));
    if (!source) {
      this.projectsLoading.set(false);
      return;
    }
    source.pipe(takeUntil(this.projectsRequestsCancelled), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (projects) => {
        if (requestEpoch !== this.projectsRequestEpoch) return;
        const activeProjects = projects.filter((project) => project.status === 'ACTIVE');
        this.projects.set(activeProjects);
        this.projectsLoading.set(false);
        if (activeProjects.length) {
          this.selectedProjectId.set(activeProjects[0].id);
          this.load(0);
        }
      },
      error: (error) => {
        if (requestEpoch !== this.projectsRequestEpoch) return;
        this.projects.set([]);
        this.projectsLoading.set(false);
        this.projectsError.set(httpErrorMessage(error));
      },
    });
  }

  private normalizedQuery(): string {
    return this.search.value.trim().replace(/\s+/g, ' ');
  }
}
