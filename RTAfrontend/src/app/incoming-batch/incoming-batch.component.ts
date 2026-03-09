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
  storedFilename: string;
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
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  private apiUrl = 'https://localhost:8086/api/incoming';

  incomingFiles: IncomingBatchFile[] = [];
  filteredFiles: IncomingBatchFile[] = [];
  searchTerm = '';
  isLoading = true;
  
  // Retry validation
  retryingFileId: number | null = null;
  
  // Error modal
  showErrorModal = false;
  errorTitle = '';
  errorMessage = '';

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
      f.storedFilename?.toLowerCase().includes(term) ||
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
      case 'VALIDATED': return 'status-validated';
      case 'PARTIAL': return 'status-partial';
      case 'VALIDATION_FAILED': return 'status-failed';
      case 'VALIDATION_ERROR': return 'status-error';
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

  /**
   * Get display filename - shows the renamed file (merchantId_datetime.ext format)
   * Extracts from storedFilename by removing the leading timestamp prefix
   */
  getDisplayFilename(file: IncomingBatchFile): string {
    // Use storedFilename if available
    const filename = file.storedFilename || file.originalFilename;
    if (!filename) return '-';
    
    // The storedFilename format is: timestamp_merchantId_datetime.ext
    // We want to show: merchantId_datetime.ext (remove the leading timestamp_)
    const underscoreIndex = filename.indexOf('_');
    if (underscoreIndex > 0) {
      // Check if the part before first underscore looks like a timestamp (all digits)
      const prefix = filename.substring(0, underscoreIndex);
      if (/^\d+$/.test(prefix)) {
        return filename.substring(underscoreIndex + 1);
      }
    }
    return filename;
  }

  viewBatchDetail(batchId: number): void {
    this.router.navigate(['/batch-detail', batchId]);
  }

  retryValidation(file: IncomingBatchFile): void {
    if (this.retryingFileId) return; // Prevent multiple clicks
    
    this.retryingFileId = file.batchFileId;
    
    this.http.post<any>(`${this.apiUrl}/retry-validation/${file.batchFileId}`, {}).subscribe({
      next: (response) => {
        this.retryingFileId = null;
        // Reload the files to see updated status
        this.loadIncomingFiles();
      },
      error: (err) => {
        this.retryingFileId = null;
        console.error('Retry validation failed:', err);
        
        // Show error modal
        const errorData = err.error || {};
        this.errorTitle = errorData.error || 'Validation Failed';
        this.errorMessage = errorData.detail || err.message || 'An unexpected error occurred during validation retry.';
        this.showErrorModal = true;
      }
    });
  }

  closeErrorModal(): void {
    this.showErrorModal = false;
    this.errorTitle = '';
    this.errorMessage = '';
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
