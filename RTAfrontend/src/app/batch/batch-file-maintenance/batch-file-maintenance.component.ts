import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { MerchantFilterComponent, MerchantFilterValues } from '../../shared/merchant-filter/merchant-filter.component';
import { TableSorter } from '../../shared/table-sorter';
interface BatchFileItem {
  batchFileId: number;
  batchId: number | null;
  originalFilename: string;
  storedFilename: string;
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
  imports: [CommonModule, RouterModule, FormsModule, MerchantFilterComponent],
  templateUrl: './batch-file-maintenance.component.html',
  styleUrl: './batch-file-maintenance.component.scss',
  encapsulation: ViewEncapsulation.None
})
export class BatchFileMaintenanceComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/batch-file-maintenance';

  pagedItems: BatchFileItem[] = [];
  isLoading = true;

  // Search
  searchTerm = '';
  dateFrom = '';
  dateTo = '';
  searched = false;
  merchantIds: string[] = [];
  merchantSelectedId = '';

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

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
    this.searched = false;
    this.searchTerm = '';
    this.dateFrom = '';
    this.dateTo = '';
    const params = new HttpParams()
      .set('page', (this.currentPage - 1).toString())
      .set('size', this.pageSize.toString());

    this.http.get<PagedResponse>(`${this.apiUrl}/list`, { params }).subscribe({
      next: (data) => {
        this.pagedItems = data.content;
        this.totalElements = data.totalElements;
        this.totalPages = Math.max(1, data.totalPages);
        this.currentPage = data.currentPage + 1;
        this.merchantIds = [...new Set(data.content.map(f => f.merchantId).filter(Boolean))].sort();
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

  // Client-side search filter (merchant name only + date range)
  get filteredItems(): BatchFileItem[] {
    if (!this.searched) return this.sortedItems;
    let items = this.sortedItems;
    if (this.merchantSelectedId) {
      items = items.filter(item => item.merchantId === this.merchantSelectedId);
    }
    if (this.searchTerm.trim()) {
      const term = this.searchTerm.toLowerCase();
      items = items.filter(item =>
        (item.merchantName || '').toLowerCase().includes(term)
      );
    }
    if (this.dateFrom) {
      const from = new Date(this.dateFrom);
      items = items.filter(item => item.createdAt && new Date(item.createdAt) >= from);
    }
    if (this.dateTo) {
      const to = new Date(this.dateTo);
      to.setHours(23, 59, 59, 999);
      items = items.filter(item => item.createdAt && new Date(item.createdAt) <= to);
    }
    return items;
  }

  onSearch(): void {
    this.searched = true;
  }

  onFilterSearch(values: MerchantFilterValues): void {
    this.merchantSelectedId = values.merchantId;
    this.dateFrom = values.dateFrom;
    this.dateTo = values.dateTo;
    this.searched = true;
  }

  // Sorting
  sorter = new TableSorter<BatchFileItem>();

  get sortKey() { return this.sorter.sortKey; }
  get sortDir() { return this.sorter.sortDir; }
  sortBy(key: string): void { this.sorter.sortBy(key); }

  get sortedItems(): BatchFileItem[] {
    return this.sorter.apply(this.pagedItems);
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
      case 'FAILED': case 'VALIDATION_FAILED': case 'DUPLICATE_TRANSACTION': return 'status-failed';
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
      case 'REPORTED': return 'status-reported';
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

  getStatusStyle(statusClass: string): { [key: string]: string } {
    const styles: { [key: string]: { 'background-color': string; color: string } } = {
      'status-ready': { 'background-color': '#dbeafe', color: '#1d4ed8' },
      'status-processing': { 'background-color': '#fef3c7', color: '#92400e' },
      'status-success': { 'background-color': '#d1fae5', color: '#065f46' },
      'status-failed': { 'background-color': '#fee2e2', color: '#991b1b' },
      'status-reported': { 'background-color': '#e0e7ff', color: '#3730a3' },
    };
    return styles[statusClass] || {};
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
