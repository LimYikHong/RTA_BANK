import { Injectable, OnDestroy } from '@angular/core';

/**
 * Singleton service that counts down to the next 5-minute boundary
 * anchored to midnight (00:00:00).
 *
 * Slots: 00:00, 00:05, 00:10 ... 23:55 — 288 slots per day.
 * Because the next slot is derived purely from the current wall-clock time,
 * the countdown is identical on every page load / refresh.
 */
@Injectable({
  providedIn: 'root',
})
export class BatchTimerService implements OnDestroy {
  private readonly INTERVAL_MS = 300_000; // 5 minutes

  minutes = 0;
  seconds = 0;

  private tickInterval: any = null;
  private initialized = false;

  /** Call once from any component that needs the timer. */
  init(): void {
    if (this.initialized) return;
    this.initialized = true;
    this.tick();
    this.tickInterval = setInterval(() => this.tick(), 1000);
  }

  /**
   * Computes milliseconds remaining until the next 5-minute boundary
   * anchored to midnight of the current local day.
   */
  private msUntilNextSlot(): number {
    const now = new Date();
    const midnightMs =
      now.getTime() -
      (now.getHours() * 3_600_000 +
        now.getMinutes() * 60_000 +
        now.getSeconds() * 1_000 +
        now.getMilliseconds());
    const elapsedSinceMidnight = now.getTime() - midnightMs;
    const slotsPassed = Math.floor(elapsedSinceMidnight / this.INTERVAL_MS);
    const nextSlotMs = midnightMs + (slotsPassed + 1) * this.INTERVAL_MS;
    return Math.max(0, nextSlotMs - now.getTime());
  }

  private tick(): void {
    const remaining = this.msUntilNextSlot();
    this.minutes = Math.floor(remaining / 60_000);
    this.seconds = Math.floor((remaining % 60_000) / 1_000);
  }

  /** True during the final 10 seconds (triggers pulse animation). */
  get isUrgent(): boolean {
    return this.minutes === 0 && this.seconds <= 10;
  }

  /** Zero-padded seconds string, e.g. "04". */
  get secondsPadded(): string {
    return this.seconds < 10 ? '0' + this.seconds : String(this.seconds);
  }

  ngOnDestroy(): void {
    if (this.tickInterval) {
      clearInterval(this.tickInterval);
      this.tickInterval = null;
    }
  }
}
