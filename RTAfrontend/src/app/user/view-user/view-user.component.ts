import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserProfile } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';

@Component({
  selector: 'app-view-user',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './view-user.component.html',
  styleUrl: './view-user.component.scss'
})
export class ViewUserComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  profile: UserProfile | null = null;
  userRole: string = '';
  isLoading = true;

  /** true when viewing your own profile via /profile */
  isOwnProfile = false;

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  get isSuperAdmin(): boolean {
    return this.authService.isSuperAdmin();
  }

  hasPermission(perm: string): boolean {
    return this.authService.hasPermission(perm);
  }

  ngOnInit(): void {
    const userIdParam = this.route.snapshot.paramMap.get('userId');

    if (userIdParam) {
      // Accessed via /view-user/:userId (from user management)
      this.isOwnProfile = false;
      this.loadUser(userIdParam);
    } else {
      // Accessed via /profile (own profile)
      this.isOwnProfile = true;
      const cached = this.profileService.getProfile();
      if (cached?.userId) {
        this.loadUser(cached.userId);
      } else {
        this.isLoading = false;
      }
    }
  }

  private loadUser(userId: string): void {
    this.isLoading = true;
    this.profileService.fetchProfile(userId).subscribe({
      next: (data) => {
        this.profile = data;
        // Fetch role
        this.profileService.getUserRole(userId).subscribe({
          next: (res) => {
            this.userRole = res.role || 'N/A';
            this.isLoading = false;
          },
          error: () => {
            this.userRole = 'N/A';
            this.isLoading = false;
          }
        });
      },
      error: () => {
        this.isLoading = false;
        if (!this.isOwnProfile) {
          this.router.navigate(['/users']);
        }
      }
    });
  }

  openPhotoUpload(): void {
    this.fileInput?.nativeElement?.click();
  }

  onFileSelected(event: any): void {
    const file = event.target.files[0];
    if (file && this.profile) {
      this.profileService.uploadProfilePhoto(this.profile.userId, file).subscribe({
        next: (res) => {
          this.profile = res;
          if (this.isOwnProfile) {
            this.profileService.setProfile(res);
          }
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

  getRoleBadgeClass(role: string): string {
    switch (role?.toUpperCase()) {
      case 'SUPER_ADMIN': return 'role-super-admin';
      case 'ADMIN': return 'role-admin';
      default: return 'role-default';
    }
  }

  editUser(): void {
    if (this.profile) {
      const from = this.isOwnProfile ? 'profile' : 'view-user';
      this.router.navigate(['/edit-user', this.profile.userId], { queryParams: { from } });
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
