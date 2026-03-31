import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

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
  files: BatchFile[];
  transactions: TransactionRecord[];
}

interface BatchFile {
  batchFileId: number;
  originalFilename: string;
  merchantId: string;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  fileStatus: string;
  createdAt: string;
}

interface TransactionRecord {
  transactionId: number;
  batchFileId: number;
  merchantId: string;
  merchantCustomer: string;
  maskedPan: string;
  amount: number;
  currency: string;
  actualBillingDate: string;
  status: string;
  createdAt: string;
}

@Component({
  selector: 'app-batch-maintenance-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './batch-maintenance-detail.component.html',
  styleUrl: './batch-maintenance-detail.component.scss'
})
export class BatchMaintenanceDetailComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/batch-maintenance';

  authBatchId!: number;
  batch: BatchDetailData | null = null;
  isLoading = true;

  // --- Files table ---
  fileSortKey = '';
  fileSortDir: 'asc' | 'desc' = 'asc';

  // --- Transactions table with pagination + file filter ---
  allTransactions: TransactionRecord[] = [];
  selectedFileId: number | null = null; // null = show all
  txnSortKey = '';
  txnSortDir: 'asc' | 'desc' = 'asc';
  txnCurrentPage = 1;
  txnPageSize = 10;
  txnPageSizeOptions = [10, 25, 50, 100];

  constructor(
    private http: HttpClient,
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.authBatchId = Number(this.route.snapshot.paramMap.get('authBatchId'));
    this.loadDetail();
  }

  loadDetail(): void {
    this.isLoading = true;
    this.http.get<BatchDetailData>(`${this.apiUrl}/detail/${this.authBatchId}`).subscribe({
      next: (data) => {
        this.batch = data;
        this.allTransactions = data.transactions || [];
        // Default filter: first file sorted by batchFileId
        if (data.files && data.files.length > 0) {
          const sorted = [...data.files].sort((a, b) => a.batchFileId - b.batchFileId);
          this.selectedFileId = sorted[0].batchFileId;
        }
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load batch detail:', err);
        this.isLoading = false;
      }
    });
  }

  // ---- File table sort ----
  fileSortBy(key: string): void {
    if (this.fileSortKey === key) {
      if (this.fileSortDir === 'asc') {
        this.fileSortDir = 'desc';
      } else {
        this.fileSortKey = '';
        this.fileSortDir = 'asc';
      }
    } else {
      this.fileSortKey = key;
      this.fileSortDir = 'asc';
    }
  }

  get sortedFiles(): BatchFile[] {
    if (!this.batch) return [];
    let list = [...this.batch.files];
    if (this.fileSortKey) {
      list.sort((a, b) => {
        const av = (a as any)[this.fileSortKey] ?? '';
        const bv = (b as any)[this.fileSortKey] ?? '';
        const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
        return this.fileSortDir === 'asc' ? cmp : -cmp;
      });
    }
    return list;
  }

  // ---- Transaction table sort + pagination ----
  txnSortBy(key: string): void {
    if (this.txnSortKey === key) {
      if (this.txnSortDir === 'asc') {
        this.txnSortDir = 'desc';
      } else {
        this.txnSortKey = '';
        this.txnSortDir = 'asc';
      }
    } else {
      this.txnSortKey = key;
      this.txnSortDir = 'asc';
    }
  }

  /** Transactions filtered by the selected file */
  get filteredTransactions(): TransactionRecord[] {
    if (this.selectedFileId == null) return this.allTransactions;
    return this.allTransactions.filter(t => t.batchFileId === this.selectedFileId);
  }

  onFileFilterChange(): void {
    this.txnCurrentPage = 1;
  }

  get sortedTransactions(): TransactionRecord[] {
    let list = [...this.filteredTransactions];
    if (this.txnSortKey) {
      list.sort((a, b) => {
        const av = (a as any)[this.txnSortKey] ?? '';
        const bv = (b as any)[this.txnSortKey] ?? '';
        const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
        return this.txnSortDir === 'asc' ? cmp : -cmp;
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

  // ---- Helpers ----
  formatAmount(cents: number): string {
    if (cents == null) return '–';
    return (cents / 100).toFixed(2);
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'READY_TO_SEND': return 'status-ready';
      case 'SENT': case 'COMPLETED': case 'SUCCESS': case 'VALIDATED': return 'status-success';
      case 'PROCESSING': return 'status-processing';
      case 'PARTIAL': return 'status-partial';
      case 'FAILED': case 'VALIDATION_FAILED': case 'VALIDATION_ERROR': return 'status-failed';
      default: return '';
    }
  }

  getSuccessRate(): number {
    if (!this.batch || this.batch.totalCount === 0) return 0;
    return Math.round((this.batch.successCount / this.batch.totalCount) * 100);
  }

  goBack(): void {
    this.router.navigate(['/batch-maintenance']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
