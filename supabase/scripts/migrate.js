#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';

/**
 * Applies pending migrations to a database: DATABASE_URL=... npm run db:migrate
 *
 * Each file runs in its own transaction together with the row that records
 * it in fulla.schema_migrations, so a failure leaves nothing half-applied and
 * nothing recorded: fix it and run again.
 *
 * A migration that has been applied is never applied again, and never edited:
 * change things with a new file.
 *
 * Needs `psql` on PATH (any recent PostgreSQL client). Use the connection
 * string from your Supabase project (Settings → Database), never a key.
 */

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { migrations, BOOTSTRAP } = require('./bundle');

const MIGRATIONS = path.join(__dirname, '..', 'migrations');
const url = process.env.DATABASE_URL;
if (!url) {
  console.error('Set DATABASE_URL to your database connection string.');
  process.exit(2);
}

function psql(sql) {
  const r = spawnSync('psql', [url, '-v', 'ON_ERROR_STOP=1', '-X', '-q', '-tA', '-f', '-'], { encoding: 'utf8', input: sql });
  if (r.status !== 0) throw new Error((r.stderr || '').trim());
  return (r.stdout || '').trim();
}

psql(`begin;\n${BOOTSTRAP}\ncommit;`);
const applied = new Set(psql('select filename from fulla.schema_migrations;').split('\n').filter(Boolean));
const pending = migrations().filter((f) => !applied.has(f));
if (!pending.length) {
  console.log('Nothing to apply.');
  process.exit(0);
}
for (const file of pending) {
  const sql = fs.readFileSync(path.join(MIGRATIONS, file), 'utf8');
  try {
    psql(`begin;\n${sql}\ninsert into fulla.schema_migrations (filename) values ('${file}');\ncommit;`);
    console.log(`applied ${file}`);
  } catch (e) {
    console.error(`${file} failed; nothing from it was applied.\n${e.message}`);
    process.exit(1);
  }
}
