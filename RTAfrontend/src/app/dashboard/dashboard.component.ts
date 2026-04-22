import { Component, OnInit, AfterViewInit, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DashboardService, DashboardStats, RsaKeyStatus, MerchantKeyOverview } from '../services/dashboard.service';
import { AuthService } from '../services/auth.service';
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, AfterViewInit, OnDestroy {
  stats: DashboardStats | null = null;
  loading = true;
  error = false;
  lastRefreshed: Date | null = null;
  private refreshInterval: any;

  // RSA Key state
  isSuperAdmin = false;
  rsaKeyStatus: RsaKeyStatus | null = null;
  rsaKeyLoading = false;
  rsaKeyMessage = '';
  rsaKeyMessageType: 'success' | 'error' | '' = '';

  // Merchant Key Overview
  merchantKeys: MerchantKeyOverview[] = [];
  merchantKeysLoading = false;

  // Canvas refs for charts
  @ViewChild('trendCanvas')     trendCanvasRef!:    ElementRef<HTMLCanvasElement>;
  @ViewChild('txnStatusCanvas') txnStatusRef!:      ElementRef<HTMLCanvasElement>;
  @ViewChild('filePieCanvas')   filePieRef!:        ElementRef<HTMLCanvasElement>;
  @ViewChild('barCanvas')       barCanvasRef!:      ElementRef<HTMLCanvasElement>;
  @ViewChild('txnBarCanvas')    txnBarCanvasRef!:   ElementRef<HTMLCanvasElement>;
  @ViewChild('recurringCanvas') recurringRef!:      ElementRef<HTMLCanvasElement>;
  @ViewChild('amountCanvas')    amountCanvasRef!:   ElementRef<HTMLCanvasElement>;

  constructor(private dashboardService: DashboardService, private authService: AuthService) {}

  ngOnInit(): void {
    this.isSuperAdmin = this.authService.isSuperAdmin();
    this.load();
    if (this.isSuperAdmin) {
      this.loadRsaKeyStatus();
    }
    this.loadMerchantKeyOverview();
    // Auto-refresh every 60 s
    this.refreshInterval = setInterval(() => this.load(), 60_000);
  }

  ngAfterViewInit(): void {
    // Charts drawn after first data load inside load()
  }

  ngOnDestroy(): void {
    clearInterval(this.refreshInterval);
  }

  load(): void {
    this.loading = true;
    this.error = false;
    this.dashboardService.getStats().subscribe({
      next: (data) => {
        this.stats = data;
        this.loading = false;
        this.lastRefreshed = new Date();
        setTimeout(() => this.drawAllCharts(), 50);
      },
      error: (err) => {
        console.error('Dashboard API error:', err);
        this.loading = false;
        this.error = true;
      }
    });
  }

  // ── Chart helpers ──────────────────────────────────────────────────────────

  private drawAllCharts(): void {
    if (!this.stats) return;
    this.drawTrendChart();
    this.drawTxnStatusPie();
    this.drawFilePie();
    this.drawBarChart();
    this.drawTxnPerMerchantBar();
    this.drawRecurringPie();
    this.drawAmountTrend();
  }

  // Trend line chart (success vs failed transactions, last 7 days)
  private drawTrendChart(): void {
    const canvas = this.trendCanvasRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width  = canvas.offsetWidth;
    canvas.height = 220;
    const ctx = canvas.getContext('2d')!;
    const trend = this.stats.transactionTrend;
    const W = canvas.width; const H = canvas.height;
    const PAD = { top: 20, right: 20, bottom: 40, left: 50 };
    const cW = W - PAD.left - PAD.right;
    const cH = H - PAD.top  - PAD.bottom;

    ctx.clearRect(0, 0, W, H);

    const maxVal = Math.max(1, ...trend.map(p => Math.max(p.success, p.failed)));
    const steps = trend.length;
    const xStep = cW / Math.max(steps - 1, 1);

    ctx.strokeStyle = '#e2e8f0'; ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = PAD.top + cH - (i / 4) * cH;
      ctx.beginPath(); ctx.moveTo(PAD.left, y); ctx.lineTo(PAD.left + cW, y); ctx.stroke();
      ctx.fillStyle = '#94a3b8'; ctx.font = '11px Segoe UI'; ctx.textAlign = 'right';
      ctx.fillText(String(Math.round((i / 4) * maxVal)), PAD.left - 6, y + 4);
    }

    ctx.fillStyle = '#64748b'; ctx.font = '11px Segoe UI'; ctx.textAlign = 'center';
    trend.forEach((p, i) => { ctx.fillText(p.day, PAD.left + i * xStep, H - 10); });

    const drawLine = (data: number[], color: string) => {
      ctx.beginPath();
      ctx.strokeStyle = color; ctx.lineWidth = 2.5; ctx.lineJoin = 'round';
      data.forEach((v, i) => {
        const x = PAD.left + i * xStep;
        const y = PAD.top + cH - (v / maxVal) * cH;
        i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
      });
      ctx.stroke();
      data.forEach((v, i) => {
        const x = PAD.left + i * xStep;
        const y = PAD.top + cH - (v / maxVal) * cH;
        ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2);
        ctx.fillStyle = color; ctx.fill();
      });
    };

    drawLine(trend.map(p => p.success), '#16a34a');
    drawLine(trend.map(p => p.failed),  '#dc2626');

    ctx.fillStyle = '#16a34a'; ctx.fillRect(PAD.left, PAD.top - 10, 12, 12);
    ctx.fillStyle = '#1e293b'; ctx.font = '11px Segoe UI'; ctx.textAlign = 'left';
    ctx.fillText('Success', PAD.left + 16, PAD.top);
    ctx.fillStyle = '#dc2626'; ctx.fillRect(PAD.left + 80, PAD.top - 10, 12, 12);
    ctx.fillStyle = '#1e293b';
    ctx.fillText('Failed', PAD.left + 96, PAD.top);
  }

  // Transaction status donut
  private drawTxnStatusPie(): void {
    const canvas = this.txnStatusRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width  = canvas.offsetWidth;
    canvas.height = canvas.offsetWidth;  // square
    const data = this.stats.transactionStatusBreakdown;
    this.drawDonut(canvas, data, this.TXN_COLORS);
  }

  // Incoming file status donut
  private drawFilePie(): void {
    const canvas = this.filePieRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width  = canvas.offsetWidth;
    canvas.height = canvas.offsetWidth;
    const data = this.stats.incomingFileStatusBreakdown;
    this.drawDonut(canvas, data, this.FILE_COLORS);
  }

  // Recurring vs One-Time donut
  private drawRecurringPie(): void {
    const canvas = this.recurringRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width  = canvas.offsetWidth;
    canvas.height = canvas.offsetWidth;
    const data = this.stats.recurringBreakdown;
    this.drawDonut(canvas, data, this.RECURRING_COLORS);
  }

  private readonly TXN_COLORS: Record<string, string> = {
    SUCCESS: '#16a34a', FAILED: '#dc2626', PENDING: '#d97706',
    PROCESSING: '#2563eb', UNKNOWN: '#94a3b8'
  };
  private readonly FILE_COLORS: Record<string, string> = {
    VALIDATED: '#16a34a', PROCESSING: '#2563eb', PENDING: '#d97706',
    FAILED: '#dc2626', RECEIVED: '#7c3aed', PARTIAL: '#2563eb', UNKNOWN: '#94a3b8'
  };
  private readonly RECURRING_COLORS: Record<string, string> = {
    'RECURRING': '#1e40af', 'ONE-TIME': '#94a3b8'
  };
  private readonly DEFAULT_COLORS = ['#2563eb','#16a34a','#d97706','#dc2626','#7c3aed','#0891b2','#94a3b8'];

  private drawDonut(canvas: HTMLCanvasElement, data: Record<string, number>, colorMap: Record<string, string>): void {
    const ctx = canvas.getContext('2d')!;
    const W = canvas.width; const H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    const entries = Object.entries(data);
    const total = entries.reduce((s, [, v]) => s + v, 0);
    if (total === 0) {
      ctx.fillStyle = '#94a3b8'; ctx.font = '13px Segoe UI'; ctx.textAlign = 'center';
      ctx.fillText('No data', W / 2, H / 2 + 5);
      return;
    }

    // Reserve bottom area for legend: 20px per row of 2 entries
    const legendRows = Math.ceil(entries.length / 2);
    const legendH = legendRows * 20 + 8;
    const chartH = H - legendH;
    const cx = W / 2; const cy = chartH / 2;
    const radius = Math.min(cx, cy) - 10;
    const innerR = radius * 0.55;
    let angle = -Math.PI / 2;

    entries.forEach(([label, val], idx) => {
      const slice = (val / total) * 2 * Math.PI;
      const color = colorMap[label] ?? this.DEFAULT_COLORS[idx % this.DEFAULT_COLORS.length];
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.arc(cx, cy, radius, angle, angle + slice);
      ctx.closePath();
      ctx.fillStyle = color; ctx.fill();
      angle += slice;
    });

    ctx.beginPath(); ctx.arc(cx, cy, innerR, 0, Math.PI * 2);
    ctx.fillStyle = '#ffffff'; ctx.fill();

    ctx.fillStyle = '#1e293b'; ctx.font = 'bold 18px Segoe UI'; ctx.textAlign = 'center';
    ctx.fillText(String(total), cx, cy + 7);

    // Legend: 2 columns below the donut
    const legendStartY = chartH + 8;
    const colW = W / 2;
    entries.forEach(([label, val], idx) => {
      const col = idx % 2; const row = Math.floor(idx / 2);
      const lx = col * colW + 8; const ly = legendStartY + row * 20;
      const color = colorMap[label] ?? this.DEFAULT_COLORS[idx % this.DEFAULT_COLORS.length];
      ctx.fillStyle = color; ctx.fillRect(lx, ly, 10, 10);
      ctx.fillStyle = '#475569'; ctx.font = '10px Segoe UI'; ctx.textAlign = 'left';
      ctx.fillText(`${label} (${val})`, lx + 14, ly + 9);
    });
  }

  // Horizontal bar chart (incoming files per merchant)
  private drawBarChart(): void {
    const canvas = this.barCanvasRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width = canvas.offsetWidth;
    canvas.height = this.getBarChartHeight();
    this.drawHorizontalBar(canvas, this.stats.incomingFilesPerMerchant, '#2563eb');
  }

  // Horizontal bar chart (transactions per merchant)
  private drawTxnPerMerchantBar(): void {
    const canvas = this.txnBarCanvasRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width = canvas.offsetWidth;
    canvas.height = this.getTxnBarChartHeight();
    this.drawHorizontalBar(canvas, this.stats.txnPerMerchant, '#1e40af');
  }

  private drawHorizontalBar(canvas: HTMLCanvasElement, items: {merchantId: string; count: number}[], barColor: string): void {
    const ctx = canvas.getContext('2d')!;
    const W = canvas.width; const H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    if (items.length === 0) {
      ctx.fillStyle = '#94a3b8'; ctx.font = '13px Segoe UI'; ctx.textAlign = 'center';
      ctx.fillText('No data', W / 2, H / 2); return;
    }

    const PAD = { top: 10, right: 50, bottom: 10, left: 90 };
    const barH = Math.min(28, (H - PAD.top - PAD.bottom) / items.length - 6);
    const maxVal = Math.max(...items.map(i => i.count));
    const availW = W - PAD.left - PAD.right;

    items.forEach((item, idx) => {
      const y = PAD.top + idx * (barH + 8);
      const barW = (item.count / maxVal) * availW;

      ctx.fillStyle = '#475569'; ctx.font = '12px Segoe UI'; ctx.textAlign = 'right';
      ctx.fillText(item.merchantId, PAD.left - 6, y + barH / 2 + 4);

      ctx.fillStyle = barColor;
      ctx.beginPath(); this.roundRect(ctx, PAD.left, y, barW, barH, 3); ctx.fill();

      ctx.fillStyle = '#1e293b'; ctx.font = '11px Segoe UI'; ctx.textAlign = 'left';
      ctx.fillText(String(item.count), PAD.left + barW + 6, y + barH / 2 + 4);
    });
  }

  // Daily transaction amount bar chart (vertical bars)
  private drawAmountTrend(): void {
    const canvas = this.amountCanvasRef?.nativeElement;
    if (!canvas || !this.stats) return;
    canvas.width  = canvas.offsetWidth;
    canvas.height = canvas.offsetWidth;  // square like donuts
    const ctx = canvas.getContext('2d')!;
    const data = this.stats.dailyAmountTrend;
    const W = canvas.width; const H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    const PAD = { top: 14, right: 10, bottom: 36, left: 58 };
    const cW = W - PAD.left - PAD.right;
    const cH = H - PAD.top  - PAD.bottom;
    const maxAmt = Math.max(1, ...data.map(d => d.amount));
    const barW = cW / data.length * 0.6;
    const gap  = cW / data.length;

    ctx.strokeStyle = '#e2e8f0'; ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = PAD.top + cH - (i / 4) * cH;
      ctx.beginPath(); ctx.moveTo(PAD.left, y); ctx.lineTo(PAD.left + cW, y); ctx.stroke();
      ctx.fillStyle = '#94a3b8'; ctx.font = '10px Segoe UI'; ctx.textAlign = 'right';
      const label = (maxAmt * i / 4 / 100).toFixed(0);
      ctx.fillText(label, PAD.left - 4, y + 4);
    }

    data.forEach((d, i) => {
      const x = PAD.left + i * gap + (gap - barW) / 2;
      const bH = (d.amount / maxAmt) * cH;
      const y = PAD.top + cH - bH;
      ctx.fillStyle = '#0f766e';
      ctx.beginPath(); this.roundRect(ctx, x, y, barW, bH, 3); ctx.fill();
      ctx.fillStyle = '#64748b'; ctx.font = '10px Segoe UI'; ctx.textAlign = 'center';
      ctx.fillText(d.day, x + barW / 2, H - 8);
    });
  }

  private roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r); ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

  // ── RSA Key methods ─────────────────────────────────────────────────────

  loadRsaKeyStatus(): void {
    this.dashboardService.getRsaKeyStatus().subscribe({
      next: (status) => { this.rsaKeyStatus = status; },
      error: () => { this.rsaKeyStatus = null; }
    });
  }

  loadMerchantKeyOverview(): void {
    this.merchantKeysLoading = true;
    this.dashboardService.getMerchantKeyOverview().subscribe({
      next: (keys) => {
        this.merchantKeys = keys;
        this.merchantKeysLoading = false;
      },
      error: () => {
        this.merchantKeys = [];
        this.merchantKeysLoading = false;
      }
    });
  }

  getMerchantKeyStatusClass(mk: MerchantKeyOverview): string {
    if (!mk.hasKey) return 'mk-no-key';
    if (mk.expired) return 'mk-expired';
    if (mk.needsRotation) return 'mk-warning';
    return 'mk-active';
  }

  getMerchantKeyStatusLabel(mk: MerchantKeyOverview): string {
    if (!mk.hasKey) return 'No Key';
    if (mk.expired) return 'Expired';
    if (mk.needsRotation) return 'Needs Rotation';
    return 'Active';
  }

  get merchantKeysNeedingAction(): MerchantKeyOverview[] {
    return this.merchantKeys.filter(mk => !mk.hasKey || mk.expired || mk.needsRotation);
  }

  get rsaButtonLabel(): string {
    if (!this.rsaKeyStatus || !this.rsaKeyStatus.hasKey) return 'Request RSA Key';
    return 'Renew RSA Key';
  }

  get rsaButtonDisabled(): boolean {
    if (this.rsaKeyLoading) return true;
    if (!this.rsaKeyStatus) return false; // allow first request
    if (!this.rsaKeyStatus.hasKey) return false; // no key yet
    return !this.rsaKeyStatus.canRequest; // disabled before day 25 and after day 30
  }

  get rsaButtonTooltip(): string {
    if (!this.rsaKeyStatus || !this.rsaKeyStatus.hasKey) return 'Click to request an RSA key from the consumer system';
    if (this.rsaKeyStatus.expired) return 'RSA key has expired. Please contact system administrator.';
    if (this.rsaKeyStatus.canRequest) return 'RSA key expiring soon. Click to renew.';
    return `RSA key valid for ${this.rsaKeyStatus.daysRemaining} more days. Renewal available from day 25.`;
  }

  get rsaAlertMessage(): string {
    if (!this.rsaKeyStatus) return '';
    if (this.rsaKeyStatus.expired) return '⚠ RSA key has expired! Please contact system administrator.';
    if (this.rsaKeyStatus.needsRenewal) return `⚠ RSA key expires in ${this.rsaKeyStatus.daysRemaining} days. Please renew now.`;
    return '';
  }

  onRequestRsaKey(): void {
    if (this.rsaButtonDisabled) return;
    this.rsaKeyLoading = true;
    this.rsaKeyMessage = '';
    this.rsaKeyMessageType = '';

    this.dashboardService.requestRsaKey().subscribe({
      next: (res) => {
        this.rsaKeyLoading = false;
        this.rsaKeyMessage = res.message;
        this.rsaKeyMessageType = res.success ? 'success' : 'error';
        if (res.success) this.loadRsaKeyStatus();
      },
      error: (err) => {
        this.rsaKeyLoading = false;
        this.rsaKeyMessage = err.error?.message || 'Failed to request RSA key.';
        this.rsaKeyMessageType = 'error';
      }
    });
  }

  // ── Template helpers ───────────────────────────────────────────────────────

  objectEntries(obj: Record<string, number> | undefined): [string, number][] {
    return obj ? Object.entries(obj) : [];
  }

  getStatusClass(status: string | null): string {
    if (!status) return 'status-received';
    switch (status.toUpperCase()) {
      case 'SUCCESS': case 'VALIDATED': case 'COMPLETED': case 'APPROVED': case 'PROCESSED': return 'status-completed';
      case 'FAILED': return 'status-failed';
      case 'PENDING': case 'PROCESSING': case 'CREATED': case 'SENT': return 'status-pending';
      default: return 'status-received';
    }
  }

  getBarChartHeight(): number {
    if (!this.stats?.incomingFilesPerMerchant) return 160;
    return Math.max(160, this.stats.incomingFilesPerMerchant.length * 38);
  }

  getTxnBarChartHeight(): number {
    if (!this.stats?.txnPerMerchant) return 160;
    return Math.max(160, this.stats.txnPerMerchant.length * 38);
  }
}
