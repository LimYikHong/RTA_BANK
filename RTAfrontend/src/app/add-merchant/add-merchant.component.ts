import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, MerchantInfoPayload } from '../services/profile.service';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-add-merchant',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './add-merchant.component.html',
  styleUrl: './add-merchant.component.scss'
})
export class AddMerchantComponent {
  merchant: MerchantInfoPayload = {
    merchantId: '',
    merchantName: '',
    merchantBank: '',
    merchantCode: '',
    merchantPhoneNum: '',
    merchantAddress: '',
    merchantContactPerson: '',
    merchantAccNum: '',
    merchantAccName: '',
    transactionCurrency: 'MYR',
    settlementCurrency: 'MYR',
    createdBy: ''
  };

  isSubmitting = false;

  // Merchant ID uniqueness check
  merchantIdExists = false;
  merchantIdChecking = false;
  merchantIdChecked = false;
  merchantIdCheckError = false;
  private merchantIdCheckTimer: any = null;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  onMerchantIdChange(): void {
    if (this.merchantIdCheckTimer) clearTimeout(this.merchantIdCheckTimer);
    const id = (this.merchant.merchantId || '').trim();
    if (!id) {
      this.merchantIdExists = false;
      this.merchantIdChecking = false;
      this.merchantIdChecked = false;
      this.merchantIdCheckError = false;
      return;
    }

    // Auto-fill merchantCode and accNum when merchantId changes
    this.merchant.merchantCode = id;
    this.merchant.merchantAccNum = 'ACC-' + id + '-001';

    this.merchantIdChecking = true;
    this.merchantIdChecked = false;
    this.merchantIdCheckError = false;
    this.merchantIdCheckTimer = setTimeout(() => {
      this.profileService.checkMerchantId(id).subscribe({
        next: (res) => {
          this.merchantIdExists = res.exists;
          this.merchantIdChecking = false;
          this.merchantIdChecked = true;
          this.merchantIdCheckError = false;
        },
        error: () => {
          this.merchantIdChecking = false;
          this.merchantIdChecked = true;
          this.merchantIdCheckError = true;
          this.merchantIdExists = false;
        }
      });
    }, 500);
  }

  onMerchantNameChange(): void {
    // Auto-fill account name when merchant name changes
    this.merchant.merchantAccName = this.merchant.merchantName;
  }

  saveMerchant(): void {
    this.isSubmitting = true;
    const currentUser = this.profileService.getProfile();
    this.merchant.createdBy = currentUser?.username || 'unknown';

    this.profileService.createMerchant(this.merchant).subscribe({
      next: () => {
        this.isSubmitting = false;
        alert('Merchant created successfully! A Kafka event has been published to notify sub-systems.');
        this.router.navigate(['/users']);
      },
      error: (err) => {
        this.isSubmitting = false;
        console.error('Failed to create merchant:', err);
        alert('Failed to create merchant: ' + (err.error || err.message));
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
