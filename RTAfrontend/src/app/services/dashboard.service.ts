import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface TrendPoint {
  day: string;
  success: number;
  failed: number;
}

export interface MerchantCount {
  merchantId: string;
  count: number;
}

export interface RecentActivity {
  action: string;
  description: string | null;
  userId: string | null;
  status: string | null;
  createdAt: string | null;
}

export interface AmountPoint {
  day: string;
  amount: number;
}

export interface DashboardStats {
  totalBatches: number;
  totalIncomingFiles: number;
  totalTransactions: number;
  activeMerchants: number;
  adminUsers: number;
  transactionStatusBreakdown: Record<string, number>;
  incomingFileStatusBreakdown: Record<string, number>;
  transactionTrend: TrendPoint[];
  incomingFilesPerMerchant: MerchantCount[];
  txnPerMerchant: MerchantCount[];
  recurringBreakdown: Record<string, number>;
  dailyAmountTrend: AmountPoint[];
  recentActivity: RecentActivity[];
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private apiUrl = 'https://localhost:8086/api/dashboard';

  constructor(private http: HttpClient) {}

  getStats(): Observable<DashboardStats> {
    return this.http.get<DashboardStats>(`${this.apiUrl}/stats`);
  }

  getRsaKeyStatus(): Observable<RsaKeyStatus> {
    return this.http.get<RsaKeyStatus>(`${this.apiUrl}/rsa-key-status`);
  }

  requestRsaKey(): Observable<RsaKeyResponse> {
    return this.http.post<RsaKeyResponse>(`${this.apiUrl}/request-rsa-key`, {});
  }

  getMerchantKeyOverview(): Observable<MerchantKeyOverview[]> {
    return this.http.get<MerchantKeyOverview[]>(`${this.apiUrl}/merchant-key-overview`);
  }
}

export interface RsaKeyStatus {
  hasKey: boolean;
  requestedAt?: string;
  expiresAt?: string;
  daysRemaining: number;
  daysElapsed?: number;
  canRequest: boolean;
  needsRenewal: boolean;
  expired: boolean;
}

export interface RsaKeyResponse {
  success: boolean;
  message: string;
  expiresAt?: string;
}

export interface MerchantKeyOverview {
  merchantId: string;
  merchantName: string;
  hasKey: boolean;
  keyVersion: number;
  keyStatus: string;
  activatedAt: string | null;
  expiresAt: string | null;
  daysElapsed: number;
  daysRemaining: number;
  needsRotation: boolean;
  canRotate: boolean;
  expired: boolean;
}
