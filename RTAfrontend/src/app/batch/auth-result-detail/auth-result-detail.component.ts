import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { AuthService } from '../../services/auth.service';
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
  selector: 'app-auth-result-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './auth-result-detail.component.html',
  styleUrl: './auth-result-detail.component.scss'
})
export class AuthResultDetailComponent implements OnInit, OnDestroy {
  private apiUrl = 'https://localhost:8086/api/batch-maintenance';

  authBatchId!: number;
  batch: BatchDetailData | null = null;
  isLoading = true;
  pdfUrl: SafeResourceUrl | null = null;
  private pdfBlobUrl: string | null = null;

  constructor(
    private http: HttpClient,
    private router: Router,
    private route: ActivatedRoute,
    private authService: AuthService,
    private sanitizer: DomSanitizer
  ) {}

  ngOnInit(): void {
    this.authBatchId = Number(this.route.snapshot.paramMap.get('authBatchId'));
    this.loadAndGeneratePdf();
  }

  loadAndGeneratePdf(): void {
    this.isLoading = true;
    this.http.get<BatchDetailData>(`${this.apiUrl}/detail/${this.authBatchId}`).subscribe({
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
    const originalFilename = files.length > 0 ? files[0].originalFilename : '–';
    const validationStatus = files.length > 0 ? files[0].fileStatus : '–';
    const authStatus = validationStatus?.toUpperCase() === 'FAILED' ? '–' : (data.batchStatus || '–');

    let y = 18;

    // ===== Title =====
    doc.setFontSize(16);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('AUTHORIZATION RESULT REPORT', pageWidth / 2, y, { align: 'center' });
    y += 6;

    // Divider line
    doc.setDrawColor(0, 0, 0);
    doc.setLineWidth(0.5);
    doc.line(margin, y, pageWidth - margin, y);
    y += 8;

    // ===== Batch & Merchant Information (two-column layout) =====
    doc.setFontSize(10);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('BATCH INFORMATION', margin, y);
    doc.text('MERCHANT INFORMATION', pageWidth / 2 + 10, y);
    y += 5;

    const infoData = [
      ['Batch Reference', data.batchReference, 'Merchant ID', data.merchantId || '–'],
      ['Batch ID', String(data.authBatchId), 'Merchant Name', data.merchantName || '–'],
      ['Validation Status', validationStatus, 'Merchant Account', data.merchantAccount || '–'],
      ['Auth Status', authStatus, 'Merchant Contact', data.merchantContact || '–'],
      ['Original File', originalFilename, 'Send Auth Datetime', this.formatDate(data.lastModifiedAt)],
      ['Remark', data.remark || '–', '', ''],
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

    // Filter to only auth-processed transactions (exclude validation-failed)
    const authTransactions = transactions.filter(t => t.validationStatus !== 'FAILED');

    const approvedCount = authTransactions.filter(t => t.status === 'APPROVED').length;
    const failedCount = authTransactions.filter(t => t.status === 'FAILED' || t.status === 'DECLINED').length;
    const pendingCount = authTransactions.filter(t => t.status === 'PENDING').length;

    doc.setFontSize(9);
    doc.setFont('helvetica', 'normal');
    doc.setTextColor(0, 0, 0);
    doc.text(`Total: ${authTransactions.length}    |    Approved: ${approvedCount}    |    Failed: ${failedCount}    |    Pending: ${pendingCount}`, margin, y);
    y += 7;

    // ===== Transaction Table (grouped by Batch File ID) =====
    doc.setFontSize(10);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('TRANSACTION DETAILS', margin, y);
    y += 3;

    // Group transactions by batchFileId
    const grouped = new Map<number, TransactionResult[]>();
    for (const txn of authTransactions) {
      const key = txn.batchFileId;
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key)!.push(txn);
    }

    const tableHead = [['#', 'Txn ID', 'Merchant ID', 'Customer', 'Acc Number', 'Amount', 'Currency', 'Auth Result', 'Decision Reason', 'Auth Time']];

    let rowNum = 0;
    const tableBody: any[][] = [];
    for (const [fileId, groupTxns] of grouped) {
      // Group header row
      const file = files.find(f => f.batchFileId === fileId);
      const groupLabel = file ? `File: ${file.originalFilename} (ID: ${fileId})` : `Batch File ID: ${fileId}`;
      tableBody.push([{ content: groupLabel, colSpan: 10, styles: { fontStyle: 'bold', fillColor: [220, 220, 220], fontSize: 7.5 } }]);
      for (const txn of groupTxns) {
        rowNum++;
        tableBody.push([
          String(rowNum),
          String(txn.transactionId),
          txn.merchantId || '–',
          txn.merchantCustomer || '–',
          txn.maskedPan || '–',
          this.formatAmount(txn.amount),
          txn.currency || '–',
          txn.status || '–',
          txn.remark || '–',
          this.formatDate(txn.authorizationDatetime),
        ]);
      }
    }

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
        1: { cellWidth: 18, halign: 'center' },
        2: { cellWidth: 25 },
        3: { cellWidth: 28 },
        4: { cellWidth: 30, font: 'courier' },
        5: { cellWidth: 22, halign: 'right' },
        6: { cellWidth: 16, halign: 'center' },
        7: { cellWidth: 22, halign: 'center' },
        8: { cellWidth: 45 },
        9: { cellWidth: 35 },
      },
      margin: { left: margin, right: margin },
    });

    // ===== Footer on each page =====
    const totalPdfPages = doc.getNumberOfPages();
    for (let i = 1; i <= totalPdfPages; i++) {
      doc.setPage(i);
      const pageH = doc.internal.pageSize.getHeight();
      // Bottom line
      doc.setDrawColor(0, 0, 0);
      doc.setLineWidth(0.3);
      doc.line(margin, pageH - 12, pageWidth - margin, pageH - 12);
      // Footer text
      doc.setFontSize(7);
      doc.setFont('helvetica', 'normal');
      doc.setTextColor(100, 100, 100);
      doc.text(`Generated on ${new Date().toLocaleString()}`, margin, pageH - 8);
      doc.text(`Page ${i} of ${totalPdfPages}`, pageWidth - margin, pageH - 8, { align: 'right' });
    }

    // Generate blob URL for embedded preview
    const pdfBlob = doc.output('blob');
    if (this.pdfBlobUrl) URL.revokeObjectURL(this.pdfBlobUrl);
    this.pdfBlobUrl = URL.createObjectURL(pdfBlob);
    this.pdfUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.pdfBlobUrl);
  }

  downloadPdf(): void {
    if (!this.batch || !this.pdfBlobUrl) return;
    const link = document.createElement('a');
    link.href = this.pdfBlobUrl;
    link.download = `Auth_Result_${this.batch.batchReference}.pdf`;
    link.click();
  }

  private formatAmount(cents: number): string {
    if (cents == null) return '–';
    return (cents / 100).toFixed(2);
  }

  private formatDate(dateStr: string): string {
    if (!dateStr) return '–';
    try {
      const d = new Date(dateStr);
      return d.toISOString().replace('T', ' ').substring(0, 19);
    } catch {
      return dateStr;
    }
  }

  goBack(): void {
    this.router.navigate(['/check-auth-result']);
  }

  ngOnDestroy(): void {
    if (this.pdfBlobUrl) URL.revokeObjectURL(this.pdfBlobUrl);
  }
}
