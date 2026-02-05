import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, MerchantProfile } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

declare var bootstrap: any;

/**
 * ViewProfileComponent
 * - Displays the current merchant profile
 * - Allows inline editing via Bootstrap modal (company/contact/address)
 * - Supports profile photo selection + preview + upload
 * - Demonstrates: standalone component, @ViewChild, template-driven forms, service calls
 */
@Component({
  selector: 'app-view-profile',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './view-profile.component.html',
  styleUrl: './view-profile.component.scss'
})
export class ViewProfileComponent implements OnInit {
  profile: MerchantProfile | null = null;
  editData: MerchantProfile = {
    merchantId: '',
    name: '',
    email: '',
    company: '',
    contact: '',
    address: '',
    joinedOn: '',
    profilePhotoUrl: '',
  };
  selectedFile: File | null = null;
  previewUrl: string | ArrayBuffer | null = null;

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
      private profileService: ProfileService,
      private authService: AuthService,
      private router: Router
  ) {}

  ngOnInit(): void {
    this.profile = this.profileService.getProfile();
  }

  /**
   * Optionally re-fetch profile from backend (useful if opening page directly)
   * - On success: update both local state and service cache
   */

  loadProfile(merchantId: string): void {
    this.profileService.fetchProfile(merchantId).subscribe({
      next: (data) => {
        this.profile = data;
        this.profileService.setProfile(data);
      },
      error: (err) => {
        console.error('Failed to load profile:', err);
        alert('Failed to fetch profile data from server.');
      },
    });
  }

  openPhotoUpload() {
    this.fileInput.nativeElement.click();
  }

  /**
   * Handles <input type="file"> change:
   * - Stores the File
   * - Generates a base64 preview
   * - Opens the edit modal automatically for convenience
   */

  onFileSelected(event: any) {
    const file = event.target.files[0];
    if (file) {
      this.selectedFile = file;
      const reader = new FileReader();
      reader.onload = (e: any) => (this.previewUrl = e.target.result);
      reader.readAsDataURL(file);
      this.openEditModal();
    }
  }

  /**
   * Opens the Bootstrap modal and pre-fills editData with the current profile
   */

  openEditModal(): void {
    if (!this.profile) return;
    this.editData = { ...this.profile };
    const modalEl = document.getElementById('editProfileModal');
    const modal = new bootstrap.Modal(modalEl!);
    modal.show();
  }

  /**
   * Saves profile fields:
   * - First updates textual fields (company/contact/address)
   * - If a new photo is selected, chains the upload and then closes the modal
   */
  updateProfile(): void {
    if (!this.editData) return;

    this.profileService
      .updateProfile(this.editData.merchantId, this.editData)
      .subscribe({
        next: (res: MerchantProfile) => {
          if (this.selectedFile) {
            this.uploadPhoto(this.editData.merchantId);
          } else {
            this.profile = res;
            this.profileService.setProfile(res);
            const modalEl = document.getElementById('editProfileModal');
            const modal = bootstrap.Modal.getInstance(modalEl!);
            modal.hide();
            alert('Profile updated successfully!');
          }
        },
        error: (err: any) => {
          console.error('Update failed:', err);
          alert('Failed to update profile!');
        },
      });
  }

  /**
   * Uploads the selected profile photo (multipart/form-data)
   * - On success: refreshes local state/cache, resets preview, closes modal
   */
  uploadPhoto(merchantId: string): void {
    if (!this.selectedFile) return;

    this.profileService
      .uploadProfilePhoto(merchantId, this.selectedFile)
      .subscribe({
        next: (res: MerchantProfile) => {
          this.profile = res;
          this.profileService.setProfile(res);
          this.selectedFile = null;
          this.previewUrl = null;
          const modalEl = document.getElementById('editProfileModal');
          const modal = bootstrap.Modal.getInstance(modalEl!);
          modal.hide();
          alert('Profile updated successfully!');
        },
        error: (err: any) => {
          console.error(' Photo upload failed:', err);
          alert('Failed to upload photo!');
        },
      });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
