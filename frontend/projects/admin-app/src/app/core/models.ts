export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export type ProjectStatus = 'ACTIVE' | 'DISABLED';
export type AdminRole = 'GENERAL_ADMIN' | 'PROJECT_ADMIN';
export type AdminUserStatus = 'ACTIVE' | 'DISABLED' | 'PASSWORD_RESET_REQUIRED';

export interface AssignedProjectSummary {
  id: string;
  name: string;
  status: ProjectStatus;
}

export interface MeResponse {
  userId: string;
  email: string;
  generalAdmin: boolean;
  /** Future adapter point; absent until the backend /me contract exposes assignments. */
  projectAssignments?: readonly AssignedProjectSummary[];
}

export function assignedProjectsFromMe(
  me: MeResponse | null,
): readonly AssignedProjectSummary[] | null {
  return me?.projectAssignments ?? null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Project {
  id: string;
  name: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUser {
  id: string;
  email: string;
  role: AdminRole;
  status: AdminUserStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateUserRequest {
  email: string;
  temporaryPassword: string;
  role: AdminRole;
}
