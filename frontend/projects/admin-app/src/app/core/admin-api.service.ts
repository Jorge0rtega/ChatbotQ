import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { AdminRole, AdminUser, CreateUserRequest, PageResponse, Project } from './models';
@Injectable({providedIn:'root'})
export class AdminApiService {
 private http=inject(HttpClient); private params(page:number,size:number){return new HttpParams().set('page',page).set('size',size);}
 listProjects(page=0,size=20){return this.http.get<PageResponse<Project>>('/api/admin/projects',{params:this.params(page,size)});}
 createProject(name:string){return this.http.post<Project>('/api/admin/projects',{name});}
 renameProject(id:string,name:string){return this.http.put<Project>(`/api/admin/projects/${id}`,{name});}
 setProjectActive(id:string,active:boolean){return this.http.post<void>(`/api/admin/projects/${id}/${active?'activate':'deactivate'}`,{});}
 listUsers(page=0,size=20){return this.http.get<PageResponse<AdminUser>>('/api/admin/users',{params:this.params(page,size)});}
 createUser(email:string,temporaryPassword:string,role:AdminRole){const body:CreateUserRequest={email,temporaryPassword,role};return this.http.post<AdminUser>('/api/admin/users',body);}
 updateUserEmail(id:string,email:string){return this.http.put<AdminUser>(`/api/admin/users/${id}`,{email});}
 setUserActive(id:string,active:boolean){return this.http.post<void>(`/api/admin/users/${id}/${active?'activate':'deactivate'}`,{});}
 resetUserPassword(id:string,temporaryPassword:string){return this.http.post<void>(`/api/admin/users/${id}/reset-password`,{temporaryPassword});}
}
