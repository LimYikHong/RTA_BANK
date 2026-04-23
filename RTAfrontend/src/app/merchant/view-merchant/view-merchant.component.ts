import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { ProfileService } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { ReportService } from '../../services/report.service';

export interface MerchantViewData {
  merchantId: string;
  name: string;
  email: string;
  username: string;
  company: string;
  contact: string;
  phone: string;
  address: string;
  createdAt?: string;
  createBy?: string;
  updatedAt?: string;
  lastModifiedBy?: string;
  fileType?: string;
  fieldDelimiter?: string;
  hasHeader?: boolean;
  dateFormat?: string;
  fieldMappings?: any[];
}

export interface ViewFieldMapping {
  canonicalField: string;
  dataType: string;
  sourceColumnName: string;
  sourceColumnIdx: number;
}

@Component({
  selector: 'app-view-merchant',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './view-merchant.component.html',
  styleUrl: './view-merchant.component.scss'
})
export class ViewMerchantComponent implements OnInit {
  merchant: MerchantViewData = {
    merchantId: '',
    name: '',
    email: '',
    username: '',
    company: '',
    contact: '',
    phone: '',
    address: ''
  };

  requiredFields: ViewFieldMapping[] = [];
  recurringFields: ViewFieldMapping[] = [];
  customFields: ViewFieldMapping[] = [];
  hasFileProfile = false;
  isLoading = true;
  merchantId = '';

  private fileProfileApiUrl = 'https://localhost:8086/api/file-profiles';

  // RSA Key management
  rsaKeyStatus: any = null;
  rsaKeyLoading = false;
  rsaKeyGenerating = false;
  rsaKeyRotating = false;
  rsaKeyDaysElapsed = 0;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private http: HttpClient,
    private reportService: ReportService
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
        this.merchant = {
          merchantId: data.merchantId || '',
          name: data.name || '',
          email: data.email || '',
          username: data.username || '',
          company: data.company || '',
          contact: data.contact || '',
          phone: data.phone || '',
          address: data.address || '',
          createdAt: data.createdAt || data.joinedOn || '',
          createBy: data.createBy || ''
        };
        this.loadFileProfile();
        this.loadRsaKeyStatus();
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
          this.hasFileProfile = true;
          const profile = fpData.profile;
          this.merchant.fileType = profile.fileType || 'CSV';
          this.merchant.fieldDelimiter = profile.fieldDelimiter || ',';
          this.merchant.hasHeader = profile.hasHeader ?? true;
          this.merchant.dateFormat = profile.dateFormat || 'yyyy-MM-dd';

          const mappings = fpData.fieldMappings || [];
          const requiredCanonical = ['customer_reference', 'account_num', 'bank_code', 'amount', 'currency', 'transaction_date', 'start_date'];
          const recurringCanonical = ['is_recurring', 'recurring_type', 'frequency_value', 'recurring_reference'];

          this.requiredFields = mappings.filter((m: any) => requiredCanonical.includes(m.canonicalField));
          this.recurringFields = mappings.filter((m: any) => recurringCanonical.includes(m.canonicalField));
          this.customFields = mappings.filter((m: any) =>
            !requiredCanonical.includes(m.canonicalField) && !recurringCanonical.includes(m.canonicalField)
          );

