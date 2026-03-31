import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { inject } from '@angular/core';

/**
 * Helper: read permissions array from localStorage user object.
 */
function getUserPermissions(): string[] {
  try {
    const userData = localStorage.getItem('user');
    if (userData) {
      const user = JSON.parse(userData);
      return user?.permissions ?? [];
    }
  } catch {
    // ignore
  }
  return [];
}

/**
 * superAdminGuard (kept for backward compatibility)
 * - Protects routes that only SUPER_ADMIN can access.
 * - Checks if user has any of the admin-level permissions.
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

  return router.createUrlTree(['/batch-list']);
};

/**
 * permissionGuard factory
 * - Creates a guard that checks if user has the required permission.
 * Usage in routes: canActivate: [permissionGuard('USER_CREATE')]
 */
export function permissionGuard(requiredPermission: string): CanActivateFn {
  return (route, state): boolean | UrlTree => {
    const router = inject(Router);
    const permissions = getUserPermissions();

    if (permissions.includes(requiredPermission)) {
      return true;
    }

    return router.createUrlTree(['/batch-list']);
  };
}
