import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ReportService, RtaReport, ReportPage } from '../../services/report.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-report-list',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './report-list.component.html',
  styleUrl: './report-list.component.scss'
})
export class ReportListComponent implements OnInit {
  reports: RtaReport[] = [];
  isLoading = true;
  searchQuery = '';
  currentPage = 0;
  pageSize = 15;
  totalPages = 0;
  totalElements = 0;
  isGenerating = false;
  userRole = '';

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
          this.reports = page.content;
          this.totalElements = page.totalElements;
          this.totalPages = page.totalPages;
          this.currentPage = page.number;
          this.isLoading = false;
        },
        error: () => {
          this.reports = [];
          this.isLoading = false;
        }
      });
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadReports();
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
    if (this.isGenerating) return;
    this.isGenerating = true;
    this.reportService.generateReports().subscribe({
      next: (res) => {
        alert(`Report generation completed. ${res.reportsGenerated} report(s) generated.`);
        this.isGenerating = false;
        this.loadReports();
      },
      error: (err) => {
        alert('Report generation failed: ' + (err.error?.detail || err.message));
        this.isGenerating = false;
      }
    });
  }

  formatAmount(cents: number): string {
    return (cents / 100).toFixed(2);
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'GENERATED': return 'status-info';
      case 'SENT': return 'status-success';
      case 'FAILED': return 'status-danger';
      default: return 'status-default';
    }
  }

  getSendStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SENT': return 'status-success';
      case 'FAILED': return 'status-danger';
      case 'PENDING': return 'status-warning';
      default: return 'status-default';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
