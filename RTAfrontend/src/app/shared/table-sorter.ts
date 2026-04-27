/**
 * Reusable table sort helper.
 *
 * Usage in any component:
 *   sorter = new TableSorter<MyItem>();
 *
 * Template:
 *   <th (click)="sorter.sortBy('name')" [attr.data-sort]="sorter.indicator('name')">Name</th>
 *
 * Sorted data:
 *   sorter.apply(items)            — returns sorted copy (no pagination)
 *   sorter.applyPaged(items, page, pageSize) — sorted + sliced
 */
export class TableSorter<T = any> {
  sortKey = '';
  sortDir: 'asc' | 'desc' = 'asc';

  /** Cycle: none → asc → desc → none */
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

  /** Returns 'asc' | 'desc' | '' for use in [attr.data-sort] */
  indicator(key: string): string {
    return this.sortKey === key ? this.sortDir : '';
  }

  /** Sort a list (returns new array, does NOT mutate). */
  apply(items: T[]): T[] {
    if (!this.sortKey || !items.length) return items;
    return [...items].sort((a, b) => {
      const av = (a as any)[this.sortKey] ?? '';
      const bv = (b as any)[this.sortKey] ?? '';
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return this.sortDir === 'asc' ? cmp : -cmp;
    });
  }

  /** Sort + paginate (1-based page). */
  applyPaged(items: T[], page: number, pageSize: number): T[] {
    const sorted = this.apply(items);
    const start = (page - 1) * pageSize;
    return sorted.slice(start, start + pageSize);
  }

  /** Reset sort state. */
  reset(): void {
    this.sortKey = '';
    this.sortDir = 'asc';
  }
}
