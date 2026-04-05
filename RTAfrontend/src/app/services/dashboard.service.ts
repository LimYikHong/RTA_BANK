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
}
