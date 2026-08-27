import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { SessionService } from '../core/session.service';

@Component({
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <header class="topbar">
        <a routerLink="/projects" class="brand" aria-label="ChatbotQ administración">
          <span class="brand-mark">Q</span><span>ChatbotQ</span>
        </a>
        <button
          class="menu"
          type="button"
          (click)="menuOpen.set(!menuOpen())"
          [attr.aria-expanded]="menuOpen()"
          aria-controls="main-nav"
        >Menú</button>
      </header>
      <aside [class.open]="menuOpen()">
        <div class="sidebar-brand"><span class="brand-mark">Q</span><strong>ChatbotQ</strong></div>
        <nav id="main-nav" aria-label="Navegación principal">
          @for (item of navigation; track item.label) {
            @if (item.visible()) {
              <a [routerLink]="item.route" routerLinkActive="active" (click)="menuOpen.set(false)">
                {{ item.label }}
              </a>
            }
          }
        </nav>
        <div class="account">
          <span>{{ session.me()?.email }}</span>
          <small>{{ session.me()?.generalAdmin ? 'Administrador general' : 'Administrador de proyecto' }}</small>
          <button class="ghost" (click)="logout()" [disabled]="loggingOut()">
            {{ loggingOut() ? 'Cerrando…' : 'Cerrar sesión' }}
          </button>
        </div>
      </aside>
      <main class="content" (click)="menuOpen.set(false)"><router-outlet /></main>
    </div>
  `,
})
export class ShellComponent implements OnInit {
  readonly session = inject(SessionService);
  private readonly router = inject(Router);
  readonly menuOpen = signal(false);
  readonly loggingOut = signal(false);
  readonly navigation = [
    { label: 'Proyectos', route: '/projects', visible: () => true },
    { label: 'Usuarios', route: '/users', visible: () => this.session.me()?.generalAdmin === true },
  ];

  ngOnInit(): void {
    if (!this.session.me()) {
      this.session.loadMe().subscribe({
        error: () => {
          // A 401 is already invalidated and redirected centrally by the interceptor.
          if (this.session.authenticated()) this.logout();
        },
      });
    }
  }

  logout(): void {
    this.loggingOut.set(true);
    this.session
      .logout()
      .pipe(finalize(() => this.loggingOut.set(false)))
      .subscribe(() => void this.router.navigateByUrl('/login'));
  }
}
