import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserProfile } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
@Component({
  selector: 'app-edit-user',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './edit-user.component.html',
  styleUrl: './edit-user.component.scss'
})
export class EditUserComponent implements OnInit {
  editData: UserProfile = {
    userId: '',
    emailAddress: '',
    email: '',
    company: '',
    contact: '',
    address: '',
    phone: '',
    firstName: '',
    lastName: '',
    officeNumber: '',
    status: ''
  };

  userRole: string = 'ADMIN';
  isSubmitting = false;
  isLoading = true;

  /** Track where user came from: 'profile' or 'users' */
  private returnTo: string = 'users';

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    const userId = this.route.snapshot.paramMap.get('userId');
    this.returnTo = this.route.snapshot.queryParamMap.get('from') || 'users';

    if (!userId) {
      this.router.navigate(['/users']);
      return;
    }

    this.isLoading = true;

    // Fetch user profile
    this.profileService.fetchProfile(userId).subscribe({
      next: (data) => {
        this.editData = { ...data };
        // Fetch user role
        this.profileService.getUserRole(userId).subscribe({
          next: (res) => {
            this.userRole = res.role || 'ADMIN';
            this.isLoading = false;
          },
          error: () => {
            this.userRole = 'ADMIN';
            this.isLoading = false;
          }
        });
      },
      error: () => {
        this.isLoading = false;
        this.router.navigate(['/users']);
      }
    });
  }

  saveUser(): void {
    if (!this.editData.userId) return;
    this.isSubmitting = true;

    const currentUser = this.profileService.getProfile();
    this.editData.lastModifiedBy = currentUser?.username || 'unknown';

    // Update profile and role in parallel
    this.profileService.updateProfile(this.editData.userId, this.editData).subscribe({
      next: () => {
        // Also update role
        this.profileService.updateUserRole(this.editData.userId, this.userRole).subscribe({
          next: () => {
            this.isSubmitting = false;
            alert('User updated successfully!');
            this.goBack();
          },
          error: (err) => {
            this.isSubmitting = false;
            console.error('Role update failed:', err);
            alert('Profile updated but role update failed.');
            this.goBack();
          }
        });
      },
      error: (err) => {
        this.isSubmitting = false;
        console.error('Update failed:', err);
        alert('Failed to update user: ' + (err.error || err.message));
      }
    });
  }

  goBack(): void {
    if (this.returnTo === 'profile') {
      this.router.navigate(['/profile']);
    } else if (this.returnTo === 'view-user') {
      const userId = this.route.snapshot.paramMap.get('userId');
      this.router.navigate(['/view-user', userId]);
    } else {
      this.router.navigate(['/users']);
    }
  }

  cancel(): void {
    this.goBack();
  }

  get backLabel(): string {
    if (this.returnTo === 'profile') return '← Back to Profile';
    if (this.returnTo === 'view-user') return '← Back to User Details';
    return '← Back to Users';
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
