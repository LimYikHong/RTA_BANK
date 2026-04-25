import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { DashboardService, MerchantKeyOverview } from '../../services/dashboard.service';

@Component({
  selector: 'app-merchant-key-management',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './merchant-key-management.component.html',
  styleUrl: './merchant-key-management.component.scss'
})
export class MerchantKeyManagementComponent implements OnInit {
  merchantKeys: MerchantKeyOverview[] = [];
  loading = false;

  constructor(private dashboardService: DashboardService) {}

  ngOnInit(): void {
    this.loadMerchantKeys();
  }

  loadMerchantKeys(): void {
    this.loading = true;
    this.dashboardService.getMerchantKeyOverview().subscribe({
      next: (keys) => {
        this.merchantKeys = keys;
        this.loading = false;
      },
      error: () => {
        this.merchantKeys = [];
        this.loading = false;
      }
    });
  }

  get merchantKeysNeedingAction(): MerchantKeyOverview[] {
    return this.merchantKeys.filter(mk => !mk.hasKey || mk.expired || mk.needsRotation);
  }

  getMerchantKeyStatusClass(mk: MerchantKeyOverview): string {
    if (!mk.hasKey) return 'mk-no-key';
    if (mk.expired) return 'mk-expired';
    if (mk.needsRotation) return 'mk-warning';
    return 'mk-active';
  }

  getMerchantKeyStatusLabel(mk: MerchantKeyOverview): string {
    if (!mk.hasKey) return 'No Key';
    if (mk.expired) return 'Expired';
    if (mk.needsRotation) return 'Needs Rotation';
    return 'Active';
  }
}
