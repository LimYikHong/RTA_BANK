import { Component, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TopBarComponent } from '../top-bar/top-bar.component';

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [CommonModule, RouterModule, TopBarComponent],
  templateUrl: './layout.component.html',
  styleUrl: './layout.component.scss',
  encapsulation: ViewEncapsulation.None
})
export class LayoutComponent {
  drawerOpen = true;

  toggleDrawer() {
    this.drawerOpen = !this.drawerOpen;
  }
}
