#!/usr/bin/env node
// FTCSim — documentation freshness check.
//
// Each doc under docs/ can carry a `refs:` list in its front matter, naming the
// source locations the prose depends on:
//
//   ---
//   summary: 一句话
//   refs:
//     - path: sim/src/main/java/ftcsim/physics/MotorState.java
//       anchor: "public double controlStep(double dt)"
//       hash: 3f9a1c2b8e40
//   ---
//
// The `anchor` (instead of line numbers) locates the code: the checker re-locates
// the anchor and hashes DEFAULT_SPAN lines from wherever it is now. So "the code
// moved but did not change" is not reported as stale.
//
// We deliberately store only path / anchor / hash — no line numbers and no "since"
// commit. Those two fields drift on every edit and produced nothing but noisy diffs
// (every -Update rewrote them even when the referenced code was unchanged).
//
// Usage:
//   node tools/docs-check.mjs            # check and report
//   node tools/docs-check.mjs --update   # recompute hashes and rewrite the docs
//
// Exit code is 0 even when there is drift — this is a report, not a gate.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const update = process.argv.includes('--update') || process.argv.includes('-Update');

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..');

if (!fs.existsSync(path.join(repoRoot, 'CLAUDE.md'))) {
  console.error("Cannot locate the repository root (expected CLAUDE.md next to " + repoRoot + ").");
  process.exit(1);
}

// Fixed hash window. Anchors point at a function/method start; 40 lines covers the
// overwhelming majority of referenced bodies. Line numbers are intentionally NOT
// stored — they drift and only created noisy diffs.
const DEFAULT_SPAN = 40;

function unquote(s) {
  if (s == null) return '';
  let t = s.trim();
  if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t = t.slice(1, -1);
  return t;
}

function shortHash(lines) {
  const text = lines.join('\n');
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 12);
}

function readDoc(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  const d = { path: filePath, hasFm: false, summary: '', refs: [], preamble: [], hasLegacy: false, body: raw };
  if (!raw.startsWith('---')) return d;
  const lines = raw.split(/\r?\n/);
  let end = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') { end = i; break; }
  }
  if (end < 0) return d;
  d.hasFm = true;
  const fmLines = lines.slice(1, end);
  d.body = lines.slice(end + 1).join('\n');

  let cur = null;
  let sawFirstPath = false;
  for (const l of fmLines) {
    let m;
    if (!sawFirstPath && (m = l.match(/^\s*summary:\s*(.*)$/))) { d.summary = unquote(m[1]); d.preamble.push(l); continue; }
    if (!sawFirstPath && /^\s*refs:\s*$/.test(l)) { d.preamble.push(l); continue; }
    if (m = l.match(/^\s*-\s*path:\s*(.*)$/)) {
      sawFirstPath = true;
      if (cur) d.refs.push(cur);
      cur = { path: unquote(m[1]), anchor: '', hash: '' };
      continue;
    }
    if (sawFirstPath) {
      if (cur && (m = l.match(/^\s+anchor:\s*(.*)$/))) { cur.anchor = unquote(m[1]); continue; }
      if (cur && /^\s+lines:\s*(.*)$/.test(l)) { d.hasLegacy = true; continue; }   // legacy, dropped
      if (cur && (m = l.match(/^\s+hash:\s*(.*)$/))) { cur.hash = unquote(m[1]); continue; }
      if (cur && /^\s+since:\s*(.*)$/.test(l)) { d.hasLegacy = true; continue; }   // legacy, dropped
    } else {
      d.preamble.push(l);   // any other top-level front-matter field is preserved
    }
  }
  if (cur) d.refs.push(cur);
  return d;
}

function writeDoc(d) {
  let out = '---\n';
  out += d.preamble.join('\n') + '\n';
  for (const r of d.refs) {
    out += '  - path: ' + r.path + '\n';
    out += '    anchor: "' + r.anchor + '"\n';
    out += '    hash: ' + r.hash + '\n';
  }
  out += '---\n' + d.body;
  fs.writeFileSync(d.path, out, 'utf8');
}

function walk(dir, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.endsWith('.md')) acc.push(p);
  }
  return acc;
}

const docs = walk(path.join(repoRoot, 'docs'), []).sort();

const stats = { ok: 0, changed: 0, missing: 0, noAnchor: 0, docsWithRefs: 0 };
const changedDocs = [];

for (const docPath of docs) {
  const d = readDoc(docPath);
  if (!d.hasFm || d.refs.length === 0) continue;
  stats.docsWithRefs++;
  const relDoc = path.relative(repoRoot, docPath).replace(/\\/g, '/');

  let docChanged = false;
  let dirty = false;   // a hash actually changed (or legacy cleanup needed)
  const report = [];

  for (const r of d.refs) {
    const srcPath = path.join(repoRoot, r.path);
    if (!fs.existsSync(srcPath)) {
      report.push('  MISSING-FILE  ' + r.path);
      stats.missing++;
      docChanged = true;
      continue;
    }
    const srcLines = fs.readFileSync(srcPath, 'utf8').split(/\r?\n/);
    if (!r.anchor) {
      report.push('  NO-ANCHOR     ' + r.path);
      stats.noAnchor++;
      continue;
    }
    let idx = -1;
    for (let i = 0; i < srcLines.length; i++) {
      if (srcLines[i].includes(r.anchor)) { idx = i; break; }
    }
    if (idx < 0) {
      report.push('  ANCHOR-GONE   ' + r.path + ' :: ' + r.anchor);
      stats.missing++;
      docChanged = true;
      continue;
    }
    const take = Math.min(DEFAULT_SPAN, srcLines.length - idx);
    const slice = srcLines.slice(idx, idx + take);
    const curHash = shortHash(slice);

    if (update) {
      if (r.hash !== curHash) { r.hash = curHash; dirty = true; }
      continue;
    }

    if (r.hash === curHash) {
      stats.ok++;
    } else {
      stats.changed++;
      docChanged = true;
      dirty = true;
      const rel = r.path;
      report.push('  CHANGED       ' + rel + ' :: ' + r.anchor);
      report.push('                was hash ' + r.hash);
      report.push('                now hash ' + curHash);
      report.push('                git diff HEAD -- ' + rel);
    }
  }

  if (update) {
    // Rewrite only when a hash actually moved, or the doc still carries the
    // legacy lines/since fields that we want gone. This keeps `git diff` silent
    // for every doc whose referenced code is unchanged.
    if (dirty || d.hasLegacy) {
      writeDoc(d);
      console.log('UPDATED  ' + relDoc + '  (' + d.refs.length + ' refs)');
    }
  } else if (docChanged) {
    changedDocs.push(relDoc);
    console.log('STALE    ' + relDoc);
    report.forEach((l) => console.log(l));
  }
}

console.log('');
if (update) {
  console.log('Done: refreshed refs in ' + stats.docsWithRefs + ' doc(s).');
} else {
  console.log('Docs with refs: ' + stats.docsWithRefs);
  console.log('  unchanged : ' + stats.ok);
  console.log('  changed   : ' + stats.changed);
  console.log('  anchor gone / file missing : ' + stats.missing);
  console.log('  refs without an anchor     : ' + stats.noAnchor);
  if (stats.changed > 0 || stats.missing > 0) {
    console.log('');
    console.log('Review the docs above; then run with --update once the prose is correct again.');
  } else {
    console.log('');
    console.log('All referenced code matches what the docs were written against.');
  }
}
