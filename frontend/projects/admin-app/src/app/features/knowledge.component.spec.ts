import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import {
  KnowledgeEntry,
  KnowledgeImportDetail,
  MeResponse,
  PageResponse,
  Project,
} from '../core/models';
import { SessionService } from '../core/session.service';
import { KnowledgeComponent } from './knowledge.component';

const project = (id: string, name: string): Project => ({
  id,
  name,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
});

const entry = (
  id: string,
  question: string,
  embeddingStatus: KnowledgeEntry['embeddingStatus'],
): KnowledgeEntry => ({
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

const importDetail = (overrides: Partial<KnowledgeImportDetail> = {}): KnowledgeImportDetail => ({
  id: 'job-1',
  projectId: 'p1',
  fileName: 'knowledge.csv',
  strategy: 'CREATE_ONLY',
  status: 'READY',
  totalRows: 1,
  validRows: 1,
  invalidRows: 0,
  importedRows: 0,
  errorSummary: [],
  createdAt: '2026-01-01T00:00:00Z',
  rows: [{ rowNumber: 2, question: 'Pregunta importada', answer: 'Respuesta importada', externalId: 'csv-1', active: true, status: 'VALID', errors: [] }],
  page: 0,
  size: 20,
  totalRowElements: 1,
  ...overrides,
});

describe('KnowledgeComponent', () => {
  async function configure(
    overrides: Record<string, unknown> = {},
    me: MeResponse | null = {
      userId: 'u1',
      email: 'admin@example.com',
      generalAdmin: true,
      projectIds: [],
    },
  ) {
    const api = {
      listAllProjects: vi.fn(() => of([project('p1', 'Proyecto uno')])),
      getProject: vi.fn((id: string) => of(project(id, 'Proyecto uno'))),
      listKnowledge: vi.fn(() => of(knowledgePage([entry('k1', '¿Cuál es el horario?', 'READY')]))),
      getKnowledge: vi.fn(() => of(entry('k1', '¿Cuál es el horario?', 'READY'))),
      createKnowledge: vi.fn(() => of(entry('new', 'Pregunta nueva', 'PENDING'))),
      updateKnowledge: vi.fn(() => of(entry('k1', '¿Cuál es el horario?', 'READY'))),
      retryKnowledgeEmbedding: vi.fn(() => of(entry('k1', '¿Cuál es el horario?', 'PENDING'))),
      createKnowledgeImport: vi.fn(() => of(importDetail())),
      getKnowledgeImport: vi.fn(() => of(importDetail())),
      executeKnowledgeImport: vi.fn(() => of(importDetail())),
      retryKnowledgeImport: vi.fn(() => of(importDetail())),
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

    sessionMe.set({
      userId: 'u1',
      email: 'renamed@example.com',
      generalAdmin: true,
      projectIds: [],
    });
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(1);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
  });

  it('discards a superseded project discovery after a manual retry', async () => {
    const firstDiscovery = new Subject<readonly Project[]>();
    const { api, fixture } = await configure({
      listAllProjects: vi
        .fn()
        .mockReturnValueOnce(firstDiscovery)
        .mockReturnValueOnce(of([project('p1', 'Proyecto actual')])),
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
          of({
            ...project(id, id === 'p1' ? 'Asignado activo' : 'Asignado inactivo'),
            status: id === 'p1' ? 'ACTIVE' : 'DISABLED',
          }),
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
        .mockReturnValueOnce(
          of({
            ...knowledgePage([entry('k1', 'Primera página', 'READY')]),
            totalElements: 21,
            totalPages: 2,
          }),
        )
        .mockReturnValueOnce(of(secondPage)),
    });
    fixture.detectChanges();

    fixture.componentInstance.load(1);
    fixture.detectChanges();

    expect(api.listKnowledge).toHaveBeenLastCalledWith('p1', 1, 20, undefined);
    expect(fixture.nativeElement.textContent).toContain('Página 2 de 2');
    expect(fixture.nativeElement.textContent).toContain('Segunda página');
  });

  it('uploads a selected CSV, then loads its first import detail page', async () => {
    const uploaded = importDetail({ strategy: 'UPSERT' });
    const { api, fixture } = await configure({
      createKnowledgeImport: vi.fn(() => of(uploaded)),
      getKnowledgeImport: vi.fn(() => of(uploaded)),
    });
    fixture.detectChanges();
    const trigger = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Importar CSV',
    ) as HTMLButtonElement;
    fixture.componentInstance.openImport(trigger);
    fixture.detectChanges();
    const file = new File(['question,answer\nPregunta importada,Respuesta importada'], 'knowledge.csv', {
      type: 'text/csv',
    });

    fixture.componentInstance.selectImportFile(file);
    fixture.componentInstance.importStrategy.set('UPSERT');
    fixture.componentInstance.uploadImport();
    fixture.detectChanges();

    expect(api.createKnowledgeImport).toHaveBeenCalledExactlyOnceWith('p1', file, 'UPSERT');
    expect(api.getKnowledgeImport).toHaveBeenCalledExactlyOnceWith('p1', 'job-1', 0, 20);
    expect(fixture.nativeElement.textContent).toContain('Pregunta importada');
  });

  it('shows only allowed import actions, confirms execution, and loads row page twenty at a time', async () => {
    const ready = importDetail({ totalRowElements: 21 });
    const pageTwo = importDetail({ page: 1, rows: [], totalRowElements: 21 });
    const { api, fixture } = await configure({
      getKnowledgeImport: vi.fn().mockReturnValueOnce(of(ready)).mockReturnValueOnce(of(pageTwo)),
      executeKnowledgeImport: vi.fn(() => of(ready)),
    });
    fixture.detectChanges();
    fixture.componentInstance.openImport(fixture.nativeElement.querySelector('button'));
    fixture.componentInstance.importDetail.set(importDetail({ status: 'FAILED', invalidRows: 1 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Reintentar importación');
    expect(fixture.nativeElement.textContent).not.toContain('Ejecutar importación');

    fixture.componentInstance.importDetail.set(ready);
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ejecutar importación')!
      .click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).not.toBeNull();
    fixture.componentInstance.confirmImportAction();
    fixture.detectChanges();

    expect(api.executeKnowledgeImport).toHaveBeenCalledExactlyOnceWith('p1', 'job-1');
    expect(api.getKnowledgeImport).toHaveBeenCalledWith('p1', 'job-1', 0, 20);
    fixture.componentInstance.loadImportDetail(1);
    expect(api.getKnowledgeImport).toHaveBeenLastCalledWith('p1', 'job-1', 1, 20);
  });

  it('clears uploaded file and rows on import failure and cancels a closed import request', async () => {
    const late = new Subject<KnowledgeImportDetail>();
    const { fixture } = await configure({
      createKnowledgeImport: vi.fn(() => throwError(() => ({ status: 500 }))),
      getKnowledgeImport: vi.fn(() => late),
    });
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    fixture.componentInstance.openImport(trigger);
    fixture.componentInstance.selectImportFile(new File(['private'], 'private.csv'));
    fixture.componentInstance.uploadImport();
    fixture.detectChanges();

    expect(fixture.componentInstance.importFile()).toBeNull();
    expect(fixture.componentInstance.importDetail()).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).not.toBe('');

    fixture.componentInstance.importDetail.set(importDetail());
    fixture.componentInstance.loadImportDetail(0);
    fixture.componentInstance.closeImport();
    late.next(importDetail({ rows: [{ ...importDetail().rows[0], question: 'Fila tardía' }] }));
    fixture.detectChanges();
    expect(fixture.componentInstance.importDetail()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Fila tardía');
  });

  it('does not announce or render stale import mutations after a newer upload starts', async () => {
    const pendingMutation = new Subject<KnowledgeImportDetail>();
    const { fixture } = await configure({ executeKnowledgeImport: vi.fn(() => pendingMutation) });
    fixture.detectChanges();
    fixture.componentInstance.openImport(fixture.nativeElement.querySelector('button'));
    fixture.componentInstance.importDetail.set(importDetail());
    fixture.componentInstance.askImportAction('execute');
    fixture.componentInstance.confirmImportAction();
    fixture.componentInstance.selectImportFile(new File(['question,answer'], 'replacement.csv'));
    pendingMutation.next(importDetail({ fileName: 'stale.csv' }));
    fixture.detectChanges();

    expect(fixture.componentInstance.importDetail()).toBeNull();
    expect(fixture.componentInstance.feedback()).not.toContain('Importación enviada a ejecución');
    expect(fixture.nativeElement.textContent).not.toContain('stale.csv');
  });

  it('permits retry only for a failed import without invalid rows and reloads after conflict', async () => {
    const failed = importDetail({ status: 'FAILED', invalidRows: 0 });
    const { api, fixture } = await configure({
      retryKnowledgeImport: vi.fn(() => throwError(() => ({ status: 409 }))),
      getKnowledgeImport: vi.fn(() => of(failed)),
    });
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    fixture.componentInstance.openImport(trigger);
    fixture.componentInstance.importDetail.set(failed);
    fixture.detectChanges();
    fixture.componentInstance.askImportAction('retry');
    fixture.componentInstance.confirmImportAction();
    fixture.detectChanges();

    expect(api.retryKnowledgeImport).toHaveBeenCalledExactlyOnceWith('p1', 'job-1');
    expect(api.getKnowledgeImport).toHaveBeenCalledWith('p1', 'job-1', 0, 20);
    expect(fixture.componentInstance.pendingImportAction()).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('cambió en otra sesión');
  });

  it('moves focus into the import panel and restores it on close', async () => {
    const { fixture } = await configure();
    fixture.detectChanges();
    const trigger = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Importar CSV',
    ) as HTMLButtonElement;
    fixture.componentInstance.openImport(trigger);
    fixture.detectChanges();
    await Promise.resolve();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('#knowledge-import-heading'));
    fixture.componentInstance.closeImport();
    await Promise.resolve();
    expect(document.activeElement).toBe(trigger);
  });

  it('traps confirmation focus, makes the background inert, and returns focus to the import action on cancel', async () => {
    const { fixture } = await configure();
    fixture.detectChanges();
    const importTrigger = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Importar CSV',
    ) as HTMLButtonElement;
    fixture.componentInstance.openImport(importTrigger);
    fixture.componentInstance.importDetail.set(importDetail());
    fixture.detectChanges();
    const execute = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Ejecutar importación',
    ) as HTMLButtonElement;

    execute.click();
    fixture.detectChanges();
    await Promise.resolve();

    const dialog = fixture.nativeElement.querySelector('[role="alertdialog"]') as HTMLElement;
    const confirm = [...dialog.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Confirmar',
    ) as HTMLButtonElement;
    const cancel = [...dialog.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Cancelar',
    ) as HTMLButtonElement;
    expect(fixture.nativeElement.querySelector('.page')?.hasAttribute('inert')).toBe(true);
    expect(document.activeElement).toBe(confirm);

    cancel.focus();
    cancel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(confirm);
    confirm.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(cancel);

    cancel.click();
    fixture.detectChanges();
    await Promise.resolve();
    expect(fixture.nativeElement.querySelector('.page')?.hasAttribute('inert')).toBe(false);
    expect(document.activeElement).toBe(execute);
  });

  it.each([
    ['execute', importDetail(), 'executeKnowledgeImport'],
    ['retry', importDetail({ status: 'FAILED', invalidRows: 0 }), 'retryKnowledgeImport'],
  ] as const)(
    'cancels a pending %s import confirmation on Escape without mutating and restores focus',
    async (action, detail, mutationMethod) => {
      const { api, fixture } = await configure();
      fixture.detectChanges();
      const importTrigger = [...fixture.nativeElement.querySelectorAll('button')].find(
        (button: HTMLButtonElement) => button.textContent?.trim() === 'Importar CSV',
      ) as HTMLButtonElement;
      fixture.componentInstance.openImport(importTrigger);
      fixture.componentInstance.importDetail.set(detail);
      fixture.detectChanges();
      const actionLabel = action === 'execute' ? 'Ejecutar importación' : 'Reintentar importación';
      const actionButton = [...fixture.nativeElement.querySelectorAll('button')].find(
        (button: HTMLButtonElement) => button.textContent?.trim() === actionLabel,
      ) as HTMLButtonElement;

      actionButton.click();
      fixture.detectChanges();
      await Promise.resolve();
      const dialog = fixture.nativeElement.querySelector('[role="alertdialog"]') as HTMLElement;
      dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      fixture.detectChanges();
      await Promise.resolve();

      expect(fixture.nativeElement.querySelector('[role="alertdialog"]')).toBeNull();
      expect(fixture.nativeElement.querySelector('.page')?.hasAttribute('inert')).toBe(false);
      expect(api[mutationMethod]).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(actionButton);
    },
  );

  it('loads the first available project and renders its knowledge status', async () => {
    const { api, fixture } = await configure();
    fixture.detectChanges();

    expect(api.listAllProjects).toHaveBeenCalledTimes(1);
    expect(api.listKnowledge).toHaveBeenCalledExactlyOnceWith('p1', 0, 20, undefined);
    expect(fixture.nativeElement.textContent).toContain('¿Cuál es el horario?');
    expect(fixture.nativeElement.textContent).toContain('Listo');
    expect(
      (fixture.nativeElement.querySelector('#knowledge-search') as HTMLInputElement).placeholder,
    ).toBe('Pregunta o ID externo');
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

  it('opens a focused create editor and submits the complete create contract', async () => {
    const created = entry('new', 'Pregunta nueva', 'PENDING');
    const { api, fixture } = await configure({ createKnowledge: vi.fn(() => of(created)) });
    fixture.detectChanges();

    const create = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Nueva entrada',
    ) as HTMLButtonElement;
    create.click();
    fixture.detectChanges();
    await Promise.resolve();

    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('#knowledge-question'));
    fixture.componentInstance.editorForm.setValue({
      question: 'Pregunta nueva',
      answer: 'Respuesta nueva',
      externalId: ' ext-1 ',
      active: true,
    });
    fixture.componentInstance.saveEditor();
    fixture.detectChanges();

    expect(api.createKnowledge).toHaveBeenCalledWith('p1', {
      question: 'Pregunta nueva',
      answer: 'Respuesta nueva',
      externalId: 'ext-1',
      active: true,
    });
    expect(fixture.componentInstance.editorEntry()).toEqual(created);
    expect(fixture.nativeElement.querySelector('[aria-live="polite"]').textContent).toContain(
      'creada',
    );
  });

  it('views a selected entry, edits it with its CAS version, and restores focus on close', async () => {
    const original = entry('k1', 'Original', 'READY');
    const updated = { ...original, question: 'Editada', answer: 'Respuesta editada', version: 2 };
    const { api, fixture } = await configure({
      getKnowledge: vi.fn(() => of(original)),
      updateKnowledge: vi.fn(() => of(updated)),
    });
    fixture.detectChanges();
    const trigger = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Ver',
    ) as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();

    expect(api.getKnowledge).toHaveBeenCalledWith('p1', 'k1');
    expect(fixture.nativeElement.textContent).toContain('Respuesta');
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Editar')!
      .click();
    fixture.componentInstance.editorForm.setValue({
      question: 'Editada',
      answer: 'Respuesta editada',
      externalId: '',
      active: true,
    });
    fixture.componentInstance.saveEditor();
    fixture.detectChanges();

    expect(api.updateKnowledge).toHaveBeenCalledWith('p1', 'k1', {
      question: 'Editada',
      answer: 'Respuesta editada',
      externalId: null,
      active: true,
      version: 1,
    });
    fixture.componentInstance.closeEditor();
    await Promise.resolve();
    expect(document.activeElement).toBe(trigger);
  });

  it('updates active state through the full CAS update contract', async () => {
    const original = entry('k1', 'Original', 'READY');
    const { api, fixture } = await configure({ getKnowledge: vi.fn(() => of(original)) });
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver')!
      .click();
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Desactivar')!
      .click();

    expect(api.updateKnowledge).toHaveBeenCalledWith('p1', 'k1', {
      question: 'Original',
      answer: 'Respuesta',
      externalId: null,
      active: false,
      version: 1,
    });
  });

  it('shows the embedding retry affordance only for FAILED entries and sends the CAS version', async () => {
    const failed = entry('failed', 'Fallida', 'FAILED');
    const { api, fixture } = await configure({
      getKnowledge: vi
        .fn()
        .mockReturnValueOnce(of(failed))
        .mockReturnValueOnce(of(entry('k1', 'Lista', 'READY'))),
    });
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver')!
      .click();
    fixture.detectChanges();

    const retry = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: HTMLButtonElement) => button.textContent?.trim() === 'Reintentar embedding',
    ) as HTMLButtonElement;
    expect(retry).toBeTruthy();
    retry.click();
    expect(api.retryKnowledgeEmbedding).toHaveBeenCalledWith('p1', 'failed', 1);

    fixture.componentInstance.closeEditor();
    fixture.componentInstance.openEntry('k1', fixture.nativeElement.querySelector('button'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Reintentar embedding');
  });

  it('clears stale editor data and announces a conflict or failure', async () => {
    const original = entry('k1', 'Original', 'READY');
    const { fixture } = await configure({
      getKnowledge: vi.fn(() => of(original)),
      updateKnowledge: vi.fn(() => throwError(() => ({ status: 409 }))),
    });
    fixture.detectChanges();
    ([...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[])
      .find((button) => button.textContent?.trim() === 'Ver')!
      .click();
    fixture.detectChanges();
    fixture.componentInstance.editEntry();
    fixture.componentInstance.saveEditor();
    fixture.detectChanges();

    expect(fixture.componentInstance.editorEntry()).toBeNull();
    expect(fixture.componentInstance.editorError()).toContain('cambió en otra sesión');
    const assertiveAnnouncements = fixture.nativeElement.querySelectorAll(
      '[role="alert"], [aria-live="assertive"]',
    );
    expect(assertiveAnnouncements).toHaveLength(1);
    expect(assertiveAnnouncements[0].textContent).toContain('cambió en otra sesión');
  });

  it('clears sensitive create values and announces a non-conflict save failure', async () => {
    const { fixture } = await configure({
      createKnowledge: vi.fn(() => throwError(() => ({ status: 500 }))),
    });
    fixture.detectChanges();
    fixture.componentInstance.openCreate(fixture.nativeElement.querySelector('button'));
    fixture.componentInstance.editorForm.setValue({
      question: 'No conservar',
      answer: 'Respuesta privada',
      externalId: 'secreto',
      active: true,
    });
    fixture.componentInstance.saveEditor();
    fixture.detectChanges();

    expect(fixture.componentInstance.editorEntry()).toBeNull();
    expect(fixture.componentInstance.editorForm.getRawValue()).toEqual({
      question: '',
      answer: '',
      externalId: '',
      active: true,
    });
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).not.toBe('');
  });

  it('closes an abandoned create editor instead of exposing an invalid edit state', async () => {
    const { fixture } = await configure();
    fixture.detectChanges();
    fixture.componentInstance.openCreate(fixture.nativeElement.querySelector('button'));
    fixture.componentInstance.cancelEditorEdit();
    fixture.detectChanges();

    expect(fixture.componentInstance.editorMode()).toBeNull();
    expect(fixture.nativeElement.querySelector('#knowledge-question')).toBeNull();
  });

  it('cancels a superseded detail request so its late response cannot repopulate the editor', async () => {
    const first = new Subject<KnowledgeEntry>();
    const second = new Subject<KnowledgeEntry>();
    const { fixture } = await configure({
      getKnowledge: vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second),
    });
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    fixture.componentInstance.openEntry('old', trigger);
    fixture.componentInstance.openEntry('new', trigger);
    first.next(entry('old', 'Secreto tardío', 'READY'));
    second.next(entry('new', 'Actual', 'READY'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Actual');
    expect(fixture.nativeElement.textContent).not.toContain('Secreto tardío');
  });

  it('discards a late result when a newer project selection supersedes it', async () => {
    const first = new Subject<PageResponse<KnowledgeEntry>>();
    const { api, fixture } = await configure({
      listAllProjects: vi.fn(() =>
        of([project('p1', 'Proyecto uno'), project('p2', 'Proyecto dos')]),
      ),
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

  it('does not render a late import detail after the selected project changes', async () => {
    const lateDetail = new Subject<KnowledgeImportDetail>();
    const { fixture } = await configure({
      listAllProjects: vi.fn(() => of([project('p1', 'Proyecto uno'), project('p2', 'Proyecto dos')])),
      getKnowledgeImport: vi.fn(() => lateDetail),
    });
    fixture.detectChanges();
    fixture.componentInstance.openImport(fixture.nativeElement.querySelector('button'));
    fixture.componentInstance.importDetail.set(importDetail());
    fixture.componentInstance.loadImportDetail(0);
    fixture.componentInstance.selectProject('p2');
    lateDetail.next(importDetail({ fileName: 'project-one-private.csv' }));
    fixture.detectChanges();

    expect(fixture.componentInstance.importOpen()).toBe(false);
    expect(fixture.componentInstance.importDetail()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('project-one-private.csv');
  });
});
