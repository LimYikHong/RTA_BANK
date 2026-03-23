import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

interface BatchSummary {
  batchFileId: number;
  batchId: number | null;
  fileName: string;
  merchantId: string;
  status: string;
  totalRecords: number;
  successCount: number;
  failCount: number;
  totalAmount: number;
  createdAt: string;
  createdBy: string;
  validationRemark: string | null;
}

interface TransactionRecord {
  transactionId: number;
  batchSeq: number;
  merchantId: string;
  customerReference: string;
  accountNum: string;
  bankCode: string;
  amount: number;
  currency: string;
  transactionDate: string;
  recurringType: string;
  description: string;
  status: string;
  remark: string;
  createdAt: string;
}

@Component({
  selector: 'app-batch-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, TopBarComponent],
  templateUrl: './batch-detail.component.html',
  styleUrl: './batch-detail.component.scss'
})
export class BatchDetailComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/incoming';

  batchFileId!: number;
  summary: BatchSummary | null = null;
  failedTransactions: TransactionRecord[] = [];
  isLoading = true;
  activeTab: 'summary' | 'failed' = 'summary';

  sortKey: string = '';
  sortDir: 'asc' | 'desc' = 'asc';

  sortBy(key: string): void {
    if (this.sortKey === key) {
      if (this.sortDir === 'asc') {
        this.sortDir = 'desc';
      } else {
        this.sortKey = '';
        this.sortDir = 'asc';
      }
    } else {
      this.sortKey = key;
      this.sortDir = 'asc';
    }
  }

  get sortedTransactions(): TransactionRecord[] {
    if (!this.sortKey) return this.failedTransactions;
    return [...this.failedTransactions].sort((a, b) => {
      const av = (a as any)[this.sortKey] ?? '';
      const bv = (b as any)[this.sortKey] ?? '';
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return this.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  constructor(
    private http: HttpClient,
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.batchFileId = Number(this.route.snapshot.paramMap.get('batchFileId'));
    this.loadBatchSummary();
    this.loadFailedTransactions();
  }

  loadBatchSummary(): void {
    this.isLoading = true;
    this.http.get<BatchSummary>(`${this.apiUrl}/file-summary/${this.batchFileId}`).subscribe({
      next: (data) => {
        this.summary = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load file summary:', err);
        this.isLoading = false;
      }
    });
  }

  loadFailedTransactions(): void {
    this.http.get<TransactionRecord[]>(`${this.apiUrl}/file-transactions/${this.batchFileId}?status=FAILED`).subscribe({
      next: (data) => {
        this.failedTransactions = data;
      },
      error: (err) => {
        console.error('Failed to load failed transactions:', err);
      }
    });
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'VALIDATED': return 'status-validated';
      case 'PARTIAL': return 'status-partial';
      case 'VALIDATION_FAILED': return 'status-failed';
      case 'VALIDATION_ERROR': return 'status-error';
      case 'RECEIVED': return 'status-received';
      case 'SUCCESS': return 'status-success';
      case 'FAILED': return 'status-failed';
      default: return '';
    }
  }

  getSuccessRate(): number {
    if (!this.summary || this.summary.totalRecords === 0) return 0;
    return Math.round((this.summary.successCount / this.summary.totalRecords) * 100);
  }

  goBack(): void {
    this.router.navigate(['/incoming-batch']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
