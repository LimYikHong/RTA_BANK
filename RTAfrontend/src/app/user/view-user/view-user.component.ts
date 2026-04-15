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
      case 'DISABLED': return 'status-disabled';
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

  // --- Disable / Enable user ---
  showDisableModal = false;
  isDisabling = false;

  /** Whether the viewed user is the currently logged-in user */
  get isSelf(): boolean {
    const currentUser = this.authService.getCurrentUser();
    return !!currentUser && !!this.profile && currentUser.userId === this.profile.userId;
  }

  /** Can the current user disable/enable the viewed user? */
  get canToggleStatus(): boolean {
    // Don't show on own profile view, and user needs permission
    return !this.isOwnProfile && this.hasPermission('USER_EDIT');
  }

  get isDisabledUser(): boolean {
    const status = this.profile?.status?.toUpperCase();
    return status === 'DISABLED' || status === 'INACTIVE';
  }

  openDisableModal(): void {
    this.showDisableModal = true;
  }

  closeDisableModal(): void {
    this.showDisableModal = false;
    this.isDisabling = false;
  }

  confirmDisable(): void {
    if (!this.profile) return;
    this.isDisabling = true;
    this.profileService.disableUser(this.profile.userId).subscribe({
      next: () => {
        this.closeDisableModal();
        this.loadUser(this.profile!.userId);
      },
      error: (err) => {
        console.error('Failed to disable user:', err);
        this.isDisabling = false;
        alert('Failed to disable user.');
      }
    });
  }

  confirmEnable(): void {
    if (!this.profile) return;
    this.isDisabling = true;
    this.profileService.enableUser(this.profile.userId).subscribe({
      next: () => {
        this.isDisabling = false;
        this.loadUser(this.profile!.userId);
      },
      error: (err) => {
        console.error('Failed to enable user:', err);
        this.isDisabling = false;
        alert('Failed to enable user.');
      }
    });
  }

  // --- Delete user ---
  showDeleteModal = false;
  isDeleting = false;

  /** Can the current user delete the viewed user? */
  get canDelete(): boolean {
    // SuperAdmin cannot delete themselves
    if (this.isSelf && this.isSuperAdmin) return false;
    return !this.isOwnProfile && this.hasPermission('USER_DELETE');
  }

  openDeleteModal(): void {
    this.showDeleteModal = true;
  }

  closeDeleteModal(): void {
    this.showDeleteModal = false;
    this.isDeleting = false;
  }

  confirmDelete(): void {
    if (!this.profile) return;
    this.isDeleting = true;
    this.profileService.deleteUser(this.profile.userId).subscribe({
      next: () => {
        this.closeDeleteModal();
        this.router.navigate(['/users']);
      },
      error: (err) => {
        console.error('Failed to delete user:', err);
        this.isDeleting = false;
        const msg = err?.error || 'Failed to delete user.';
        alert(msg);
      }
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
