import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { BatchListComponent } from './batch-list/batch-list.component';
import { ViewProfileComponent } from './view-profile/view-profile.component';
import { AddUserComponent } from './add-user/add-user.component';
import { AddMerchantComponent } from './add-merchant/add-merchant.component';
import { EditProfileComponent } from './edit-profile/edit-profile.component';
import { IncomingBatchComponent } from './incoming-batch/incoming-batch.component';
import { BatchDetailComponent } from './batch-detail/batch-detail.component';
import { UserManagementComponent } from './user-management/user-management.component';
import { MerchantMaintenanceComponent } from './merchant-maintenance/merchant-maintenance.component';
import { RecurringListComponent } from './recurring-list/recurring-list.component';
import { RecurringDetailComponent } from './recurring-detail/recurring-detail.component';
import { authGuard } from './services/auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'batch-list', component: BatchListComponent, canActivate: [authGuard] },
  { path: 'incoming-batch', component: IncomingBatchComponent, canActivate: [authGuard] },
  { path: 'batch-detail/:batchId', component: BatchDetailComponent, canActivate: [authGuard] },
  { path: 'profile', component: ViewProfileComponent, canActivate: [authGuard] },
  { path: 'edit-profile', component: EditProfileComponent, canActivate: [authGuard] },
  { path: 'users', component: UserManagementComponent, canActivate: [authGuard] },
  { path: 'merchant-maintenance', component: MerchantMaintenanceComponent, canActivate: [authGuard] },
  { path: 'recurring-list', component: RecurringListComponent, canActivate: [authGuard] },
  { path: 'recurring-detail/:recurringReference', component: RecurringDetailComponent, canActivate: [authGuard] },
  { path: 'add-user', component: AddUserComponent, canActivate: [authGuard] },
  { path: 'add-merchant', component: AddMerchantComponent, canActivate: [authGuard] },
  { path: '', redirectTo: '/login', pathMatch: 'full' },
];
