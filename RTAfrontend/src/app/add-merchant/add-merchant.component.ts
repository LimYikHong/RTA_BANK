import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, MerchantInfoPayload, FieldMappingPayload } from '../services/profile.service';
import { AuthService } from '../services/auth.service';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';

@Component({
  selector: 'app-add-merchant',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './add-merchant.component.html',
  styleUrl: './add-merchant.component.scss'
})
export class AddMerchantComponent implements OnInit {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  merchant: MerchantInfoPayload = {
    merchantId: '',
    name: '',
    email: '',
    username: '',
    password: '',
    company: '',
    contact: '',
    phone: '',
    address: '',
    merchantAccNum: '',
    merchantAccName: '',
    transactionCurrency: 'MYR',
    settlementCurrency: 'MYR',
    createdBy: '',
    fileType: 'CSV',
    fieldDelimiter: ',',
    hasHeader: true,
    dateFormat: 'yyyy-MM-dd',
    fieldMappings: []
  };

  /** The 9 required canonical fields for file format */
  requiredFields: FieldMappingPayload[] = [
    // Transaction fields
    { canonicalField: 'customer_reference', dataType: 'STRING', required: true, sourceColumnName: 'customer_reference', sourceColumnIdx: 0 },
    { canonicalField: 'account_num', dataType: 'STRING', required: true, sourceColumnName: 'account_num', sourceColumnIdx: 1 },
    { canonicalField: 'bank_code', dataType: 'STRING', required: true, sourceColumnName: 'bank_code', sourceColumnIdx: 2 },
    { canonicalField: 'amount', dataType: 'DECIMAL', required: true, sourceColumnName: 'amount', sourceColumnIdx: 3 },
    { canonicalField: 'currency', dataType: 'STRING', required: true, sourceColumnName: 'currency', sourceColumnIdx: 4 },
    { canonicalField: 'transaction_date', dataType: 'DATE', required: true, sourceColumnName: 'transaction_date', sourceColumnIdx: 5 },
    { canonicalField: 'start_date', dataType: 'DATE', required: true, sourceColumnName: 'start_date', sourceColumnIdx: 6 }
  ];

  /** Recurring-related required fields (grouped separately) */
  recurringFields: FieldMappingPayload[] = [
    { canonicalField: 'is_recurring', dataType: 'BOOLEAN', required: true, sourceColumnName: 'is_recurring', sourceColumnIdx: 7 },
    { canonicalField: 'recurring_type', dataType: 'STRING', required: true, sourceColumnName: 'recurring_type', sourceColumnIdx: 8 },
    { canonicalField: 'frequency_value', dataType: 'INTEGER', required: true, sourceColumnName: 'frequency_value', sourceColumnIdx: 9 },
    { canonicalField: 'recurring_reference', dataType: 'STRING', required: true, sourceColumnName: 'recurring_reference', sourceColumnIdx: 10 }
  ];

  /** User-added optional custom fields */
  customFields: FieldMappingPayload[] = [];

  isSubmitting = false;
  isLoadingId = true;
  usernameExists = false;
  isCheckingUsername = false;
  private usernameCheck$ = new Subject<string>();

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
        this.isLoadingId = false;
      },
      error: () => {
        this.merchant.merchantId = 'M001';
        this.isLoadingId = false;
      }
    });

    // Debounced username uniqueness check
    this.usernameCheck$.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      switchMap(username => {
        if (!username || username.trim().length === 0) {
          this.usernameExists = false;
          this.isCheckingUsername = false;
          return [];
        }
        this.isCheckingUsername = true;
        return this.profileService.checkMerchantUsername(username);
      })
    ).subscribe({
      next: (res) => {
        this.usernameExists = res.exists;
        this.isCheckingUsername = false;
      },
      error: () => {
        this.usernameExists = false;
        this.isCheckingUsername = false;
      }
    });
  }

  onUsernameChange(): void {
    this.usernameCheck$.next(this.merchant.username);
  }

  onMerchantNameChange(): void {
    // Auto-fill account name when name changes
    this.merchant.merchantAccName = this.merchant.name;
  }

  /** Format a canonical field name for display: customer_reference -> Customer Reference */
  formatFieldName(name: string): string {
    return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  addCustomField(): void {
    const nextIdx = this.requiredFields.length + this.recurringFields.length + this.customFields.length;
    this.customFields.push({
      canonicalField: '',
      dataType: 'STRING',
      required: false,
      sourceColumnName: '',
      sourceColumnIdx: nextIdx
    });
  }

  removeCustomField(index: number): void {
    this.customFields.splice(index, 1);
  }

  saveMerchant(): void {
    this.isSubmitting = true;
    const currentUser = this.profileService.getProfile();
    this.merchant.createdBy = currentUser?.username || 'unknown';
    // Merge required + recurring + custom fields
    const allFields = [
      ...this.requiredFields,
      ...this.recurringFields,
      ...this.customFields.map(f => ({ ...f, sourceColumnName: f.sourceColumnName || f.canonicalField }))
    ];
    this.merchant.fieldMappings = allFields;

    this.profileService.createMerchant(this.merchant).subscribe({
      next: () => {
        this.isSubmitting = false;
        alert('Merchant created successfully! A Kafka event has been published to notify sub-systems.');
        this.router.navigate(['/merchant-maintenance']);
      },
      error: (err) => {
        this.isSubmitting = false;
        console.error('Failed to create merchant:', err);
        alert('Failed to create merchant: ' + (err.error || err.message));
      }
    });
  }

  cancel(): void {
    this.router.navigate(['/merchant-maintenance']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
