import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserProfile } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-view-profile',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './view-profile.component.html',
  styleUrl: './view-profile.component.scss'
})
export class ViewProfileComponent implements OnInit {
  profile: UserProfile | null = null;

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    const cached = this.profileService.getProfile();
    if (cached && cached.userId) {
      this.profileService.fetchProfile(cached.userId).subscribe({
        next: (data) => {
          this.profile = data;
        },
        error: () => {
          this.profile = cached;
        }
      });
    } else {
      this.profile = cached;
    }
  }

  openPhotoUpload(): void {
    this.fileInput.nativeElement.click();
  }

  onFileSelected(event: any): void {
    const file = event.target.files[0];
    if (file && this.profile) {
      this.profileService.uploadProfilePhoto(this.profile.userId, file).subscribe({
        next: (res) => {
          this.profile = res;
          this.profileService.setProfile(res);
          alert('Photo uploaded successfully!');
        },
        error: (err) => {
          console.error('Photo upload failed:', err);
          alert('Failed to upload photo!');
        }
      });
    }
  }

  getStatusClass(status?: string): string {
    switch (status?.toUpperCase()) {
      case 'ACTIVE': return 'status-active';
      case 'INACTIVE': return 'status-inactive';
      case 'SUSPENDED': return 'status-suspended';
      default: return '';
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
