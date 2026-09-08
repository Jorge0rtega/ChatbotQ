import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { KnowledgeEntry, MeResponse, PageResponse, Project } from '../core/models';
import { SessionService } from '../core/session.service';
import { KnowledgeComponent } from './knowledge.component';

const project = (id: string, name: string): Project => ({
  id,
  name,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
});

const entry = (id: string, question: string, embeddingStatus: KnowledgeEntry['embeddingStatus']): KnowledgeEntry => ({
  id,
  projectId: 'p1',
  question,
  answer: 'Respuesta',
  externalId: null,
  active: true,
  embeddingStatus,
  embeddingRevision: 1,
  embeddingAttemptCount: 0,
  embeddingLastAttemptAt: null,
  embeddingLastErrorCode: null,
  embeddingLastErrorMessage: null,
  version: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
});

const knowledgePage = (items: KnowledgeEntry[]): PageResponse<KnowledgeEntry> => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
});

describe('KnowledgeComponent', () => {
  async function configure(
    overrides: Record<string, unknown> = {},
    me: MeResponse | null = { userId: 'u1', email: 'admin@example.com', generalAdmin: true, projectIds: [] },
  ) {
    const api = {
      listAllProjects: vi.fn(() => of([project('p1', 'Proyecto uno')])),
      getProject: vi.fn((id: string) => of(project(id, 'Proyecto uno'))),
      listKnowledge: vi.fn(() => of(knowledgePage([entry('k1', '¿Cuál es el horario?', 'READY')]))),
      ...overrides,
    };
    const sessionMe = signal(me);
    await TestBed.configureTestingModule({
      imports: [KnowledgeComponent],
      providers: [
        { provide: AdminApiService, useValue: api },
        { provide: SessionService, useValue: { me: sessionMe } },
      ],
    }).compileComponents();
    return { api, fixture: TestBed.createComponent(KnowledgeComponent), sessionMe };
  }

  it('loads projects and page zero once when session restoration populates the user', async () => {
    const { api, fixture, sessionMe } = await configure({}, null);
    fixture.detectChanges();

    expect(api.listAllProjects).not.toHaveBeenCalled();
    expect(api.listKnowledge).not.toHaveBeenCalled();

    sessionMe.set({ userId: 'u1', email: 'admin@example.com', generalAdmin: true, projectIds: [] });
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(1);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('does not rediscover projects when the same restored user is refreshed', async () => {
    const { api, fixture, sessionMe } = await configure();
    fixture.detectChanges();

    sessionMe.set({ userId: 'u1', email: 'renamed@example.com', generalAdmin: true, projectIds: [] });
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(1);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('discards a superseded project discovery after a manual retry', async () => {
    const firstDiscovery = new Subject<readonly Project[]>();
    const { api, fixture } = await configure({
      listAllProjects: vi.fn().mockReturnValueOnce(firstDiscovery).mockReturnValueOnce(of([project('p1', 'Proyecto actual')])),
    });
    fixture.detectChanges();

    fixture.componentInstance.retryProjects();
    firstDiscovery.next([project('p2', 'Proyecto tardío')]);
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.projects().map((item) => item.id)).toEqual(['p1']);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('loads only assigned ACTIVE projects for a project admin without listing every project', async () => {
    const { api, fixture } = await configure(
      {
        getProject: vi.fn((id: string) =>
          of({ ...project(id, id === 'p1' ? 'Asignado activo' : 'Asignado inactivo'), status: id === 'p1' ? 'ACTIVE' : 'DISABLED' }),
        ),
      },
      { userId: 'u1', email: 'project@example.com', generalAdmin: false, projectIds: ['p1', 'p2'] },
    );
    fixture.detectChanges();

    expect(api.listAllProjects).not.toHaveBeenCalled();
    expect(api.getProject).toHaveBeenCalledTimes(2);
    expect(api.getProject).toHaveBeenCalledWith('p1');
    expect(api.getProject).toHaveBeenCalledWith('p2');
    expect(fixture.componentInstance.projects().map((item) => item.id)).toEqual(['p1']);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('loads the requested knowledge page when pagination advances', async () => {
    const secondPage: PageResponse<KnowledgeEntry> = {
      ...knowledgePage([entry('k2', 'Segunda página', 'READY')]),
      page: 1,
      totalElements: 21,
      totalPages: 2,
    };
    const { api, fixture } = await configure({
      listKnowledge: vi
        .fn()
        .mockReturnValueOnce(of({ ...knowledgePage([entry('k1', 'Primera página', 'READY')]), totalElements: 21, totalPages: 2 }))
        .mockReturnValueOnce(of(secondPage)),
    });
    fixture.detectChanges();

    fixture.componentInstance.load(1);
    fixture.detectChanges();

    expect(api.listKnowledge).toHaveBeenLastCalledWith('p1', 1, 20, undefined);
    expect(fixture.nativeElement.textContent).toContain('Página 2 de 2');
    expect(fixture.nativeElement.textContent).toContain('Segunda página');
  });

  it('loads the first available project and renders its knowledge status', async () => {
    const { api, fixture } = await configure();
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(1);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
    expect(fixture.nativeElement.textContent).toContain('¿Cuál es el horario?');
    expect(fixture.nativeElement.textContent).toContain('Listo');
    expect((fixture.nativeElement.querySelector('#knowledge-search') as HTMLInputElement).placeholder).toBe(
      'Pregunta o ID externo',
    );
  });

  it('shows an accessible project-load failure without an empty selector and retries it', async () => {
    const { api, fixture } = await configure({
      listAllProjects: vi
        .fn()
        .mockReturnValueOnce(throwError(() => ({ status: 500 })))
        .mockReturnValueOnce(of([project('p1', 'Proyecto uno')])),
    });
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).not.toBe('');
    expect(alert.getAttribute('aria-live')).toBe('assertive');
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();

    const retry = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Reintentar',
    ) as HTMLButtonElement;
    retry.click();
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('select')).not.toBeNull();
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('retries a failed knowledge listing through an accessible error state', async () => {
    const { api, fixture } = await configure({
      listKnowledge: vi
        .fn()
        .mockReturnValueOnce(throwError(() => ({ status: 500 })))
        .mockReturnValueOnce(of(knowledgePage([entry('k1', 'Recuperada', 'READY')]))),
    });
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).not.toBe('');
    expect(alert.getAttribute('aria-live')).toBe('assertive');
    expect(fixture.nativeElement.querySelector('table')).toBeNull();

    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Reintentar')!
      .click();
    fixture.detectChanges();

    expect(api.listKnowledge).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Recuperada');
  });

  it('reloads page zero with the selected project and normalized search query', async () => {
    const { api, fixture } = await configure();
    fixture.detectChanges();
    api.listKnowledge.mockClear();

    fixture.componentInstance.search.setValue('  horario  ');
    fixture.componentInstance.searchKnowledge();

    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, 'horario');
  });

  it('discards a late result when a newer project selection supersedes it', async () => {
    const first = new Subject<PageResponse<KnowledgeEntry>>();
    const { api, fixture } = await configure({
      listAllProjects: vi.fn(() => of([project('p1', 'Proyecto uno'), project('p2', 'Proyecto dos')])),
      listKnowledge: vi.fn((projectId: string) =>
        projectId === 'p1' ? first : of(knowledgePage([entry('k2', 'Pregunta dos', 'FAILED')])),
      ),
    });
    fixture.detectChanges();

    fixture.componentInstance.selectProject('p2');
    first.next(knowledgePage([entry('late', 'Pregunta tardía', 'READY')]));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Pregunta dos');
    expect(fixture.nativeElement.textContent).not.toContain('Pregunta tardía');
  });
});
