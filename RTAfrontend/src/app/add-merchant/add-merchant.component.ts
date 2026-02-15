import { Component, OnInit } from '@angular/core';
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
export class AddMerchantComponent implements OnInit {
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
  isLoadingId = true;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.isLoadingId = true;
    this.profileService.getNextMerchantId().subscribe({
      next: (res) => {
        this.merchant.merchantId = res.nextId;
        this.merchant.merchantCode = res.nextId;
        this.isLoadingId = false;
      },
      error: () => {
        this.merchant.merchantId = 'M001';
        this.merchant.merchantCode = 'M001';
        this.isLoadingId = false;
      }
    });
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
