import { HttpInterceptorFn } from '@angular/common/http';

/**
 * JWT Interceptor (functional)
 * - Attaches the JWT token from localStorage to every outgoing HTTP request.
 * - Skips /api/auth/** endpoints (login/2FA don't need a token).
 */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  // Don't attach token to auth endpoints
  if (req.url.includes('/api/auth/')) {
    return next(req);
  }

  const userData = localStorage.getItem('user');
  if (userData) {
    try {
      const user = JSON.parse(userData);
      if (user?.token) {
        req = req.clone({
          setHeaders: {
            Authorization: `Bearer ${user.token}`
          }
        });
      }
    } catch {
      // ignore parse errors
    }
  }

  return next(req);
};
