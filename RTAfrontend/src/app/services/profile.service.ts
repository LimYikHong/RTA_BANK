import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of, tap, throwError } from 'rxjs';

/**
 * User profile shape used by the app.
 * Optional fields (username/password/photoUrl) support simple demo flows.
 */
export interface UserProfile {
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
  phone?: string;
  firstName?: string;
  lastName?: string;
  officeNumber?: string;
  status?: string;
  lastLoginAt?: string;
  failedAttempts?: number;
  isEnabled?: boolean;
  createdBy?: string;
  updatedAt?: string;
  lastModifiedBy?: string;
  deletedAt?: string;
  isTwoFactorEnabled?: boolean;
}

export interface UserListItem {
  id: number;
  username: string;
  name: string;
  email: string;
  merchantId: string;
  company: string;
  phone: string;
  status: string;
  joinedOn: string;
  role: string;
}

export interface MerchantInfoPayload {
  merchantId: string;
  merchantName: string;
  merchantBank: string;
  merchantCode: string;
  merchantPhoneNum: string;
  merchantAddress: string;
  merchantContactPerson: string;
  merchantAccNum: string;
  merchantAccName: string;
  transactionCurrency: string;
  settlementCurrency: string;
  createdBy: string;
}

@Injectable({
  providedIn: 'root',
})

/**
 * ProfileService
 * - Encapsulates all HTTP requests related to user profile
 * - Caches the latest profile in memory and mirrors it in localStorage
 * - Provides helpers for CRUD-like actions: fetch, update, upload photo
 */
export class ProfileService {
  private apiUrl = 'https://localhost:8086/api/profile';
  private merchantApiUrl = 'https://localhost:8086/api/merchants';
  private cachedProfile: UserProfile | null = null;

  constructor(private http: HttpClient) {}
  /**
   * GET /api/profile/{merchantId}
   * - Fetches profile from server
   * - On success: cache + return
   * - On error: log and return an empty profile (keeps UI flowing)
   */
  fetchProfile(merchantId: string): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.apiUrl}/${merchantId}`).pipe(
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
    updatedProfile: UserProfile
  ): Observable<UserProfile> {
    return this.http
      .put<UserProfile>(`${this.apiUrl}/${merchantId}`, updatedProfile)
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
  ): Observable<UserProfile> {
    const formData = new FormData();
    formData.append('profilePhoto', file);

    return this.http
      .post<UserProfile>(`${this.apiUrl}/${merchantId}/photo`, formData)
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

  createUser(user: UserProfile, role: string): Observable<UserProfile> {
    return this.http.post<UserProfile>(`${this.apiUrl}/users?role=${role}`, user);
  }

  checkUsername(username: string): Observable<{ exists: boolean }> {
    return this.http.get<{ exists: boolean }>(`${this.apiUrl}/check-username?username=${encodeURIComponent(username)}`);
  }

  checkUserId(userId: string): Observable<{ exists: boolean }> {
    return this.http.get<{ exists: boolean }>(`${this.apiUrl}/check-userid?userId=${encodeURIComponent(userId)}`);
  }

  getAllUsers(): Observable<UserListItem[]> {
    return this.http.get<UserListItem[]>(`${this.apiUrl}/users`).pipe(
      catchError((err) => {
        console.error('Failed to fetch users:', err);
        return of([]);
      })
    );
  }

  searchUsers(keyword: string): Observable<UserListItem[]> {
    return this.http.get<UserListItem[]>(`${this.apiUrl}/users/search?keyword=${encodeURIComponent(keyword)}`).pipe(
      catchError((err) => {
        console.error('Failed to search users:', err);
        return of([]);
      })
    );
  }

  createMerchant(payload: MerchantInfoPayload): Observable<any> {
    return this.http.post<any>(`${this.merchantApiUrl}`, payload);
  }

  checkMerchantId(merchantId: string): Observable<{ exists: boolean }> {
    return this.http.get<{ exists: boolean }>(`${this.merchantApiUrl}/check-id?merchantId=${encodeURIComponent(merchantId)}`);
  }

  setProfile(profile: UserProfile): void {
    this.cachedProfile = profile;
    localStorage.setItem('userProfile', JSON.stringify(profile));
  }

  getProfile(): UserProfile {
    if (!this.cachedProfile) {
      const stored = localStorage.getItem('userProfile');
      if (stored) {
        this.cachedProfile = JSON.parse(stored);
      }
    }
    return this.cachedProfile ?? this.emptyProfile();
  }

  clearProfile(): void {
    this.cachedProfile = null;
    localStorage.removeItem('userProfile');
  }

  /**
   * emptyProfile
   * - Returns a safe, UI-friendly empty profile object to avoid null checks.
   */

  private emptyProfile(): UserProfile {
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
      phone: '', // Initialize phone as empty string
    };
  }
}
