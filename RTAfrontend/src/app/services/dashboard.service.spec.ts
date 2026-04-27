import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { DashboardService, DashboardStats } from './dashboard.service';

describe('DashboardService', () => {
  let service: DashboardService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DashboardService]
    });
    service = TestBed.inject(DashboardService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ──────────────── getStats() ────────────────

  it('should GET dashboard stats from correct URL', () => {
    const mockStats: Partial<DashboardStats> = {
      totalBatches: 10,
      totalIncomingFiles: 50,
      totalTransactions: 5000,
      activeMerchants: 5,
      adminUsers: 3,
      avgProcessingTimeSeconds: 2.5,
      processedBatchCount: 8,
      transactionStatusBreakdown: { APPROVED: 4500, FAILED: 500 },
      incomingFileStatusBreakdown: { VALIDATED: 45, FAILED: 5 },
      recurringBreakdown: { RECURRING: 3000, NON_RECURRING: 2000 },
      authStatusBreakdown: { PROCESSED: 8, SEND_FAILED: 2 }
    };

    service.getStats().subscribe(stats => {
      expect(stats.totalBatches).toBe(10);
      expect(stats.totalTransactions).toBe(5000);
      expect(stats.activeMerchants).toBe(5);
      expect(stats.transactionStatusBreakdown['APPROVED']).toBe(4500);
    });

    const req = httpMock.expectOne('https://localhost:8086/api/dashboard/stats');
    expect(req.request.method).toBe('GET');
    req.flush(mockStats);
  });

  it('should propagate HTTP error on getStats failure', () => {
    service.getStats().subscribe({
      next: () => fail('Should have errored'),
      error: (err) => {
        expect(err.status).toBe(500);
      }
    });

    const req = httpMock.expectOne('https://localhost:8086/api/dashboard/stats');
    req.flush('Internal Server Error', { status: 500, statusText: 'Server Error' });
  });

  // ──────────────── getRsaKeyStatus() ────────────────

  it('should GET RSA key status', () => {
    const mockStatus = {
      hasKey: true,
      daysRemaining: 120,
      canRequest: false,
      needsRenewal: false,
      expired: false
    };

    service.getRsaKeyStatus().subscribe(status => {
      expect(status.hasKey).toBeTrue();
      expect(status.daysRemaining).toBe(120);
      expect(status.expired).toBeFalse();
    });

    const req = httpMock.expectOne('https://localhost:8086/api/dashboard/rsa-key-status');
    expect(req.request.method).toBe('GET');
    req.flush(mockStatus);
  });

  // ──────────────── requestRsaKey() ────────────────

  it('should POST to request a new RSA key', () => {
    const mockResponse = { success: true, message: 'Key generated', expiresAt: '2027-04-26' };

    service.requestRsaKey().subscribe(res => {
      expect(res.success).toBeTrue();
      expect(res.message).toBe('Key generated');
    });

    const req = httpMock.expectOne('https://localhost:8086/api/dashboard/request-rsa-key');
    expect(req.request.method).toBe('POST');
    req.flush(mockResponse);
  });

  // ──────────────── getMerchantKeyOverview() ────────────────

  it('should GET merchant key overview list', () => {
    const mockOverview = [
      { merchantId: 'M001', merchantName: 'Test', hasKey: true, keyStatus: 'ACTIVE', needsRotation: false },
      { merchantId: 'M002', merchantName: 'Test2', hasKey: false, keyStatus: 'No Key', needsRotation: true }
    ];

    service.getMerchantKeyOverview().subscribe(overview => {
      expect(overview.length).toBe(2);
      expect(overview[0].keyStatus).toBe('ACTIVE');
      expect(overview[1].needsRotation).toBeTrue();
    });

    const req = httpMock.expectOne('https://localhost:8086/api/dashboard/merchant-key-overview');
    expect(req.request.method).toBe('GET');
    req.flush(mockOverview);
  });
});
