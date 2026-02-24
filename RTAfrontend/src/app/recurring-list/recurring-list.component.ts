import { Component, OnInit } from '@angular/core';
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
  searchTerm = '';
  isLoading = true;

  constructor(
    private http: HttpClient,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadRecurringList();
  }

  loadRecurringList(): void {
    this.isLoading = true;
    this.http.get<RecurringItem[]>(`${this.apiUrl}/list`).subscribe({
      next: (data) => {
        this.recurringItems = data;
        this.filteredItems = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load recurring transactions:', err);
        this.isLoading = false;
      }
    });
  }

  onSearch(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filteredItems = this.recurringItems;
      return;
    }
    this.filteredItems = this.recurringItems.filter(item =>
      item.recurringReference?.toLowerCase().includes(term) ||
      item.merchantId?.toLowerCase().includes(term)
    );
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
