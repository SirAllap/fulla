-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Deleting an account, as app stores require: the person's sign-in goes, and
-- so does everything that was theirs alone.
--
--   * A household where they are the only member with an account was theirs
--     alone (members without an account are people they added): it is erased,
--     every row of it.
--   * A household others still use keeps its history: their membership is
--     detached from the account and marked removed, like leaving, and what
--     they wrote down stays for the others.
--   * The owner of a household others still use must hand it over first.
--
-- Rows are never deleted anywhere else. Erasing a household is the one
-- exception, and the delete guard allows it only for the household named in a
-- transaction-local setting that only this function sets.

create or replace function fulla.forbid_delete() returns trigger
language plpgsql
as $$
declare
  v_erasing text := nullif(current_setting('fulla.erasing_household', true), '');
  v_row jsonb := to_jsonb(old);
begin
  if v_erasing is not null
     and v_erasing = coalesce(v_row ->> 'household_id', case when tg_table_name = 'households' then v_row ->> 'id' end) then
    return old;
  end if;
  raise exception using
    errcode = 'PT409',
    message = format('Rows in %s are never deleted: archive or tombstone them instead.', tg_table_name),
    detail  = 'delete_forbidden';
end;
$$;

/* Every row of one household, in an order the foreign keys accept. */
create function fulla.erase_household(p_household_id uuid) returns void
language plpgsql volatile
as $$
begin
  perform set_config('fulla.erasing_household', p_household_id::text, true);
  delete from fulla.transactions where household_id = p_household_id;
  delete from fulla.invites where household_id = p_household_id;
  delete from fulla.budgets where household_id = p_household_id;
  delete from fulla.recurring_rules where household_id = p_household_id;
  delete from fulla.categorization_rules where household_id = p_household_id;
  delete from fulla.import_profiles where household_id = p_household_id;
  delete from fulla.custom_fields where household_id = p_household_id;
  update fulla.categories set parent_id = null where household_id = p_household_id and parent_id is not null;
  delete from fulla.categories where household_id = p_household_id;
  delete from fulla.accounts where household_id = p_household_id;
  delete from fulla.members where household_id = p_household_id;
  delete from fulla.households where id = p_household_id;
  perform set_config('fulla.erasing_household', '', true);
end;
$$;

create function public.fulla_account_delete() returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
  m fulla.members;
  v_others integer;
  v_erased integer := 0;
  v_left integer := 0;
begin
  -- Refuse before changing anything.
  for m in select * from fulla.members x where x.user_id = v_uid and x.status = 'active' and x.role = 'owner' loop
    select count(*) into v_others from fulla.members o
     where o.household_id = m.household_id and o.id <> m.id and o.status = 'active' and o.user_id is not null;
    if v_others > 0 then
      perform fulla.fail(409, 'owner_must_hand_over',
        format('Hand over «%s» to someone else before deleting your account.',
               (select h.name from fulla.households h where h.id = m.household_id)));
    end if;
  end loop;

  for m in select * from fulla.members x where x.user_id = v_uid loop
    select count(*) into v_others from fulla.members o
     where o.household_id = m.household_id and o.id <> m.id and o.status = 'active' and o.user_id is not null;
    if v_others = 0 then
      perform fulla.erase_household(m.household_id);
      v_erased := v_erased + 1;
    else
      update fulla.members x set user_id = null, role = 'member', status = 'removed' where x.id = m.id;
      v_left := v_left + 1;
    end if;
  end loop;

  update fulla.transactions set updated_by_user_id = null where updated_by_user_id = v_uid;
  update fulla.invites set used_by_user_id = null where used_by_user_id = v_uid;
  update fulla.households set created_by = null where created_by = v_uid;
  delete from fulla.invite_attempts where user_id = v_uid;
  delete from auth.users where id = v_uid;
  return jsonb_build_object('ok', true, 'households_erased', v_erased, 'households_left', v_left);
end;
$$;

revoke all on function fulla.erase_household(uuid) from public, anon, authenticated;
revoke all on function public.fulla_account_delete() from public, anon;
grant execute on function public.fulla_account_delete() to authenticated;
