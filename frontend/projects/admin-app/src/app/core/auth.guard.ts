import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { safeAdminReturnUrl } from './safe-return-url';
import { SessionService } from './session.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const session = inject(SessionService);
  return (
    session.authenticated() ||
    inject(Router).createUrlTree(['/login'], {
      queryParams: { returnUrl: safeAdminReturnUrl(state.url) },
    })
  );
};
