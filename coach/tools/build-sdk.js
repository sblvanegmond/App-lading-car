#!/usr/bin/env node
/**
 * Bundle the official Anthropic SDK into one browser file,
 * coach/vendor/anthropic-sdk.js, so the coach app loads it from its own
 * origin (and from the service worker cache) instead of a CDN.
 *
 *   npm install            # once, for @anthropic-ai/sdk and esbuild
 *   npm run coach:sdk
 *
 * Packages are resolved from the current directory, so the Pages workflow
 * can install them outside the published folder and run this from there.
 * Like the icons, the bundle is generated and not committed.
 */

import { createRequire } from 'node:module';
import { mkdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '..', 'vendor', 'anthropic-sdk.js');
const require = createRequire(join(process.cwd(), 'noop.js'));

function expectedVersion() {
  const source = readFileSync(resolve(HERE, '..', 'js', 'claude.js'), 'utf8');
  return source.match(/SDK_VERSION = '([^']+)'/)?.[1];
}

let esbuild;
let sdkPackage;
try {
  esbuild = await import(pathToFileURL(require.resolve('esbuild')).href);
  // The package does not export its package.json; find it next to the entry point.
  const entry = require.resolve('@anthropic-ai/sdk');
  sdkPackage = join(entry.slice(0, entry.lastIndexOf('@anthropic-ai/sdk') + '@anthropic-ai/sdk'.length), 'package.json');
} catch {
  console.log('coach:sdk overgeslagen: draai eerst `npm install`. De app laadt de bibliotheek dan via een CDN.');
  process.exit(0);
}

const version = JSON.parse(readFileSync(sdkPackage, 'utf8')).version;
const wanted = expectedVersion();
if (wanted && version !== wanted) {
  console.error(`@anthropic-ai/sdk ${version} geïnstalleerd, maar coach/js/claude.js verwacht ${wanted}.`);
  process.exit(1);
}

mkdirSync(dirname(OUT), { recursive: true });
await esbuild.build({
  stdin: {
    contents: "export { default } from '@anthropic-ai/sdk';\nexport * from '@anthropic-ai/sdk';\n",
    resolveDir: dirname(sdkPackage),
    loader: 'js',
  },
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2022',
  minify: true,
  legalComments: 'eof',
  outfile: OUT,
  logLevel: 'warning',
});
console.log(`coach/vendor/anthropic-sdk.js (@anthropic-ai/sdk ${version}, ${Math.round(statSync(OUT).size / 1024)} kB)`);
