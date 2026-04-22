import { Component, Input, Output, EventEmitter, ElementRef, HostListener, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface MerchantFilterValues {
  merchantId: string;
  dateFrom: string;
  dateTo: string;
}

@Component({
  selector: 'app-merchant-filter',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './merchant-filter.component.html',
  styles: [`:host { display: contents; }`]
})
export class MerchantFilterComponent implements OnChanges {
  @Input() merchantIds: string[] = [];
  @Input() showDateRange = true;
  @Input() filterStyle = '';
  @Input() dropdownLabel = 'Merchant ID';
  @Input() dropdownPlaceholder = 'All Merchants';

  @Output() search = new EventEmitter<MerchantFilterValues>();

  merchantIdInput = '';
  merchantSelectedId = '';
  showMerchantDropdown = false;
  filteredMerchantIds: string[] = [];
  dateFrom = '';
  dateTo = '';

  constructor(private elRef: ElementRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['merchantIds']) {
      this.filteredMerchantIds = [...this.merchantIds];
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    const combobox = this.elRef.nativeElement.querySelector('.merchant-combobox');
    if (combobox && !combobox.contains(event.target)) {
      this.showMerchantDropdown = false;
    }
  }

  toggleMerchantDropdown(): void {
    this.showMerchantDropdown = !this.showMerchantDropdown;
    if (this.showMerchantDropdown) {
      this.filteredMerchantIds = [...this.merchantIds];
    }
  }

  onMerchantInputFocus(): void {
    this.filteredMerchantIds = this.merchantIdInput.trim()
      ? this.merchantIds.filter(id => id.toLowerCase().includes(this.merchantIdInput.trim().toLowerCase()))
      : [...this.merchantIds];
    this.showMerchantDropdown = true;
  }

  onMerchantInputChange(): void {
    const typed = this.merchantIdInput.trim().toLowerCase();
    this.showMerchantDropdown = true;
    if (!typed) {
      this.filteredMerchantIds = [...this.merchantIds];
      this.merchantSelectedId = '';
      return;
    }
    this.filteredMerchantIds = this.merchantIds.filter(id => id.toLowerCase().includes(typed));
    const exact = this.merchantIds.find(id => id.toLowerCase() === typed);
    this.merchantSelectedId = exact ?? '';
  }

  selectMerchant(id: string): void {
    this.merchantSelectedId = id;
    this.merchantIdInput = id;
    this.showMerchantDropdown = false;
  }

  clearMerchant(): void {
    this.merchantIdInput = '';
    this.merchantSelectedId = '';
    this.filteredMerchantIds = [...this.merchantIds];
    this.showMerchantDropdown = false;
  }

  onSearch(): void {
    this.showMerchantDropdown = false;
    this.search.emit({
      merchantId: this.merchantSelectedId,
      dateFrom: this.dateFrom,
      dateTo: this.dateTo
    });
  }
}
