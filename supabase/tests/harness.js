// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';

/**
 * Boots a throwaway PostgreSQL, applies shim.sql and then the real migration
 * files, byte for byte, into a template database, and hands each test its own
 * copy of that template.
 *
 * It drives `psql` through child processes instead of a driver so the suite
 * has no npm dependencies: `npm run test:db` needs a PostgreSQL *installed*
 * (initdb, pg_ctl, psql), never a PostgreSQL *running*, and never a real
 * Supabase project.
 */

const { execFileSync, spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Debian and Ubuntu (GitHub's runners included) keep server binaries under a
// per-version directory, off PATH. Use the newest one; fall back to PATH.
const BIN = (() => {
  const root = '/usr/lib/postgresql';
  if (!fs.existsSync(root)) return null;
  const versions = fs.readdirSync(root)
    .filter((v) => fs.existsSync(path.join(root, v, 'bin', 'initdb')))
    .sort((a, b) => Number(b) - Number(a));
  return versions.length ? path.join(root, versions[0], 'bin') : null;
})();
const bin = (name) => (BIN ? path.join(BIN, name) : name);

const SOCK = path.join(os.tmpdir(), 'fulla-pg');
const DATA = path.join(SOCK, 'data');
const PORT = '55433';
const MIGRATIONS = path.join(__dirname, '..', 'migrations');
const TEMPLATE = 'fulla_template';
// initdb refuses to run as root, which is how CI containers run.
const RUN_AS = process.getuid && process.getuid() === 0 ? 'fulla_pg' : null;

let startedHere = false;

function running() {
  return spawnSync(bin('pg_isready'), ['-h', SOCK, '-p', PORT], { encoding: 'utf8' }).status === 0;
}

function asServerUser(cmd, args) {
  const exe = RUN_AS ? 'runuser' : cmd;
  const full = RUN_AS ? ['-u', RUN_AS, '--', cmd, ...args] : args;
  const r = spawnSync(exe, full, { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`${path.basename(cmd)} failed:\n${r.stdout}\n${r.stderr}`);
}

function start() {
  if (running()) return;
  if (RUN_AS) spawnSync('useradd', ['-m', '-s', '/bin/bash', RUN_AS], { encoding: 'utf8' });
  fs.rmSync(SOCK, { recursive: true, force: true });
  fs.mkdirSync(DATA, { recursive: true });
  if (RUN_AS) execFileSync('chown', ['-R', `${RUN_AS}:${RUN_AS}`, SOCK]);
  asServerUser(bin('initdb'), ['-D', DATA, '-U', 'postgres', '--auth=trust', '-E', 'UTF8', '--locale=C.UTF-8']);
  asServerUser(bin('pg_ctl'), [
    '-D', DATA,
    '-o', `-p ${PORT} -k ${SOCK} -c listen_addresses='' -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=200`,
    '-l', path.join(DATA, 'server.log'),
    '-w', 'start',
  ]);
  startedHere = true;
}

function stop() {
  if (!startedHere) return;
  try {
    asServerUser(bin('pg_ctl'), ['-D', DATA, '-m', 'immediate', '-w', 'stop']);
  } catch {
    // Best effort: the directory is temporary anyway.
  }
}

const PSQL_ARGS = (db) => [
  '-h', SOCK, '-p', PORT, '-U', 'postgres', '-d', db,
  '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', '-X', '-tA', '-q',
];

/** Parses psql's stderr into {sqlstate, message, detail}. */
function parseError(stderr) {
  const m = /ERROR:\s+([0-9A-Z]{5}):\s+(.*)/.exec(stderr);
  const d = /DETAIL:\s+(.*)/.exec(stderr);
  return {
    sqlstate: m ? m[1] : null,
    message: m ? m[2].trim() : stderr.trim(),
    detail: d ? d[1].trim() : null,
  };
}

function psql(db, sql, { expectFailure = false } = {}) {
  const r = spawnSync(bin('psql'), [...PSQL_ARGS(db), '-f', '-'], { encoding: 'utf8', input: sql });
  const ok = r.status === 0;
  if (!ok && !expectFailure) {
    throw new Error(`SQL failed:\n${sql.slice(0, 2000)}\n──\n${(r.stderr || '').trim()}`);
  }
  return { ok, out: (r.stdout || '').trim(), err: ok ? null : parseError(r.stderr || '') };
}

/** Same as psql() but asynchronous, for tests that need two sessions at once. */
function psqlAsync(db, sql) {
  return new Promise((resolve) => {
    const child = spawn(bin('psql'), [...PSQL_ARGS(db), '-f', '-']);
    let out = '';
    let err = '';
    child.stdout.on('data', (b) => { out += b; });
    child.stderr.on('data', (b) => { err += b; });
    child.on('close', (code) => resolve({ ok: code === 0, out: out.trim(), err: code === 0 ? null : parseError(err) }));
    child.stdin.end(sql);
  });
}

function runFile(db, file) {
  const r = spawnSync(bin('psql'), [...PSQL_ARGS(db), '-f', file], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`${path.basename(file)} failed:\n${(r.stderr || '').trim()}`);
}

function migrations() {
  return fs.readdirSync(MIGRATIONS).filter((f) => /^\d{4}_.+\.sql$/.test(f)).sort();
}

/** The shim, then every migration in filename order, into a template database. */
function buildTemplate({ files = migrations().map((f) => path.join(MIGRATIONS, f)) } = {}) {
  psql('postgres', `update pg_database set datistemplate = false where datname = '${TEMPLATE}';`);
  psql('postgres', `drop database if exists ${TEMPLATE};`);
  psql('postgres', `create database ${TEMPLATE};`);
  runFile(TEMPLATE, path.join(__dirname, 'shim.sql'));
  for (const f of files) runFile(TEMPLATE, f);
  psql('postgres', `update pg_database set datistemplate = true where datname = '${TEMPLATE}';`);
}

let counter = 0;

/** A fresh database with the schema applied, isolated from every other test. */
function freshDb() {
  const name = `fulla_t${process.pid}_${counter++}`;
  psql('postgres', `drop database if exists ${name};`);
  psql('postgres', `create database ${name} template ${TEMPLATE};`);
  return new Db(name);
}

/** An empty database with only the shim, for testing the installers. */
function emptyDb() {
  const name = `fulla_e${process.pid}_${counter++}`;
  psql('postgres', `drop database if exists ${name};`);
  psql('postgres', `create database ${name};`);
  runFile(name, path.join(__dirname, 'shim.sql'));
  return new Db(name);
}

function dropDb(db) {
  psql('postgres', `drop database if exists ${db.name} with (force);`);
}

function literal(s) {
  return `'${String(s).replace(/'/g, "''")}'`;
}

class Db {
  constructor(name) {
    this.name = name;
  }

  /** Full privileges: setting up state and asserting about stored rows. */
  admin(sql, opts) {
    return psql(this.name, sql, opts);
  }

  /**
   * Runs SQL exactly as PostgREST would for a signed-in user: role
   * `authenticated` and the user's id in the JWT claims. Anything a phone can
   * do is tested through this, never through admin(), because the owner
   * bypasses row level security.
   */
  wrapAs(userId, sql) {
    const claims = JSON.stringify({ sub: userId, role: 'authenticated' });
    return `begin;
set local role authenticated;
do $h$ begin perform set_config('request.jwt.claims', ${literal(claims)}, true); end $h$;
${sql}
commit;`;
  }

  as(userId, sql, opts) {
    return psql(this.name, this.wrapAs(userId, sql), opts);
  }

  asAsync(userId, sql) {
    return psqlAsync(this.name, this.wrapAs(userId, sql));
  }

  anon(sql, opts) {
    return psql(this.name, `begin;\nset local role anon;\n${sql}\ncommit;`, opts);
  }
}

module.exports = {
  start, stop, buildTemplate, freshDb, emptyDb, dropDb, migrations, psql, literal,
  MIGRATIONS, SOCK, PORT, bin,
};
