import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationEnd } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProfileService, UserListItem } from '../../services/profile.service';
import { AuthService } from '../../services/auth.service';
import { TopBarComponent } from '../../top-bar/top-bar.component';
import { Subscription, filter } from 'rxjs';

@Component({
  selector: 'app-user-management',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TopBarComponent],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.scss'
})
export class UserManagementComponent implements OnInit, OnDestroy {
  drawerOpen = true;
  toggleDrawer() { this.drawerOpen = !this.drawerOpen; }

  users: UserListItem[] = [];
  filteredUsers: UserListItem[] = [];
  searchKeyword: string = '';
  isLoading: boolean = false;
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
      this.loadUsers();
      return;
    }
    this.profileService.searchUsers(keyword).subscribe({
      next: (data) => {
        this.filteredUsers = data;
        this.currentPage = 1;
      },
      error: (err) => {
        console.error('Search failed:', err);
      }
    });
  }

  clearSearch(): void {
    this.searchKeyword = '';
    this.filteredUsers = this.users;
    this.currentPage = 1;
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
