import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';
import { BatchTimerService } from '../../services/batch-timer.service';

interface AuthBatchItem {
  authBatchId: number;
  batchReference: string;
  fileCount: number;
  totalCount: number;
  successCount: number;
  failCount: number;
  totalAmountCents: number;
  batchStatus: string;
  createdAt: string;
  lastModifiedAt: string;
  remark: string;
}

interface PagedResponse {
  content: AuthBatchItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

@Component({
  selector: 'app-batch-maintenance',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './batch-maintenance.component.html',
  styleUrl: './batch-maintenance.component.scss'
})
export class BatchMaintenanceComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/batch-maintenance';

  pagedItems: AuthBatchItem[] = [];
  isLoading = true;

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

  // Detail modal
  showDetailModal = false;
  detailBatch: any = null;
  detailTransactions: any[] = [];
  detailFiles: any[] = [];
  isLoadingDetail = false;

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

  get sortedItems(): AuthBatchItem[] {
    if (!this.sortKey) return this.pagedItems;
    return [...this.pagedItems].sort((a, b) => {
      const av = (a as any)[this.sortKey] ?? '';
      const bv = (b as any)[this.sortKey] ?? '';
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return this.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  constructor(
    private http: HttpClient,
    private router: Router,
    private authService: AuthService,
    public timer: BatchTimerService
  ) {}

  ngOnInit(): void {
    this.timer.init();
    this.loadPage();
  }

  loadPage(): void {
    this.isLoading = true;

    const params = new HttpParams()
      .set('page', (this.currentPage - 1).toString())
      .set('size', this.pageSize.toString());

    this.http.get<PagedResponse>(`${this.apiUrl}/list`, { params }).subscribe({
      next: (data) => {
        this.pagedItems = data.content;
        this.totalElements = data.totalElements;
        this.totalPages = Math.max(1, data.totalPages);
        this.currentPage = data.currentPage + 1;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load authorization batches:', err);
        this.pagedItems = [];
        this.totalElements = 0;
        this.totalPages = 1;
        this.isLoading = false;
      }
    });
  }

  /** View batch detail */
  viewDetail(authBatchId: number): void {
    this.showDetailModal = true;
    this.isLoadingDetail = true;
    this.http.get<any>(`${this.apiUrl}/detail/${authBatchId}`).subscribe({
      next: (data) => {
        this.detailBatch = data;
        this.detailTransactions = data.transactions || [];
        this.detailFiles = data.files || [];
        this.isLoadingDetail = false;
      },
      error: (err) => {
        console.error('Failed to load batch detail:', err);
        this.isLoadingDetail = false;
      }
    });
  }

  closeDetailModal(): void {
    this.showDetailModal = false;
    this.detailBatch = null;
    this.detailTransactions = [];
    this.detailFiles = [];
  }

  onPageSizeChange(): void {
    this.currentPage = 1;
    this.loadPage();
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.totalPages) {
      this.currentPage = page;
      this.loadPage();
    }
  }

  get startRecord(): number {
    return this.totalElements === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1;
  }

  get endRecord(): number {
    return Math.min(this.currentPage * this.pageSize, this.totalElements);
  }

  get visiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.currentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.totalPages) {
      end = this.totalPages;
      start = Math.max(1, end - maxVisible + 1);
    }
    for (let i = start; i <= end; i++) {
      pages.push(i);
    }
    return pages;
  }

  formatAmount(cents: number): string {
    if (cents == null) return '–';
    return (cents / 100).toFixed(2);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'READY_TO_SEND': return 'status-ready';
      case 'SENT': return 'status-success';
      case 'PROCESSING': return 'status-processing';
      case 'COMPLETED': return 'status-success';
      case 'FAILED': return 'status-failed';
      default: return '';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
