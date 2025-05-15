import { registerPlugin } from '@capacitor/core';

import type { CapacitorAudioEnginePlugin } from './definitions';

const CapacitorAudioEngine = registerPlugin<CapacitorAudioEnginePlugin>('CapacitorAudioEngine', {
  web: () => import('./web').then((m) => new m.CapacitorAudioEngineWeb()),
});

export * from './definitions';
export { CapacitorAudioEngine };
