import terser from '@rollup/plugin-terser';

// eslint-disable-next-line no-undef
const isDev = process.env.NODE_ENV === 'development';

export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/plugin.js',
      format: 'iife',
      name: 'capacitorNativeAudio',
      globals: {
        '@capacitor/core': 'capacitorExports',
      },
      sourcemap: isDev,
      inlineDynamicImports: true,
      plugins: [!isDev && terser()].filter(Boolean),
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      sourcemap: isDev,
      inlineDynamicImports: true,
      plugins: [!isDev && terser()].filter(Boolean),
    },
    {
      file: 'dist/plugin.esm.js',
      format: 'esm',
      sourcemap: isDev,
      inlineDynamicImports: true,
      plugins: [!isDev && terser()].filter(Boolean),
    },
  ],
  external: ['@capacitor/core'],
};
