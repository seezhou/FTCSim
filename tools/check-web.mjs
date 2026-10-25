// Syntax-checks the web front ends: the 3D ES modules the way the browser loads them, plus the
// classic script of the 2D view.
//
// Node's --check with --input-type=module is the honest test: stripping the import lines (as an
// earlier version of this did) makes a file that references an unimported `THREE` look fine, which
// is exactly the bug that reached the page once. Bare specifiers like 'three' are mapped to a
// stub here; nothing is executed, so the stub only has to parse.
//
// usage: node tools/check-web.mjs
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'sim', 'src', 'main', 'resources', 'web');
const web = join(webRoot, '3d');
const files = readdirSync(web).filter(f => f.endsWith('.js'));
const classic = ['2d/app.js'];
const tmp = mkdtempSync(join(tmpdir(), 'ftcsim-webcheck-'));
let failed = 0;

for (const f of files) {
  const src = readFileSync(join(web, f), 'utf8');
  // Rewrite bare specifiers to a relative stub so the module graph resolves offline.
  const patched = src.replace(/from\s+(['"])(?![.\/])([^'"]+)\1/g, "from './__stub__.js'");
  const target = join(tmp, f);
  writeFileSync(target, patched);
  try {
    execFileSync(process.execPath, ['--check', target], { stdio: 'pipe' });
    console.log(`OK      ${f}`);
  } catch (e) {
    failed++;
    console.log(`BROKEN  ${f}`);
    console.log('  ' + (e.stderr?.toString().split('\n').slice(0, 4).join('\n  ') || e.message));
  }
}

for (const rel of classic) {
  try {
    execFileSync(process.execPath, ['--check', join(webRoot, rel)], { stdio: 'pipe' });
    console.log(`OK      ${rel} (classic)`);
  } catch (e) {
    failed++;
    console.log(`BROKEN  ${rel} (classic)`);
    console.log('  ' + (e.stderr?.toString().split('\n').slice(0, 4).join('\n  ') || e.message));
  }
}

rmSync(tmp, { recursive: true, force: true });
console.log(failed ? `\n${failed} file(s) failed` : `\nall ${files.length} modules and ${classic.length} classic script(s) parse`);
process.exitCode = failed ? 1 : 0;