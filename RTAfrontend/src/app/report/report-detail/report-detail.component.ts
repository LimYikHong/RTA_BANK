import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { ReportService, RtaReport } from '../../services/report.service';
import { AuthService } from '../../services/auth.service';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'app-report-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './report-detail.component.html',
  styleUrl: './report-detail.component.scss'
})
export class ReportDetailComponent implements OnInit {
  report: RtaReport | null = null;
  reportHtml: SafeHtml | null = null;
  isLoading = true;
  reportId = 0;

  constructor(
    private reportService: ReportService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private sanitizer: DomSanitizer
  ) {}

  ngOnInit(): void {
    this.reportId = Number(this.route.snapshot.paramMap.get('reportId')) || 0;
    if (!this.reportId) {
      this.router.navigate(['/report-list']);
      return;
    }
    this.loadReport();
  }

  loadReport(): void {
    this.isLoading = true;
    this.reportService.getReport(this.reportId).subscribe({
      next: (report) => {
        this.report = report;
        this.loadReportContent();
      },
      error: () => {
        alert('Failed to load report.');
        this.router.navigate(['/report-list']);
      }
    });
  }

  loadReportContent(): void {
    this.reportService.downloadReport(this.reportId).subscribe({
      next: (blob) => {
        blob.text().then(html => {
          this.reportHtml = this.sanitizer.bypassSecurityTrustHtml(html);
          this.isLoading = false;
        });
      },
      error: () => {
        this.isLoading = false;
      }
    });
  }

  downloadOutput(): void {
    if (!this.report) return;
    this.reportService.downloadOutputFile(this.reportId).subscribe(blob => {
      const a = document.createElement('a');
      a.href = window.URL.createObjectURL(blob);
      const ext = this.report?.fileFormat === 'XLSX' ? '.xlsx' : '.csv';
      a.download = (this.report?.reportName || 'output') + ext;
      a.click();
    });
  }

  printReport(): void {
    window.print();
  }

  formatAmount(cents: number): string {
    return (cents / 100).toFixed(2);
  }

  back(): void {
    this.router.navigate(['/report-list']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
