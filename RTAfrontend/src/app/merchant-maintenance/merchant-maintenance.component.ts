import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationEnd } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService } from '../services/profile.service';
import { AuthService } from '../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { Subscription, filter, catchError, of } from 'rxjs';

export interface MerchantListItem {
  id: number;
  merchantId: string;
  name: string;
  email: string;
  username: string;
  company: string;
  contact: string;
  phone: string;
  address: string;
  joinedOn: string;
  createBy: string;
}

@Component({
  selector: 'app-merchant-maintenance',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './merchant-maintenance.component.html',
  styleUrl: './merchant-maintenance.component.scss'
})
export class MerchantMaintenanceComponent implements OnInit, OnDestroy {
  merchants: MerchantListItem[] = [];
  filteredMerchants: MerchantListItem[] = [];
  searchKeyword: string = '';
  isLoading: boolean = false;
  private routerSub!: Subscription;
  private merchantApiUrl = 'https://localhost:8086/api/merchants';

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.loadMerchants();

    // Auto-refresh when navigating back to this page (e.g. after creating a merchant)
    this.routerSub = this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      filter(event => event.urlAfterRedirects === '/merchant-maintenance')
    ).subscribe(() => {
      this.loadMerchants();
    });
  }

  ngOnDestroy(): void {
    if (this.routerSub) {
      this.routerSub.unsubscribe();
    }
  }

  loadMerchants(): void {
    this.isLoading = true;
    this.http.get<MerchantListItem[]>(this.merchantApiUrl).pipe(
      catchError((err) => {
        console.error('Failed to load merchants:', err);
        return of([]);
      })
    ).subscribe({
      next: (data) => {
        this.merchants = data;
        this.filteredMerchants = data;
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
      }
    });
  }

  onSearch(): void {
    const keyword = this.searchKeyword.trim();
    if (!keyword) {
      this.loadMerchants();
      return;
    }
    const kw = keyword.toLowerCase();
    this.filteredMerchants = this.merchants.filter(m =>
      (m.name && m.name.toLowerCase().includes(kw)) ||
      (m.merchantId && m.merchantId.toLowerCase().includes(kw)) ||
      (m.username && m.username.toLowerCase().includes(kw)) ||
      (m.email && m.email.toLowerCase().includes(kw)) ||
      (m.company && m.company.toLowerCase().includes(kw)) ||
      (m.contact && m.contact.toLowerCase().includes(kw)) ||
      (m.phone && m.phone.toLowerCase().includes(kw))
    );
  }

  clearSearch(): void {
    this.searchKeyword = '';
    this.filteredMerchants = this.merchants;
  }

  addMerchant(): void {
    this.router.navigate(['/add-merchant']);
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
