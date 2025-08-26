import fs from 'fs';
import { gzipSize } from 'gzip-size';

const data = fs.readFileSync('dist/plugin.esm.js', 'utf8');
gzipSize(data).then((size) => {
  // eslint-disable-next-line no-undef
  console.log(`Gzipped size of plugin.esm.js: ${(size / 1024).toFixed(2)} KB`);
});
