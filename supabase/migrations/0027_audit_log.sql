-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Nothing is overwritten without a trace.
--
-- Example (invented): Alice edits "copias" from 12,50 to 14,50. Before this, the
-- 12,50 was gone the moment she saved. Now the log holds one line: who, when,
-- which fields changed, and the whole row before and after.
--
-- `fulla.audit_log` is written by triggers on `transactions` and `accounts`, so
-- nothing that writes to them (the sync, an import, a recurring item, a future
-- screen) can forget to leave its line. It is append-only: no update, no delete,
-- not even for the owner. A transaction is never deleted either (a delete is a
-- change of `status`), so a delete is one more line in the log, and so is its
-- undo.
--
-- Actions: create, update, delete (active → deleted), restore (deleted → active).
-- A write that changes nothing but the sync's own bookkeeping (server_seq,
-- client_updated_at, updated_by_user_id) leaves no line.
--
-- What was there before this migration is filled in as far as it is known and
-- marked `source = 'backfill'`: a line for each row's creation and for each
-- deletion (with whoever made the last change), but no before and after, which
-- were never kept.
--
-- Reading goes through `public.fulla_audit_list`, for the household's members.

create table fulla.audit_log (
  id               bigint generated always as identity primary key,
  household_id     uuid not null references fulla.households (id),
  at               timestamptz not null default now(),
  entity           text not null check (entity in ('transaction', 'account')),
  entity_id        uuid not null,
  action           text not null check (action in ('create', 'update', 'delete', 'restore')),
  actor_user_id    uuid,
  actor_member_id  uuid,
  source           text not null default 'live' check (source in ('live', 'backfill')),
  changed          text[] not null default '{}',
  before           jsonb,
  after            jsonb
);

create index audit_log_household on fulla.audit_log (household_id, id desc);
create index audit_log_entity on fulla.audit_log (household_id, entity_id, id desc);

alter table fulla.audit_log enable row level security;

create policy audit_log_members_read on fulla.audit_log
  for select to authenticated using (fulla.is_active_member(household_id));

create function fulla.audit_forbid_change() returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = 'PT409',
    message = 'The audit log only grows: its lines are never changed or deleted.',
    detail  = 'audit_log_append_only';
end;
$$;

create trigger audit_log_no_update
  before update on fulla.audit_log
  for each row execute function fulla.audit_forbid_change();

-- A delete is refused by the same guard as everywhere else, which lets through only the erasing of a whole household.
create trigger audit_log_no_delete
  before delete on fulla.audit_log
  for each row execute function fulla.forbid_delete();

create trigger audit_log_no_truncate
  before truncate on fulla.audit_log
  for each statement execute function fulla.audit_forbid_change();

-- ── erasing a household erases its log too ──────────────────────────────────

create or replace function fulla.erase_household(p_household_id uuid) returns void
language plpgsql volatile
as $$
begin
  perform set_config('fulla.erasing_household', p_household_id::text, true);
  delete from fulla.transactions where household_id = p_household_id;
  delete from fulla.trips where household_id = p_household_id;
  delete from fulla.invites where household_id = p_household_id;
  delete from fulla.budgets where household_id = p_household_id;
  delete from fulla.recurring_rules where household_id = p_household_id;
  delete from fulla.categorization_rules where household_id = p_household_id;
  delete from fulla.import_profiles where household_id = p_household_id;
  delete from fulla.custom_fields where household_id = p_household_id;
  update fulla.categories set parent_id = null where household_id = p_household_id and parent_id is not null;
  delete from fulla.categories where household_id = p_household_id;
  delete from fulla.goals where household_id = p_household_id;
  delete from fulla.accounts where household_id = p_household_id;
  delete from fulla.audit_log where household_id = p_household_id;
  delete from fulla.members where household_id = p_household_id;
  delete from fulla.households where id = p_household_id;
  perform set_config('fulla.erasing_household', '', true);
end;
$$;

-- ── what a line keeps of a row ───────────────────────────────────────────────

create function fulla.audit_row(p jsonb) returns jsonb
language sql immutable
as $$
  select p - 'household_id' - 'server_seq' - 'updated_by_user_id';
$$;

-- ── the trigger ──────────────────────────────────────────────────────────────

create function fulla.audit_change() returns trigger
language plpgsql security definer
set search_path = ''
as $$
declare
  v_new jsonb := to_jsonb(new);
  v_old jsonb;
  v_actor uuid := coalesce(auth.uid(), nullif(v_new ->> 'updated_by_user_id', '')::uuid);
  v_entity text := case tg_table_name when 'transactions' then 'transaction' else 'account' end;
  v_member uuid;
  v_changed text[];
  v_action text;
