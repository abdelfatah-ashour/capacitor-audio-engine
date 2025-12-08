import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'features-demo',
    pathMatch: 'full',
  },
  {
    path: 'features-demo',
    loadComponent: () =>
      import('./features-demo/features-demo.component').then(m => m.FeaturesDemoComponent),
  },
  {
    path: 'playback',
    loadComponent: () =>
      import('./features/playback/playback.component').then(m => m.PlaybackComponent),
  },
];
