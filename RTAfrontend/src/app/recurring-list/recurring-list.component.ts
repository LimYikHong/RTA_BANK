import { Component, OnInit, HostListener, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

interface RecurringItem {
  recurringReference: string;
  merchantId: string;
  totalTransactions: number;
  successCount: number;
  failedCount: number;
}

@Component({
  selector: 'app-recurring-list',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './recurring-list.component.html',
  styleUrl: './recurring-list.component.scss'
})
export class RecurringListComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/recurring';

  recurringItems: RecurringItem[] = [];
  filteredItems: RecurringItem[] = [];
  pagedItems: RecurringItem[] = [];
  searchTerm = '';
  merchantIdInput = '';        // text shown in the combobox input
  merchantSelectedId = '';     // the actual selected merchant ID for filtering
  showDropdown = false;        // controls combobox dropdown visibility
  isLoading = true;

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];
  totalPages = 1;

  // Merchant ID lists
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
    this.loadRecurringList();
  }

  loadRecurringList(): void {
    this.isLoading = true;
    this.http.get<RecurringItem[]>(`${this.apiUrl}/list`).subscribe({
      next: (data) => {
        this.recurringItems = data;
        // Extract unique merchant IDs for the filter dropdown
        this.merchantIds = [...new Set(data.map(item => item.merchantId))].sort();
        this.filteredMerchantIds = [...this.merchantIds];
        this.applyFilters();
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load recurring transactions:', err);
        this.isLoading = false;
      }
    });
  }

  applyFilters(): void {
    let result = this.recurringItems;

    // Filter by selected merchant ID from dropdown
    if (this.merchantSelectedId) {
      result = result.filter(item => item.merchantId === this.merchantSelectedId);
    }

    // Filter by search term
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      result = result.filter(item =>
        item.recurringReference?.toLowerCase().includes(term) ||
        item.merchantId?.toLowerCase().includes(term)
      );
    }

    this.filteredItems = result;
    this.currentPage = 1;
    this.updatePagination();
  }

  onSearch(): void {
    this.applyFilters();
  }

  onMerchantInputChange(): void {
    const typed = this.merchantIdInput.trim().toLowerCase();
    this.showDropdown = true;
    if (!typed) {
      this.filteredMerchantIds = [...this.merchantIds];
      this.merchantSelectedId = '';
      this.applyFilters();
      return;
    }
    this.filteredMerchantIds = this.merchantIds.filter(id =>
      id.toLowerCase().includes(typed)
    );
    // Auto-select if exact match typed
    const exact = this.merchantIds.find(id => id.toLowerCase() === typed);
    if (exact) {
      this.merchantSelectedId = exact;
      this.applyFilters();
    } else {
      this.merchantSelectedId = '';
      this.applyFilters();
    }
  }

  selectMerchant(id: string): void {
    this.merchantSelectedId = id;
    this.merchantIdInput = id;  // show selected value in input
    this.showDropdown = false;
    this.filteredMerchantIds = [...this.merchantIds];
    this.applyFilters();
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
    this.updatePagination();
  }

  updatePagination(): void {
    this.totalPages = Math.max(1, Math.ceil(this.filteredItems.length / this.pageSize));
    if (this.currentPage > this.totalPages) {
      this.currentPage = this.totalPages;
    }
    const start = (this.currentPage - 1) * this.pageSize;
    this.pagedItems = this.filteredItems.slice(start, start + this.pageSize);
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.totalPages) {
      this.currentPage = page;
      this.updatePagination();
    }
  }

  get startRecord(): number {
    return this.filteredItems.length === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1;
  }

  get endRecord(): number {
    return Math.min(this.currentPage * this.pageSize, this.filteredItems.length);
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
