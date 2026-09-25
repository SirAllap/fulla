-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Tables.
--
-- Everything lives in the private `fulla` schema, which is never added to the
-- schemas PostgREST exposes. No table has an HTTP endpoint; the API is the set
-- of `public.fulla_*` functions defined in later migrations, and that list is
-- the whole attack surface.
--
-- Two rules shape every table here:
--
--   * Nothing is ever deleted. Rows are archived or tombstoned, and triggers
--     refuse DELETE outright. A deletion made on one phone has to reach the
--     others, and a row that no longer exists cannot carry that news.
--
--   * References between household-owned rows are composite foreign keys on
--     (household_id, id), so a row can never point at another household's
--     category, account or member, whatever a client sends.

create schema if not exists fulla;

-- ── helpers used by constraints ──────────────────────────────────────────────

/*
 * Names are compared with combining marks removed, lower-cased and trimmed, so
 * that a name typed on a keyboard that composes accents and one that does not
 * are the same name. The same function exists in core (`normalizeName`) and
 * the two are held together by testdata/vectors/normalize_name.json.
 *
 * The character class lists the Unicode combining-mark blocks explicitly
 * because PostgreSQL regular expressions have no \p{M}.
 */
create function fulla.normalize_name(p text) returns text
language sql immutable parallel safe
as $$
  select lower(btrim(regexp_replace(
    normalize(coalesce(p, ''), nfd),
    '[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]', '', 'g'
  )));
$$;

-- ── households and people ────────────────────────────────────────────────────

