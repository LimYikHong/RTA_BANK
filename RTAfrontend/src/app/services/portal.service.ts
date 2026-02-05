import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * RtaBatch
 * - Minimal shape for batch list, upload result, etc.
 * - Optional fields (id/createdBy/createdAt) are filled by backend.
 */
export interface RtaBatch {
  id?: number;
  fileName: string;
  status: string;
  merchantId: string;
  createdBy?: string;
  createdAt?: string;
}

@Injectable({
  providedIn: 'root',
})

/**
 * PortalService
 * - Wraps HTTP calls for batch operations: list, upload, process, delete.
 * - Keeps API URLs centralized and easy to change.
 */
export class PortalService {
  private apiUrl = 'http://localhost:8088/api/batches';

  constructor(private http: HttpClient) {}
  /**
   * GET /api/batches
   * - Fetch all batches (optionally filtered by backend auth/user).
   */
  getBatches(): Observable<RtaBatch[]> {
    return this.http.get<RtaBatch[]>(this.apiUrl);
  }

  getActivityLogs(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/activity`);
  }
  /**
   * POST /api/batches/upload
   * - Uploads a batch file using multipart/form-data.
   * - Includes merchantId and original file name for audit trail.
   * - Returns the created RtaBatch metadata from backend.
   */
  uploadBatch(
    file: File,
    merchantId: string,
    originalFileName: string
  ): Observable<RtaBatch> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('merchantId', merchantId);
    formData.append('originalFileName', originalFileName);
    return this.http.post<RtaBatch>(`${this.apiUrl}/upload`, formData);
  }
  /**
   * POST /api/batches/{id}/process
   * - Triggers server-side processing of a specific batch.
   * - Expects plain text response (status/summary).
   */
  processBatch(id: number): Observable<string> {
    return this.http.post(
      `${this.apiUrl}/${id}/process`,
      {},
      { responseType: 'text' }
    );
  }
  /**
   * DELETE /api/batches/{id}
   * - Removes a batch by id.
   */
  deleteBatch(id: number): Observable<any> {
    return this.http.delete(`${this.apiUrl}/${id}`);
  }
}
