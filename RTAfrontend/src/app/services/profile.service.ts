import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of, tap, throwError } from 'rxjs';

/**
 * Merchant profile shape used by the app.
 * Optional fields (username/password/photoUrl) support simple demo flows.
 */
export interface MerchantProfile {
  merchantId: string;
  name: string;
  email: string;
  company: string;
  contact: string;
  address: string;
  joinedOn?: string;
  username?: string;
  password?: string;
  profilePhotoUrl?: string;
}

@Injectable({
  providedIn: 'root',
})

/**
 * ProfileService
 * - Encapsulates all HTTP requests related to merchant profile
 * - Caches the latest profile in memory and mirrors it in localStorage
 * - Provides helpers for CRUD-like actions: fetch, update, upload photo
 */
export class ProfileService {
  private apiUrl = 'http://localhost:8088/api/profile';
  private cachedProfile: MerchantProfile | null = null;

  constructor(private http: HttpClient) {}
  /**
   * GET /api/profile/{merchantId}
   * - Fetches profile from server
   * - On success: cache + return
   * - On error: log and return an empty profile (keeps UI flowing)
   */
  fetchProfile(merchantId: string): Observable<MerchantProfile> {
    return this.http.get<MerchantProfile>(`${this.apiUrl}/${merchantId}`).pipe(
      tap((profile) => this.setProfile(profile)),
      catchError((err) => {
        console.error('Failed to fetch profile:', err);
        return of(this.emptyProfile());
      })
    );
  }

  /**
   * PUT /api/profile/{merchantId}
   * - Sends updated profile to server
   * - On success: refresh cache + return updated profile
   * - On error: propagate error to the caller (form can show message)
   */

  updateProfile(
    merchantId: string,
    updatedProfile: MerchantProfile
  ): Observable<MerchantProfile> {
    return this.http
      .put<MerchantProfile>(`${this.apiUrl}/${merchantId}`, updatedProfile)
      .pipe(
        tap((profile) => {
          this.setProfile(profile);
          console.log('Profile updated successfully');
        }),
        catchError((err) => {
          console.error('Failed to update profile:', err);
          return throwError(() => err);
        })
      );
  }

  /**
   * POST /api/profile/{merchantId}/photo
   * - Uploads a profile photo via multipart/form-data
   * - On success: cache refreshed profile returned by backend
   * - On error: propagate error to the caller
   */

  uploadProfilePhoto(
    merchantId: string,
    file: File
  ): Observable<MerchantProfile> {
    const formData = new FormData();
    formData.append('profilePhoto', file);

    return this.http
      .post<MerchantProfile>(`${this.apiUrl}/${merchantId}/photo`, formData)
      .pipe(
        tap((profile) => {
          this.setProfile(profile);
          console.log('Photo uploaded successfully');
        }),
        catchError((err) => {
          console.error('Failed to upload photo:', err);
          return throwError(() => err);
        })
      );
  }

  setProfile(profile: MerchantProfile): void {
    this.cachedProfile = profile;
    localStorage.setItem('merchantProfile', JSON.stringify(profile));
  }

  getProfile(): MerchantProfile {
    if (!this.cachedProfile) {
      const stored = localStorage.getItem('merchantProfile');
      if (stored) {
        this.cachedProfile = JSON.parse(stored);
      }
    }
    return this.cachedProfile ?? this.emptyProfile();
  }

  clearProfile(): void {
    this.cachedProfile = null;
    localStorage.removeItem('merchantProfile');
  }

  /**
   * emptyProfile
   * - Returns a safe, UI-friendly empty profile object to avoid null checks.
   */

  private emptyProfile(): MerchantProfile {
    return {
      merchantId: '',
      name: '',
      email: '',
      company: '',
      contact: '',
      address: '',
      joinedOn: '',
      username: '',
      password: '',
      profilePhotoUrl: '',
    };
  }
}
