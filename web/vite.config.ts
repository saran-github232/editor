import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import wasm from 'vite-plugin-wasm';
export default defineConfig({plugins:[react(),wasm()],build:{target:'esnext'},optimizeDeps:{exclude:['opencut-wasm']},server:{host:'127.0.0.1'}});
