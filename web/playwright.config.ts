import {defineConfig} from '@playwright/test';
import path from 'node:path';
export default defineConfig({
  testDir:'./tests/e2e',outputDir:'./test-results/playwright',timeout:90000,workers:1,
  use:{baseURL:'http://127.0.0.1:4322',channel:'msedge',headless:true,viewport:{width:1440,height:1000},screenshot:'only-on-failure',trace:'retain-on-failure'},
  webServer:{command:'node server/start.mjs --dev',url:'http://127.0.0.1:4322',timeout:60000,reuseExistingServer:false,
    env:{PORT:'4322',CLIPPER_DATA_DIR:path.resolve('test-results/e2e-data'),CLIPPER_PREVIEW_DIR:path.resolve('test-results/e2e-previews')}},
});
