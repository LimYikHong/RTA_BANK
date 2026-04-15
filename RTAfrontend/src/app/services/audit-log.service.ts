import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AuditLogEntry {
  logId: number;
  logType: string;       // 'USER' or 'SYSTEM'
  action: string;        // LOGIN, LOGOUT, UPLOAD_FILE, CREATE_USER, etc.
  userId: string | null;
  targetId: string | null;
  description: string | null;
  status: string | null;
  ipAddress: string | null;
  createdAt: string;
}

@Injectable({
  providedIn: 'root',
})
export class AuditLogService {
  private apiUrl = 'https://localhost:8086/api/audit-logs';

  constructor(private http: HttpClient) {}

  getUserActivityLogs(): Observable<AuditLogEntry[]> {
    return this.http.get<AuditLogEntry[]>(`${this.apiUrl}/user`);
  }

  getSystemActivityLogs(): Observable<AuditLogEntry[]> {
    return this.http.get<AuditLogEntry[]>(`${this.apiUrl}/system`);
  }

  getAllLogs(): Observable<AuditLogEntry[]> {
    return this.http.get<AuditLogEntry[]>(this.apiUrl);
  }
}
