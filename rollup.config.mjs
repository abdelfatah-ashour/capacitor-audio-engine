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
      sourcemap: process.env.NODE_ENV === 'development',
      inlineDynamicImports: true,
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      sourcemap: process.env.NODE_ENV === 'development',
      inlineDynamicImports: true,
    },
  ],
  external: ['@capacitor/core'],
};
