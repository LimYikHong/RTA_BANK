import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
interface BatchFileDetail {
  batchFileId: number;
  batchId: number | null;
  authBatchId?: number;
  originalFilename: string;
  storedFilename: string;
  merchantId: string;
  merchantName: string;
  fileStatus: string;
  batchStatus: string;
  sizeBytes: number;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  createdAt: string;
  createdBy: string;
  lastModifiedAt: string;
  lastModifiedBy: string;
  transactionRecordRemark: string | null;
  authorizationBatches?: AuthBatchInfo[];
  transactions: TransactionRecord[];
  transactionCount: number;
}

interface AuthBatchInfo {
  authBatchId: number;
  batchReference: string;
  batchStatus: string;
  totalCount: number;
  totalAmountCents: number;
  createdAt: string;
}

interface TransactionRecord {
  transactionId: number;
  batchSeq: number;
  merchantId: string;
  customerReference: string;
  maskedPan: string;
  billingRef: string;
  description: string;
  amount: number | null;
  currency: string;
  actualBillingDate: string;
  recurringIndicator: string;
  isRecurring: boolean;
  recurringReference: string;
  status: string;
  remark: string;
  createdAt: string;
}

@Component({
  selector: 'app-batch-file-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './batch-file-detail.component.html',
  styleUrl: './batch-file-detail.component.scss'
})
export class BatchFileDetailComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/batch-file-maintenance';

  batchFileId!: number;
  detail: BatchFileDetail | null = null;
  isLoading = true;

  // Transaction filter
  statusFilter = 'ALL';
  searchTerm = '';

  // Transaction table sort
  sortKey = '';
  sortDir: 'asc' | 'desc' = 'asc';

  // Transaction pagination (client-side)
  txnPage = 1;
  txnPageSize = 20;
  txnPageSizeOptions = [10, 20, 50, 100];

  constructor(
    private http: HttpClient,
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.batchFileId = Number(this.route.snapshot.paramMap.get('batchFileId'));
    this.loadDetail();
  }

  loadDetail(): void {
    this.isLoading = true;
    this.http.get<BatchFileDetail>(`${this.apiUrl}/detail/${this.batchFileId}`).subscribe({
      next: (data) => {
        this.detail = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load batch file detail:', err);
        this.isLoading = false;
      }
    });
  }

  // ---- Transaction filtering ----
  get filteredTransactions(): TransactionRecord[] {
    if (!this.detail) return [];
    let txns = this.detail.transactions;

    // Status filter
    if (this.statusFilter !== 'ALL') {
      txns = txns.filter(t => t.status === this.statusFilter);
    }

    // Search filter
    if (this.searchTerm.trim()) {
      const term = this.searchTerm.toLowerCase();
      txns = txns.filter(t =>
        (t.transactionId?.toString() || '').includes(term) ||
        (t.customerReference || '').toLowerCase().includes(term) ||
        (t.maskedPan || '').toLowerCase().includes(term) ||
        (t.billingRef || '').toLowerCase().includes(term) ||
        (t.description || '').toLowerCase().includes(term) ||
        (t.recurringReference || '').toLowerCase().includes(term) ||
        (t.status || '').toLowerCase().includes(term) ||
        (t.remark || '').toLowerCase().includes(term)
      );
    }

    return txns;
  }

  // ---- Sorting ----
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
    const txns = this.filteredTransactions;
    if (!this.sortKey) return txns;
    return [...txns].sort((a, b) => {
      const av = (a as any)[this.sortKey] ?? '';
      const bv = (b as any)[this.sortKey] ?? '';
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return this.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  // ---- Client-side pagination ----
  get paginatedTransactions(): TransactionRecord[] {
    const start = (this.txnPage - 1) * this.txnPageSize;
    return this.sortedTransactions.slice(start, start + this.txnPageSize);
  }

  get txnTotalPages(): number {
    return Math.max(1, Math.ceil(this.sortedTransactions.length / this.txnPageSize));
  }

  get txnStartRecord(): number {
    return this.sortedTransactions.length === 0 ? 0 : (this.txnPage - 1) * this.txnPageSize + 1;
  }

  get txnEndRecord(): number {
    return Math.min(this.txnPage * this.txnPageSize, this.sortedTransactions.length);
  }

  get txnVisiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.txnPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.txnTotalPages) {
      end = this.txnTotalPages;
      start = Math.max(1, end - maxVisible + 1);
    }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  txnGoToPage(page: number): void {
    if (page >= 1 && page <= this.txnTotalPages) {
      this.txnPage = page;
    }
  }

  onTxnPageSizeChange(): void {
    this.txnPage = 1;
  }

  onFilterChange(): void {
    this.txnPage = 1;
  }

  // ---- Summary helpers ----
  getSuccessRate(): number {
    if (!this.detail || !this.detail.totalRecordCount) return 0;
    return Math.round((this.detail.successCount / this.detail.totalRecordCount) * 100);
  }

  formatFileSize(bytes: number): string {
    if (!bytes) return '0 B';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }

  formatAmount(cents: number | null): string {
    if (cents == null) return '—';
    return (cents / 100).toFixed(2);
  }

  // ---- Status helpers ----
  getFileStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'VALIDATED': return 'status-success';
      case 'RECEIVED': return 'status-ready';
      case 'FAILED': case 'VALIDATION_FAILED': return 'status-failed';
      case 'PROCESSING': return 'status-processing';
      default: return '';
    }
  }

  getBatchStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'BATCHED': return 'status-success';
      case 'PENDING': return 'status-ready';
      case 'READY_TO_SEND': return 'status-ready';
      case 'SENT': case 'COMPLETED': return 'status-success';
      case 'PROCESSING': return 'status-processing';
      case 'FAILED': return 'status-failed';
      default: return '';
    }
  }

  getTxnStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SUCCESS': case 'APPROVED': return 'status-success';
      case 'FAILED': return 'status-failed';
      case 'PENDING': return 'status-ready';
      case 'SENT': return 'status-processing';
      default: return '';
    }
  }

  goBack(): void {
    this.router.navigate(['/batch-file-maintenance']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
