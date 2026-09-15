import {
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin, Subject, takeUntil } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { httpErrorMessage } from '../core/http-error';
import {
  CreateKnowledgeRequest,
  KnowledgeEmbeddingStatus,
  KnowledgeEntry,
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
          <p class="eyebrow">Base de conocimiento</p>
          <h1>Conocimiento</h1>
          <p class="muted">Consulta y edita las respuestas disponibles por proyecto.</p>
        </div>
        @if (selectedProjectId()) {
          <button #createTrigger class="primary" type="button" (click)="openCreate(createTrigger)">
            Nueva entrada
          </button>
        }
      </header>
      <p class="success" aria-live="polite">{{ feedback() }}</p>
      <p class="alert">{{ mutationError() }}</p>

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
          <select
            id="knowledge-project"
            [value]="selectedProjectId()"
            (change)="selectProject($any($event.target).value)"
          >
            @for (project of projects(); track project.id) {
              <option [value]="project.id">{{ project.name }}</option>
            }
          </select>
          <label for="knowledge-search">Buscar</label>
          <input
            id="knowledge-search"
            [formControl]="search"
            (keyup.enter)="searchKnowledge()"
            placeholder="Pregunta o ID externo"
          />
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
        } @else if (!data()?.items?.length) {
          <div class="empty" role="status">
            <h2>No hay entradas de conocimiento</h2>
            <p>Crea la primera entrada para comenzar.</p>
          </div>
        } @else if (data()?.items?.length) {
          <div class="table-wrap">
            <table>
              <caption class="sr-only">
                Listado de conocimiento
              </caption>
              <thead>
                <tr>
                  <th>Pregunta</th>
                  <th>Estado del embedding</th>
                  <th>Activo</th>
                  <th>Actualizado</th>
                  <th>Acciones</th>
                </tr>
              </thead>
              <tbody>
                @for (entry of data()!.items; track entry.id) {
                  <tr>
                    <td>
                      <strong>{{ entry.question }}</strong>
                    </td>
                    <td>
                      <span class="badge" [class.inactive]="entry.embeddingStatus === 'FAILED'">{{
                        embeddingStatusLabel(entry.embeddingStatus)
                      }}</span>
                    </td>
                    <td>{{ entry.active ? 'Sí' : 'No' }}</td>
                    <td>{{ format(entry.updatedAt) }}</td>
                    <td class="actions">
                      <button
                        #entryTrigger
                        class="ghost"
                        type="button"
                        (click)="openEntry(entry.id, entryTrigger)"
                      >
                        Ver
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <nav class="pagination" aria-label="Paginación de conocimiento">
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

      @if (editorMode()) {
        <section class="panel" aria-labelledby="knowledge-editor-heading">
          <header class="page-header">
            <div>
              <p class="eyebrow">Entrada de conocimiento</p>
              <h2 #editorHeading id="knowledge-editor-heading" tabindex="-1">
                {{ editorMode() === 'create' ? 'Nueva entrada' : 'Detalle de entrada' }}
              </h2>
            </div>
            <button class="ghost" type="button" (click)="closeEditor()">Cerrar</button>
          </header>
          @if (editorLoading()) {
            <p class="state" aria-live="polite">Cargando entrada…</p>
          } @else if (editorError()) {
            <p class="alert" role="alert">{{ editorError() }}</p>
          } @else if (editorMode() === 'view' && editorEntry()) {
            <dl>
              <dt>Pregunta</dt>
              <dd>{{ editorEntry()!.question }}</dd>
              <dt>Respuesta</dt>
              <dd>{{ editorEntry()!.answer }}</dd>
              <dt>ID externo</dt>
              <dd>{{ editorEntry()!.externalId || 'Sin ID externo' }}</dd>
              <dt>Embedding</dt>
              <dd>{{ embeddingStatusLabel(editorEntry()!.embeddingStatus) }}</dd>
            </dl>
            <div class="actions">
              <button class="primary" type="button" (click)="editEntry()">Editar</button>
              <button
                class="ghost"
                type="button"
                [disabled]="editorSaving()"
                (click)="toggleEntryActive()"
              >
                {{ editorEntry()!.active ? 'Desactivar' : 'Activar' }}
              </button>
              @if (editorEntry()!.embeddingStatus === 'FAILED') {
                <button
                  class="ghost"
                  type="button"
                  [disabled]="editorSaving()"
                  (click)="retryEmbedding()"
                >
                  Reintentar embedding
                </button>
              }
            </div>
          } @else {
            <form class="form-grid" [formGroup]="editorForm" (ngSubmit)="saveEditor()" novalidate>
              <label for="knowledge-question">Pregunta</label>
              <input
                #questionInput
                id="knowledge-question"
                formControlName="question"
                [attr.aria-invalid]="
                  editorForm.controls.question.invalid && editorForm.controls.question.touched
                "
              />
              <label for="knowledge-answer">Respuesta</label>
              <textarea
                id="knowledge-answer"
                formControlName="answer"
                [attr.aria-invalid]="
                  editorForm.controls.answer.invalid && editorForm.controls.answer.touched
                "
              ></textarea>
              <label for="knowledge-external-id">ID externo</label>
              <input id="knowledge-external-id" formControlName="externalId" />
              <label><input type="checkbox" formControlName="active" /> Activa</label>
              <div class="actions">
                <button class="primary" [disabled]="editorSaving()">
                  {{
                    editorSaving()
                      ? 'Guardando…'
                      : editorMode() === 'create'
                        ? 'Crear entrada'
                        : 'Guardar cambios'
                  }}
                </button>
                <button class="ghost" type="button" (click)="cancelEditorEdit()">
                  Cancelar
                </button>
              </div>
            </form>
          }
        </section>
      }
    </section>
  `,
})
export class KnowledgeComponent {
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requestsCancelled = new Subject<void>();
  private readonly projectsRequestsCancelled = new Subject<void>();
  private readonly editorRequestsCancelled = new Subject<void>();
  private requestEpoch = 0;
  private projectsRequestEpoch = 0;
  private editorRequestEpoch = 0;
  private loadedForUserId: string | null = null;
  private editorOrigin: HTMLElement | null = null;
  @ViewChild('questionInput') private questionInput?: ElementRef<HTMLInputElement>;
  @ViewChild('editorHeading') private editorHeading?: ElementRef<HTMLHeadingElement>;
  readonly session = inject(SessionService);
  readonly projects = signal<readonly Project[]>([]);
  readonly selectedProjectId = signal('');
  readonly projectsLoading = signal(true);
  readonly projectsError = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly data = signal<PageResponse<KnowledgeEntry> | null>(null);
  readonly search = new FormControl('', { nonNullable: true });
  readonly feedback = signal('');
  readonly mutationError = signal('');
  readonly editorMode = signal<'create' | 'view' | 'edit' | null>(null);
  readonly editorEntry = signal<KnowledgeEntry | null>(null);
  readonly editorLoading = signal(false);
  readonly editorSaving = signal(false);
  readonly editorError = signal('');
  readonly editorForm = new FormGroup({
    question: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    answer: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    externalId: new FormControl('', { nonNullable: true }),
    active: new FormControl(true, { nonNullable: true }),
  });

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
    if (
      !this.projects().some((project) => project.id === projectId) ||
      projectId === this.selectedProjectId()
    )
      return;
    this.closeEditor(false);
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
          if (requestEpoch === this.requestEpoch) {
            this.data.set(null);
            this.error.set(httpErrorMessage(error));
          }
        },
      });
  }

  openCreate(origin: HTMLElement): void {
    if (!this.selectedProjectId()) return;
    this.cancelEditorRequests();
    this.editorOrigin = origin;
    this.feedback.set('');
    this.mutationError.set('');
    this.editorError.set('');
    this.editorEntry.set(null);
    this.editorForm.reset({ question: '', answer: '', externalId: '', active: true });
    this.editorMode.set('create');
    queueMicrotask(() => this.questionInput?.nativeElement.focus());
  }

  openEntry(entryId: string, origin: HTMLElement): void {
    const projectId = this.selectedProjectId();
    if (!projectId) return;
    this.cancelEditorRequests();
    const requestEpoch = this.editorRequestEpoch;
    this.editorOrigin = origin;
    this.feedback.set('');
    this.mutationError.set('');
    this.editorError.set('');
    this.editorEntry.set(null);
    this.editorForm.reset();
    this.editorMode.set('view');
    this.editorLoading.set(true);
    queueMicrotask(() => this.editorHeading?.nativeElement.focus());
    this.api
      .getKnowledge(projectId, entryId)
      .pipe(takeUntil(this.editorRequestsCancelled), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (entry) => {
          if (requestEpoch === this.editorRequestEpoch) {
            this.editorEntry.set(entry);
            this.editorLoading.set(false);
          }
        },
        error: (error) => {
          if (requestEpoch === this.editorRequestEpoch) {
            this.editorEntry.set(null);
            this.editorLoading.set(false);
            this.editorError.set(httpErrorMessage(error));
          }
        },
      });
  }

  editEntry(): void {
    const entry = this.editorEntry();
    if (!entry) return;
    this.editorForm.reset({
      question: entry.question,
      answer: entry.answer,
      externalId: entry.externalId ?? '',
      active: entry.active,
    });
    this.editorMode.set('edit');
    queueMicrotask(() => this.questionInput?.nativeElement.focus());
  }

  saveEditor(): void {
    const projectId = this.selectedProjectId();
    if (!projectId || this.editorForm.invalid || this.editorSaving()) {
      this.editorForm.markAllAsTouched();
      return;
    }
    const values = this.editorForm.getRawValue();
    const body: CreateKnowledgeRequest = {
      question: values.question.trim(),
      answer: values.answer.trim(),
      externalId: this.normalizeExternalId(values.externalId),
      active: values.active,
    };
    if (!body.question || !body.answer) {
      this.editorForm.markAllAsTouched();
      return;
    }
    const current = this.editorEntry();
    const creating = this.editorMode() === 'create';
    if (!creating && !current) return;
    this.startEditorMutation(
      creating
        ? this.api.createKnowledge(projectId, body)
        : this.api.updateKnowledge(projectId, current!.id, { ...body, version: current!.version }),
      creating ? 'Entrada creada correctamente.' : 'Entrada actualizada correctamente.',
    );
  }

  toggleEntryActive(): void {
    const projectId = this.selectedProjectId();
    const entry = this.editorEntry();
    if (!projectId || !entry || this.editorSaving()) return;
    this.startEditorMutation(
      this.api.updateKnowledge(projectId, entry.id, {
        question: entry.question,
        answer: entry.answer,
        externalId: entry.externalId,
        active: !entry.active,
        version: entry.version,
      }),
      'Estado de la entrada actualizado correctamente.',
    );
  }

  retryEmbedding(): void {
    const projectId = this.selectedProjectId();
    const entry = this.editorEntry();
    if (!projectId || !entry || entry.embeddingStatus !== 'FAILED' || this.editorSaving()) return;
    this.startEditorMutation(
      this.api.retryKnowledgeEmbedding(projectId, entry.id, entry.version),
      'Embedding enviado a reintento correctamente.',
    );
  }

  cancelEditorEdit(): void {
    if (this.editorMode() === 'create' || !this.editorEntry()) this.closeEditor();
    else this.editorMode.set('view');
  }

  closeEditor(restoreFocus = true): void {
    this.cancelEditorRequests();
    this.editorMode.set(null);
    this.editorEntry.set(null);
    this.editorLoading.set(false);
    this.editorSaving.set(false);
    this.editorError.set('');
    this.editorForm.reset({ question: '', answer: '', externalId: '', active: true });
    const origin = this.editorOrigin;
    this.editorOrigin = null;
    if (restoreFocus)
      queueMicrotask(() => {
        if (origin?.isConnected) origin.focus();
      });
  }

  embeddingStatusLabel(status: KnowledgeEmbeddingStatus): string {
    return { PENDING: 'Pendiente', PROCESSING: 'Procesando', READY: 'Listo', FAILED: 'Falló' }[
      status
    ];
  }
  format(value: string): string {
    return new Intl.DateTimeFormat('es', { dateStyle: 'medium' }).format(new Date(value));
  }

  private startEditorMutation(
    source: ReturnType<AdminApiService['createKnowledge']>,
    success: string,
  ): void {
    this.editorRequestsCancelled.next();
    const requestEpoch = ++this.editorRequestEpoch;
    this.editorSaving.set(true);
    this.editorError.set('');
    this.feedback.set('');
    this.mutationError.set('');
    source
      .pipe(
        takeUntil(this.editorRequestsCancelled),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (requestEpoch === this.editorRequestEpoch) this.editorSaving.set(false);
        }),
      )
      .subscribe({
        next: (entry) => {
          if (requestEpoch === this.editorRequestEpoch) {
            this.editorEntry.set(entry);
            this.editorMode.set('view');
            this.feedback.set(success);
            this.load(this.data()?.page ?? 0);
          }
        },
        error: (error) => {
          if (requestEpoch === this.editorRequestEpoch) {
            this.editorEntry.set(null);
            this.editorForm.reset({ question: '', answer: '', externalId: '', active: true });
            const message =
              error?.status === 409
                ? 'La entrada cambió en otra sesión. Vuelve a abrirla antes de guardar.'
                : httpErrorMessage(error);
            this.editorError.set(message);
            this.mutationError.set(message);
          }
        },
      });
  }

  private cancelEditorRequests(): void {
    this.editorRequestsCancelled.next();
    ++this.editorRequestEpoch;
  }

  private loadProjects(me = this.session.me()): void {
    this.projectsRequestsCancelled.next();
    const requestEpoch = ++this.projectsRequestEpoch;
    this.requestsCancelled.next();
    ++this.requestEpoch;
    this.closeEditor(false);
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
    source
      .pipe(takeUntil(this.projectsRequestsCancelled), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (projects) => {
          if (requestEpoch === this.projectsRequestEpoch) {
            const activeProjects = projects.filter((project) => project.status === 'ACTIVE');
            this.projects.set(activeProjects);
            this.projectsLoading.set(false);
            if (activeProjects.length) {
              this.selectedProjectId.set(activeProjects[0].id);
              this.load(0);
            }
          }
        },
        error: (error) => {
          if (requestEpoch === this.projectsRequestEpoch) {
            this.projects.set([]);
            this.projectsLoading.set(false);
            this.projectsError.set(httpErrorMessage(error));
          }
        },
      });
  }

  private normalizedQuery(): string {
    return this.search.value.trim().replace(/\s+/g, ' ');
  }
  private normalizeExternalId(value: string): string | null {
    const normalized = value.trim();
    return normalized || null;
  }
}
