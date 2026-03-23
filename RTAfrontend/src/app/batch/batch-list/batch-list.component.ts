import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router } from '@angular/router';
import { PortalService, RtaBatch } from '../../services/portal.service';
import { ProfileService, UserProfile } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

/**
 * BatchListComponent
 * - Shows uploaded RTA batch files for the current user
 * - Allows uploading a new batch file (renamed with userId + timestamp)
 * - Supports viewing simple batch details and deleting a batch
 * - Demonstrates Angular features: standalone component, routing links, *ngFor, pipes, service calls, error handling
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

  batches: RtaBatch[] = [];
  // Holds the file chosen from the input
  selectedFile?: File;
  user: UserProfile | null = null;

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

  get sortedBatches(): RtaBatch[] {
    let list = [...this.batches];
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

  get totalElements(): number { return this.batches.length; }
  get totalPages(): number { return Math.max(1, Math.ceil(this.batches.length / this.pageSize)); }
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

  // Inject services:
  // - PortalService: HTTP calls for batches (list/upload/delete)
  // - ProfileService: provides current merchant profile

  constructor(
    private portalService: PortalService,
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  // read user profile from cache and load batch list
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

    this.loadBatches();
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }

  // Fetch batches from backend and update table
  loadBatches(): void {
    this.portalService.getBatches().subscribe({
      next: (data) => (this.batches = data),
      error: (err) => console.error('Failed to fetch batches: ' + err.message),
    });
  }

  // Handle <input type="file"> change event
  onFileSelected(event: any): void {
    this.selectedFile = event.target.files[0];
  }

  // Upload selected file:
  // - Guard: require both file and merchant
  // - Rename file to {userId}_{yyyy-mm-dd_HH-mm-ss}.xlsx before upload
  // - Call service and refresh list; log success/error
  uploadBatch(): void {
    if (!this.selectedFile || !this.user) {
      alert('Missing file or user info.');
      return;
    }

    // Build timestamp for unique file naming
    const originalFileName = this.selectedFile.name;
    const timestamp = new Date();
    const formattedTime = `${timestamp.getFullYear()}-${(
      timestamp.getMonth() + 1
    )
      .toString()
      .padStart(2, '0')}-${timestamp
      .getDate()
      .toString()
      .padStart(2, '0')}_${timestamp
      .getHours()
      .toString()
      .padStart(2, '0')}-${timestamp
      .getMinutes()
      .toString()
      .padStart(2, '0')}-${timestamp.getSeconds().toString().padStart(2, '0')}`;

    // Create a new File object with the new name (content unchanged)
    const newFileName = `${this.user.userId}_${formattedTime}.xlsx`;
    const renamedFile = new File([this.selectedFile], newFileName, {
      type: this.selectedFile.type,
    });

    // Call upload API with renamed file, userId and original name for audit trail
    this.portalService
      .uploadBatch(renamedFile, this.user.userId, originalFileName)
      .subscribe({
        next: (res) => {
          console.log(`File ${res.fileName} uploaded successfully`);
          this.loadBatches();
        },
        error: (err) => {
          console.error(`Upload failed: ${err.message}`);
          this.loadBatches();
        },
      });
  }

  // Show a simple alert with batch details (for quick view)
  viewBatch(id: number): void {
    const batch = this.batches.find((b) => b.batchId === id);
    if (!batch) {
      console.error(`Batch with ID ${id} not found`);
      return;
    }
    alert(
      `📄 Batch Details:\nFile: ${batch.fileName}\nUser: ${batch.merchantId}\nStatus: ${batch.status}`
    );
  }

  /**
   * Strip the leading timestamp prefix from storedFileName.
   * storedFileName format: "1771837642067_M007_2026-02-23_17-07-16.txt"
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

  // Delete a batch by id and refresh table
  deleteBatch(id: number): void {
    this.portalService.deleteBatch(id).subscribe({
      next: () => {
        console.log(`Batch ID ${id} deleted.`);
        this.loadBatches();
      },
      error: (err) => console.error(`Failed to delete batch: ${err.message}`),
    });
  }
}
