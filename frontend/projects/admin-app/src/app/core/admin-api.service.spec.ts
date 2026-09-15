import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminApiService } from './admin-api.service';
import { Project } from './models';

describe('AdminApiService contracts', () => {
  let api: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the exact project controller contracts', () => {
    api.listProjects(2, 10).subscribe();
    const list = http.expectOne(
      (request) =>
        request.url === '/api/admin/projects' &&
        request.params.get('page') === '2' &&
        request.params.get('size') === '10',
    );
    expect(list.request.method).toBe('GET');

    api.getProject('p1').subscribe();
    expect(http.expectOne('/api/admin/projects/p1').request.method).toBe('GET');

    api.createProject('Alpha').subscribe();
    expect(http.expectOne('/api/admin/projects').request.body).toEqual({ name: 'Alpha' });

    api.renameProject('p1', 'Beta').subscribe();
    expect(http.expectOne('/api/admin/projects/p1').request.method).toBe('PUT');

    api.setProjectActive('p1', false).subscribe();
    expect(http.expectOne('/api/admin/projects/p1/deactivate').request.method).toBe('POST');
  });

  it('uses exact knowledge editor contracts', () => {
    api.listKnowledge('project id', 2, 10, 'hours').subscribe();
    const list = http.expectOne(
      (request) =>
        request.url === '/api/admin/projects/project%20id/knowledge' &&
        request.params.get('page') === '2' &&
        request.params.get('size') === '10' &&
        request.params.get('q') === 'hours',
    );
    expect(list.request.method).toBe('GET');

    api.getKnowledge('project id', 'entry id').subscribe();
    expect(
      http.expectOne('/api/admin/projects/project%20id/knowledge/entry%20id').request.method,
    ).toBe('GET');

    api
      .createKnowledge('project id', {
        question: 'Pregunta',
        answer: 'Respuesta',
        externalId: 'external-1',
        active: true,
      })
      .subscribe();
    const create = http.expectOne('/api/admin/projects/project%20id/knowledge');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({
      question: 'Pregunta',
      answer: 'Respuesta',
      externalId: 'external-1',
      active: true,
    });

    api
      .updateKnowledge('project id', 'entry id', {
        question: 'Editada',
        answer: 'Nueva respuesta',
        externalId: null,
        active: false,
        version: 7,
      })
      .subscribe();
    const update = http.expectOne('/api/admin/projects/project%20id/knowledge/entry%20id');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      question: 'Editada',
      answer: 'Nueva respuesta',
      externalId: null,
      active: false,
      version: 7,
    });

    api.retryKnowledgeEmbedding('project id', 'entry id', 7).subscribe();
    const retry = http.expectOne(
      '/api/admin/projects/project%20id/knowledge/entry%20id/embedding-retry',
    );
    expect(retry.request.method).toBe('POST');
    expect(retry.request.body).toEqual({ version: 7 });
  });

  it('uses exact user-project assignment URLs, verbs and body', () => {
    api.getUserProjectIds('u1').subscribe();
    const get = http.expectOne('/api/admin/users/u1/projects');
    expect(get.request.method).toBe('GET');
    expect(get.request.body).toBeNull();

    api.replaceUserProjectIds('u1', ['p2', 'p1']).subscribe();
    const put = http.expectOne('/api/admin/users/u1/projects');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ projectIds: ['p2', 'p1'] });
  });

  it('uses exact siteKey read and rotation contracts', () => {
    api.getProjectSiteKey('p 1').subscribe();
    const get = http.expectOne('/api/admin/projects/p%201/site-key');
    expect(get.request.method).toBe('GET');
    expect(get.request.body).toBeNull();

    api.rotateProjectSiteKey('p 1', 7).subscribe();
    const post = http.expectOne('/api/admin/projects/p%201/site-key/rotate');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ expectedVersion: 7 });
  });

  it('lists every project page serially with size 100 and stable item order', () => {
    let result: readonly Project[] | undefined;
    api.listAllProjects().subscribe((projects) => (result = projects));

    const first = http.expectOne(
      (request) => request.url === '/api/admin/projects' && request.params.get('page') === '0',
    );
    expect(first.request.params.get('size')).toBe('100');
    first.flush({ items: [project('p1')], page: 0, size: 100, totalElements: 3, totalPages: 3 });

    const second = http.expectOne(
      (request) => request.url === '/api/admin/projects' && request.params.get('page') === '1',
    );
    second.flush({ items: [project('p2')], page: 1, size: 100, totalElements: 3, totalPages: 3 });

    const third = http.expectOne(
      (request) => request.url === '/api/admin/projects' && request.params.get('page') === '2',
    );
    third.flush({ items: [project('p3')], page: 2, size: 100, totalElements: 3, totalPages: 3 });

    expect(result?.map(({ id }) => id)).toEqual(['p1', 'p2', 'p3']);
    http.expectNone((request) => request.url === '/api/admin/projects');
  });

  it('rejects changed or repeated pagination metadata before accumulating it', () => {
    let result: readonly Project[] | undefined;
    let failed = false;
    api.listAllProjects().subscribe({
      next: (projects) => (result = projects),
      error: () => (failed = true),
    });

    http
      .expectOne((request) => request.params.get('page') === '0')
      .flush({ items: [project('p1')], page: 0, size: 100, totalElements: 2, totalPages: 2 });
    http
      .expectOne((request) => request.params.get('page') === '1')
      .flush({
        items: [project('duplicate')],
        page: 0,
        size: 100,
        totalElements: 3,
        totalPages: 3,
      });

    expect(failed).toBe(true);
    expect(result).toBeUndefined();
    http.expectNone((request) => request.url === '/api/admin/projects');
  });

  it('accepts an empty first page with totalPages zero as terminal', () => {
    let result: readonly Project[] | undefined;
    api.listAllProjects().subscribe((projects) => (result = projects));
    http
      .expectOne((request) => request.params.get('page') === '0')
      .flush({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
    expect(result).toEqual([]);
    http.expectNone((request) => request.url === '/api/admin/projects');
  });

  it.each([0, Number.NaN, null, -1, 1.5])(
    'rejects incoherent totalPages %s without exposing first-page items',
    (totalPages) => {
      let result: readonly Project[] | undefined;
      let failed = false;
      api.listAllProjects().subscribe({
        next: (projects) => (result = projects),
        error: () => (failed = true),
      });
      http
        .expectOne((request) => request.params.get('page') === '0')
        .flush({ items: [project('hidden')], page: 0, size: 100, totalElements: 1, totalPages });
      expect(failed).toBe(true);
      expect(result).toBeUndefined();
      http.expectNone((request) => request.url === '/api/admin/projects');
    },
  );

  it('rejects a mismatched first page without exposing its items', () => {
    let result: readonly Project[] | undefined;
    let failed = false;
    api.listAllProjects().subscribe({
      next: (projects) => (result = projects),
      error: () => (failed = true),
    });
    http
      .expectOne((request) => request.params.get('page') === '0')
      .flush({ items: [project('wrong')], page: 4, size: 100, totalElements: 1, totalPages: 2 });
    expect(failed).toBe(true);
    expect(result).toBeUndefined();
  });

  it('sends temporary passwords only in create and reset bodies', () => {
    api.createUser('a@b.co', 'temporary', 'PROJECT_ADMIN').subscribe();
    expect(http.expectOne('/api/admin/users').request.body).toEqual({
      email: 'a@b.co',
      temporaryPassword: 'temporary',
      role: 'PROJECT_ADMIN',
    });

    api.updateUserEmail('u1', 'new@b.co').subscribe();
    expect(http.expectOne('/api/admin/users/u1').request.body).toEqual({ email: 'new@b.co' });

    api.resetUserPassword('u1', 'other-temp').subscribe();
    expect(http.expectOne('/api/admin/users/u1/reset-password').request.body).toEqual({
      temporaryPassword: 'other-temp',
    });
  });
});

function project(id: string): Project {
  return {
    id,
    name: id,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}
