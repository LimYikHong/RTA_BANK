import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ReportService, RtaReport } from '../../services/report.service';
import { AuthService } from '../../services/auth.service';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

interface TransactionResult {
  transactionId: number;
  batchFileId: number;
  merchantId: string;
  merchantCustomer: string;
  maskedPan: string;
  amount: number;
  currency: string;
  actualBillingDate: string;
  status: string;
  validationStatus: string;
  remark: string;
  authorizationDatetime: string;
  createdAt: string;
}

interface FileInfo {
  batchFileId: number;
  originalFilename: string;
  merchantId: string;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  fileStatus: string;
  createdAt: string;
}

interface BatchDetailData {
  authBatchId: number;
  batchReference: string;
  totalCount: number;
  successCount: number;
  failCount: number;
  totalAmountCents: number;
  batchStatus: string;
  createdAt: string;
  lastModifiedAt: string;
  remark: string;
  merchantId: string;
  merchantName: string;
  merchantAccount: string;
  merchantContact: string;
  files: FileInfo[];
  transactions: TransactionResult[];
}

@Component({
  selector: 'app-report-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './report-detail.component.html',
  styleUrl: './report-detail.component.scss'
})
export class ReportDetailComponent implements OnInit, OnDestroy {
  private batchApiUrl = 'https://localhost:8086/api/batch-maintenance';

  report: RtaReport | null = null;
  batch: BatchDetailData | null = null;
  isLoading = true;
  pdfUrl: SafeResourceUrl | null = null;
  private pdfBlobUrl: string | null = null;
  reportId = 0;

