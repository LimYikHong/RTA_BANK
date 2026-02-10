import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../services/auth.service';

interface IncomingBatchFile {
  batchFileId: number;
  merchantId: string;
  batchId: number;
  originalFilename: string;
  storageUri: string;
  sizeBytes: number;
  totalRecordCount: number;
  successCount: number;
  failCount: number;
  fileStatus: string;
  createBy: string;
  createdAt: string;
  lastModifiedAt: string;
  lastModifiedBy: string;
}

@Component({
  selector: 'app-incoming-batch',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './incoming-batch.component.html',
  styleUrl: './incoming-batch.component.scss'
})
export class IncomingBatchComponent implements OnInit {
  private apiUrl = 'https://localhost:8086/api/incoming';

  incomingFiles: IncomingBatchFile[] = [];
  filteredFiles: IncomingBatchFile[] = [];
  searchTerm = '';
  isLoading = true;

  constructor(
    private http: HttpClient,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadIncomingFiles();
  }

  loadIncomingFiles(): void {
    this.isLoading = true;
    this.http.get<IncomingBatchFile[]>(`${this.apiUrl}/files`).subscribe({
      next: (data) => {
        this.incomingFiles = data;
        this.filteredFiles = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load incoming files:', err);
        this.isLoading = false;
      }
    });
  }

  onSearch(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filteredFiles = this.incomingFiles;
      return;
    }
    this.filteredFiles = this.incomingFiles.filter(f =>
      f.originalFilename?.toLowerCase().includes(term) ||
      f.merchantId?.toLowerCase().includes(term) ||
      f.fileStatus?.toLowerCase().includes(term) ||
      f.createBy?.toLowerCase().includes(term)
    );
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'RECEIVED': return 'status-received';
      case 'PROCESSING': return 'status-processing';
      case 'COMPLETED': return 'status-completed';
      case 'FAILED': return 'status-failed';
      default: return '';
    }
  }

  formatBytes(bytes: number): string {
    if (!bytes) return '-';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
