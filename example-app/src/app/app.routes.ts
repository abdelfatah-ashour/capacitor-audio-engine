import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'home',
    loadComponent: () => import('./home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'cdn-audio-player',
    loadComponent: () => import('./cdn-audio-player/cdn-audio-player.component').then((m) => m.CdnAudioPlayerComponent),
  },
  {
    path: 'local-audio-player',
    loadComponent: () => import('./local-audio-player/local-audio-player.component').then((m) => m.LocalAudioPlayerComponent),
  },
  {
    path: 'multi-audio-resume',
    loadComponent: () => import('./multi-audio-resume/multi-audio-resume.component').then((m) => m.MultiAudioResumeComponent),
  },
  {
    path: '',
    redirectTo: 'home',
    pathMatch: 'full',
  },
];
