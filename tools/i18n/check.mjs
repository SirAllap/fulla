#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Every language has every string English has, with the same placeholders.
// A missing string would show English in the middle of another language; a
// missing or extra placeholder crashes String.format at runtime.

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const RES = 'app/src/main/res';
const read = (dir) => {
  const xml = readFileSync(join(RES, dir, 'strings.xml'), 'utf8');
  const out = new Map();
  for (const m of xml.matchAll(/<string name="([^"]+)"( translatable="false")?>([\s\S]*?)<\/string>/g)) {
    if (!m[2]) out.set(m[1], m[3]);
  }
  return out;
};
const placeholders = (s) => [...new Set(s.match(/%\d\$[sd]/g) || [])].sort().join(',');

const en = read('values');
const problems = [];
for (const dir of readdirSync(RES).filter((d) => /^values-[a-z]{2}(-r[A-Z]{2})?$/.test(d))) {
  const other = read(dir);
  for (const [key, text] of en) {
    if (!other.has(key)) problems.push(`${dir}: missing ${key}`);
    else if (placeholders(other.get(key)) !== placeholders(text)) problems.push(`${dir}: placeholders differ in ${key}`);
  }
  for (const key of other.keys()) if (!en.has(key)) problems.push(`${dir}: ${key} is not in English`);
}
if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log(`i18n: ${en.size} strings, every language complete.`);
