import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TableSorter } from '../../shared/table-sorter';
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
  validationStatus: string;
  remark: string;
  createdAt: string;
}

@Component({
  selector: 'app-batch-file-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './batch-file-detail.component.html',
  styleUrl: './batch-file-detail.component.scss',
  encapsulation: ViewEncapsulation.None
})
export class BatchFileDetailComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/batch-file-maintenance';

  batchFileId!: number;
  detail: BatchFileDetail | null = null;
  isLoading = true;

  // Transaction filter
  statusFilter = 'ALL';
  txnIdSearch = '';
  appliedTxnIdSearch = '';
  appliedStatusFilter = 'ALL';

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

    if (this.appliedStatusFilter !== 'ALL') {
      txns = txns.filter(t => t.status === this.appliedStatusFilter);
    }
    if (this.appliedTxnIdSearch.trim()) {
      const term = this.appliedTxnIdSearch.trim().toLowerCase();
      txns = txns.filter(t => t.transactionId?.toString().includes(term));
    }
    return txns;
  }

  applyFilter(): void {
    this.appliedTxnIdSearch = this.txnIdSearch;
    this.appliedStatusFilter = this.statusFilter;
    this.txnPage = 1;
  }

  onFilterSearch(values: any): void {
    this.txnPage = 1;
  }

  onTxnSearch(): void {}

  // ---- Sorting ----
  sorter = new TableSorter<TransactionRecord>();

  get sortKey() { return this.sorter.sortKey; }
  get sortDir() { return this.sorter.sortDir; }
  sortBy(key: string): void { this.sorter.sortBy(key); }

  get sortedTransactions(): TransactionRecord[] {
    return this.sorter.apply(this.filteredTransactions);
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

  // ---- Summary helpers ----
  getValidationRate(): number {
    if (!this.detail || !this.detail.totalRecordCount) return 0;
    return Math.round((this.detail.successCount / this.detail.totalRecordCount) * 100);
  }

  getApprovedRate(): number {
    if (!this.detail || !this.detail.transactions || this.detail.totalRecordCount === 0) return 0;
    const approvedCount = this.detail.transactions.filter(t => t.status === 'APPROVED').length;
    return Math.round((approvedCount / this.detail.totalRecordCount) * 100);
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
  getValidationStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'PASSED': return 'status-success';
      case 'FAILED': return 'status-failed';
      case 'DUPLICATE': return 'status-failed';
      default: return '';
    }
  }

  getFileStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'VALIDATED': return 'status-success';
      case 'RECEIVED': return 'status-ready';
      case 'PROCESSING': return 'status-processing';
      case 'PARTIAL': return 'status-processing';
      case 'FAILED':
      case 'VALIDATION_FAILED':
      case 'VALIDATION_ERROR':
      case 'NO_FILE_PROFILE':
      case 'NO_FIELD_MAPPING':
      case 'WRONG_FILE_FORMAT':
      case 'MISSING_HEADER':
      case 'INVALID_FILE_CONTENT':
        return 'status-failed';
      default: return 'status-ready';
    }
  }

  getBatchStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'BATCHED':
      case 'SENT':
      case 'COMPLETED':
      case 'PROCESSED':
        return 'status-success';
      case 'PENDING': return 'status-ready';
      case 'READY_TO_SEND': return 'status-ready';
      case 'CREATED': return 'status-ready';
      case 'PROCESSING':
      case 'RETRYING':
        return 'status-processing';
      case 'FAILED':
      case 'SEND_FAILED':
        return 'status-failed';
      case 'REPORTED': return 'status-reported';
      default: return 'status-ready';
    }
  }

  getStatusBg(statusClass: string): string {
    const bg: { [key: string]: string } = {
      'status-ready': '#dbeafe',
      'status-processing': '#fef3c7',
      'status-success': '#d1fae5',
      'status-failed': '#fee2e2',
      'status-reported': '#e0e7ff',
    };
    return bg[statusClass] || 'transparent';
  }

  getStatusColor(statusClass: string): string {
    const fg: { [key: string]: string } = {
      'status-ready': '#1d4ed8',
      'status-processing': '#92400e',
      'status-success': '#065f46',
      'status-failed': '#991b1b',
      'status-reported': '#3730a3',
    };
    return fg[statusClass] || 'inherit';
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
