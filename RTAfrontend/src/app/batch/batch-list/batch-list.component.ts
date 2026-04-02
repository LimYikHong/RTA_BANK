import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router } from '@angular/router';
import { PortalService, UploadHistoryItem } from '../../services/portal.service';
import { ProfileService, UserProfile } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

interface MerchantOption {
  merchantId: string;
  name: string;
}

/**
 * BatchListComponent
 * - Shows upload history (ALL uploads including failed validations)
 * - Allows uploading a new batch file with merchant selection
 * - Shows validation status and remarks for each upload attempt
 */

@Component({
  selector: 'app-batch-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, TopBarComponent],
  templateUrl: './batch-list.component.html',
  styleUrl: './batch-list.component.scss'
})
export class BatchListComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  files: UploadHistoryItem[] = [];
  // Holds the file chosen from the input
  selectedFile?: File;
  user: UserProfile | null = null;

  // Merchant selection modal
  showMerchantModal = false;
  merchants: MerchantOption[] = [];
  selectedMerchantId = '';
  isLoadingMerchants = false;

  // Error modal
  showErrorModal = false;
  errorTitle = '';
  errorMessage = '';

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];

  sortKey: string = '';
  sortDir: 'asc' | 'desc' = 'asc';

  sortBy(key: string): void {
    if (this.sortKey === key) {
      if (this.sortDir === 'asc') {
        this.sortDir = 'desc';
      } else {
        this.sortKey = '';
        this.sortDir = 'asc';
      }
    } else {
      this.sortKey = key;
      this.sortDir = 'asc';
    }
  }

  get sortedFiles(): UploadHistoryItem[] {
    let list = [...this.files];
    if (this.sortKey) {
      list.sort((a, b) => {
        const av = (a as any)[this.sortKey] ?? '';
        const bv = (b as any)[this.sortKey] ?? '';
        const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
        return this.sortDir === 'asc' ? cmp : -cmp;
      });
    }
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  get totalElements(): number { return this.files.length; }
  get totalPages(): number { return Math.max(1, Math.ceil(this.files.length / this.pageSize)); }
  get startRecord(): number { return this.totalElements === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1; }
  get endRecord(): number { return Math.min(this.currentPage * this.pageSize, this.totalElements); }
  get visiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.currentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.totalPages) { end = this.totalPages; start = Math.max(1, end - maxVisible + 1); }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }
  goToPage(page: number): void { if (page >= 1 && page <= this.totalPages) this.currentPage = page; }
  onPageSizeChange(): void { this.currentPage = 1; }

  constructor(
    private portalService: PortalService,
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.user = this.profileService.getProfile();

    if (this.user && this.user.userId) {
      this.profileService.fetchProfile(this.user.userId).subscribe({
        next: (profile) => {
          this.user = profile;
        },
        error: (err) => console.error('Failed to refresh profile from DB', err),
      });
    }

    this.loadFiles();
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }

  // Fetch upload history from backend (all upload attempts including failed)
  loadFiles(): void {
    this.portalService.getUploadHistory().subscribe({
      next: (data) => (this.files = data),
      error: (err) => console.error('Failed to fetch upload history: ' + err.message),
    });
  }

  // Handle <input type="file"> change event
  onFileSelected(event: any): void {
    this.selectedFile = event.target.files[0];
  }

  // Upload selected file:
  // - Guard: require file
  // - Open merchant selection modal
  uploadBatch(): void {
    if (!this.selectedFile) {
      alert('Please select a file first.');
      return;
    }

    // Load merchants and show selection modal
    this.isLoadingMerchants = true;
    this.selectedMerchantId = '';
    this.profileService.getAllMerchants().subscribe({
      next: (data) => {
        this.merchants = data.map((m: any) => ({ merchantId: m.merchantId, name: m.name }));
        this.isLoadingMerchants = false;
        this.showMerchantModal = true;
      },
      error: (err) => {
        console.error('Failed to load merchants:', err);
        this.isLoadingMerchants = false;
        alert('Failed to load merchant list. Please try again.');
      }
    });
  }

  closeMerchantModal(): void {
    this.showMerchantModal = false;
    this.selectedMerchantId = '';
  }

  // Confirm upload after merchant selection — uses incoming validation pipeline
  confirmUpload(): void {
    if (!this.selectedMerchantId) {
      alert('Please select a merchant.');
      return;
    }
    if (!this.selectedFile || !this.user) {
      alert('Missing file or user info.');
      return;
    }

    const originalFileName = this.selectedFile.name;
    const createdBy = this.user.userId || 'unknown';
    const merchantId = this.selectedMerchantId;

    // Close modal immediately — don't make user wait
    this.closeMerchantModal();

    // Insert a temporary "UPLOADING" row at the top of the list
    const tempRow: UploadHistoryItem = {
      id: 0,
      merchantId: merchantId,
      originalFilename: originalFileName,
      storedFilename: '',
      fileHash: '',
      uploadedAt: new Date().toISOString(),
      status: 'UPLOADING',
      uploadCount: 0,
      validationRemark: 'Upload in progress…',
      createdBy: createdBy,
      sizeBytes: this.selectedFile.size
    };
    this.files = [tempRow, ...this.files];

    // Clear file input
    const fileRef = this.selectedFile;
    this.selectedFile = undefined;
    const fileInput = document.querySelector('.file-input') as HTMLInputElement;
    if (fileInput) fileInput.value = '';

    // Call incoming upload API (full validation pipeline)
    this.portalService
      .uploadIncoming(fileRef, merchantId, originalFileName, createdBy)
      .subscribe({
        next: () => {
          // Reload real data from backend to replace the temp row
          this.loadFiles();
        },
        error: (err) => {
          console.error('Upload failed:', err);
          // Remove the temp "UPLOADING" row immediately
          this.files = this.files.filter(f => f.status !== 'UPLOADING');
          // Show error modal with backend rejection message
          const errorData = err.error || {};
          this.errorTitle = errorData.error || 'Upload Failed';
          const detail = errorData.detail || err.message || 'An unexpected error occurred during upload.';
          const dupInfo = errorData.duplicateFileInfo;
          if (dupInfo) {
            this.errorMessage = detail +
              (dupInfo.merchantId && dupInfo.merchantId !== merchantId
                ? '\n\nOriginal upload: Merchant ' + dupInfo.merchantId + ' | Status: ' + dupInfo.status + ' | Uploaded at: ' + dupInfo.uploadedAt
                : '\n\nStatus: ' + dupInfo.status + ' | Uploaded at: ' + dupInfo.uploadedAt);
          } else {
            this.errorMessage = detail;
          }
          this.showErrorModal = true;
          // Also reload to ensure list is in sync
          this.loadFiles();
        },
      });
  }

  closeErrorModal(): void {
    this.showErrorModal = false;
    this.errorTitle = '';
    this.errorMessage = '';
  }

  // Navigate to batch file detail page (only for files that passed validation)
  viewFileDetail(batchFileId: number): void {
    this.router.navigate(['/batch-file-detail', batchFileId]);
  }

  /**
   * Strip the leading timestamp prefix from storedFilename.
   * storedFilename format: "1771837642067_M007_2026-02-23_17-07-16.txt"
   * Display format:                      "M007_2026-02-23_17-07-16.txt"
   */
  getDisplayFileName(fileName: string): string {
    if (!fileName) return '-';
    const underscoreIndex = fileName.indexOf('_');
    if (underscoreIndex > 0) {
      const prefix = fileName.substring(0, underscoreIndex);
      if (/^\d+$/.test(prefix)) {
        return fileName.substring(underscoreIndex + 1);
      }
    }
    return fileName;
  }

  // Check if file passed validation (saved to incoming batch file table)
  isValidated(status: string): boolean {
    return status === 'VALIDATED' || status === 'PARTIAL';
  }

  // Status badge CSS class helper
  getFileStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'VALIDATED': return 'status-success';
      case 'PARTIAL': return 'status-warning';
      case 'RECEIVED': return 'status-ready';
      case 'PENDING': return 'status-ready';
      case 'FAILED':
      case 'VALIDATION_FAILED':
      case 'WRONG_FILE_FORMAT':
      case 'NO_FILE_PROFILE':
      case 'NO_FIELD_MAPPING':
      case 'MISSING_HEADER':
      case 'VALIDATION_ERROR':
      case 'INVALID_FILE_CONTENT':
        return 'status-failed';
      case 'PROCESSING': return 'status-processing';
      case 'UPLOADING': return 'status-uploading';
      default: return '';
    }
  }
}
