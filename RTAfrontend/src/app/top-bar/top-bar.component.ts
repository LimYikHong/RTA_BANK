import { Component, OnInit, HostListener, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { ProfileService } from '../services/profile.service';

@Component({
  selector: 'app-top-bar',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './top-bar.component.html',
  styleUrl: './top-bar.component.scss'
})
export class TopBarComponent implements OnInit {
  userId: string | null = null;
  userRole: string | null = null;
  dropdownOpen = false;

  constructor(
    private authService: AuthService,
    private profileService: ProfileService,
    private router: Router,
    private elRef: ElementRef
  ) {}

  ngOnInit(): void {
    const profile = this.profileService.getProfile();
    this.userId = profile?.userId ?? null;
    this.userRole = profile?.role ?? null;
  }

  toggleDropdown(): void {
    this.dropdownOpen = !this.dropdownOpen;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    if (!this.elRef.nativeElement.contains(event.target)) {
      this.dropdownOpen = false;
    }
  }

  goToProfile(): void {
    this.dropdownOpen = false;
    this.router.navigate(['/profile']);
  }

  logout(): void {
    this.dropdownOpen = false;
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