          // Fill missing required/recurring slots with defaults
          if (this.requiredFields.length === 0) { this.setDefaultRequiredFields(); }
          if (this.recurringFields.length === 0) { this.setDefaultRecurringFields(); }
        } else {
          // No profile — show default field layout
          this.merchant.fileType = 'CSV';
          this.merchant.fieldDelimiter = ',';
          this.merchant.hasHeader = true;
          this.merchant.dateFormat = 'yyyy-MM-dd';
          this.setDefaultRequiredFields();
          this.setDefaultRecurringFields();
        }
        this.isLoading = false;
      },
      error: () => {
        this.merchant.fileType = 'CSV';
        this.merchant.fieldDelimiter = ',';
        this.merchant.hasHeader = true;
        this.merchant.dateFormat = 'yyyy-MM-dd';
        this.setDefaultRequiredFields();
        this.setDefaultRecurringFields();
        this.isLoading = false;
      }
    });
  }

  setDefaultRequiredFields(): void {
    this.requiredFields = [
      { canonicalField: 'customer_reference', dataType: 'STRING', sourceColumnName: 'customer_reference', sourceColumnIdx: 0 },
      { canonicalField: 'account_num',        dataType: 'STRING', sourceColumnName: 'account_num',        sourceColumnIdx: 1 },
      { canonicalField: 'bank_code',          dataType: 'STRING', sourceColumnName: 'bank_code',          sourceColumnIdx: 2 },
      { canonicalField: 'amount',             dataType: 'DECIMAL', sourceColumnName: 'amount',            sourceColumnIdx: 3 },
      { canonicalField: 'currency',           dataType: 'STRING', sourceColumnName: 'currency',           sourceColumnIdx: 4 },
      { canonicalField: 'transaction_date',   dataType: 'DATE',   sourceColumnName: 'transaction_date',   sourceColumnIdx: 5 },
      { canonicalField: 'start_date',         dataType: 'DATE',   sourceColumnName: 'start_date',         sourceColumnIdx: 6 }
    ];
  }

  setDefaultRecurringFields(): void {
    this.recurringFields = [
      { canonicalField: 'is_recurring',        dataType: 'BOOLEAN', sourceColumnName: 'is_recurring',        sourceColumnIdx: 7 },
      { canonicalField: 'recurring_type',      dataType: 'STRING',  sourceColumnName: 'recurring_type',      sourceColumnIdx: 8 },
      { canonicalField: 'frequency_value',     dataType: 'INTEGER', sourceColumnName: 'frequency_value',     sourceColumnIdx: 9 },
      { canonicalField: 'recurring_reference', dataType: 'STRING',  sourceColumnName: 'recurring_reference', sourceColumnIdx: 10 }
    ];
  }

  formatFieldName(name: string): string {
    return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  getDelimiterLabel(delimiter: string): string {
    switch (delimiter) {
      case ',': return 'Comma ( , )';
      case '|': return 'Pipe ( | )';
      case '\t': return 'Tab';
      case ';': return 'Semicolon ( ; )';
      default: return delimiter || '-';
    }
  }

  loadRsaKeyStatus(): void {
    this.rsaKeyLoading = true;
    this.reportService.getRsaKeyStatus(this.merchantId).subscribe({
      next: (status: any) => {
        this.rsaKeyStatus = status;
        if (status?.activatedAt) {
          const activated = new Date(status.activatedAt);
          const now = new Date();
          this.rsaKeyDaysElapsed = Math.floor((now.getTime() - activated.getTime()) / (1000 * 60 * 60 * 24));
        } else {
          this.rsaKeyDaysElapsed = 0;
        }
        this.rsaKeyLoading = false;
      },
      error: () => {
        this.rsaKeyStatus = null;
        this.rsaKeyDaysElapsed = 0;
        this.rsaKeyLoading = false;
      }
    });
  }

  generateRsaKey(): void {
    if (this.rsaKeyGenerating) return;
    if (!confirm('Request the RSA public key from this merchant? This will be used to encrypt return batch files.')) return;
    this.rsaKeyGenerating = true;
    this.reportService.requestRsaKey(this.merchantId).subscribe({
      next: () => {
        alert('Merchant RSA public key received successfully!');
        this.rsaKeyGenerating = false;
        this.loadRsaKeyStatus();
      },
      error: (err: any) => {
        alert('Failed to request RSA key from merchant: ' + (err.error?.detail || err.message));
        this.rsaKeyGenerating = false;
      }
    });
  }

  get canRotateKey(): boolean {
    return this.rsaKeyStatus && this.rsaKeyDaysElapsed >= 25 && this.rsaKeyDaysElapsed < 30;
  }

  get rotateButtonTooltip(): string {
    if (!this.rsaKeyStatus) return 'No key to rotate';
    if (this.rsaKeyDaysElapsed < 25) return `Key rotation available from day 25 (currently day ${this.rsaKeyDaysElapsed})`;
    if (this.rsaKeyDaysElapsed >= 30) return 'Key expired. Please request a new key.';
    return `Key rotation available (day ${this.rsaKeyDaysElapsed}/30). Click to rotate.`;
  }

  rotateRsaKey(): void {
    if (this.rsaKeyRotating || !this.canRotateKey) return;
    if (!confirm('Rotate the RSA key for this merchant? The old key will be marked as ROTATED and a new key pair will be generated.')) return;
    this.rsaKeyRotating = true;
    this.reportService.requestRsaKey(this.merchantId).subscribe({
      next: () => {
        alert('RSA key rotated successfully!');
        this.rsaKeyRotating = false;
        this.loadRsaKeyStatus();
      },
      error: (err: any) => {
        alert('Failed to rotate RSA key: ' + (err.error?.error || err.message));
        this.rsaKeyRotating = false;
      }
    });
  }

  back(): void {
    this.router.navigate(['/merchant-maintenance']);
  }

  editMerchant(): void {
    this.router.navigate(['/edit-merchant', this.merchantId]);
  }

  // Delete modal
  showDeleteModal = false;
  isDeleting = false;

  openDeleteModal(): void {
    this.showDeleteModal = true;
  }

  closeDeleteModal(): void {
    this.showDeleteModal = false;
  }

  confirmDelete(): void {
    this.isDeleting = true;
    this.profileService.deleteMerchant(this.merchantId).subscribe({
      next: () => {
        this.isDeleting = false;
        this.showDeleteModal = false;
        alert('Merchant deleted successfully.');
        this.router.navigate(['/merchant-maintenance']);
      },
      error: (err: any) => {
        this.isDeleting = false;
        console.error('Failed to delete merchant:', err);
        alert('Failed to delete merchant.');
      }
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
