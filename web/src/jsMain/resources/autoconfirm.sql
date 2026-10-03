-- Fulla (web): accounts made in this project need no confirmation email.
-- A new Supabase project asks for one and sends its link to localhost:3000, which nobody can open.
-- Safe to run again; it also confirms accounts that are waiting.
create or replace function public.fulla_autoconfirm() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
  new.email_confirmed_at := coalesce(new.email_confirmed_at, now());
  return new;
end
$$;
revoke all on function public.fulla_autoconfirm() from public, anon, authenticated;
drop trigger if exists fulla_autoconfirm on auth.users;
create trigger fulla_autoconfirm before insert on auth.users for each row execute function public.fulla_autoconfirm();
update auth.users set email_confirmed_at = now() where email_confirmed_at is null;