begin
  select m.id into v_member
    from fulla.members m
   where m.household_id = new.household_id and m.user_id = v_actor
   limit 1;

  if tg_op = 'INSERT' then
    insert into fulla.audit_log (household_id, entity, entity_id, action, actor_user_id, actor_member_id, after)
    values (new.household_id, v_entity, new.id, 'create', v_actor,
            coalesce(v_member, nullif(v_new ->> 'created_by_member_id', '')::uuid), fulla.audit_row(v_new));
    return new;
  end if;

  v_old := to_jsonb(old);
  select coalesce(array_agg(t.k order by t.k), '{}') into v_changed
    from jsonb_object_keys(v_new) as t(k)
   where t.k not in ('household_id', 'server_seq', 'client_updated_at', 'updated_by_user_id')
     and v_new -> t.k is distinct from v_old -> t.k;
  if cardinality(v_changed) = 0 then
    return new;
  end if;

  v_action := case
    when v_old ->> 'status' = 'active' and v_new ->> 'status' = 'deleted' then 'delete'
    when v_old ->> 'status' = 'deleted' and v_new ->> 'status' = 'active' then 'restore'
    else 'update'
  end;
  insert into fulla.audit_log (household_id, entity, entity_id, action, actor_user_id, actor_member_id, changed, before, after)
  values (new.household_id, v_entity, new.id, v_action, v_actor, v_member, v_changed,
          fulla.audit_row(v_old), fulla.audit_row(v_new));
  return new;
end;
$$;

create trigger transactions_audit
  after insert or update on fulla.transactions
  for each row execute function fulla.audit_change();

create trigger accounts_audit
  after insert or update on fulla.accounts
  for each row execute function fulla.audit_change();

-- ── what was there before ────────────────────────────────────────────────────

insert into fulla.audit_log (household_id, at, entity, entity_id, action, actor_user_id, actor_member_id, source)
select e.household_id, e.at, e.entity, e.entity_id, e.action, e.actor_user_id, e.actor_member_id, 'backfill'
  from (
    select t.household_id, t.created_at as at, 'transaction'::text as entity, t.id as entity_id, 'create'::text as action,
           null::uuid as actor_user_id, t.created_by_member_id as actor_member_id
      from fulla.transactions t
    union all
    select t.household_id, t.client_updated_at, 'transaction', t.id, 'delete',
           t.updated_by_user_id,
           (select m.id from fulla.members m where m.household_id = t.household_id and m.user_id = t.updated_by_user_id limit 1)
      from fulla.transactions t
     where t.status = 'deleted'
  ) e
 order by e.at, e.entity_id;

-- ── reading it ───────────────────────────────────────────────────────────────

/*
 * The household's changes, newest first. `p_account_id` keeps the lines that touch an account (a
 * transaction that left or reached it, before or after the change, or the account itself); `p_entity_id`
 * keeps the lines of one transaction or account. `p_before` is the id of the oldest line already seen,
 * to read the next page.
 */
create function public.fulla_audit_list(
  p_household_id uuid,
  p_account_id uuid default null,
  p_entity_id uuid default null,
  p_before bigint default null,
  p_limit integer default 100
) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 100), 1), 500);
  v_rows jsonb;
  v_count integer;
begin
  perform fulla.require_member(p_household_id, 'member');
  select coalesce(jsonb_agg(to_jsonb(x) order by x.id desc), '[]'::jsonb), count(*) into v_rows, v_count
    from (
      select a.id, a.at, a.entity, a.entity_id, a.action, a.actor_member_id, a.source, a.changed, a.before, a.after
        from fulla.audit_log a
       where a.household_id = p_household_id
         and (p_entity_id is null or a.entity_id = p_entity_id)
         and (p_before is null or a.id < p_before)
         and (p_account_id is null
              or a.entity_id = p_account_id
              or a.after ->> 'account_id' = p_account_id::text
              or a.after ->> 'to_account_id' = p_account_id::text
              or a.before ->> 'account_id' = p_account_id::text
              or a.before ->> 'to_account_id' = p_account_id::text)
       order by a.id desc
       limit v_limit + 1
    ) x;
  return jsonb_build_object(
    'events', case when v_count > v_limit then v_rows - v_limit else v_rows end,
    'has_more', v_count > v_limit);
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 27;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on all functions in schema fulla from public, anon, authenticated;

do $$
declare
  f regprocedure;
begin
  for f in select p.oid::regprocedure from pg_proc p
            where p.pronamespace = 'public'::regnamespace and p.proname like 'fulla\_%'
  loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end
$$;
