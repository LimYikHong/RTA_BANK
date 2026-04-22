import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { MerchantFilterComponent, MerchantFilterValues } from '../../shared/merchant-filter/merchant-filter.component';
interface RecurringItem {
  recurringReference: string;
  merchantId: string;
  totalTransactions: number;
  successCount: number;
  failedCount: number;
  authStatus: string | null;
  transactionId: number | null;
  isRecurring: boolean | null;
}

interface PagedResponse {
  content: RecurringItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

@Component({
  selector: 'app-recurring-list',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, MerchantFilterComponent],
  templateUrl: './recurring-list.component.html',
  styleUrl: './recurring-list.component.scss'
})
export class RecurringListComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/recurring';

  pagedItems: RecurringItem[] = [];
  searchTerm = '';
  merchantSelectedId = '';     // the actual selected merchant ID for filtering
  recurringType = 'ALL';       // ALL | RECURRING | NON_RECURRING
  isLoading = true;

  // Pagination (server-side)
  currentPage = 1;             // 1-based for UI
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

  // Merchant ID lists (loaded once from dedicated endpoint)
  merchantIds: string[] = [];          // full list from API

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

  get sortedItems(): RecurringItem[] {
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
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadMerchantIds();
    this.loadPage();
  }

  /** Load merchant IDs for filter dropdown */
  loadMerchantIds(): void {
    let url = `${this.apiUrl}/merchant-ids`;
    if (this.recurringType && this.recurringType !== 'ALL') {
      url += `?recurringType=${this.recurringType}`;
    }
    this.http.get<string[]>(url).subscribe({
      next: (ids) => {
        this.merchantIds = ids;
      },
      error: (err) => console.error('Failed to load merchant IDs:', err)
    });
  }

  /** Load one page of data from the server */
  loadPage(): void {
    this.isLoading = true;

    let params = new HttpParams()
      .set('page', (this.currentPage - 1).toString())   // API is 0-based
      .set('size', this.pageSize.toString());

    if (this.searchTerm.trim()) {
      params = params.set('search', this.searchTerm.trim());
    }
    if (this.merchantSelectedId) {
      params = params.set('merchantId', this.merchantSelectedId);
    }
    if (this.recurringType && this.recurringType !== 'ALL') {
      params = params.set('recurringType', this.recurringType);
    }

    this.http.get<PagedResponse>(`${this.apiUrl}/list`, { params }).subscribe({
      next: (data) => {
        this.pagedItems = data.content;
        this.totalElements = data.totalElements;
        this.totalPages = Math.max(1, data.totalPages);
        this.currentPage = data.currentPage + 1;  // convert 0-based → 1-based
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load recurring transactions:', err);
        this.pagedItems = [];
        this.totalElements = 0;
        this.totalPages = 1;
        this.isLoading = false;
      }
    });
  }

  /** Only triggered by the Search button or Enter key */
  applyFilters(): void {
    this.currentPage = 1;
    this.loadMerchantIds();
    this.loadPage();
  }

  onFilterSearch(values: MerchantFilterValues): void {
    this.merchantSelectedId = values.merchantId;
    this.applyFilters();
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

  viewDetail(item: RecurringItem): void {
    if (item.isRecurring === false || item.isRecurring === null) {
      // Non-recurring: navigate to batch file detail using batchFileId if available,
      // or just show nothing — for now navigate to recurring-detail by transactionId as fallback
      this.router.navigate(['/recurring-detail', item.transactionId ?? item.recurringReference]);
    } else {
      this.router.navigate(['/recurring-detail', item.recurringReference]);
    }
  }

  getStatusSummary(item: RecurringItem): string {
    if (item.failedCount === 0) {
      return 'All Success';
    } else if (item.successCount === 0) {
      return 'All Failed';
    } else {
      return 'Partial';
    }
  }

  getStatusClass(item: RecurringItem): string {
    if (item.failedCount === 0) {
      return 'status-success';
    } else if (item.successCount === 0) {
      return 'status-failed';
    } else {
      return 'status-partial';
    }
  }

  getAuthStatusClass(authStatus: string | null): string {
    const status = (authStatus || 'PENDING').toUpperCase();
    switch (status) {
      case 'READY_TO_SEND': return 'status-ready';
      case 'PROCESSING':    return 'status-processing';
      case 'COMPLETED':     return 'status-success';
      case 'FAILED':        return 'status-failed';
      default:              return 'status-pending';
    }
  }

  formatAuthStatus(authStatus: string | null): string {
    const status = (authStatus || 'PENDING').toUpperCase();
    switch (status) {
      case 'READY_TO_SEND': return 'Ready to Send';
      case 'PROCESSING':    return 'Processing';
      case 'COMPLETED':     return 'Completed';
      case 'FAILED':        return 'Failed';
      case 'PENDING':       return 'Pending';
      default:              return status.replace(/_/g, ' ');
    }
  }
}
