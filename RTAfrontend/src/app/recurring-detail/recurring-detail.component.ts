import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';

interface TransactionItem {
  transactionId: number;
  batchSeq: number;
  merchantCustomer: string;
  maskedPan: string;
  merchantBillingRef: string;
  amount: number;
  currency: string;
  actualBillingDate: string;
  status: string;
  remark: string;
  createdAt: string;
  batchId: number;
}

interface RecurringDetail {
  recurringReference: string;
  merchantId: string;
  merchantCustomer: string;
  isRecurring: boolean;
  recurringIndicator: string;
  frequencyValue: number;
  totalTransactions: number;
  successCount: number;
  failedCount: number;
  totalAmountCents: number;
  transactions: TransactionItem[];
}

@Component({
  selector: 'app-recurring-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './recurring-detail.component.html',
  styleUrl: './recurring-detail.component.scss'
})
export class RecurringDetailComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/recurring';

  recurringReference: string = '';
  detail: RecurringDetail | null = null;
  isLoading = true;
  errorMessage = '';

  constructor(
    private http: HttpClient,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.recurringReference = this.route.snapshot.paramMap.get('recurringReference') || '';
    if (this.recurringReference) {
      this.loadDetail();
    }
  }

  loadDetail(): void {
    this.isLoading = true;
    this.http.get<RecurringDetail>(`${this.apiUrl}/detail/${encodeURIComponent(this.recurringReference)}`).subscribe({
      next: (data) => {
        this.detail = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load recurring detail:', err);
        this.errorMessage = 'Failed to load recurring transaction details.';
        this.isLoading = false;
      }
    });
  }

  goBack(): void {
    this.router.navigate(['/recurring-list']);
  }

  formatAmount(amountCents: number | null, currency: string): string {
    if (amountCents === null || amountCents === undefined) return '-';
    const amount = amountCents / 100;
    return `${currency || ''} ${amount.toFixed(2)}`;
  }

  formatDate(dateStr: string | null): string {
    if (!dateStr) return '-';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric'
      });
    } catch {
      return dateStr;
    }
  }

  formatDateTime(dateStr: string | null): string {
    if (!dateStr) return '-';
    try {
      const date = new Date(dateStr);
      return date.toLocaleString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch {
      return dateStr;
    }
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SUCCESS': return 'status-success';
      case 'FAILED': return 'status-failed';
      case 'PENDING': return 'status-pending';
      default: return '';
    }
  }
}
