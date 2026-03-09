import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, MerchantInfoPayload, FieldMappingPayload } from '../services/profile.service';
import { AuthService } from '../services/auth.service';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-edit-merchant',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './edit-merchant.component.html',
  styleUrl: './edit-merchant.component.scss'
})
export class EditMerchantComponent implements OnInit {
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

  requiredFields: FieldMappingPayload[] = [];
  recurringFields: FieldMappingPayload[] = [];
  customFields: FieldMappingPayload[] = [];

  isLoading = true;
  isSubmitting = false;
  merchantId = '';

  private fileProfileApiUrl = 'https://localhost:8086/api/file-profiles';

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.merchantId = this.route.snapshot.paramMap.get('merchantId') || '';
    if (!this.merchantId) {
      this.router.navigate(['/merchant-maintenance']);
      return;
    }
    this.loadMerchant();
  }

  loadMerchant(): void {
    this.isLoading = true;
    this.profileService.getMerchant(this.merchantId).subscribe({
      next: (data: any) => {
        this.merchant.merchantId = data.merchantId || '';
        this.merchant.name = data.name || '';
        this.merchant.email = data.email || '';
        this.merchant.username = data.username || '';
        this.merchant.password = '';  // blank on load — only fill if user wants to change
        this.merchant.company = data.company || '';
        this.merchant.contact = data.contact || '';
        this.merchant.phone = data.phone || '';
        this.merchant.address = data.address || '';
        this.merchant.createdBy = data.createBy || '';
        this.loadFileProfile();
      },
      error: () => {
        alert('Failed to load merchant data.');
        this.router.navigate(['/merchant-maintenance']);
      }
    });
  }

  loadFileProfile(): void {
    this.http.get<any>(`${this.fileProfileApiUrl}/merchant/${this.merchantId}`).subscribe({
      next: (fpData: any) => {
        if (fpData && fpData.hasProfile) {
          const profile = fpData.profile;
          this.merchant.fileType = profile.fileType || 'CSV';
          this.merchant.fieldDelimiter = profile.fieldDelimiter || ',';
          this.merchant.hasHeader = profile.hasHeader ?? true;
          this.merchant.dateFormat = profile.dateFormat || 'yyyy-MM-dd';

          const mappings: FieldMappingPayload[] = (fpData.fieldMappings || []).map((m: any) => ({
            canonicalField: m.canonicalField,
            dataType: m.dataType,
            required: m.required,
            sourceColumnName: m.sourceColumnName,
            sourceColumnIdx: m.sourceColumnIdx
          }));

          // Separate by canonical field category
          const requiredCanonical = ['customer_reference', 'account_num', 'bank_code', 'amount', 'currency', 'transaction_date', 'start_date'];
          const recurringCanonical = ['is_recurring', 'recurring_type', 'frequency_value', 'recurring_reference'];

          this.requiredFields = mappings.filter(m => requiredCanonical.includes(m.canonicalField));
          this.recurringFields = mappings.filter(m => recurringCanonical.includes(m.canonicalField));
          this.customFields = mappings.filter(m =>
            !requiredCanonical.includes(m.canonicalField) && !recurringCanonical.includes(m.canonicalField)
          );

          // Fill in any missing required fields with defaults
          if (this.requiredFields.length === 0) {
            this.requiredFields = [
              { canonicalField: 'customer_reference', dataType: 'STRING', required: true, sourceColumnName: 'customer_reference', sourceColumnIdx: 0 },
              { canonicalField: 'account_num', dataType: 'STRING', required: true, sourceColumnName: 'account_num', sourceColumnIdx: 1 },
              { canonicalField: 'bank_code', dataType: 'STRING', required: true, sourceColumnName: 'bank_code', sourceColumnIdx: 2 },
              { canonicalField: 'amount', dataType: 'DECIMAL', required: true, sourceColumnName: 'amount', sourceColumnIdx: 3 },
              { canonicalField: 'currency', dataType: 'STRING', required: true, sourceColumnName: 'currency', sourceColumnIdx: 4 },
              { canonicalField: 'transaction_date', dataType: 'DATE', required: true, sourceColumnName: 'transaction_date', sourceColumnIdx: 5 },
              { canonicalField: 'start_date', dataType: 'DATE', required: true, sourceColumnName: 'start_date', sourceColumnIdx: 6 }
            ];
          }
          if (this.recurringFields.length === 0) {
            this.recurringFields = [
              { canonicalField: 'is_recurring', dataType: 'BOOLEAN', required: true, sourceColumnName: 'is_recurring', sourceColumnIdx: 7 },
              { canonicalField: 'recurring_type', dataType: 'STRING', required: true, sourceColumnName: 'recurring_type', sourceColumnIdx: 8 },
              { canonicalField: 'frequency_value', dataType: 'INTEGER', required: true, sourceColumnName: 'frequency_value', sourceColumnIdx: 9 },
              { canonicalField: 'recurring_reference', dataType: 'STRING', required: true, sourceColumnName: 'recurring_reference', sourceColumnIdx: 10 }
            ];
          }
        } else {
          // No file profile yet — use defaults
          this.setDefaultFieldMappings();
        }
        this.isLoading = false;
      },
      error: () => {
        this.setDefaultFieldMappings();
        this.isLoading = false;
      }
    });
  }

  setDefaultFieldMappings(): void {
    this.requiredFields = [
      { canonicalField: 'customer_reference', dataType: 'STRING', required: true, sourceColumnName: 'customer_reference', sourceColumnIdx: 0 },
      { canonicalField: 'account_num', dataType: 'STRING', required: true, sourceColumnName: 'account_num', sourceColumnIdx: 1 },
      { canonicalField: 'bank_code', dataType: 'STRING', required: true, sourceColumnName: 'bank_code', sourceColumnIdx: 2 },
      { canonicalField: 'amount', dataType: 'DECIMAL', required: true, sourceColumnName: 'amount', sourceColumnIdx: 3 },
      { canonicalField: 'currency', dataType: 'STRING', required: true, sourceColumnName: 'currency', sourceColumnIdx: 4 },
      { canonicalField: 'transaction_date', dataType: 'DATE', required: true, sourceColumnName: 'transaction_date', sourceColumnIdx: 5 },
      { canonicalField: 'start_date', dataType: 'DATE', required: true, sourceColumnName: 'start_date', sourceColumnIdx: 6 }
    ];
    this.recurringFields = [
      { canonicalField: 'is_recurring', dataType: 'BOOLEAN', required: true, sourceColumnName: 'is_recurring', sourceColumnIdx: 7 },
      { canonicalField: 'recurring_type', dataType: 'STRING', required: true, sourceColumnName: 'recurring_type', sourceColumnIdx: 8 },
      { canonicalField: 'frequency_value', dataType: 'INTEGER', required: true, sourceColumnName: 'frequency_value', sourceColumnIdx: 9 },
      { canonicalField: 'recurring_reference', dataType: 'STRING', required: true, sourceColumnName: 'recurring_reference', sourceColumnIdx: 10 }
    ];
  }

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

    // Update basic info
    const updatePayload: any = {
      name: this.merchant.name,
      email: this.merchant.email,
      company: this.merchant.company,
      contact: this.merchant.contact,
      phone: this.merchant.phone,
      address: this.merchant.address,
      modifiedBy: currentUser?.username || 'unknown'
    };
    if (this.merchant.password && this.merchant.password.trim().length > 0) {
      updatePayload.password = this.merchant.password;
    }

    this.profileService.updateMerchant(this.merchantId, updatePayload).subscribe({
      next: () => {
        this.isSubmitting = false;
        alert('Merchant updated successfully!');
        this.router.navigate(['/merchant-maintenance']);
      },
      error: (err) => {
        this.isSubmitting = false;
        alert('Failed to update merchant: ' + (err.error || err.message));
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
