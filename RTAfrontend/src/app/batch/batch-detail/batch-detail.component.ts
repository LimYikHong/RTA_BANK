import { Component, OnInit } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
interface BatchSummary {
  batchFileId: number;
  batchId: number | null;
  authBatchId?: number;
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
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './batch-detail.component.html',
  styleUrl: './batch-detail.component.scss'
})
export class BatchDetailComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/incoming';

  batchFileId!: number;
  summary: BatchSummary | null = null;
  failedTransactions: TransactionRecord[] = [];
  isLoading = true;
  activeTab: 'summary' | 'failed' = 'summary';

  sortKey: string = '';
  sortDir: 'asc' | 'desc' = 'asc';

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];

  get totalPages(): number {
    return Math.ceil(this.failedTransactions.length / this.pageSize);
  }

  get startRecord(): number {
    return this.failedTransactions.length === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1;
  }

  get endRecord(): number {
    return Math.min(this.currentPage * this.pageSize, this.failedTransactions.length);
  }

  get pagedTransactions(): TransactionRecord[] {
    const sorted = this.sortedTransactions;
    const start = (this.currentPage - 1) * this.pageSize;
    return sorted.slice(start, start + this.pageSize);
  }

  get visiblePages(): number[] {
    const pages: number[] = [];
    const total = this.totalPages;
    const current = this.currentPage;
    let start = Math.max(1, current - 2);
    let end = Math.min(total, start + 4);
    start = Math.max(1, end - 4);
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.totalPages) this.currentPage = page;
  }

  onPageSizeChange(): void {
    this.currentPage = 1;
  }

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
    private authService: AuthService,
    private location: Location
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
      case 'VALIDATION_FAILED': case 'DUPLICATE_TRANSACTION': return 'status-failed';
      case 'VALIDATION_ERROR': return 'status-error';
      case 'RECEIVED': return 'status-received';
      case 'SUCCESS': case 'APPROVED': return 'status-success';
      case 'FAILED': return 'status-failed';
      case 'PENDING': case 'SENT': return 'status-pending';
      default: return '';
    }
  }

  getSuccessRate(): number {
    if (!this.summary || this.summary.totalRecords === 0) return 0;
    return Math.round((this.summary.successCount / this.summary.totalRecords) * 100);
  }

  goBack(): void {
    this.location.back();
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
