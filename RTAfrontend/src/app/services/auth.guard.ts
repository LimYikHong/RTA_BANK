import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { inject } from '@angular/core';

/**
 * authGuard
 * - Protects routes by checking if a merchant profile exists in localStorage.
 * - If authenticated -> allow navigation (return true).
 * - If not -> return a UrlTree that redirects to /login (preferred over imperative navigate()).
 */
export const authGuard: CanActivateFn = (route, state): boolean | UrlTree => {
  const router = inject(Router); // Properly inject the Router in a functional guard

  // Read auth state (simple demo: presence of 'merchant' in localStorage)
  // In real apps, prefer a dedicated AuthService and token/expiry checks.
  let hasUser = false;
  try {
    hasUser = !!localStorage.getItem('merchant');
  } catch {
    hasUser = false;
  }

  if (hasUser) {
    return true; 
  }

  return router.createUrlTree(['/login'], {
    queryParams: { returnUrl: state.url }
  });
};
