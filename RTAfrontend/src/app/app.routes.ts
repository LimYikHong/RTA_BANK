import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { DashboardComponent } from './dashboard/dashboard.component';
import { BatchListComponent } from './batch/batch-list/batch-list.component';
import { AddUserComponent } from './user/add-user/add-user.component';
import { EditUserComponent } from './user/edit-user/edit-user.component';
import { ViewUserComponent } from './user/view-user/view-user.component';
import { AddMerchantComponent } from './merchant/add-merchant/add-merchant.component';
import { EditMerchantComponent } from './merchant/edit-merchant/edit-merchant.component';
import { ViewMerchantComponent } from './merchant/view-merchant/view-merchant.component';
import { IncomingBatchComponent } from './batch/incoming-batch/incoming-batch.component';
import { BatchDetailComponent } from './batch/batch-detail/batch-detail.component';
import { BatchMaintenanceComponent } from './batch/batch-maintenance/batch-maintenance.component';
import { BatchMaintenanceDetailComponent } from './batch/batch-maintenance-detail/batch-maintenance-detail.component';
import { BatchFileMaintenanceComponent } from './batch/batch-file-maintenance/batch-file-maintenance.component';
import { BatchFileDetailComponent } from './batch/batch-file-detail/batch-file-detail.component';
import { UserManagementComponent } from './user/user-management/user-management.component';
import { MerchantMaintenanceComponent } from './merchant/merchant-maintenance/merchant-maintenance.component';
import { RecurringListComponent } from './recurring/recurring-list/recurring-list.component';
import { RecurringDetailComponent } from './recurring/recurring-detail/recurring-detail.component';
import { AuditLogComponent } from './audit-log/audit-log.component';
import { SystemLogComponent } from './audit-log/system-log/system-log.component';
import { CheckAuthResultComponent } from './batch/check-auth-result/check-auth-result.component';
import { AuthResultDetailComponent } from './batch/auth-result-detail/auth-result-detail.component';
import { ReportListComponent } from './report/report-list/report-list.component';
import { ReportDetailComponent } from './report/report-detail/report-detail.component';
import { authGuard } from './services/auth.guard';
import { superAdminGuard, permissionGuard } from './services/role.guard';
import { LayoutComponent } from './layout/layout.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  {
    path: '',
    component: LayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', component: DashboardComponent },
      { path: 'upload-batch-file', component: BatchListComponent },
      { path: 'incoming-batch', component: IncomingBatchComponent },
      { path: 'batch-detail/:batchFileId', component: BatchDetailComponent },
      { path: 'batch-file-maintenance', component: BatchFileMaintenanceComponent },
      { path: 'batch-file-detail/:batchFileId', component: BatchFileDetailComponent },
      { path: 'batch-maintenance', component: BatchMaintenanceComponent },
      { path: 'batch-maintenance-detail/:authBatchId', component: BatchMaintenanceDetailComponent },
      { path: 'check-auth-result', component: CheckAuthResultComponent },
      { path: 'auth-result-detail/:authBatchId', component: AuthResultDetailComponent },
      { path: 'profile', component: ViewUserComponent },
      { path: 'users', component: UserManagementComponent },
      { path: 'view-user/:userId', component: ViewUserComponent },
      { path: 'edit-user/:userId', component: EditUserComponent, canActivate: [permissionGuard('USER_EDIT')] },
      { path: 'merchant-maintenance', component: MerchantMaintenanceComponent },
      { path: 'recurring-list', component: RecurringListComponent },
      { path: 'recurring-detail/:recurringReference', component: RecurringDetailComponent },
      { path: 'add-user', component: AddUserComponent, canActivate: [permissionGuard('USER_CREATE')] },
      { path: 'add-merchant', component: AddMerchantComponent, canActivate: [permissionGuard('MERCHANT_CREATE')] },
      { path: 'edit-merchant/:merchantId', component: EditMerchantComponent, canActivate: [permissionGuard('MERCHANT_EDIT')] },
      { path: 'view-merchant/:merchantId', component: ViewMerchantComponent },
      { path: 'audit-log', component: AuditLogComponent },
      { path: 'system-log', component: SystemLogComponent },
      { path: 'report-list', component: ReportListComponent },
      { path: 'report-detail/:reportId', component: ReportDetailComponent },
    ]
  },
  { path: '', redirectTo: '/login', pathMatch: 'full' },
];
