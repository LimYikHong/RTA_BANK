import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * RtaBatch
 * - Minimal shape for batch list, upload result, etc.
 * - Optional fields (id/createdBy/createdAt) are filled by backend.
 */
export interface RtaBatch {
  batchId?: number;
  fileName: string;
  originalFileName?: string;
  status: string;
  merchantId: string;
  createdBy?: string;
  createdAt?: string;
}

/**
 * IncomingBatchFile
 * - Shape for incoming batch files uploaded via the validation pipeline.
 * - Includes validation status, record counts, and merchant info.
 */
export interface IncomingBatchFile {
  batchFileId: number;
  merchantId: string;
  batchId: number | null;
  originalFilename: string;
  storedFilename: string;
  storageUri: string;
  sizeBytes: number;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  fileStatus: string;
  batchStatus: string;
  createBy: string;
  createdAt: string;
  lastModifiedAt: string;
  lastModifiedBy: string;
  authBatchId?: number;
  authBatchStatus?: string;
  authBatchReference?: string;
}

/**
 * UploadHistoryItem
 * - Shape for upload history records from rta_uploaded_file_hash table.
 * - Shows ALL upload attempts including failed validations.
 */
export interface UploadHistoryItem {
  id: number;
  merchantId: string;
  originalFilename: string;
  storedFilename: string;
  fileHash: string;
  uploadedAt: string;
  status: string;
  uploadCount: number;
  validationRemark: string | null;
  createdBy: string;
  sizeBytes: number | null;
}

/**
 * UploadResponse
 * - Shape for the response from the incoming upload endpoint.
 */
export interface UploadResponse {
  message: string;
  batchFileId?: number;
  hashId: number;
  fileName: string;
  originalFileName: string;
  fileHash: string;
  sizeBytes: number;
  status: string;
  totalRecords: number;
  successCount: number;
  failCount: number;
  totalAmount: number;
  duplicateTransactionCount?: number;
  duplicateTransactions?: string[];
  validationErrors?: string[];
  error?: string;
  detail?: string;
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
  private apiUrl = 'https://localhost:8086/api/batches';
  private incomingApiUrl = 'https://localhost:8086/api/incoming';

  constructor(private http: HttpClient) {}
  /**
   * GET /api/batches
   * - Fetch batches for the given merchantId (current user).
   * - If no merchantId is provided, returns all batches.
   */
  getBatches(merchantId?: string): Observable<RtaBatch[]> {
    if (merchantId) {
      return this.http.get<RtaBatch[]>(this.apiUrl, {
        params: { merchantId }
      });
    }
    return this.http.get<RtaBatch[]>(this.apiUrl);
  }

  /**
   * GET /api/incoming/files
   * - Fetch incoming batch files (validated uploads).
   * - Optionally filtered by merchantId.
   */
  getIncomingFiles(merchantId?: string): Observable<IncomingBatchFile[]> {
    if (merchantId) {
      return this.http.get<IncomingBatchFile[]>(`${this.incomingApiUrl}/files`, {
        params: { merchantId }
      });
    }
    return this.http.get<IncomingBatchFile[]>(`${this.incomingApiUrl}/files`);
  }

  /**
   * GET /api/incoming/upload-history
   * - Fetch all upload attempts (from rta_uploaded_file_hash).
   * - Includes both passed and failed validations.
   */
  getUploadHistory(): Observable<UploadHistoryItem[]> {
    return this.http.get<UploadHistoryItem[]>(`${this.incomingApiUrl}/upload-history`);
  }

  /**
   * POST /api/incoming/upload
   * - Uploads a batch file through the incoming validation pipeline.
   * - Performs: merchant validation, duplicate detection, file format check,
   *   content validation, transaction record creation.
   * - Returns detailed validation results.
   */
  uploadIncoming(
    file: File,
    merchantId: string,
    originalFileName: string,
    createdBy: string
  ): Observable<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('merchantId', merchantId);
    formData.append('originalFileName', originalFileName);
    formData.append('createdBy', createdBy);
    return this.http.post<UploadResponse>(`${this.incomingApiUrl}/upload`, formData);
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
