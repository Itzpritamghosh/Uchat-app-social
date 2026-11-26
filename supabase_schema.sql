create extension if not exists pgcrypto;

do $mig$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'profiles'
      and column_name = 'id' and data_type = 'uuid'
  ) then
    drop table if exists
      public.reports, public.call_ice_candidates, public.calls,
      public.followed_channels, public.channel_posts, public.channels,
      public.messages, public.chat_prefs, public.conversations,
      public.blocked_users, public.contacts, public.user_settings, public.profiles
    cascade;
  end if;
end $mig$;

create table if not exists public.user_accounts (
  user_id text primary key,
  username_lower text not null unique,
  password_hash text not null,
  created_at timestamptz not null default now()
);

create table if not exists public.user_sessions (
  token_hash text primary key,
  user_id text not null references public.user_accounts(user_id) on delete cascade,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index if not exists idx_user_sessions_user on public.user_sessions (user_id);

create table if not exists public.profiles (
  id text primary key,
  username text not null,
  username_lower text not null unique,
  display_name text not null,
  display_name_lower text not null,
  about text not null default 'Hey there! I am using UChat.',
  avatar_url text,
  online boolean not null default false,
  last_seen timestamptz not null default now(),
  show_last_seen boolean not null default true,
  show_online boolean not null default true,
  show_about boolean not null default true,
  read_receipts boolean not null default true,
  discoverable boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create or replace function public.sync_account_username()
returns trigger
language plpgsql
security definer
set search_path = public
as $fn$
begin
  if new.username_lower is distinct from old.username_lower then
    update public.user_accounts set username_lower = new.username_lower where user_id = new.id;
  end if;
  return new;
end;
$fn$;

drop trigger if exists trg_sync_account_username on public.profiles;
create trigger trg_sync_account_username
after update of username_lower on public.profiles
for each row execute function public.sync_account_username();

create table if not exists public.user_settings (
  user_id text primary key,
  accent_hex text not null default '#25D366',
  wallpaper_style text not null default 'doodle',
  font_scale double precision not null default 1.0,
  msg_notif boolean not null default true,
  group_notif boolean not null default true,
  call_notif boolean not null default true,
  sound_enabled boolean not null default true,
  ringtone_name text not null default 'Default Ringtone',
  ringtone_uri text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.contacts (
  owner_id text not null,
  contact_uid text not null,
  username text not null,
  display_name text not null,
  avatar_url text,
  created_at timestamptz not null default now(),
  primary key (owner_id, contact_uid)
);

create table if not exists public.blocked_users (
  owner_id text not null,
  blocked_uid text not null,
  username text not null,
  display_name text not null,
  created_at timestamptz not null default now(),
  primary key (owner_id, blocked_uid)
);

create table if not exists public.conversations (
  id text primary key,
  kind text not null default 'direct',
  member_uids text[] not null default '{}',
  admin_uids text[] not null default '{}',
  title text not null default '',
  description text not null default '',
  avatar_url text,
  member_names jsonb not null default '{}'::jsonb,
  member_usernames jsonb not null default '{}'::jsonb,
  last_message_text text not null default '',
  last_message_type text not null default 'text',
  last_message_sender_id text not null default '',
  last_message_at timestamptz not null default now(),
  typing_uid text,
  typing_at timestamptz,
  created_by text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_conversations_members on public.conversations using gin (member_uids);

create table if not exists public.chat_prefs (
  owner_id text not null,
  conversation_id text not null references public.conversations(id) on delete cascade,
  pinned boolean not null default false,
  archived boolean not null default false,
  muted boolean not null default false,
  hidden boolean not null default false,
  unread_count bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner_id, conversation_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id text not null references public.conversations(id) on delete cascade,
  sender_id text not null,
  sender_name text not null default 'UChat User',
  member_uids text[] not null default '{}',
  message_type text not null default 'text',
  text text not null default '',
  media_url text,
  media_name text,
  media_duration_sec bigint,
  waveform bigint[],
  latitude double precision,
  longitude double precision,
  contact_name text,
  contact_username text,
  reply_to_id text,
  reply_to_text text,
  reply_to_sender text,
  forwarded boolean not null default false,
  edited boolean not null default false,
  deleted boolean not null default false,
  delivered_to text[] not null default '{}',
  read_by text[] not null default '{}',
  starred_by text[] not null default '{}',
  reactions jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_messages_conversation on public.messages (conversation_id, created_at);

create table if not exists public.channels (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  description text not null default '',
  owner_id text not null,
  owner_name text not null default 'UChat Creator',
  avatar_url text,
  follower_count bigint not null default 1,
  discoverable boolean not null default true,
  last_post_text text not null default '',
  last_post_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.channel_posts (
  id uuid primary key default gen_random_uuid(),
  channel_id uuid not null references public.channels(id) on delete cascade,
  author_id text not null,
  author_name text not null default 'Channel Admin',
  post_type text not null default 'text',
  text text not null default '',
  media_url text,
  reactions jsonb not null default '{}'::jsonb,
  discoverable boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_channel_posts_channel on public.channel_posts (channel_id, created_at desc);

create table if not exists public.followed_channels (
  owner_id text not null,
  channel_id uuid not null references public.channels(id) on delete cascade,
  channel_name text not null default '',
  created_at timestamptz not null default now(),
  primary key (owner_id, channel_id)
);

create table if not exists public.calls (
  id uuid primary key default gen_random_uuid(),
  caller_id text not null,
  caller_name text not null default '',
  caller_username text not null default '',
  callee_id text not null,
  callee_name text not null default '',
  callee_username text not null default '',
  participant_uids text[] not null default '{}',
  call_type text not null default 'audio',
  status text not null default 'ringing',
  sdp_offer text,
  sdp_answer text,
  duration_sec bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_calls_participants on public.calls using gin (participant_uids);

create table if not exists public.call_ice_candidates (
  id uuid primary key default gen_random_uuid(),
  call_id uuid not null references public.calls(id) on delete cascade,
  sender_id text not null,
  participant_uids text[] not null default '{}',
  candidate text not null,
  sdp_mid text not null default '',
  sdp_mline_index bigint not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists idx_ice_candidates_call on public.call_ice_candidates (call_id, created_at);

create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id text not null,
  target_uid text,
  category text not null default 'general',
  details text not null,
  created_at timestamptz not null default now()
);

create or replace function public.uchat_uid()
returns text
language sql
stable
security definer
set search_path = public, extensions
as $fn$
  select s.user_id
  from public.user_sessions s
  where s.token_hash = encode(
    digest(
      convert_to(
        coalesce(nullif((nullif(current_setting('request.headers', true), '')::jsonb) ->> 'x-uchat-token', ''), ''),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  )
    and s.expires_at > now()
  limit 1;
$fn$;

create or replace function public.uchat_username_available(p_username text)
returns boolean
language sql
stable
security definer
set search_path = public
as $fn$
  select
    lower(trim(coalesce(p_username, ''))) ~ '^[a-z0-9_]{3,24}$'
    and not exists (select 1 from public.user_accounts where username_lower = lower(trim(coalesce(p_username, ''))))
    and not exists (select 1 from public.profiles where username_lower = lower(trim(coalesce(p_username, ''))));
$fn$;

create or replace function public.uchat_signup(p_username text, p_display_name text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $fn$
declare
  v_username text := lower(trim(regexp_replace(coalesce(p_username, ''), '^@+', '')));
  v_display_name text := trim(coalesce(p_display_name, ''));
  v_password text := coalesce(p_password, '');
  v_user_id text;
  v_token text;
  v_token_hash text;
  v_now timestamptz := now();
begin
  if v_username !~ '^[a-z0-9_]{3,24}$' then
    raise exception 'Username must be 3-24 lowercase letters, numbers, or underscores.';
  end if;
  if length(v_display_name) < 1 or length(v_display_name) > 60 then
    raise exception 'Display name must be between 1 and 60 characters.';
  end if;
  if length(v_password) < 6 or length(v_password) > 128 then
    raise exception 'Password must be at least 6 characters.';
  end if;
  if exists (select 1 from public.user_accounts where username_lower = v_username)
     or exists (select 1 from public.profiles where username_lower = v_username) then
    raise exception 'Username @% is already taken. Pick another or switch to Sign In.', v_username;
  end if;

  v_user_id := 'usr_' || encode(gen_random_bytes(12), 'hex');
  insert into public.user_accounts (user_id, username_lower, password_hash, created_at)
  values (v_user_id, v_username, crypt(v_password, gen_salt('bf', 10)), v_now);

  insert into public.profiles (
    id, username, username_lower, display_name, display_name_lower,
    about, online, last_seen, created_at, updated_at
  ) values (
    v_user_id, v_username, v_username, v_display_name, lower(v_display_name),
    'Hey there! I am using UChat.', true, v_now, v_now, v_now
  );

  insert into public.user_settings (user_id, created_at, updated_at)
  values (v_user_id, v_now, v_now)
  on conflict (user_id) do nothing;

  v_token := encode(gen_random_bytes(32), 'hex');
  v_token_hash := encode(digest(convert_to(v_token, 'UTF8'), 'sha256'), 'hex');
  insert into public.user_sessions (token_hash, user_id, expires_at, created_at)
  values (v_token_hash, v_user_id, v_now + interval '90 days', v_now);

  return jsonb_build_object(
    'user_id', v_user_id,
    'username', v_username,
    'display_name', v_display_name,
    'session_token', v_token
  );
end;
$fn$;

create or replace function public.uchat_signin(p_username text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $fn$
declare
  v_username text := lower(trim(regexp_replace(coalesce(p_username, ''), '^@+', '')));
  v_password text := coalesce(p_password, '');
  v_account record;
  v_display_name text;
  v_token text;
  v_token_hash text;
  v_now timestamptz := now();
begin
  if v_username = '' or v_password = '' then
    raise exception 'Please enter both your @username and password.';
  end if;

  select user_id, username_lower, password_hash into v_account
  from public.user_accounts where username_lower = v_username limit 1;

  if v_account is null or v_account.password_hash <> crypt(v_password, v_account.password_hash) then
    raise exception 'Invalid @username or password.';
  end if;

  delete from public.user_sessions where user_id = v_account.user_id and expires_at <= v_now;

  v_token := encode(gen_random_bytes(32), 'hex');
  v_token_hash := encode(digest(convert_to(v_token, 'UTF8'), 'sha256'), 'hex');
  insert into public.user_sessions (token_hash, user_id, expires_at, created_at)
  values (v_token_hash, v_account.user_id, v_now + interval '90 days', v_now);

  select display_name into v_display_name from public.profiles where id = v_account.user_id limit 1;

  return jsonb_build_object(
    'user_id', v_account.user_id,
    'username', v_account.username_lower,
    'display_name', coalesce(v_display_name, v_account.username_lower),
    'session_token', v_token
  );
end;
$fn$;

create or replace function public.uchat_signout()
returns void
language plpgsql
security definer
set search_path = public, extensions
as $fn$
declare
  v_token text := nullif((nullif(current_setting('request.headers', true), '')::jsonb) ->> 'x-uchat-token', '');
  v_hash text;
  v_uid text;
begin
  if v_token is null then return; end if;
  v_hash := encode(digest(convert_to(v_token, 'UTF8'), 'sha256'), 'hex');
  delete from public.user_sessions where token_hash = v_hash returning user_id into v_uid;
  if v_uid is not null then
    update public.profiles set online = false, last_seen = now(), updated_at = now() where id = v_uid;
  end if;
end;
$fn$;

create or replace function public.uchat_validate_session()
returns text
language sql
stable
security definer
set search_path = public, extensions
as $fn$
  select public.uchat_uid();
$fn$;

alter table public.user_accounts enable row level security;
alter table public.user_sessions enable row level security;
alter table public.profiles enable row level security;
alter table public.user_settings enable row level security;
alter table public.contacts enable row level security;
alter table public.blocked_users enable row level security;
alter table public.conversations enable row level security;
alter table public.chat_prefs enable row level security;
alter table public.messages enable row level security;
alter table public.channels enable row level security;
alter table public.channel_posts enable row level security;
alter table public.followed_channels enable row level security;
alter table public.calls enable row level security;
alter table public.call_ice_candidates enable row level security;
alter table public.reports enable row level security;

drop policy if exists "profiles_select_authenticated" on public.profiles;
create policy "profiles_select_authenticated" on public.profiles
  for select to anon, authenticated using (public.uchat_uid() is not null);
drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
  for insert to anon, authenticated with check (public.uchat_uid() = id);
drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update to anon, authenticated using (public.uchat_uid() = id) with check (public.uchat_uid() = id);

drop policy if exists "user_settings_own" on public.user_settings;
create policy "user_settings_own" on public.user_settings
  for all to anon, authenticated using (public.uchat_uid() = user_id) with check (public.uchat_uid() = user_id);

drop policy if exists "contacts_own" on public.contacts;
create policy "contacts_own" on public.contacts
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

drop policy if exists "blocked_own" on public.blocked_users;
create policy "blocked_own" on public.blocked_users
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member" on public.conversations
  for select to anon, authenticated using (public.uchat_uid() = any(member_uids));
drop policy if exists "conversations_insert_member" on public.conversations;
create policy "conversations_insert_member" on public.conversations
  for insert to anon, authenticated with check (public.uchat_uid() = created_by and public.uchat_uid() = any(member_uids));
drop policy if exists "conversations_update_member" on public.conversations;
create policy "conversations_update_member" on public.conversations
  for update to anon, authenticated using (public.uchat_uid() = any(member_uids)) with check (public.uchat_uid() = any(member_uids));

drop policy if exists "chat_prefs_own" on public.chat_prefs;
create policy "chat_prefs_own" on public.chat_prefs
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

drop policy if exists "messages_select_member" on public.messages;
create policy "messages_select_member" on public.messages
  for select to anon, authenticated using (public.uchat_uid() = any(member_uids));
drop policy if exists "messages_insert_member" on public.messages;
create policy "messages_insert_member" on public.messages
  for insert to anon, authenticated with check (public.uchat_uid() = sender_id and public.uchat_uid() = any(member_uids));
drop policy if exists "messages_update_member" on public.messages;
create policy "messages_update_member" on public.messages
  for update to anon, authenticated using (public.uchat_uid() = any(member_uids)) with check (public.uchat_uid() = any(member_uids));

drop policy if exists "channels_select_public" on public.channels;
create policy "channels_select_public" on public.channels
  for select to anon, authenticated using (public.uchat_uid() is not null and (discoverable = true or public.uchat_uid() = owner_id));
drop policy if exists "channels_insert_owner" on public.channels;
create policy "channels_insert_owner" on public.channels
  for insert to anon, authenticated with check (public.uchat_uid() = owner_id);
drop policy if exists "channels_update_authenticated" on public.channels;
create policy "channels_update_authenticated" on public.channels
  for update to anon, authenticated using (public.uchat_uid() is not null) with check (public.uchat_uid() is not null);

drop policy if exists "channel_posts_select_public" on public.channel_posts;
create policy "channel_posts_select_public" on public.channel_posts
  for select to anon, authenticated using (public.uchat_uid() is not null and (discoverable = true or public.uchat_uid() = author_id));
drop policy if exists "channel_posts_insert_author" on public.channel_posts;
create policy "channel_posts_insert_author" on public.channel_posts
  for insert to anon, authenticated with check (public.uchat_uid() = author_id);
drop policy if exists "channel_posts_update_authenticated" on public.channel_posts;
create policy "channel_posts_update_authenticated" on public.channel_posts
  for update to anon, authenticated using (public.uchat_uid() is not null) with check (public.uchat_uid() is not null);

drop policy if exists "followed_channels_own" on public.followed_channels;
create policy "followed_channels_own" on public.followed_channels
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

drop policy if exists "calls_select_participant" on public.calls;
create policy "calls_select_participant" on public.calls
  for select to anon, authenticated using (public.uchat_uid() = any(participant_uids));
drop policy if exists "calls_insert_caller" on public.calls;
create policy "calls_insert_caller" on public.calls
  for insert to anon, authenticated with check (public.uchat_uid() = caller_id and public.uchat_uid() = any(participant_uids));
drop policy if exists "calls_update_participant" on public.calls;
create policy "calls_update_participant" on public.calls
  for update to anon, authenticated using (public.uchat_uid() = any(participant_uids)) with check (public.uchat_uid() = any(participant_uids));
drop policy if exists "calls_delete_participant" on public.calls;
create policy "calls_delete_participant" on public.calls
  for delete to anon, authenticated using (public.uchat_uid() = any(participant_uids));

drop policy if exists "ice_select_participant" on public.call_ice_candidates;
create policy "ice_select_participant" on public.call_ice_candidates
  for select to anon, authenticated using (public.uchat_uid() = any(participant_uids));
drop policy if exists "ice_insert_sender" on public.call_ice_candidates;
create policy "ice_insert_sender" on public.call_ice_candidates
  for insert to anon, authenticated with check (public.uchat_uid() = sender_id and public.uchat_uid() = any(participant_uids));

drop policy if exists "reports_insert_own" on public.reports;
create policy "reports_insert_own" on public.reports
  for insert to anon, authenticated with check (public.uchat_uid() = reporter_id);

do $rt$
declare
  tbl text;
begin
  foreach tbl in array array[
    'profiles', 'conversations', 'messages', 'calls', 'call_ice_candidates',
    'channels', 'channel_posts', 'contacts', 'chat_prefs', 'user_settings',
    'blocked_users', 'followed_channels'
  ] loop
    begin
      execute format('alter publication supabase_realtime add table public.%I', tbl);
    exception when duplicate_object or undefined_object then
      null;
    end;
  end loop;
end $rt$;

insert into storage.buckets (id, name, public)
values ('uchat-media', 'uchat-media', true)
on conflict (id) do nothing;

drop policy if exists "uchat_media_public_read" on storage.objects;
create policy "uchat_media_public_read" on storage.objects
  for select to public using (bucket_id = 'uchat-media');
drop policy if exists "uchat_media_auth_upload" on storage.objects;
create policy "uchat_media_auth_upload" on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'uchat-media');
drop policy if exists "uchat_media_auth_update" on storage.objects;
create policy "uchat_media_auth_update" on storage.objects
  for update to anon, authenticated using (bucket_id = 'uchat-media');

grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on
  public.profiles, public.user_settings, public.contacts, public.blocked_users,
  public.conversations, public.chat_prefs, public.messages, public.channels,
  public.channel_posts, public.followed_channels, public.calls,
  public.call_ice_candidates, public.reports
to anon, authenticated;
grant execute on function public.uchat_uid() to anon, authenticated;
grant execute on function public.uchat_username_available(text) to anon, authenticated;
grant execute on function public.uchat_signup(text, text, text) to anon, authenticated;
grant execute on function public.uchat_signin(text, text) to anon, authenticated;
grant execute on function public.uchat_signout() to anon, authenticated;
grant execute on function public.uchat_validate_session() to anon, authenticated;
notify pgrst, 'reload schema';
