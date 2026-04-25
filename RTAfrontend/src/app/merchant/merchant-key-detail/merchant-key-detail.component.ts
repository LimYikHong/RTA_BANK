import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ReportService } from '../../services/report.service';

export interface KeyInfo {
  purpose: string;
  hasKey: boolean;
  keyId: number | null;
  version: number;
  status: string;
  activatedAt: string | null;
  expiresAt: string | null;
  daysElapsed: number;
  daysRemaining: number;
  expired: boolean;
  canRotate: boolean;
}

export interface MerchantKeyDetail {
  merchantId: string;
  merchantName: string;
  inbound: KeyInfo;
  outbound: KeyInfo;
}

@Component({
  selector: 'app-merchant-key-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './merchant-key-detail.component.html',
  styleUrl: './merchant-key-detail.component.scss'
})
export class MerchantKeyDetailComponent implements OnInit {
  merchantId = '';
  merchantName = '';
  keyDetail: MerchantKeyDetail | null = null;
  loading = true;
  rotating = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private http: HttpClient,
    private reportService: ReportService
  ) {}

  ngOnInit(): void {
    this.merchantId = this.route.snapshot.paramMap.get('merchantId') || '';
    if (!this.merchantId) {
      this.router.navigate(['/merchant-key-management']);
      return;
    }
    this.loadKeyDetail();
  }

  loadKeyDetail(): void {
    this.loading = true;
    this.http.get<MerchantKeyDetail>(`https://localhost:8086/api/merchant-keys/${this.merchantId}/detail`)
      .subscribe({
        next: (data) => {
          this.keyDetail = data;
          this.merchantName = data.merchantName || '';
          this.loading = false;
        },
        error: () => {
          this.keyDetail = null;
          this.loading = false;
        }
      });
  }

  getStatusClass(key: KeyInfo): string {
    if (!key.hasKey) return 'status-received';
    if (key.expired) return 'status-failed';
    if (key.canRotate) return 'status-pending';
    return 'status-completed';
  }

  getStatusLabel(key: KeyInfo): string {
    if (!key.hasKey) return 'No Key';
    if (key.expired) return 'Expired';
    if (key.canRotate) return 'Expiring Soon';
    return 'Active';
  }

  get canRotateKeys(): boolean {
    if (!this.keyDetail) return false;
    return this.keyDetail.inbound.canRotate || this.keyDetail.outbound.canRotate
        || !this.keyDetail.inbound.hasKey || !this.keyDetail.outbound.hasKey;
  }

  rotateKeys(): void {
    if (this.rotating || !this.canRotateKeys) return;
    if (!confirm('Rotate RSA key pairs for this merchant? This will generate new INBOUND and OUTBOUND keys, deactivating the old ones.')) return;
    this.rotating = true;
    this.reportService.requestRsaKey(this.merchantId).subscribe({
      next: () => {
        this.rotating = false;
        this.loadKeyDetail();
      },
      error: (err: any) => {
        alert('Failed to rotate keys: ' + (err.error?.error || err.message));
        this.rotating = false;
      }
    });
  }

  back(): void {
    this.router.navigate(['/merchant-key-management']);
  }
}