create table fulla.households (
  id               uuid primary key default gen_random_uuid(),
  name             text not null check (char_length(name) between 1 and 60),
  currency         char(3) not null check (currency ~ '^[A-Z]{3}$'),
  locale           text not null check (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  period_start_day smallint not null default 1 check (period_start_day between 1 and 28),
  income_shift_day smallint check (income_shift_day between 1 and 31),
  week_start       smallint not null default 1 check (week_start between 1 and 7),
  member_limit     smallint not null default 20 check (member_limit between 1 and 50),
  config_version   integer not null default 1,
  created_at       timestamptz not null default now(),
  created_by       uuid references auth.users (id),
  -- A period that starts mid-month and income that shifts to the next period
  -- are two answers to the same question; allowing both would count a salary
  -- twice over the boundary.
  constraint period_rules_exclusive check (period_start_day = 1 or income_shift_day is null)
);

create table fulla.members (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references fulla.households (id),
  user_id       uuid references auth.users (id),
  display_name  text not null check (char_length(display_name) between 1 and 40),
  initials      text not null check (char_length(initials) between 1 and 2),
  color_index   smallint not null default 0 check (color_index between 0 and 9),
  role          text not null default 'member' check (role in ('owner', 'admin', 'member')),
  status        text not null default 'active' check (status in ('active', 'archived', 'removed')),
  created_at    timestamptz not null default now(),
  joined_at     timestamptz,
  unique (household_id, id),
  -- A member without an account cannot sign in, so a role would mean nothing.
  constraint virtual_members_are_members check (user_id is not null or role = 'member')
);
create unique index members_one_account_per_household on fulla.members (household_id, user_id) where user_id is not null;
create unique index members_one_active_owner on fulla.members (household_id) where role = 'owner' and status = 'active';
create index members_user on fulla.members (user_id) where user_id is not null;

create table fulla.invites (
  code                  text primary key check (code ~ '^[2-9A-HJKMNP-TV-Z]{10}$'),
  household_id          uuid not null references fulla.households (id),
  role                  text not null check (role in ('admin', 'member')),
  claim_member_id       uuid,
  created_by_member_id  uuid not null,
  created_at            timestamptz not null default now(),
  expires_at            timestamptz not null,
  used_by_user_id       uuid references auth.users (id),
  used_at               timestamptz,
  revoked_at            timestamptz,
  foreign key (household_id, claim_member_id) references fulla.members (household_id, id),
  foreign key (household_id, created_by_member_id) references fulla.members (household_id, id)
);
create index invites_household on fulla.invites (household_id);

-- Failed attempts to accept an invite, for throttling guesses. This is the one
-- table rows may be removed from: it is bookkeeping, not household data.
create table fulla.invite_attempts (
  user_id  uuid not null,
  at       timestamptz not null default now()
);
create index invite_attempts_user_at on fulla.invite_attempts (user_id, at);

-- ── structure ────────────────────────────────────────────────────────────────

create table fulla.accounts (
  id                     uuid primary key,
  household_id           uuid not null references fulla.households (id),
  name                   text not null check (char_length(name) between 1 and 40),
  type                   text not null check (type in ('cash', 'checking', 'savings', 'credit_card', 'other')),
  opening_balance_minor  bigint not null default 0,
  sort                   integer not null default 0,
  archived               boolean not null default false,
  unique (household_id, id)
);

create table fulla.categories (
  id            uuid primary key,
  household_id  uuid not null references fulla.households (id),
  parent_id     uuid,
  name          text not null check (char_length(name) between 1 and 40),
  applies_to    text not null check (applies_to in ('expense', 'income', 'both')),
  icon          text not null default 'label' check (icon ~ '^[a-z0-9_]{1,40}$'),
  color_index   smallint not null default 0 check (color_index between 0 and 11),
  sort          integer not null default 0,
  archived      boolean not null default false,
  unique (household_id, id),
  foreign key (household_id, parent_id) references fulla.categories (household_id, id),
  check (parent_id is distinct from id)
);

create table fulla.custom_fields (
  id             uuid primary key,
  household_id   uuid not null references fulla.households (id),
  key            text not null check (key ~ '^[a-z][a-z0-9_]{0,31}$'),
  labels         jsonb not null check (jsonb_typeof(labels) = 'object'),
  type           text not null check (type in ('text', 'number', 'money', 'date', 'select', 'multiselect', 'boolean', 'member')),
  applies_to     text[] not null check (
                   cardinality(applies_to) > 0
                   and applies_to <@ array['expense', 'income', 'refund', 'transfer', 'settlement']),
  required       boolean not null default false,
  options        text[] not null default '{}',
  default_value  text,
  show_in_list   boolean not null default false,
  sort           integer not null default 0,
  archived       boolean not null default false,
  unique (household_id, id),
  unique (household_id, key)
);

create table fulla.budgets (
  id            uuid primary key,
  household_id  uuid not null references fulla.households (id),
  category_id   uuid not null,
  period        text check (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  amount_minor  bigint not null check (amount_minor >= 0),
  foreign key (household_id, category_id) references fulla.categories (household_id, id)
);
-- One limit per category and period; a null period is the default for every period.
create unique index budgets_one_per_period on fulla.budgets (household_id, category_id, (coalesce(period, '*')));

create table fulla.recurring_rules (
  id            uuid primary key,
  household_id  uuid not null references fulla.households (id),
  name          text not null check (char_length(name) between 1 and 60),
  template      jsonb not null check (jsonb_typeof(template) = 'object'),
  schedule      jsonb not null check (jsonb_typeof(schedule) = 'object'),
  start_date    date not null,
  end_date      date,
  auto_create   boolean not null default false,
  active        boolean not null default true,
  unique (household_id, id),
  check (end_date is null or end_date >= start_date)
);

create table fulla.categorization_rules (
  id            uuid primary key,
  household_id  uuid not null references fulla.households (id),
  pattern       text not null check (char_length(pattern) between 1 and 100),
  action        jsonb not null check (jsonb_typeof(action) = 'object'),
  sort          integer not null default 0,
  active        boolean not null default true
);

create table fulla.import_profiles (
  id            uuid primary key,
  household_id  uuid not null references fulla.households (id),
  name          text not null check (char_length(name) between 1 and 60),
  format        text not null default 'csv' check (format in ('csv')),
  mapping       jsonb not null check (jsonb_typeof(mapping) = 'object'),
  account_id    uuid,
  foreign key (household_id, account_id) references fulla.accounts (household_id, id)
);

-- ── transactions ─────────────────────────────────────────────────────────────

/*
 * The pull cursor. A sequence, not a clock: a phone's clock says when a person
 * made an edit, which can be hours before the server hears of it, and paging on
 * it would step over rows written offline. See the trigger below for why the
 * sequence alone is not enough either.
 */
create sequence fulla.server_seq as bigint minvalue 1 start 1;

create table fulla.transactions (
  id                     uuid primary key,
  household_id           uuid not null references fulla.households (id),
  kind                   text not null check (kind in ('expense', 'income', 'refund', 'transfer', 'settlement')),
  date                   date not null,
  amount_minor           bigint not null check (amount_minor > 0),
  category_id            uuid,
  account_id             uuid,
  to_account_id          uuid,
  paid_by_member_id      uuid,
  to_member_id           uuid,
  split                  jsonb,
  recurrence             text not null default 'variable' check (recurrence in ('fixed', 'variable')),
  note                   text not null default '' check (char_length(note) <= 500),
  tags                   text[] not null default '{}',
  extras                 jsonb not null default '{}' check (jsonb_typeof(extras) = 'object'),
  status                 text not null default 'active' check (status in ('active', 'deleted')),
  recurring_rule_id      uuid,
  occurrence_date        date,
  import_fingerprint     text,
  original_amount_minor  bigint check (original_amount_minor > 0),
  original_currency      char(3) check (original_currency ~ '^[A-Z]{3}$'),
  created_at             timestamptz not null,
  client_updated_at      timestamptz not null,
  server_seq             bigint not null,
  created_by_member_id   uuid,
  updated_by_user_id     uuid references auth.users (id),
  foreign key (household_id, category_id) references fulla.categories (household_id, id),
  foreign key (household_id, account_id) references fulla.accounts (household_id, id),
  foreign key (household_id, to_account_id) references fulla.accounts (household_id, id),
  foreign key (household_id, paid_by_member_id) references fulla.members (household_id, id),
  foreign key (household_id, to_member_id) references fulla.members (household_id, id),
  foreign key (household_id, created_by_member_id) references fulla.members (household_id, id),
  foreign key (household_id, recurring_rule_id) references fulla.recurring_rules (household_id, id),
  check (to_account_id is distinct from account_id or to_account_id is null),
  check (to_member_id is distinct from paid_by_member_id or to_member_id is null),
  check ((recurring_rule_id is null) = (occurrence_date is null)),
  check ((original_amount_minor is null) = (original_currency is null))
);
create index transactions_cursor on fulla.transactions (household_id, server_seq);
create index transactions_date on fulla.transactions (household_id, date desc);
create index transactions_category on fulla.transactions (household_id, category_id);
create unique index transactions_import_once on fulla.transactions (household_id, import_fingerprint)
  where import_fingerprint is not null;
create unique index transactions_occurrence_once on fulla.transactions (household_id, recurring_rule_id, occurrence_date)
  where recurring_rule_id is not null;

-- ── triggers ─────────────────────────────────────────────────────────────────

/*
 * Stamps every write with the next cursor value, under a per-household lock.
 *
 * A sequence hands out numbers when a row is written, not when its
 * transaction commits. Two concurrent writers in one household can take 10 and
 * 11 and commit in the order 11, 10. A pull that runs between those commits
 * sees 11, moves its cursor to 11, and never asks for 10 again: the row is
 * lost to that phone for good.
 *
 * Taking a transaction-scoped advisory lock before nextval() makes writes in a
 * household take their numbers in commit order: the second writer waits for
 * the first to commit before it can draw a number. Households do not block
 * each other. supabase/tests/concurrency.js proves this with two live
 * sessions.
 */
create function fulla.assign_server_seq() returns trigger
language plpgsql
as $$
begin
  perform pg_advisory_xact_lock(hashtextextended('fulla.server_seq:' || new.household_id::text, 0));
  new.server_seq := nextval('fulla.server_seq');
  return new;
end;
$$;

create trigger transactions_assign_seq
  before insert or update on fulla.transactions
  for each row execute function fulla.assign_server_seq();

create function fulla.forbid_delete() returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = 'PT409',
    message = format('Rows in %s are never deleted: archive or tombstone them instead.', tg_table_name),
    detail  = 'delete_forbidden';
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array['households', 'members', 'invites', 'accounts', 'categories', 'custom_fields',
                           'budgets', 'recurring_rules', 'categorization_rules', 'import_profiles', 'transactions']
  loop
    execute format('create trigger %I before delete on fulla.%I for each row execute function fulla.forbid_delete()',
                   t || '_forbid_delete', t);
  end loop;
end
$$;

/*
 * Any change to a household's structure bumps its config_version. Phones send
 * the version they hold on every pull and get the whole config back when it
 * differs, so a new field or category reaches them before rows that use it.
 */
create function fulla.bump_config_version() returns trigger
language plpgsql
as $$
begin
  update fulla.households set config_version = config_version + 1 where id = new.household_id;
  return null;
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array['members', 'accounts', 'categories', 'custom_fields', 'budgets',
                           'recurring_rules', 'categorization_rules', 'import_profiles']
  loop
    execute format('create trigger %I after insert or update on fulla.%I for each row execute function fulla.bump_config_version()',
                   t || '_bump_config', t);
  end loop;
end
$$;

create function fulla.bump_household_config_version() returns trigger
language plpgsql
as $$
begin
  if (new.name, new.currency, new.locale, new.period_start_day, new.income_shift_day, new.week_start, new.member_limit)
     is distinct from
     (old.name, old.currency, old.locale, old.period_start_day, old.income_shift_day, old.week_start, old.member_limit)
  then
    new.config_version := old.config_version + 1;
  end if;
  return new;
end;
$$;

create trigger households_bump_config
  before update on fulla.households
  for each row execute function fulla.bump_household_config_version();
