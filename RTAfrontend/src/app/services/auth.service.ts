import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { MerchantProfile } from './profile.service';

@Injectable({
  providedIn: 'root',
})

/**
 * AuthService
 * - Handles login/logout and simple session helpers.
 * - Stores the authenticated merchant in localStorage under 'merchant'.
 */
export class AuthService {
  private apiUrl = 'http://localhost:8088/api/auth';

  constructor(private http: HttpClient) {}

  /**
   * POST /api/auth/login
   * - Sends credentials.
   * - Response can be actual MerchantProfile (old flow) 
   * - OR a Map with status: "2FA_REQUIRED" | "SETUP_2FA"
   */
  login(username: string, password: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/login`, {
      username,
      password,
    });
  }

  verify2fa(username: string, code: number): Observable<MerchantProfile> {
    return this.http.post<MerchantProfile>(`${this.apiUrl}/verify-2fa`, {
      username,
      code
    });
  }

  /**
   * Clears the local session.
   * - Removes 'merchant' from localStorage.
   * - Caller can also clear other caches if used elsewhere.
   */

  logout(): void {
    localStorage.removeItem('merchant');
  }

  /**
   * Quick boolean flag for route guards/UI.
   * - True if 'merchant' exists in localStorage.
   * - Does not validate token/expiry (demo-friendly).
   */
  isLoggedIn(): boolean {
    return !!localStorage.getItem('merchant');
  }

  /**
   * Reads the current user from localStorage.
   * - Returns parsed MerchantProfile or null if missing.
   */
  getCurrentUser(): MerchantProfile | null {
    const data = localStorage.getItem('merchant');
    return data ? (JSON.parse(data) as MerchantProfile) : null;
  }
}
