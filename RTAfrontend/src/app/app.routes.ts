import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
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
import { authGuard } from './services/auth.guard';
import { superAdminGuard, permissionGuard } from './services/role.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'batch-list', component: BatchListComponent, canActivate: [authGuard] },
  { path: 'incoming-batch', component: IncomingBatchComponent, canActivate: [authGuard] },
  { path: 'batch-detail/:batchFileId', component: BatchDetailComponent, canActivate: [authGuard] },
  { path: 'batch-file-maintenance', component: BatchFileMaintenanceComponent, canActivate: [authGuard] },
  { path: 'batch-file-detail/:batchFileId', component: BatchFileDetailComponent, canActivate: [authGuard] },
  { path: 'batch-maintenance', component: BatchMaintenanceComponent, canActivate: [authGuard] },
  { path: 'batch-maintenance-detail/:authBatchId', component: BatchMaintenanceDetailComponent, canActivate: [authGuard] },
  { path: 'profile', component: ViewUserComponent, canActivate: [authGuard] },
  { path: 'users', component: UserManagementComponent, canActivate: [authGuard] },
  { path: 'view-user/:userId', component: ViewUserComponent, canActivate: [authGuard] },
  { path: 'edit-user/:userId', component: EditUserComponent, canActivate: [authGuard, permissionGuard('USER_EDIT')] },
  { path: 'merchant-maintenance', component: MerchantMaintenanceComponent, canActivate: [authGuard] },
  { path: 'recurring-list', component: RecurringListComponent, canActivate: [authGuard] },
  { path: 'recurring-detail/:recurringReference', component: RecurringDetailComponent, canActivate: [authGuard] },
  { path: 'add-user', component: AddUserComponent, canActivate: [authGuard, permissionGuard('USER_CREATE')] },
  { path: 'add-merchant', component: AddMerchantComponent, canActivate: [authGuard, permissionGuard('MERCHANT_CREATE')] },
  { path: 'edit-merchant/:merchantId', component: EditMerchantComponent, canActivate: [authGuard, permissionGuard('MERCHANT_EDIT')] },
  { path: 'view-merchant/:merchantId', component: ViewMerchantComponent, canActivate: [authGuard] },
  { path: '', redirectTo: '/login', pathMatch: 'full' },
];
