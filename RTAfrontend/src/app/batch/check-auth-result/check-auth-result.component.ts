import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

interface AuthBatchSummary {
  authBatchId: number;
  batchReference: string;
  fileCount: number;
  totalCount: number;
  successCount: number;
  failCount: number;
  totalAmountCents: number;
  batchStatus: string;
  sendAuthStatus: string;
  createdAt: string;
  lastModifiedAt: string;
  remark: string;
}

interface TransactionResult {
  transactionId: number;
  batchFileId: number;
  merchantId: string;
  merchantCustomer: string;
  maskedPan: string;
  amount: number;
  currency: string;
  actualBillingDate: string;
  status: string;
  remark: string;
  authorizationDatetime: string;
  createdAt: string;
}

interface BatchDetailData {
  authBatchId: number;
  batchReference: string;
  totalCount: number;
  successCount: number;
  failCount: number;
  totalAmountCents: number;
  batchStatus: string;
  createdAt: string;
  lastModifiedAt: string;
  remark: string;
  transactions: TransactionResult[];
}

interface PagedResponse {
  content: AuthBatchSummary[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

@Component({
  selector: 'app-check-auth-result',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './check-auth-result.component.html',
  styleUrl: './check-auth-result.component.scss'
})
export class CheckAuthResultComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/batch-maintenance';

  // --- Batch list (only PROCESSED batches) ---
  batches: AuthBatchSummary[] = [];
  isLoadingBatches = true;
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

  // --- Selected batch detail ---
  selectedBatch: BatchDetailData | null = null;
  isLoadingDetail = false;
  transactions: TransactionResult[] = [];

  // --- Transaction table sort + pagination ---
  sortKey = '';
  sortDir: 'asc' | 'desc' = 'asc';
  txnCurrentPage = 1;
  txnPageSize = 10;
  txnPageSizeOptions = [10, 25, 50, 100];

  // --- Filter ---
  statusFilter: string = '';

  constructor(
    private http: HttpClient,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.loadBatches();
  }

  // ===================== Batch List =====================

  loadBatches(): void {
    this.isLoadingBatches = true;
    const params = new HttpParams()
      .set('page', (this.currentPage - 1).toString())
      .set('size', this.pageSize.toString());

    this.http.get<PagedResponse>(`${this.apiUrl}/list`, { params }).subscribe({
      next: (data) => {
        // Only show batches that have been processed (auth result available)
        this.batches = data.content.filter(b =>
          b.sendAuthStatus === 'PROCESSED' || b.batchStatus === 'PROCESSED'
        );
        this.totalElements = data.totalElements;
        this.totalPages = Math.max(1, data.totalPages);
        this.currentPage = data.currentPage + 1;
        this.isLoadingBatches = false;
      },
      error: (err) => {
        console.error('Failed to load batches:', err);
        this.batches = [];
        this.isLoadingBatches = false;
      }
    });
  }

  selectBatch(batch: AuthBatchSummary): void {
    this.isLoadingDetail = true;
    this.selectedBatch = null;
    this.transactions = [];
    this.txnCurrentPage = 1;
    this.statusFilter = '';

    this.http.get<BatchDetailData>(`${this.apiUrl}/detail/${batch.authBatchId}`).subscribe({
      next: (data) => {
        this.selectedBatch = data;
        this.transactions = data.transactions || [];
        this.isLoadingDetail = false;
      },
      error: (err) => {
        console.error('Failed to load batch detail:', err);
        this.isLoadingDetail = false;
      }
    });
  }

  clearSelection(): void {
    this.selectedBatch = null;
    this.transactions = [];
  }

  // ===================== Transaction Table =====================

  get filteredTransactions(): TransactionResult[] {
    if (!this.statusFilter) return this.transactions;
    return this.transactions.filter(t => t.status === this.statusFilter);
  }

  sortBy(key: string): void {
    if (this.sortKey === key) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortKey = key;
      this.sortDir = 'asc';
    }
  }

  get sortedTransactions(): TransactionResult[] {
    let list = [...this.filteredTransactions];
    if (this.sortKey) {
      list.sort((a, b) => {
        const av = (a as any)[this.sortKey] ?? '';
        const bv = (b as any)[this.sortKey] ?? '';
        const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
        return this.sortDir === 'asc' ? cmp : -cmp;
      });
    }
    const start = (this.txnCurrentPage - 1) * this.txnPageSize;
    return list.slice(start, start + this.txnPageSize);
  }

  get txnTotalElements(): number { return this.filteredTransactions.length; }
  get txnTotalPages(): number { return Math.max(1, Math.ceil(this.filteredTransactions.length / this.txnPageSize)); }
  get txnStartRecord(): number { return this.txnTotalElements === 0 ? 0 : (this.txnCurrentPage - 1) * this.txnPageSize + 1; }
  get txnEndRecord(): number { return Math.min(this.txnCurrentPage * this.txnPageSize, this.txnTotalElements); }

  get txnVisiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.txnCurrentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.txnTotalPages) { end = this.txnTotalPages; start = Math.max(1, end - maxVisible + 1); }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  txnGoToPage(page: number): void {
    if (page >= 1 && page <= this.txnTotalPages) this.txnCurrentPage = page;
  }

  txnOnPageSizeChange(): void {
    this.txnCurrentPage = 1;
  }

  onStatusFilterChange(): void {
    this.txnCurrentPage = 1;
  }

  // ===================== Pagination (batch list) =====================

  onPageSizeChange(): void { this.currentPage = 1; this.loadBatches(); }
  goToPage(page: number): void { if (page >= 1 && page <= this.totalPages) { this.currentPage = page; this.loadBatches(); } }
  get startRecord(): number { return this.totalElements === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1; }
  get endRecord(): number { return Math.min(this.currentPage * this.pageSize, this.totalElements); }
  get visiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.currentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.totalPages) { end = this.totalPages; start = Math.max(1, end - maxVisible + 1); }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  // ===================== Helpers =====================

  get approvedCount(): number {
    return this.transactions.filter(t => t.status === 'APPROVED').length;
  }

  get failedCount(): number {
    return this.transactions.filter(t => t.status === 'FAILED').length;
  }

  get pendingCount(): number {
    return this.transactions.filter(t => t.status === 'PENDING').length;
  }

  formatAmount(cents: number): string {
    if (cents == null) return '–';
    return (cents / 100).toFixed(2);
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'APPROVED': case 'PROCESSED': case 'COMPLETED': case 'SUCCESS': return 'status-success';
      case 'FAILED': case 'DECLINED': case 'SEND_FAILED': return 'status-failed';
      case 'PENDING': case 'CREATED': return 'status-ready';
      case 'PROCESSING': case 'SENT': case 'RETRYING': return 'status-processing';
      default: return '';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
