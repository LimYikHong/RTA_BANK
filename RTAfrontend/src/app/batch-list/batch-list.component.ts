import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router } from '@angular/router';
import { PortalService, RtaBatch } from '../services/portal.service';
import { ProfileService, MerchantProfile } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

/**
 * BatchListComponent
 * - Shows uploaded RTA batch files for the current merchant
 * - Allows uploading a new batch file (renamed with merchantId + timestamp)
 * - Supports viewing simple batch details and deleting a batch
 * - Demonstrates Angular features: standalone component, routing links, *ngFor, pipes, service calls, error handling
 */

@Component({
  selector: 'app-batch-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './batch-list.component.html',
  styleUrl: './batch-list.component.scss'
})
export class BatchListComponent implements OnInit {
  batches: RtaBatch[] = [];
  // Holds the file chosen from the input
  selectedFile?: File;
  merchant: MerchantProfile | null = null;

  // Inject services:
  // - PortalService: HTTP calls for batches (list/upload/delete)
  // - ProfileService: provides current merchant profile

  constructor(
    private portalService: PortalService,
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  // read merchant profile from cache and load batch list
  ngOnInit(): void {
    this.merchant = this.profileService.getProfile();

    if (this.merchant && this.merchant.merchantId) {
      this.profileService.fetchProfile(this.merchant.merchantId).subscribe({
        next: (profile) => {
          this.merchant = profile;
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
  // - Rename file to {merchantId}_{yyyy-mm-dd_HH-mm-ss}.xlsx before upload
  // - Call service and refresh list; log success/error
  uploadBatch(): void {
    if (!this.selectedFile || !this.merchant) {
      alert('Missing file or merchant info.');
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
    const newFileName = `${this.merchant.merchantId}_${formattedTime}.xlsx`;
    const renamedFile = new File([this.selectedFile], newFileName, {
      type: this.selectedFile.type,
    });

    // Call upload API with renamed file, merchantId and original name for audit trail
    this.portalService
      .uploadBatch(renamedFile, this.merchant.merchantId, originalFileName)
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
      `📄 Batch Details:\nFile: ${batch.fileName}\nMerchant: ${batch.merchantId}\nStatus: ${batch.status}`
    );
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
