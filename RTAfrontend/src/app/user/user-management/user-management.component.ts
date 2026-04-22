import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationEnd } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserListItem } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { Subscription, filter } from 'rxjs';
import { MerchantFilterComponent, MerchantFilterValues } from '../../shared/merchant-filter/merchant-filter.component';

@Component({
  selector: 'app-user-management',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, MerchantFilterComponent],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.scss'
})
export class UserManagementComponent implements OnInit, OnDestroy {
  users: UserListItem[] = [];
  filteredUsers: UserListItem[] = [];
  searchKeyword: string = '';
  isLoading: boolean = false;
  userIds: string[] = [];
  selectedUserId: string = '';
  roleFilter: string = '';
  private routerSub!: Subscription;

  // Delete confirmation modal
  showDeleteModal: boolean = false;
  userToDelete: UserListItem | null = null;
  isDeleting: boolean = false;

  // Pagination
  currentPage = 1;
  pageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];

  sortKey: string = '';
  sortDir: 'asc' | 'desc' = 'asc';

  sortBy(key: string): void {
    if (this.sortKey === key) {
      if (this.sortDir === 'asc') {
        this.sortDir = 'desc';
      } else {
        this.sortKey = '';
        this.sortDir = 'asc';
      }
    } else {
      this.sortKey = key;
      this.sortDir = 'asc';
    }
  }

  get sortedUsers(): UserListItem[] {
    let list = this.filteredUsers;
    if (this.sortKey) {
      list = [...list].sort((a, b) => {
        const av = (a as any)[this.sortKey] ?? '';
        const bv = (b as any)[this.sortKey] ?? '';
        const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
        return this.sortDir === 'asc' ? cmp : -cmp;
      });
    }
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  get totalElements(): number { return this.filteredUsers.length; }
  get totalPages(): number { return Math.max(1, Math.ceil(this.filteredUsers.length / this.pageSize)); }
  get startRecord(): number { return this.totalElements === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1; }
  get endRecord(): number { return Math.min(this.currentPage * this.pageSize, this.totalElements); }
  get visiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.currentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.totalPages) { end = this.totalPages; start = Math.max(1, end - maxVisible + 1); }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }
  goToPage(page: number): void { if (page >= 1 && page <= this.totalPages) this.currentPage = page; }
  onPageSizeChange(): void { this.currentPage = 1; }

  constructor(
    private profileService: ProfileService,
    private authService: AuthService,
    private router: Router
  ) {}

  get isSuperAdmin(): boolean {
    return this.authService.isSuperAdmin();
  }

  hasPermission(perm: string): boolean {
    return this.authService.hasPermission(perm);
  }

  isSelf(user: UserListItem): boolean {
    const current = this.authService.getCurrentUser();
    return !!current && current.username === user.username;
  }

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

  loadUsers(): void {
    this.isLoading = true;
    this.profileService.getAllUsers().subscribe({
      next: (data) => {
        this.users = data;
        this.filteredUsers = data;
        this.userIds = [...new Set(data.map(u => u.userId).filter(Boolean))].sort();
        this.isLoading = false;
      },
      error: (err) => {
        console.error('Failed to load users:', err);
        this.isLoading = false;
      }
    });
  }

  onSearch(): void {
    let list = this.users;
    if (this.selectedUserId) {
      list = list.filter(u => u.userId === this.selectedUserId);
    }
    if (this.roleFilter) {
      list = list.filter(u => u.role === this.roleFilter);
    }
    const keyword = this.searchKeyword.trim();
    if (keyword) {
      const kw = keyword.toLowerCase();
      list = list.filter(u =>
        (u.username && u.username.toLowerCase().includes(kw))
      );
    }
    this.filteredUsers = list;
    this.currentPage = 1;
  }

  onFilterSearch(values: MerchantFilterValues): void {
    this.selectedUserId = values.merchantId;
    this.onSearch();
  }

  clearSearch(): void {
    this.searchKeyword = '';
    this.selectedUserId = '';
    this.roleFilter = '';
    this.filteredUsers = this.users;
    this.currentPage = 1;
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'ACTIVE': return 'status-active';
      case 'INACTIVE': return 'status-inactive';
      case 'SUSPENDED': return 'status-suspended';
      case 'DISABLED': return 'status-disabled';
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

  addUser(): void {
    this.router.navigate(['/add-user']);
  }

  viewUser(user: UserListItem): void {
    this.router.navigate(['/view-user', user.userId]);
  }

  editUser(user: UserListItem): void {
    this.router.navigate(['/edit-user', user.userId]);
  }

  openDeleteModal(user: UserListItem): void {
    this.userToDelete = user;
    this.showDeleteModal = true;
  }

  closeDeleteModal(): void {
    this.userToDelete = null;
    this.showDeleteModal = false;
    this.isDeleting = false;
  }

  confirmDelete(): void {
    if (!this.userToDelete) return;
    this.isDeleting = true;
    this.profileService.deleteUser(this.userToDelete.userId).subscribe({
      next: () => {
        this.closeDeleteModal();
        this.loadUsers();
      },
      error: (err) => {
        console.error('Failed to delete user:', err);
        this.isDeleting = false;
        alert('Failed to delete user. Please try again.');
      }
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
