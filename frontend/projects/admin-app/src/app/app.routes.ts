import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { generalAdminGuard } from './core/general-admin.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login.component').then((module) => module.LoginComponent),
  },
  {
    path: 'complete-password-reset',
    loadComponent: () =>
      import('./features/complete-password-reset.component').then(
        (module) => module.CompletePasswordResetComponent,
      ),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell.component').then((module) => module.ShellComponent),
    children: [
      {
        path: 'projects',
        loadComponent: () =>
          import('./features/projects.component').then((module) => module.ProjectsComponent),
      },
      {
        path: 'users',
        canActivate: [generalAdminGuard],
        loadComponent: () =>
          import('./features/users.component').then((module) => module.UsersComponent),
      },
      { path: '', pathMatch: 'full', redirectTo: 'projects' },
    ],
  },
  { path: '**', redirectTo: 'projects' },
];
