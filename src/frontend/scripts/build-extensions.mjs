#!/usr/bin/env node
/**
 * Builds every extension's frontend (any `src/backend/extensions/<name>/src/frontend/` with a
 * `package.json`), so `npm run build:extensions` builds the whole platform in one command instead
 * of requiring a manual `npm run build` in each extension directory.
 */
import { existsSync, readdirSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const frontendDir = path.dirname(fileURLToPath(new URL('.', import.meta.url)));
const extensionsRoot = path.resolve(frontendDir, '../backend/extensions');

function findExtensionFrontends() {
    if (!existsSync(extensionsRoot)) return [];

    return readdirSync(extensionsRoot)
        .filter((name) => statSync(path.join(extensionsRoot, name)).isDirectory())
        .map((name) => ({ name, dir: path.join(extensionsRoot, name, 'src', 'frontend') }))
        .filter(({ dir }) => existsSync(path.join(dir, 'package.json')));
}

function buildExtension({ name, dir }) {
    console.log(`\n[build:extensions] Building ${name}...`);
    const result = spawnSync('npm', ['run', 'build'], { cwd: dir, stdio: 'inherit', shell: false });
    if (result.status !== 0) {
        throw new Error(`[build:extensions] Build failed for ${name} (exit code ${result.status})`);
    }
}

const extensions = findExtensionFrontends();
if (extensions.length === 0) {
    console.log('[build:extensions] No extension frontends found.');
    process.exit(0);
}

console.log(`[build:extensions] Found ${extensions.length} extension frontend(s): ${extensions.map((e) => e.name).join(', ')}`);

for (const extension of extensions) {
    buildExtension(extension);
}

console.log(`\n[build:extensions] Successfully built ${extensions.length} extension(s).`);
