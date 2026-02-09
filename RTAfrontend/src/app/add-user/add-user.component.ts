import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserProfile } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-add-user',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './add-user.component.html',
  styleUrl: './add-user.component.scss'
})
export class AddUserComponent {
  newUser: UserProfile = {
    merchantId: '',
    name: '',
    email: '',
    company: '',
    contact: '',
    address: '',
    username: '',
    password: '',
    phone: '',
    firstName: '',
    lastName: '',
    officeNumber: ''
  };
  newUserRole: string = 'ADMIN';
  isSubmitting = false;

  showPassword = false;
  private passwordTimer: any = null;
  isPasswordValid = false;
  passwordTouched = false;
  passwordChecks = {
    minLength: false,
    uppercase: false,
    lowercase: false,
    number: false,
    special: false
  };

  usernameExists = false;
  usernameChecking = false;
  usernameChecked = false;
  usernameCheckError = false;
  private usernameCheckTimer: any = null;

  userIdExists = false;
  userIdChecking = false;
  userIdChecked = false;
  userIdCheckError = false;
  private userIdCheckTimer: any = null;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  onUsernameChange(): void {
    if (this.usernameCheckTimer) clearTimeout(this.usernameCheckTimer);
    const username = (this.newUser.username || '').trim();
    if (!username) {
      this.usernameExists = false;
      this.usernameChecking = false;
      this.usernameChecked = false;
      this.usernameCheckError = false;
      return;
    }
    this.usernameChecking = true;
    this.usernameChecked = false;
    this.usernameCheckError = false;
    this.usernameCheckTimer = setTimeout(() => {
      this.profileService.checkUsername(username).subscribe({
        next: (res) => { this.usernameExists = res.exists; this.usernameChecking = false; this.usernameChecked = true; this.usernameCheckError = false; },
        error: () => { this.usernameChecking = false; this.usernameChecked = true; this.usernameCheckError = true; this.usernameExists = false; }
      });
    }, 500);
  }

  onUserIdChange(): void {
    if (this.userIdCheckTimer) clearTimeout(this.userIdCheckTimer);
    const userId = (this.newUser.merchantId || '').trim();
    if (!userId) {
      this.userIdExists = false;
      this.userIdChecking = false;
      this.userIdChecked = false;
      this.userIdCheckError = false;
      return;
    }
    this.userIdChecking = true;
    this.userIdChecked = false;
    this.userIdCheckError = false;
    this.userIdCheckTimer = setTimeout(() => {
      this.profileService.checkUserId(userId).subscribe({
        next: (res) => { this.userIdExists = res.exists; this.userIdChecking = false; this.userIdChecked = true; this.userIdCheckError = false; },
        error: () => { this.userIdChecking = false; this.userIdChecked = true; this.userIdCheckError = true; this.userIdExists = false; }
      });
    }, 500);
  }

  togglePasswordVisibility(): void {
    this.showPassword = true;
    if (this.passwordTimer) {
      clearTimeout(this.passwordTimer);
    }
    this.passwordTimer = setTimeout(() => {
      this.showPassword = false;
    }, 3000);
  }

  validatePassword(): void {
    const pw = this.newUser.password || '';
    this.passwordChecks = {
      minLength: pw.length >= 8,
      uppercase: /[A-Z]/.test(pw),
      lowercase: /[a-z]/.test(pw),
      number: /[0-9]/.test(pw),
      special: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?`~]/.test(pw)
    };
    this.isPasswordValid = Object.values(this.passwordChecks).every(v => v);
  }

  saveNewUser(): void {
    this.isSubmitting = true;
    const currentUser = this.profileService.getProfile();
    this.newUser.createdBy = currentUser?.username || 'unknown';
    this.profileService.createUser(this.newUser, this.newUserRole).subscribe({
      next: (res) => {
        this.isSubmitting = false;
        alert('User created successfully!');
        this.router.navigate(['/users']);
      },
      error: (err) => {
        this.isSubmitting = false;
        console.error('Failed to create user:', err);
        alert('Failed to create user: ' + (err.error || err.message));
      }
    });
  }

  cancel(): void {
    this.router.navigate(['/users']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