  constructor(
    private http: HttpClient,
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
        if (report.authBatchId) {
          this.loadBatchAndGeneratePdf(report.authBatchId);
        } else {
          this.isLoading = false;
        }
      },
      error: () => {
        alert('Failed to load report.');
        this.router.navigate(['/report-list']);
      }
    });
  }

  loadBatchAndGeneratePdf(authBatchId: number): void {
    this.http.get<BatchDetailData>(`${this.batchApiUrl}/detail/${authBatchId}?includeAll=true`).subscribe({
      next: (data) => {
        this.batch = data;
        this.generatePdf(data);
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load batch detail:', err);
        this.isLoading = false;
      }
    });
  }

  private generatePdf(data: BatchDetailData): void {
    const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' });
    const pageWidth = doc.internal.pageSize.getWidth();
    const margin = 15;
    const transactions = data.transactions || [];
    const files = data.files || [];
    const originalFilename = files.length > 0 ? files[0].originalFilename : '\u2013';
    const validationStatus = files.length > 0 ? files[0].fileStatus : '\u2013';
    const authStatus = validationStatus?.toUpperCase() === 'FAILED' ? '\u2013' : (data.batchStatus || '\u2013');

    let y = 18;

    // ===== Title =====
    doc.setFontSize(16);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('BATCH FILE RESULT REPORT', pageWidth / 2, y, { align: 'center' });
    y += 6;

    // Divider line
    doc.setDrawColor(0, 0, 0);
    doc.setLineWidth(0.5);
    doc.line(margin, y, pageWidth - margin, y);
    y += 8;

    // ===== Batch & Merchant Information =====
    doc.setFontSize(10);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('BATCH INFORMATION', margin, y);
    doc.text('MERCHANT INFORMATION', pageWidth / 2 + 10, y);
    y += 5;

    const infoData = [
      ['Batch Reference', data.batchReference, 'Merchant ID', data.merchantId || '\u2013'],
      ['Batch ID', String(data.authBatchId), 'Merchant Name', data.merchantName || '\u2013'],
      ['Validation Status', validationStatus, 'Merchant Account', data.merchantAccount || '\u2013'],
      ['Auth Status', authStatus, 'Merchant Contact', data.merchantContact || '\u2013'],
      ['Original File', originalFilename, 'Send Auth Datetime', this.formatDate(data.lastModifiedAt)],
      ['Remark', data.remark || '\u2013', '', ''],
    ];

    autoTable(doc, {
      startY: y,
      body: infoData,
      theme: 'plain',
      styles: { fontSize: 8.5, cellPadding: 2.5, textColor: [0, 0, 0] },
      columnStyles: {
        0: { fontStyle: 'bold', cellWidth: 35 },
        1: { cellWidth: pageWidth / 2 - margin - 35 },
        2: { fontStyle: 'bold', cellWidth: 40 },
        3: { cellWidth: pageWidth / 2 - margin - 40 },
      },
      margin: { left: margin, right: margin },
    });

    y = (doc as any).lastAutoTable.finalY + 4;

    // ===== Summary line =====
    doc.setDrawColor(180, 180, 180);
    doc.setLineWidth(0.3);
    doc.line(margin, y, pageWidth - margin, y);
    y += 5;

    const approvedCount = transactions.filter(t => t.status === 'APPROVED').length;
    const validationFailedCount = transactions.filter(t => t.validationStatus === 'FAILED').length;
    const authFailedCount = transactions.filter(t => t.validationStatus !== 'FAILED' && (t.status === 'FAILED' || t.status === 'DECLINED')).length;
    const pendingCount = transactions.filter(t => t.status === 'PENDING').length;

    doc.setFontSize(9);
    doc.setFont('helvetica', 'normal');
    doc.setTextColor(0, 0, 0);
    doc.text(`Total: ${transactions.length}    |    Approved: ${approvedCount}    |    Validation Failed: ${validationFailedCount}    |    Auth Failed: ${authFailedCount}    |    Pending: ${pendingCount}`, margin, y);
    y += 7;

    // ===== Transaction Table =====
    doc.setFontSize(10);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('TRANSACTION DETAILS', margin, y);
    y += 3;

    const tableHead = [['#', 'Txn ID', 'Merchant ID', 'Customer', 'Acc Number', 'Amount', 'Currency', 'Validation', 'Auth Status', 'Decision Reason', 'Auth Time']];
    const tableBody = transactions.map((txn, i) => {
      const isValidationFailed = txn.validationStatus === 'FAILED';
      const txnValidation = txn.validationStatus || 'PASSED';
      const txnAuthStatus = isValidationFailed ? '\u2013' : (txn.status || '\u2013');
      return [
        String(i + 1),
        String(txn.transactionId),
        txn.merchantId || '\u2013',
        txn.merchantCustomer || '\u2013',
        txn.maskedPan || '\u2013',
        this.formatAmount(txn.amount),
        txn.currency || '\u2013',
        txnValidation,
        txnAuthStatus,
        isValidationFailed ? (txn.remark || '\u2013') : (txn.remark || '\u2013'),
        isValidationFailed ? '\u2013' : this.formatDate(txn.authorizationDatetime),
      ];
    });

    autoTable(doc, {
      startY: y,
      head: tableHead,
      body: tableBody,
      theme: 'grid',
      headStyles: {
        fillColor: [50, 50, 50],
        textColor: [255, 255, 255],
        fontStyle: 'bold',
        fontSize: 7.5,
        halign: 'center',
      },
      bodyStyles: {
        fontSize: 7,
        textColor: [0, 0, 0],
        cellPadding: 2,
      },
      alternateRowStyles: {
        fillColor: [245, 245, 245],
      },
      columnStyles: {
        0: { cellWidth: 8, halign: 'center' },
        1: { cellWidth: 16, halign: 'center' },
        2: { cellWidth: 22 },
        3: { cellWidth: 25 },
        4: { cellWidth: 28, font: 'courier' },
        5: { cellWidth: 20, halign: 'right' },
        6: { cellWidth: 14, halign: 'center' },
        7: { cellWidth: 20, halign: 'center' },
        8: { cellWidth: 20, halign: 'center' },
        9: { cellWidth: 40 },
        10: { cellWidth: 34 },
      },
      margin: { left: margin, right: margin },
    });

    // ===== Footer on each page =====
    const totalPdfPages = doc.getNumberOfPages();
    for (let i = 1; i <= totalPdfPages; i++) {
      doc.setPage(i);
      const pageH = doc.internal.pageSize.getHeight();
      doc.setDrawColor(0, 0, 0);
      doc.setLineWidth(0.3);
      doc.line(margin, pageH - 12, pageWidth - margin, pageH - 12);
      doc.setFontSize(7);
      doc.setFont('helvetica', 'normal');
      doc.setTextColor(100, 100, 100);
      doc.text(`Generated on ${new Date().toLocaleString()}`, margin, pageH - 8);
      doc.text(`Page ${i} of ${totalPdfPages}`, pageWidth - margin, pageH - 8, { align: 'right' });
    }

    const pdfBlob = doc.output('blob');
    if (this.pdfBlobUrl) URL.revokeObjectURL(this.pdfBlobUrl);
    this.pdfBlobUrl = URL.createObjectURL(pdfBlob);
    this.pdfUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.pdfBlobUrl);
  }

  downloadPdf(): void {
    if (!this.batch || !this.pdfBlobUrl) return;
    const link = document.createElement('a');
    link.href = this.pdfBlobUrl;
    link.download = `Batch_Result_${this.batch.batchReference}.pdf`;
    link.click();
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

  private formatAmount(cents: number): string {
    if (cents == null) return '\u2013';
    return (cents / 100).toFixed(2);
  }

  private formatDate(dateStr: string): string {
    if (!dateStr) return '\u2013';
    try {
      const d = new Date(dateStr);
      return d.toISOString().replace('T', ' ').substring(0, 19);
    } catch {
      return dateStr;
    }
  }

  back(): void {
    this.router.navigate(['/report-list']);
  }

  ngOnDestroy(): void {
    if (this.pdfBlobUrl) URL.revokeObjectURL(this.pdfBlobUrl);
  }
}
