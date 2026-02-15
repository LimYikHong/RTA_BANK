import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationEnd } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserListItem } from '../services/profile.service';
import { AuthService } from '../services/auth.service';
import { Subscription, filter } from 'rxjs';

@Component({
  selector: 'app-user-management',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.scss'
})
export class UserManagementComponent implements OnInit, OnDestroy {
  users: UserListItem[] = [];
  filteredUsers: UserListItem[] = [];
  searchKeyword: string = '';
  isLoading: boolean = false;
  isSyncing: boolean = false;
  showAddUserModal: boolean = false;
  private routerSub!: Subscription;

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadUsers();

    // Auto-refresh when navigating back to this page (e.g. after creating a user)
    this.routerSub = this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      filter(event => event.urlAfterRedirects === '/users')
    ).subscribe(() => {
      this.loadUsers();
    });
  }

  ngOnDestroy(): void {
    if (this.routerSub) {
      this.routerSub.unsubscribe();
    }
  }

  syncUsers(): void {
    this.isSyncing = true;
    this.searchKeyword = '';
    this.profileService.getAllUsers().subscribe({
      next: (data) => {
        this.users = data;
        this.filteredUsers = data;
        this.isSyncing = false;
      },
      error: (err) => {
        console.error('Failed to sync users:', err);
        this.isSyncing = false;
      }
    });
  }

  loadUsers(): void {
    this.isLoading = true;
    this.profileService.getAllUsers().subscribe({
      next: (data) => {
        this.users = data;
        this.filteredUsers = data;
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load users:', err);
        this.isLoading = false;
      }
    });
  }

  onSearch(): void {
    const keyword = this.searchKeyword.trim();
    if (!keyword) {
      this.filteredUsers = this.users;
      return;
    }
    this.profileService.searchUsers(keyword).subscribe({
      next: (data) => {
        this.filteredUsers = data;
      },
      error: (err) => {
        console.error('Search failed:', err);
      }
    });
  }

  clearSearch(): void {
    this.searchKeyword = '';
    this.filteredUsers = this.users;
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'ACTIVE': return 'status-active';
      case 'INACTIVE': return 'status-inactive';
      case 'SUSPENDED': return 'status-suspended';
      default: return '';
    }
  }

  getRoleBadgeClass(role: string): string {
    switch (role?.toUpperCase()) {
      case 'SUPER_ADMIN': return 'role-super-admin';
      case 'ADMIN': return 'role-admin';
      case 'MERCHANT': return 'role-merchant';
      default: return 'role-default';
    }
  }

  openAddUserModal(): void {
    this.showAddUserModal = true;
  }

  closeAddUserModal(): void {
    this.showAddUserModal = false;
  }

  selectAddType(type: string): void {
    this.showAddUserModal = false;
    if (type === 'admin') {
      this.router.navigate(['/add-user']);
    } else if (type === 'merchant') {
      this.router.navigate(['/add-merchant']);
    }
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
