import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    // Clear localStorage before each test
    localStorage.clear();

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  // ──────────────── login() ────────────────

  it('should POST credentials to /api/auth/login', () => {
    const mockResponse = { status: '2FA_REQUIRED', username: 'admin' };

    service.login('admin', 'password').subscribe(res => {
      expect(res.status).toBe('2FA_REQUIRED');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'admin', password: 'password' });
    req.flush(mockResponse);
  });

  // ──────────────── verify2fa() ────────────────

  it('should POST 2FA code to /api/auth/verify-2fa', () => {
    const mockResponse = { token: 'jwt-123', userId: 'A001', role: 'SUPER_ADMIN' };

    service.verify2fa('admin', 123456).subscribe(res => {
      expect(res.token).toBe('jwt-123');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/auth/verify-2fa');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'admin', code: 123456 });
    req.flush(mockResponse);
  });

  // ──────────────── logout() ────────────────

  it('should remove user from localStorage on logout', () => {
    localStorage.setItem('user', JSON.stringify({ userId: 'A001', username: 'admin' }));

    service.logout();

    expect(localStorage.getItem('user')).toBeNull();

    // Flush the fire-and-forget logout request
    const req = httpMock.expectOne('https://localhost:8086/api/auth/logout');
    req.flush({});
  });

  // ──────────────── isLoggedIn() ────────────────

  it('should return false when no user in localStorage', () => {
    expect(service.isLoggedIn()).toBeFalse();
  });

  it('should return true when user exists in localStorage', () => {
    localStorage.setItem('user', JSON.stringify({ userId: 'A001' }));
    expect(service.isLoggedIn()).toBeTrue();
  });

  // ──────────────── getCurrentUser() ────────────────

  it('should return null when no user stored', () => {
    expect(service.getCurrentUser()).toBeNull();
  });

  it('should return parsed user from localStorage', () => {
    const user = { userId: 'A001', username: 'admin', role: 'SUPER_ADMIN' };
    localStorage.setItem('user', JSON.stringify(user));

    const result = service.getCurrentUser();

    expect(result).not.toBeNull();
    expect(result!.userId).toBe('A001');
    expect(result!.username).toBe('admin');
  });

  // ──────────────── getUserRole() ────────────────

  it('should return null when no user', () => {
    expect(service.getUserRole()).toBeNull();
  });

  it('should return role from stored user', () => {
    localStorage.setItem('user', JSON.stringify({ role: 'ADMIN' }));
    expect(service.getUserRole()).toBe('ADMIN');
  });

  // ──────────────── isSuperAdmin() ────────────────

  it('should return true for SUPER_ADMIN role', () => {
    localStorage.setItem('user', JSON.stringify({ role: 'SUPER_ADMIN' }));
    expect(service.isSuperAdmin()).toBeTrue();
  });

  it('should return false for ADMIN role', () => {
    localStorage.setItem('user', JSON.stringify({ role: 'ADMIN' }));
    expect(service.isSuperAdmin()).toBeFalse();
  });

  it('should return false when no user', () => {
    expect(service.isSuperAdmin()).toBeFalse();
  });

  // ──────────────── getPermissions() ────────────────

  it('should return empty array when no user', () => {
    expect(service.getPermissions()).toEqual([]);
  });

  it('should return permissions from stored user', () => {
    localStorage.setItem('user', JSON.stringify({
      permissions: ['VIEW_DASHBOARD', 'MANAGE_USERS', 'UPLOAD_FILE']
    }));

    const perms = service.getPermissions();

    expect(perms.length).toBe(3);
    expect(perms).toContain('VIEW_DASHBOARD');
    expect(perms).toContain('MANAGE_USERS');
  });

  // ──────────────── hasPermission() ────────────────

  it('should return true for existing permission', () => {
    localStorage.setItem('user', JSON.stringify({
      permissions: ['VIEW_DASHBOARD', 'MANAGE_USERS']
    }));
    expect(service.hasPermission('VIEW_DASHBOARD')).toBeTrue();
  });

  it('should return false for missing permission', () => {
    localStorage.setItem('user', JSON.stringify({
      permissions: ['VIEW_DASHBOARD']
    }));
    expect(service.hasPermission('DELETE_BATCH')).toBeFalse();
  });

  it('should return false when no user', () => {
    expect(service.hasPermission('VIEW_DASHBOARD')).toBeFalse();
  });
});
