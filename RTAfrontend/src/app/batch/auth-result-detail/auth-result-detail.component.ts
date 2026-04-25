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
    const pageHeight = doc.internal.pageSize.getHeight();
    const margin = 15;
    const transactions = data.transactions || [];
    const files = data.files || [];

    // Filter to only auth-processed transactions (exclude validation-failed)
    const authTransactions = transactions.filter(t => t.validationStatus !== 'FAILED' && t.validationStatus !== 'DUPLICATE');

    // Group transactions by batchFileId
    const grouped = new Map<number, TransactionResult[]>();
    for (const txn of authTransactions) {
      const key = txn.batchFileId;
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key)!.push(txn);
    }

    // Build ordered list of file sections (preserve file order)
    const fileSections: { file: FileInfo | undefined; fileId: number; txns: TransactionResult[] }[] = [];
    for (const file of files) {
      const txns = grouped.get(file.batchFileId);
      if (txns && txns.length > 0) {
        fileSections.push({ file, fileId: file.batchFileId, txns });
        grouped.delete(file.batchFileId);
      }
    }
    // Any remaining groups without a matching file entry
    for (const [fileId, txns] of grouped) {
      fileSections.push({ file: undefined, fileId, txns });
    }

    let y = 18;

    // ===== Title =====
    doc.setFontSize(16);
    doc.setFont('helvetica', 'bold');
    doc.setTextColor(0, 0, 0);
    doc.text('AUTHORIZATION RESULT REPORT', pageWidth / 2, y, { align: 'center' });
    y += 6;

    doc.setDrawColor(0, 0, 0);
    doc.setLineWidth(0.5);
    doc.line(margin, y, pageWidth - margin, y);
    y += 8;

    const tableHead = [['#', 'Txn ID', 'Merchant ID', 'Customer', 'Acc Number', 'Amount', 'Currency', 'Auth Result', 'Decision Reason', 'Auth Time']];

    // ===== Render each file section =====
    fileSections.forEach((section, sectionIdx) => {
      const file = section.file;
      const fileTxns = section.txns;
      const originalFilename = file ? file.originalFilename : '\u2013';
      const validationStatus = file ? file.fileStatus : '\u2013';
      const authStatus = validationStatus?.toUpperCase() === 'FAILED' ? '\u2013' : (data.batchStatus || '\u2013');

      // Check if we need a new page (if not enough room for info table header)
      if (sectionIdx > 0) {
        doc.addPage();
        y = 18;
      }

      // ===== Section Header =====
      doc.setFontSize(11);
      doc.setFont('helvetica', 'bold');
      doc.setTextColor(0, 0, 0);
      doc.text(`FILE ${sectionIdx + 1} OF ${fileSections.length}:  ${originalFilename}`, margin, y);
      y += 6;

      doc.setDrawColor(180, 180, 180);
      doc.setLineWidth(0.3);
      doc.line(margin, y, pageWidth - margin, y);
      y += 5;

      // ===== Batch & Merchant Info as a clean table =====
      const halfWidth = (pageWidth - margin * 2) / 2;
      const infoLeft = [
        ['Batch Reference', data.batchReference || '\u2013'],
        ['Batch ID', String(data.authBatchId)],
        ['Batch File ID', String(section.fileId)],
        ['Original File', originalFilename],
        ['Validation Status', validationStatus],
        ['Auth Status', authStatus],
        ['Remark', data.remark || '\u2013'],
      ];
      const approvedCount = fileTxns.filter(t => t.status === 'APPROVED').length;
      const failedCount = fileTxns.filter(t => t.status === 'FAILED' || t.status === 'DECLINED').length;
      const pendingCount = fileTxns.filter(t => t.status === 'PENDING').length;

      const infoRight = [
        ['Merchant ID', data.merchantId || '\u2013'],
        ['Merchant Name', data.merchantName || '\u2013'],
        ['Merchant Account', data.merchantAccount || '\u2013'],
        ['Merchant Contact', data.merchantContact || '\u2013'],
        ['Send Auth Datetime', this.formatDate(data.lastModifiedAt)],
        ['Total', String(fileTxns.length)],
        ['Approved / Failed / Pending', `${approvedCount} / ${failedCount} / ${pendingCount}`],
      ];

      // Combine into 4-column table
      const infoRows = infoLeft.map((left, i) => [left[0], left[1], infoRight[i][0], infoRight[i][1]]);

      autoTable(doc, {
        startY: y,
        head: [['BATCH INFORMATION', '', 'MERCHANT INFORMATION', '']],
        body: infoRows,
        theme: 'grid',
        headStyles: {
          fillColor: [50, 50, 50],
          textColor: [255, 255, 255],
          lineColor: [255, 255, 255],
          lineWidth: 0.5,
          fontStyle: 'bold',
          fontSize: 8.5,
          cellPadding: 3,
        },
        styles: { fontSize: 8, cellPadding: 2.5, textColor: [0, 0, 0] },
        columnStyles: {
          0: { fontStyle: 'bold', cellWidth: 35, fillColor: [240, 240, 240] },
          1: { cellWidth: halfWidth - 35 },
          2: { fontStyle: 'bold', cellWidth: 40, fillColor: [240, 240, 240] },
          3: { cellWidth: halfWidth - 40 },
        },
        margin: { left: margin, right: margin },
      });

      y = (doc as any).lastAutoTable.finalY + 5;

      // ===== Transaction Table for this file =====
      doc.setFontSize(10);
      doc.setFont('helvetica', 'bold');
      doc.setTextColor(0, 0, 0);
      doc.text('TRANSACTION DETAILS', margin, y);
      y += 3;

      const tableBody = fileTxns.map((txn, i) => [
        String(i + 1),
        String(txn.transactionId),
        txn.merchantId || '\u2013',
        txn.merchantCustomer || '\u2013',
        txn.maskedPan || '\u2013',
        this.formatAmount(txn.amount),
        txn.currency || '\u2013',
        txn.status || '\u2013',
        txn.remark || '\u2013',
        this.formatDate(txn.authorizationDatetime),
      ]);

      autoTable(doc, {
        startY: y,
        head: tableHead,
        body: tableBody,
        theme: 'grid',
        headStyles: {
          fillColor: [50, 50, 50],
          textColor: [255, 255, 255],
          lineColor: [255, 255, 255],
          lineWidth: 0.5,
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

      y = (doc as any).lastAutoTable.finalY + 10;
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
