import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AuditLogService, AuditLogEntry } from './audit-log.service';

//audit-log.service.ts
describe('AuditLogService', () => {
  let service: AuditLogService;
  let httpMock: HttpTestingController;

  const mockUserLogs: AuditLogEntry[] = [
    { logId: 1, logType: 'USER', action: 'LOGIN', userId: 'A001', targetId: 'A001', description: 'User logged in', status: 'SUCCESS', ipAddress: '127.0.0.1', createdAt: '2026-04-26T10:00:00' },
    { logId: 2, logType: 'USER', action: 'CREATE_USER', userId: 'A001', targetId: 'A005', description: 'Created user A005', status: 'SUCCESS', ipAddress: '127.0.0.1', createdAt: '2026-04-26T10:05:00' },
  ];

  const mockSystemLogs: AuditLogEntry[] = [
    { logId: 3, logType: 'SYSTEM', action: 'RUN_BATCH', userId: null, targetId: '100', description: 'Batch created', status: 'SUCCESS', ipAddress: null, createdAt: '2026-04-26T10:10:00' },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuditLogService]
    });
    service = TestBed.inject(AuditLogService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('should fetch user activity logs', () => {
    service.getUserActivityLogs().subscribe(logs => {
      expect(logs.length).toBe(2);
      expect(logs[0].action).toBe('LOGIN');
      expect(logs[1].action).toBe('CREATE_USER');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/audit-logs/user');
    expect(req.request.method).toBe('GET');
    req.flush(mockUserLogs);
  });

  it('should fetch system activity logs', () => {
    service.getSystemActivityLogs().subscribe(logs => {
      expect(logs.length).toBe(1);
      expect(logs[0].logType).toBe('SYSTEM');
      expect(logs[0].userId).toBeNull();
    });

    const req = httpMock.expectOne('https://localhost:8086/api/audit-logs/system');
    req.flush(mockSystemLogs);
  });

  it('should fetch all logs', () => {
    const allLogs = [...mockUserLogs, ...mockSystemLogs];

    service.getAllLogs().subscribe(logs => {
      expect(logs.length).toBe(3);
    });

    const req = httpMock.expectOne('https://localhost:8086/api/audit-logs');
    req.flush(allLogs);
  });
});
