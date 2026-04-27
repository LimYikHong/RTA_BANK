import { TableSorter } from './table-sorter';

interface TestItem {
  id: number;
  name: string;
  amount: number;
}

describe('TableSorter', () => {
  let sorter: TableSorter<TestItem>;

  const sampleData: TestItem[] = [
    { id: 3, name: 'Charlie', amount: 300 },
    { id: 1, name: 'Alice',   amount: 100 },
    { id: 5, name: 'Eve',     amount: 500 },
    { id: 2, name: 'Bob',     amount: 200 },
    { id: 4, name: 'Diana',   amount: 400 },
  ];

  beforeEach(() => {
    sorter = new TableSorter<TestItem>();
  });

  // ──────────────── Initial state ────────────────

  it('should have empty sortKey and asc direction by default', () => {
    expect(sorter.sortKey).toBe('');
    expect(sorter.sortDir).toBe('asc');
  });

  // ──────────────── sortBy() ────────────────

  it('should set sortKey and asc on first click', () => {
    sorter.sortBy('id');
    expect(sorter.sortKey).toBe('id');
    expect(sorter.sortDir).toBe('asc');
  });

  it('should toggle to desc on second click of same key', () => {
    sorter.sortBy('id');
    sorter.sortBy('id');
    expect(sorter.sortKey).toBe('id');
    expect(sorter.sortDir).toBe('desc');
  });

  it('should clear sortKey on third click of same key', () => {
    sorter.sortBy('id');
    sorter.sortBy('id');
    sorter.sortBy('id');
    expect(sorter.sortKey).toBe('');
    expect(sorter.sortDir).toBe('asc');
  });

  it('should reset to asc when switching to a different key', () => {
    sorter.sortBy('id');
    sorter.sortBy('id');  // desc
    sorter.sortBy('name');
    expect(sorter.sortKey).toBe('name');
    expect(sorter.sortDir).toBe('asc');
  });

  // ──────────────── indicator() ────────────────

  it('should return sort direction for active key', () => {
    sorter.sortBy('id');
    expect(sorter.indicator('id')).toBe('asc');
    expect(sorter.indicator('name')).toBe('');
  });

  // ──────────────── apply() ────────────────

  it('should return unsorted items when no sortKey is set', () => {
    const result = sorter.apply(sampleData);
    expect(result[0].id).toBe(3); // original order
  });

  it('should sort ascending by id', () => {
    sorter.sortBy('id');
    const result = sorter.apply(sampleData);
    expect(result.map(r => r.id)).toEqual([1, 2, 3, 4, 5]);
  });

  it('should sort descending by id', () => {
    sorter.sortBy('id');
    sorter.sortBy('id'); // toggle to desc
    const result = sorter.apply(sampleData);
    expect(result.map(r => r.id)).toEqual([5, 4, 3, 2, 1]);
  });

  it('should sort alphabetically by name', () => {
    sorter.sortBy('name');
    const result = sorter.apply(sampleData);
    expect(result.map(r => r.name)).toEqual(['Alice', 'Bob', 'Charlie', 'Diana', 'Eve']);
  });

  it('should not mutate the original array', () => {
    sorter.sortBy('id');
    const original = [...sampleData];
    sorter.apply(sampleData);
    expect(sampleData[0].id).toBe(original[0].id);
  });

  it('should handle empty array', () => {
    sorter.sortBy('id');
    const result = sorter.apply([]);
    expect(result).toEqual([]);
  });

  // ──────────────── applyPaged() ────────────────

  it('should return first page correctly', () => {
    sorter.sortBy('id');
    const result = sorter.applyPaged(sampleData, 1, 2);
    expect(result.length).toBe(2);
    expect(result[0].id).toBe(1);
    expect(result[1].id).toBe(2);
  });

  it('should return second page correctly', () => {
    sorter.sortBy('id');
    const result = sorter.applyPaged(sampleData, 2, 2);
    expect(result.length).toBe(2);
    expect(result[0].id).toBe(3);
    expect(result[1].id).toBe(4);
  });

  it('should return partial last page', () => {
    sorter.sortBy('id');
    const result = sorter.applyPaged(sampleData, 3, 2);
    expect(result.length).toBe(1);
    expect(result[0].id).toBe(5);
  });

  it('should return empty for out-of-range page', () => {
    sorter.sortBy('id');
    const result = sorter.applyPaged(sampleData, 10, 2);
    expect(result.length).toBe(0);
  });

  // ──────────────── reset() ────────────────

  it('should clear sort state on reset', () => {
    sorter.sortBy('id');
    sorter.sortBy('id');
    sorter.reset();
    expect(sorter.sortKey).toBe('');
    expect(sorter.sortDir).toBe('asc');
  });

  // ──────────────── Edge cases ────────────────

  it('should handle null/undefined values gracefully', () => {
    const dataWithNulls: any[] = [
      { id: 1, name: null },
      { id: 2, name: 'Bob' },
      { id: 3, name: undefined },
    ];
    sorter.sortBy('name');
    const result = sorter.apply(dataWithNulls);
    expect(result.length).toBe(3);
    // Should not throw
  });

  it('should sort numeric strings correctly with numeric option', () => {
    const data: any[] = [
      { id: 1, name: 'Item 10' },
      { id: 2, name: 'Item 2' },
      { id: 3, name: 'Item 1' },
    ];
    sorter.sortBy('name');
    const result = sorter.apply(data);
    expect(result[0].name).toBe('Item 1');
    expect(result[1].name).toBe('Item 2');
    expect(result[2].name).toBe('Item 10');
  });
});
