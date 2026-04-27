import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { DashboardService, MerchantKeyOverview } from '../services/dashboard.service';

@Component({
  selector: 'app-top-bar',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './top-bar.component.html',
  styleUrl: './top-bar.component.scss'
})
export class TopBarComponent implements OnInit {
  alerts: MerchantKeyOverview[] = [];
  showModal = false;
  isDashboard = false;

  get alertCount(): number { return this.alerts.length; }

  constructor(private router: Router, private dashboardService: DashboardService) {}

  ngOnInit(): void {
    // Track current route
    this.isDashboard = this.router.url === '/dashboard';
    this.router.events.pipe(filter(e => e instanceof NavigationEnd)).subscribe((e: any) => {
      this.isDashboard = e.urlAfterRedirects === '/dashboard';
    });

    this.dashboardService.getMerchantKeyOverview().subscribe({
      next: (keys) => {
        this.alerts = keys.filter(mk => !mk.hasKey || mk.expired || mk.needsRotation);
      },
      error: () => { this.alerts = []; }
    });
  }

  openAlerts(): void  { this.showModal = true; }
  closeAlerts(): void { this.showModal = false; }

  goToKey(merchantId: string): void {
    this.showModal = false;
    this.router.navigate(['/merchant-key-detail', merchantId]);
  }
}
