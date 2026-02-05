import { Routes } from '@angular/router';
import { LoginComponent } from './login/login.component';
import { BatchListComponent } from './batch-list/batch-list.component';
import { ViewProfileComponent } from './view-profile/view-profile.component';
import { authGuard } from './services/auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'batch-list', component: BatchListComponent, canActivate: [authGuard] },
  { path: 'profile', component: ViewProfileComponent, canActivate: [authGuard] },
  { path: '', redirectTo: '/login', pathMatch: 'full' },
];
