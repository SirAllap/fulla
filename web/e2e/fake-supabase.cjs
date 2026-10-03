// SPDX-License-Identifier: GPL-3.0-or-later
//
// A Supabase for the browser tests: the project's real migrations in a throwaway PostgreSQL (supabase/tests/harness.js),
// behind the three calls the app makes (sign up, sign in, rpc). The page is told it is talking to
// https://<20 letters>.supabase.co; Playwright answers those requests with this.
'use strict';
const crypto = require('node:crypto');
const h = require('../../supabase/tests/harness.js');

function start() {
  h.start();
  h.buildTemplate();
  const db = h.freshDb();
  const users = new Map(); // email -> { id, password }

  const json = (status, body) => ({ status, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(body) });
  const session = (u) => ({ access_token: 't:' + u.id, refresh_token: 'r:' + u.id, expires_in: 3600, expires_at: Math.floor(Date.now() / 1000) + 3600, user: { id: u.id, email: u.email } });

  function rpc(userId, fn, args) {
    if (!/^[a-z_]+$/.test(fn)) return json(404, { message: 'no such function' });
    const meta = db.admin(`select coalesce(array_to_string(proargnames, ','), ''), coalesce(array_to_string(proargtypes::regtype[], ','), ''), proretset from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and proname = ${h.literal(fn)}`).out;
    if (!meta) return json(404, { message: 'no such function' });
    const [names, types, set] = meta.split('|');
    const typeOf = Object.fromEntries(names.split(',').filter(Boolean).map((n, i) => [n, types.split(',')[i]]));
    const call = Object.keys(args).map((k) => {
      const t = typeOf[k];
      const v = h.literal(JSON.stringify(args[k]));
      return t === 'jsonb' ? `${k} := ${v}::jsonb` : `${k} := (${v}::jsonb #>> '{}')::${t}`;
    }).join(', ');
    const sql = userId
      ? db.wrapAs(userId, `select coalesce(jsonb_agg(to_jsonb(r)), '[]') from (select ${set === 't' ? '* from ' : ''}public.${fn}(${call}) as r) x;`)
      : `begin; set local role anon; select to_jsonb(public.${fn}(${call})); commit;`;
    const out = h.psql(db.name, sql, { expectFailure: true });
    if (!out.ok) return json(out.err.sqlstate === '42501' ? 403 : 400, { code: out.err.sqlstate, message: out.err.message, details: out.err.detail });
    const lines = out.out.split('\n').filter((l) => l && !/^(BEGIN|COMMIT|SET)/.test(l));
    const last = lines[lines.length - 1] || 'null';
    const value = JSON.parse(last);
    return json(200, set === 't' ? value : (Array.isArray(value) && value.length === 1 ? value[0] : value));
  }

  async function handle(route) {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } });
    const body = req.postDataJSON() || {};
    if (url.pathname === '/auth/v1/signup') {
      if (users.has(body.email)) return route.fulfill(json(422, { error_code: 'user_already_exists', msg: 'User already registered' }));
      const u = { id: crypto.randomUUID(), email: body.email, password: body.password };
      users.set(u.email, u);
      db.admin(`insert into auth.users (id, email) values (${h.literal(u.id)}, ${h.literal(u.email)});`);
      return route.fulfill(json(200, session(u)));
    }
    if (url.pathname === '/auth/v1/token') {
      if (url.searchParams.get('grant_type') === 'refresh_token') {
        const u = [...users.values()].find((x) => 'r:' + x.id === body.refresh_token);
        return route.fulfill(u ? json(200, session(u)) : json(400, { error_code: 'invalid_grant' }));
      }
      const u = users.get(body.email);
      return route.fulfill(u && u.password === body.password ? json(200, session(u)) : json(400, { error_code: 'invalid_credentials', msg: 'Invalid login credentials' }));
    }
    if (url.pathname.startsWith('/rest/v1/rpc/')) {
      const bearer = (req.headers()['authorization'] || '').replace('Bearer ', '');
      const res = rpc(bearer.startsWith('t:') ? bearer.slice(2) : null, url.pathname.split('/').pop(), body);
      if (process.env.FAKE_LOG) console.log('RPC', url.pathname.split('/').pop(), res.status, String(res.body).slice(0, 300));
      return route.fulfill(res);
    }
    return route.fulfill(json(404, { message: 'not found' }));
  }

  return { db, handle, stop: () => { h.dropDb(db); h.stop(); } };
}

module.exports = { start, URL: 'https://abcdefghijklmnopqrst.supabase.co', KEY: 'anon-key-for-tests' };
