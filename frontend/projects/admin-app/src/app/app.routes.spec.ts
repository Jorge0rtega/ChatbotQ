import { KnowledgeComponent } from './features/knowledge.component';
import { authGuard } from './core/auth.guard';
import { routes } from './app.routes';

describe('admin routes', () => {
  it('protects and lazily loads the knowledge route', async () => {
    const shell = routes.find((route) => route.path === '');
    const knowledge = shell?.children?.find((route) => route.path === 'knowledge');

    expect(shell?.canActivate).toContain(authGuard);
    expect(knowledge?.loadComponent).toBeTypeOf('function');
    await expect(knowledge!.loadComponent!()).resolves.toBe(KnowledgeComponent);
  });
});
