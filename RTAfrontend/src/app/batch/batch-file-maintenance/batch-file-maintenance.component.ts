import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

interface BatchFileItem {
  batchFileId: number;
  batchId: number | null;
  originalFilename: string;
  merchantId: string;
  merchantName: string;
  fileStatus: string;
  batchStatus: string;
  insertionStatus: string;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  createdAt: string;
  createdBy: string;
  authBatchId?: number;
  authBatchStatus?: string;
  authBatchReference?: string;
}

interface PagedResponse {
  content: BatchFileItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

@Component({
  selector: 'app-batch-file-maintenance',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './batch-file-maintenance.component.html',
  styleUrl: './batch-file-maintenance.component.scss'
})
export class BatchFileMaintenanceComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/batch-file-maintenance';

  pagedItems: BatchFileItem[] = [];
  isLoading = true;

  // Search
  searchTerm = '';

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

  // Sorting
  sortKey = '';
  sortDir: 'asc' | 'desc' = 'asc';

  constructor(
    private http: HttpClient,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
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
        console.error('Failed to load batch files:', err);
        this.pagedItems = [];
        this.totalElements = 0;
        this.totalPages = 1;
        this.isLoading = false;
      }
    });
  }

  // Client-side search filter
  get filteredItems(): BatchFileItem[] {
    if (!this.searchTerm.trim()) return this.sortedItems;
    const term = this.searchTerm.toLowerCase();
    return this.sortedItems.filter(item =>
      (item.batchFileId?.toString() || '').includes(term) ||
      (item.authBatchId?.toString() || '').includes(term) ||
      (item.originalFilename || '').toLowerCase().includes(term) ||
      (item.merchantId || '').toLowerCase().includes(term) ||
      (item.merchantName || '').toLowerCase().includes(term) ||
      (item.fileStatus || '').toLowerCase().includes(term) ||
      (item.batchStatus || '').toLowerCase().includes(term) ||
      (item.insertionStatus || '').toLowerCase().includes(term)
    );
  }

  // Sorting
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

  get sortedItems(): BatchFileItem[] {
    if (!this.sortKey) return this.pagedItems;
    return [...this.pagedItems].sort((a, b) => {
      const av = (a as any)[this.sortKey] ?? '';
      const bv = (b as any)[this.sortKey] ?? '';
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return this.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  viewDetail(batchFileId: number): void {
    this.router.navigate(['/batch-file-detail', batchFileId]);
  }

  // Pagination
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

  // Status helpers
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
      case 'CREATED': return 'status-ready';
      case 'SENT': return 'status-processing';
      case 'PROCESSED': return 'status-success';
      case 'PENDING': return 'status-ready';
      case 'PROCESSING': return 'status-processing';
      case 'FAILED': return 'status-failed';
      default: return '';
    }
  }

  getInsertionStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'COMPLETED': return 'status-success';
      case 'INSERTING': return 'status-processing';
      default: return '';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
