import { Component, Inject } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { PLATFORM_ID } from '@angular/core';
import { AuthService } from '../services/auth.service';
import { ProfileService, MerchantProfile } from '../services/profile.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss'
})
export class LoginComponent {
  username = '';
  password = '';
  errorMessage = '';

  // 1 = login, 2 = setup 2FA, 3 = verify 2FA
  step = 1;
  twoFaCode = '';
  qrCodeUrl?: string;
  tempData: any = {};

  constructor(
    private auth: AuthService,
    private router: Router,
    private profileService: ProfileService,
    @Inject(PLATFORM_ID) private platformId: Object
  ) {}

  onLogin(): void {
    if (!this.username || !this.password) {
      this.errorMessage = 'Please enter both username and password.';
      return;
    }

    this.errorMessage = '';

    this.auth.login(this.username, this.password).subscribe({
      next: async (response) => {

        if (response.status === 'SETUP_2FA') {
          this.step = 2;
          this.tempData = response;

          // ⚠️ SSR-safe: only generate QR in browser
          if (isPlatformBrowser(this.platformId)) {
            const QRCode = await import('qrcode');
            
            // Fallback: construct URI if backend didn't send it (e.g. older backend version)
            const otpUri = response.otpAuthUri || `otpauth://totp/RTA_Example:${this.username}?secret=${response.secret}&issuer=RTA_Example`;
            
            this.qrCodeUrl = await QRCode.toDataURL(otpUri);
          }

        } else if (response.status === '2FA_REQUIRED') {
          this.step = 3;
          this.tempData = response;

        } else {
          this.handleLoginSuccess(response);
        }
      },
      error: () => {
        this.errorMessage = 'Invalid username or password';
      }
    });
  }

  verify2FA(): void {
    if (!this.twoFaCode) return;

    // Ensure we have username from step 1 or response
    const user = this.tempData.username || this.username;

    this.auth.verify2fa(user, Number(this.twoFaCode)).subscribe({
      next: (profile) => {
        this.handleLoginSuccess(profile);
      },
      error: () => {
        this.errorMessage = 'Invalid Code. Please try again.';
      }
    });
  }

  private handleLoginSuccess(profile: MerchantProfile) {
    this.profileService.setProfile(profile);
    localStorage.setItem('merchant', JSON.stringify(profile));
    this.router.navigate(['/batch-list']);
  }
}
