import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ProfileService, UserProfile } from './profile.service';

//profile.service.spec.ts
describe('ProfileService', () => {
  let service: ProfileService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProfileService]
    });
    service = TestBed.inject(ProfileService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  // ──────────────── fetchProfile() ────────────────

  it('should GET profile and cache it', () => {
    const mockProfile: Partial<UserProfile> = {
      userId: 'A001', username: 'admin', email: 'admin@rta.com',
      emailAddress: 'admin@rta.com', company: 'RTA', contact: '123', address: 'Test'
    };

    service.fetchProfile('A001').subscribe(profile => {
      expect(profile.userId).toBe('A001');
      expect(profile.username).toBe('admin');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/A001');
    expect(req.request.method).toBe('GET');
    req.flush(mockProfile);

    // Should be cached
    const cached = service.getProfile();
    expect(cached.userId).toBe('A001');
  });

  it('should return empty profile on fetch error', () => {
    service.fetchProfile('INVALID').subscribe(profile => {
      expect(profile.userId).toBe('');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/INVALID');
    req.flush('Not Found', { status: 404, statusText: 'Not Found' });
  });

  // ──────────────── updateProfile() ────────────────

  it('should PUT updated profile', () => {
    const updated: Partial<UserProfile> = {
      userId: 'A001', username: 'admin', company: 'Updated Corp',
      email: 'a@b.com', emailAddress: 'a@b.com', contact: '999', address: 'New'
    };

    service.updateProfile('A001', updated as UserProfile).subscribe(profile => {
      expect(profile.company).toBe('Updated Corp');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/A001');
    expect(req.request.method).toBe('PUT');
    req.flush(updated);
  });

  // ──────────────── createUser() ────────────────

  it('should POST new user with role', () => {
    const newUser: Partial<UserProfile> = {
      userId: 'A005', username: 'newuser',
      email: 'new@rta.com', emailAddress: 'new@rta.com',
      company: 'Test', contact: '123', address: 'Test'
    };

    service.createUser(newUser as UserProfile, 'ADMIN').subscribe(res => {
      expect(res.userId).toBe('A005');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/users?role=ADMIN');
    expect(req.request.method).toBe('POST');
    req.flush(newUser);
  });

  // ──────────────── getAllUsers() ────────────────

  it('should GET all users', () => {
    const mockUsers = [
      { id: 1, username: 'admin', userId: 'A001', role: 'SUPER_ADMIN', status: 'ACTIVE' },
      { id: 2, username: 'user1', userId: 'A002', role: 'ADMIN', status: 'ACTIVE' },
    ];

    service.getAllUsers().subscribe(users => {
      expect(users.length).toBe(2);
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/users');
    req.flush(mockUsers);
  });

  it('should return empty array on getAllUsers error', () => {
    service.getAllUsers().subscribe(users => {
      expect(users).toEqual([]);
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/users');
    req.flush('Error', { status: 500, statusText: 'Error' });
  });

  // ──────────────── deleteUser() ────────────────

  it('should DELETE user by userId', () => {
    service.deleteUser('A005').subscribe();

    const req = httpMock.expectOne('https://localhost:8086/api/profile/users/A005');
    expect(req.request.method).toBe('DELETE');
    req.flush({ message: 'Deleted' });
  });

  // ──────────────── checkUsername() ────────────────

  it('should check if username exists', () => {
    service.checkUsername('admin').subscribe(res => {
      expect(res.exists).toBeTrue();
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/check-username?username=admin');
    req.flush({ exists: true });
  });

  // ──────────────── getNextAdminId() ────────────────

  it('should return next admin ID', () => {
    service.getNextAdminId().subscribe(res => {
      expect(res.nextId).toBe('A003');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/profile/next-admin-id');
    req.flush({ nextId: 'A003' });
  });

  // ──────────────── Cache management ────────────────

  it('should cache profile in localStorage', () => {
    const profile: Partial<UserProfile> = {
      userId: 'A001', username: 'admin', email: '', emailAddress: '',
      company: '', contact: '', address: ''
    };
    service.setProfile(profile as UserProfile);

    const stored = JSON.parse(localStorage.getItem('userProfile')!);
    expect(stored.userId).toBe('A001');
  });

  it('should read profile from localStorage if not cached', () => {
    localStorage.setItem('userProfile', JSON.stringify({
      userId: 'A002', username: 'user2', email: '', emailAddress: '',
      company: '', contact: '', address: ''
    }));

    service.clearProfile(); // clear in-memory cache
    localStorage.setItem('userProfile', JSON.stringify({
      userId: 'A002', username: 'user2'
    }));

    const profile = service.getProfile();
    expect(profile.userId).toBe('A002');
  });

  it('should return empty profile after clearProfile', () => {
    service.setProfile({ userId: 'A001' } as UserProfile);
    service.clearProfile();
    const profile = service.getProfile();
    expect(profile.userId).toBe('');
  });
});
