import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { EMPTY, expand, map, Observable, reduce } from 'rxjs';
import {
  AdminRole,
  AdminUser,
  CreateUserRequest,
  KnowledgeEntry,
  PageResponse,
  Project,
  ProjectIdsResponse,
  ProjectSiteKey,
} from './models';

const MAX_PROJECT_PAGES = 1000;

@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly http = inject(HttpClient);

  listProjects(page = 0, size = 20): Observable<PageResponse<Project>> {
    return this.http.get<PageResponse<Project>>('/api/admin/projects', {
      params: this.pageParams(page, size),
    });
  }

  listAllProjects(): Observable<readonly Project[]> {
    return this.listProjects(0, 100).pipe(
      map((response) => {
        if (response.page !== 0) throw new Error('Inconsistent project pagination');
        let totalPages: number;
        if (
          response.totalPages === 0 &&
          response.items.length === 0 &&
          response.totalElements === 0
        ) {
          totalPages = 1;
        } else if (Number.isSafeInteger(response.totalPages) && response.totalPages > 0) {
          totalPages = response.totalPages;
        } else {
          throw new Error('Inconsistent project pagination');
        }
        if (totalPages > MAX_PROJECT_PAGES) throw new Error('Project pagination limit exceeded');
        return { response, page: 0, totalPages };
      }),
      expand((state) => {
        const nextPage = state.page + 1;
        if (nextPage >= state.totalPages) return EMPTY;
        return this.listProjects(nextPage, 100).pipe(
          map((response) => {
            if (response.page !== nextPage || response.totalPages !== state.totalPages) {
              throw new Error('Inconsistent project pagination');
            }
            return { response, page: nextPage, totalPages: state.totalPages };
          }),
        );
      }),
      map(({ response }) => response.items),
      reduce((projects, items) => [...projects, ...items], [] as Project[]),
    );
  }

  getProject(id: string): Observable<Project> {
    return this.http.get<Project>(`/api/admin/projects/${id}`);
  }

  createProject(name: string): Observable<Project> {
    return this.http.post<Project>('/api/admin/projects', { name });
  }

  renameProject(id: string, name: string): Observable<Project> {
    return this.http.put<Project>(`/api/admin/projects/${id}`, { name });
  }

  setProjectActive(id: string, active: boolean): Observable<void> {
    return this.http.post<void>(
      `/api/admin/projects/${id}/${active ? 'activate' : 'deactivate'}`,
      {},
    );
  }

  listKnowledge(projectId: string, page = 0, size = 20, query?: string): Observable<PageResponse<KnowledgeEntry>> {
    let params = this.pageParams(page, size);
    if (query !== undefined && query !== '') params = params.set('q', query);
    return this.http.get<PageResponse<KnowledgeEntry>>(
      `/api/admin/projects/${encodeURIComponent(projectId)}/knowledge`, { params },
    );
  }

  retryKnowledgeEmbedding(projectId: string, entryId: string, version: number): Observable<KnowledgeEntry> {
    return this.http.post<KnowledgeEntry>(
      `/api/admin/projects/${encodeURIComponent(projectId)}/knowledge/${encodeURIComponent(entryId)}/embedding-retry`,
      { version },
    );
  }

  getProjectSiteKey(projectId: string): Observable<ProjectSiteKey> {
    return this.http.get<ProjectSiteKey>(`/api/admin/projects/${encodeURIComponent(projectId)}/site-key`);
  }

  rotateProjectSiteKey(projectId: string, expectedVersion: number): Observable<ProjectSiteKey> {
    return this.http.post<ProjectSiteKey>(
      `/api/admin/projects/${encodeURIComponent(projectId)}/site-key/rotate`,
      { expectedVersion },
    );
  }

  listUsers(page = 0, size = 20): Observable<PageResponse<AdminUser>> {
    return this.http.get<PageResponse<AdminUser>>('/api/admin/users', {
      params: this.pageParams(page, size),
    });
  }

  createUser(email: string, temporaryPassword: string, role: AdminRole): Observable<AdminUser> {
    const body: CreateUserRequest = { email, temporaryPassword, role };
    return this.http.post<AdminUser>('/api/admin/users', body);
  }

  updateUserEmail(id: string, email: string): Observable<AdminUser> {
    return this.http.put<AdminUser>(`/api/admin/users/${id}`, { email });
  }

  setUserActive(id: string, active: boolean): Observable<void> {
    return this.http.post<void>(`/api/admin/users/${id}/${active ? 'activate' : 'deactivate'}`, {});
  }

  resetUserPassword(id: string, temporaryPassword: string): Observable<void> {
    return this.http.post<void>(`/api/admin/users/${id}/reset-password`, { temporaryPassword });
  }

  getUserProjectIds(userId: string): Observable<ProjectIdsResponse> {
    return this.http.get<ProjectIdsResponse>(`/api/admin/users/${userId}/projects`);
  }

  replaceUserProjectIds(userId: string, ids: readonly string[]): Observable<ProjectIdsResponse> {
    return this.http.put<ProjectIdsResponse>(`/api/admin/users/${userId}/projects`, {
      projectIds: ids,
    });
  }

  private pageParams(page: number, size: number): HttpParams {
    return new HttpParams().set('page', page).set('size', size);
  }
}
