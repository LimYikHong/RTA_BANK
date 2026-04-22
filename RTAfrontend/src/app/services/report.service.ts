import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface RtaReport {
  reportId: number;
  merchantId: string;
  batchFileId: number | null;
  batchId: number | null;
  authBatchId: number | null;
  reportName: string;
  reportType: string;
  fileFormat: string;
  storageUri: string;
  outputFileUri: string;
  totalRecords: number;
  successCount: number;
  failCount: number;
  approvedCount: number;
  declinedCount: number;
  totalAmount: number;
  digitalSignature: string | null;
  status: string;
  sendStatus: string;
  sentAt: string | null;
  createdAt: string;
  createdBy: string;
}

export interface ReportPage {
  content: RtaReport[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Injectable({
  providedIn: 'root',
})
export class ReportService {
  private apiUrl = 'https://localhost:8086/api/reports';

  constructor(private http: HttpClient) {}

  getReports(page = 0, size = 20, merchantId?: string, search?: string): Observable<ReportPage> {
    let params: any = { page: page.toString(), size: size.toString() };
    if (merchantId) params.merchantId = merchantId;
    if (search) params.search = search;
    return this.http.get<ReportPage>(this.apiUrl, { params });
  }

  getReport(reportId: number): Observable<RtaReport> {
    return this.http.get<RtaReport>(`${this.apiUrl}/${reportId}`);
  }

  downloadReport(reportId: number): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/${reportId}/download`, { responseType: 'blob' });
  }

  downloadOutputFile(reportId: number): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/${reportId}/output`, { responseType: 'blob' });
  }

  generateReports(): Observable<any> {
    return this.http.post(`${this.apiUrl}/generate`, {});
  }

  generateReportForBatchFile(batchFileId: number, triggeredBy = 'USER'): Observable<RtaReport> {
    return this.http.post<RtaReport>(`${this.apiUrl}/generate/${batchFileId}`, null, {
      params: { triggeredBy }
    });
  }

  requestRsaKey(merchantId: string): Observable<any> {
    return this.http.post(`https://localhost:8086/api/merchant-keys/${merchantId}/rotate`, {});
  }

  getRsaKeyStatus(merchantId: string): Observable<any> {
    return this.http.get(`https://localhost:8086/api/merchant-keys/${merchantId}/status`);
  }
}
