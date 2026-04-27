import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { DashboardService, MerchantKeyOverview } from '../../services/dashboard.service';
import { TableSorter } from '../../shared/table-sorter';

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

  // Sorting
  sorter = new TableSorter<MerchantKeyOverview>();
  get sortKey() { return this.sorter.sortKey; }
  get sortDir() { return this.sorter.sortDir; }
  sortBy(key: string): void { this.sorter.sortBy(key); }

  get sortedMerchantKeys(): MerchantKeyOverview[] {
    return this.sorter.apply(this.merchantKeys);
  }

  constructor(private dashboardService: DashboardService, private router: Router) {}

  viewDetail(merchantId: string): void {
    this.router.navigate(['/merchant-key-detail', merchantId]);
  }

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
