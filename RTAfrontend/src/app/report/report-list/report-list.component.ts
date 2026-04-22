import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ReportService, RtaReport, ReportPage } from '../../services/report.service';
import { AuthService } from '../../services/auth.service';
import { MerchantFilterComponent, MerchantFilterValues } from '../../shared/merchant-filter/merchant-filter.component';

@Component({
  selector: 'app-report-list',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, MerchantFilterComponent],
  templateUrl: './report-list.component.html',
  styleUrl: './report-list.component.scss'
})
export class ReportListComponent implements OnInit {
  reports: RtaReport[] = [];
  allReports: RtaReport[] = [];
  isLoading = true;
  searchQuery = '';
  currentPage = 0;
  pageSize = 15;
  totalPages = 0;
  totalElements = 0;
  isGenerating = false;
  userRole = '';

  // Merchant filter
  merchantIds: string[] = [];
  merchantSelectedId = '';
  dateFrom = '';
  dateTo = '';

  constructor(
    private reportService: ReportService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.userRole = this.authService.getUserRole() || '';
    this.loadReports();
  }

  loadReports(): void {
    this.isLoading = true;
    const user = this.authService.getCurrentUser();
    const merchantId = (this.userRole === 'merchant' && user) ? (user as any).merchantId : undefined;
    this.reportService.getReports(this.currentPage, this.pageSize, merchantId, this.searchQuery || undefined)
      .subscribe({
        next: (page: ReportPage) => {
          this.allReports = page.content;
          this.applyClientFilters();
          this.totalElements = page.totalElements;
          this.totalPages = page.totalPages;
          this.currentPage = page.number;
          this.isLoading = false;
          // Build merchant list from results
          this.merchantIds = [...new Set(this.allReports.map(r => r.merchantId).filter(Boolean))].sort();
        },
        error: () => {
          this.allReports = [];
          this.reports = [];
          this.isLoading = false;
        }
      });
  }

  applyClientFilters(): void {
    let list = this.allReports;
    if (this.merchantSelectedId) {
      list = list.filter(r => r.merchantId === this.merchantSelectedId);
    }
    if (this.dateFrom) {
      const from = new Date(this.dateFrom);
      list = list.filter(r => r.createdAt && new Date(r.createdAt) >= from);
    }
    if (this.dateTo) {
      const to = new Date(this.dateTo);
      to.setHours(23, 59, 59, 999);
      list = list.filter(r => r.createdAt && new Date(r.createdAt) <= to);
    }
    this.reports = list;
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadReports();
  }

  onFilterSearch(values: MerchantFilterValues): void {
    this.merchantSelectedId = values.merchantId;
    this.dateFrom = values.dateFrom;
    this.dateTo = values.dateTo;
    this.onSearch();
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.currentPage = page;
      this.loadReports();
    }
  }

  viewReport(reportId: number): void {
    this.router.navigate(['/report-detail', reportId]);
  }

  downloadReport(reportId: number, event: Event): void {
    event.stopPropagation();
    this.reportService.downloadReport(reportId).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      window.open(url, '_blank');
    });
  }

  downloadOutput(reportId: number, event: Event): void {
    event.stopPropagation();
    const report = this.reports.find(r => r.reportId === reportId);
    this.reportService.downloadOutputFile(reportId).subscribe(blob => {
      const a = document.createElement('a');
      a.href = window.URL.createObjectURL(blob);
      a.download = (report?.reportName || 'output') + (report?.fileFormat === 'XLSX' ? '.xlsx' : '.csv');
      a.click();
    });
  }

  generateReports(): void {
    // Batch results are now auto-generated — kept for backward compatibility
  }

  retrySend(report: any, event: Event): void {
    event.stopPropagation();
    if (report._retrying) return;
    report._retrying = true;
    this.reportService.resendReport(report.reportId).subscribe({
      next: () => {
        report.sendStatus = 'SENT';
        report._retrying = false;
      },
      error: (err: any) => {
        alert('Retry send failed: ' + (err.error?.detail || err.message));
        report._retrying = false;
      }
    });
  }

  formatAmount(cents: number): string {
    return (cents / 100).toFixed(2);
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'GENERATED': return 'status-processing';
      case 'SENT': return 'status-completed';
      case 'FAILED': return 'status-failed';
      default: return 'status-received';
    }
  }

  getSendStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SENT': return 'status-completed';
      case 'FAILED': return 'status-failed';
      case 'PENDING': return 'status-pending';
      default: return 'status-received';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
