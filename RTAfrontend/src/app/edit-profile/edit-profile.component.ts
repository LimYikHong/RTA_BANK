import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserProfile } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-edit-profile',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './edit-profile.component.html',
  styleUrl: './edit-profile.component.scss'
})
export class EditProfileComponent implements OnInit {
  editData: UserProfile = {
    merchantId: '',
    name: '',
    email: '',
    company: '',
    contact: '',
    address: '',
    phone: '',
    firstName: '',
    lastName: '',
    officeNumber: ''
  };

  isSubmitting = false;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    const cached = this.profileService.getProfile();
    if (cached) {
      this.editData = { ...cached };
      // Also fetch fresh data from backend
      if (cached.merchantId) {
        this.profileService.fetchProfile(cached.merchantId).subscribe({
          next: (data) => {
            this.editData = { ...data };
          },
          error: () => {
            // keep cached data
          }
        });
      }
    } else {
      // No profile cached, redirect back
      this.router.navigate(['/profile']);
    }
  }

  saveProfile(): void {
    if (!this.editData.merchantId) return;
    this.isSubmitting = true;

    this.profileService.updateProfile(this.editData.merchantId, this.editData).subscribe({
      next: (res) => {
        this.isSubmitting = false;
        this.profileService.setProfile(res);
        alert('Profile updated successfully!');
        this.router.navigate(['/profile']);
      },
      error: (err) => {
        this.isSubmitting = false;
        console.error('Update failed:', err);
        alert('Failed to update profile: ' + (err.error || err.message));
      }
    });
  }

  cancel(): void {
    this.router.navigate(['/profile']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
