import { Component, OnInit, HostListener, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';

interface RecurringItem {
  recurringReference: string;
  merchantId: string;
  totalTransactions: number;
  successCount: number;
  failedCount: number;
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
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './recurring-list.component.html',
  styleUrl: './recurring-list.component.scss'
})
export class RecurringListComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/recurring';

  pagedItems: RecurringItem[] = [];
  searchTerm = '';
  merchantIdInput = '';        // text shown in the combobox input
  merchantSelectedId = '';     // the actual selected merchant ID for filtering
  showDropdown = false;        // controls combobox dropdown visibility
  isLoading = true;

  // Pagination (server-side)
  currentPage = 1;             // 1-based for UI
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalElements = 0;

  // Merchant ID lists (loaded once from dedicated endpoint)
  merchantIds: string[] = [];          // full list from API
  filteredMerchantIds: string[] = [];  // subset shown in dropdown based on text input

  constructor(
    private http: HttpClient,
    private router: Router,
    private elRef: ElementRef
  ) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    const combobox = this.elRef.nativeElement.querySelector('.merchant-combobox');
    if (combobox && !combobox.contains(event.target)) {
      this.showDropdown = false;
    }
  }

  ngOnInit(): void {
    this.loadMerchantIds();
    this.loadPage();
  }

  /** Load merchant IDs for filter dropdown (one-time call) */
  loadMerchantIds(): void {
    this.http.get<string[]>(`${this.apiUrl}/merchant-ids`).subscribe({
      next: (ids) => {
        this.merchantIds = ids;
        this.filteredMerchantIds = [...this.merchantIds];
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
    this.loadPage();
  }

  onInputFocus(): void {
    // Always show the full list when the user clicks into the input
    this.filteredMerchantIds = this.merchantIdInput.trim()
      ? this.merchantIds.filter(id => id.toLowerCase().includes(this.merchantIdInput.trim().toLowerCase()))
      : [...this.merchantIds];
    this.showDropdown = true;
  }

  onMerchantInputChange(): void {
    const typed = this.merchantIdInput.trim().toLowerCase();
    this.showDropdown = true;
    if (!typed) {
      this.filteredMerchantIds = [...this.merchantIds];
      this.merchantSelectedId = '';
      return;
    }
    this.filteredMerchantIds = this.merchantIds.filter(id =>
      id.toLowerCase().includes(typed)
    );
    // Auto-select if exact match typed but do NOT fire search yet
    const exact = this.merchantIds.find(id => id.toLowerCase() === typed);
    this.merchantSelectedId = exact ?? '';
  }

  selectMerchant(id: string): void {
    this.merchantSelectedId = id;
    this.merchantIdInput = id;
    this.showDropdown = false;
    this.filteredMerchantIds = [...this.merchantIds];
    // Do NOT auto-search; user must press the Search button
  }

  toggleDropdown(): void {
    this.showDropdown = !this.showDropdown;
    if (this.showDropdown) {
      this.filteredMerchantIds = this.merchantIdInput.trim()
        ? this.merchantIds.filter(id => id.toLowerCase().includes(this.merchantIdInput.trim().toLowerCase()))
        : [...this.merchantIds];
    }
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

  viewDetail(recurringReference: string): void {
    this.router.navigate(['/recurring-detail', recurringReference]);
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
}
