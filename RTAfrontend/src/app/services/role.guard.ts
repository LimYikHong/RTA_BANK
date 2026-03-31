import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { inject } from '@angular/core';

/**
 * superAdminGuard
 * - Protects routes that only SUPER_ADMIN can access.
 * - If user is SUPER_ADMIN -> allow navigation.
 * - If user is ADMIN or not logged in -> redirect to previous page or batch-list.
 */
export const superAdminGuard: CanActivateFn = (route, state): boolean | UrlTree => {
  const router = inject(Router);

  let role: string | null = null;
  try {
    const userData = localStorage.getItem('user');
    if (userData) {
      const user = JSON.parse(userData);
      role = user?.role ?? null;
    }
  } catch {
    role = null;
  }

  if (role === 'SUPER_ADMIN') {
    return true;
  }

  // Redirect non-SUPER_ADMIN users to batch-list
  return router.createUrlTree(['/batch-list']);
};
