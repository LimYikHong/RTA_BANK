import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuditLogService, AuditLogEntry } from '../../services/audit-log.service';
import { TableSorter } from '../../shared/table-sorter';

@Component({
  selector: 'app-system-log',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './system-log.component.html',
  styleUrl: './system-log.component.scss'
})
export class SystemLogComponent implements OnInit {
  systemLogs: AuditLogEntry[] = [];
  filteredSystemLogs: AuditLogEntry[] = [];
  systemSearchKeyword = '';
  systemLoading = false;
  systemSorter = new TableSorter<AuditLogEntry>();
  get systemSortKey() { return this.systemSorter.sortKey; }
  get systemSortDir() { return this.systemSorter.sortDir; }
  systemCurrentPage = 1;
  systemPageSize = 10;
  pageSizeOptions = [10, 25, 50, 100];

  constructor(private auditLogService: AuditLogService) {}

  ngOnInit(): void {
    this.loadSystemLogs();
  }

  loadSystemLogs(): void {
    this.systemLoading = true;
    this.auditLogService.getSystemActivityLogs().subscribe({
      next: (data) => {
        this.systemLogs = data;
        this.filteredSystemLogs = data;
        this.systemLoading = false;
      },
      error: (err) => {
        console.error('Failed to load system activity logs:', err);
        this.systemLoading = false;
      }
    });
  }

  onSystemSearch(): void {
    const kw = this.systemSearchKeyword.trim().toLowerCase();
    if (!kw) {
      this.filteredSystemLogs = this.systemLogs;
    } else {
      this.filteredSystemLogs = this.systemLogs.filter(log =>
        (log.action?.toLowerCase().includes(kw)) ||
        (log.description?.toLowerCase().includes(kw)) ||
        (log.status?.toLowerCase().includes(kw))
      );
    }
    this.systemCurrentPage = 1;
  }

  clearSystemSearch(): void {
    this.systemSearchKeyword = '';
    this.filteredSystemLogs = this.systemLogs;
    this.systemCurrentPage = 1;
  }

  systemSortBy(key: string): void {
    this.systemSorter.sortBy(key);
  }

  get sortedSystemLogs(): AuditLogEntry[] {
    return this.systemSorter.applyPaged(this.filteredSystemLogs, this.systemCurrentPage, this.systemPageSize);
  }

  get systemTotalElements(): number { return this.filteredSystemLogs.length; }
  get systemTotalPages(): number { return Math.max(1, Math.ceil(this.filteredSystemLogs.length / this.systemPageSize)); }
  get systemStartRecord(): number { return this.systemTotalElements === 0 ? 0 : (this.systemCurrentPage - 1) * this.systemPageSize + 1; }
  get systemEndRecord(): number { return Math.min(this.systemCurrentPage * this.systemPageSize, this.systemTotalElements); }
  get systemVisiblePages(): number[] {
    const pages: number[] = [];
    const maxVisible = 5;
    let start = Math.max(1, this.systemCurrentPage - Math.floor(maxVisible / 2));
    let end = start + maxVisible - 1;
    if (end > this.systemTotalPages) { end = this.systemTotalPages; start = Math.max(1, end - maxVisible + 1); }
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }
  goToSystemPage(page: number): void { if (page >= 1 && page <= this.systemTotalPages) this.systemCurrentPage = page; }
  onSystemPageSizeChange(): void { this.systemCurrentPage = 1; }

  getStatusClass(status: string | null): string {
    if (!status) return '';
    switch (status.toUpperCase()) {
      case 'SUCCESS': case 'VALIDATED': case 'PASS': return 'status-completed';
      case 'FAILED': return 'status-failed';
      case 'PENDING': case 'PROCESSING': return 'status-pending';
      case 'RECEIVED': return 'status-received';
      default: return 'status-received';
    }
  }
}
