-- UChat Supabase Schema: Postgres Tables, Row Level Security (RLS), Realtime, and Storage
-- Designed for Firebase Authentication (alphanumeric Firebase UIDs) + Supabase Realtime & Storage.
-- Run this once in your Supabase Dashboard -> SQL Editor (safe to re-run).

create extension if not exists pgcrypto;

-- Automatically upgrade older UUID-based tables from earlier Supabase Auth schema if present
do $$
begin
  if exists (
    select 1
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'profiles'
      and column_name = 'id'
      and data_type = 'uuid'
  ) then
    drop table if exists
      public.reports,
      public.call_ice_candidates,
      public.calls,
      public.followed_channels,
      public.channel_posts,
      public.channels,
      public.messages,
      public.chat_prefs,
      public.conversations,
      public.blocked_users,
      public.contacts,
      public.user_settings,
      public.profiles
    cascade;
  end if;
end $$;

-- Helper to resolve the signed-in Firebase UID from either Supabase Third-Party Firebase JWT
-- (auth.jwt() ->> 'sub') or the x-firebase-uid request header sent by authenticated UChat clients.
create or replace function public.uchat_uid()
returns text
language sql
stable
as $$
  select coalesce(
    nullif(auth.jwt() ->> 'sub', ''),
    nullif((nullif(current_setting('request.headers', true), '')::jsonb) ->> 'x-firebase-uid', '')
  );
$$;

-- 1. Profiles & Real-Time Presence (id = Firebase Auth UID)
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

-- 2. Per-User Settings
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

-- 3. Saved Contacts
create table if not exists public.contacts (
  owner_id text not null,
  contact_uid text not null,
  username text not null,
  display_name text not null,
  avatar_url text,
  created_at timestamptz not null default now(),
  primary key (owner_id, contact_uid)
);

-- 4. Blocked Users
create table if not exists public.blocked_users (
  owner_id text not null,
  blocked_uid text not null,
  username text not null,
  display_name text not null,
  created_at timestamptz not null default now(),
  primary key (owner_id, blocked_uid)
);

-- 5. Conversations (1:1 Direct, Self Saved Messages, and Groups)
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

-- 6. Per-User Chat Preferences
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

-- 7. Real-Time Chat Messages
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

-- 8. Broadcast Channels & Posts
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

-- 9. WebRTC Audio & Video Call Signaling
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

-- 10. Safety Reports
create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id text not null,
  target_uid text,
  category text not null default 'general',
  details text not null,
  created_at timestamptz not null default now()
);

-- Row Level Security (RLS)
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

-- Profiles policies
drop policy if exists "profiles_select_authenticated" on public.profiles;
create policy "profiles_select_authenticated" on public.profiles
  for select to anon, authenticated using (public.uchat_uid() is not null);
drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
  for insert to anon, authenticated with check (public.uchat_uid() = id);
drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update to anon, authenticated using (public.uchat_uid() = id) with check (public.uchat_uid() = id);

-- User settings policies
drop policy if exists "user_settings_own" on public.user_settings;
create policy "user_settings_own" on public.user_settings
  for all to anon, authenticated using (public.uchat_uid() = user_id) with check (public.uchat_uid() = user_id);

-- Contacts & Blocked policies
drop policy if exists "contacts_own" on public.contacts;
create policy "contacts_own" on public.contacts
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);
drop policy if exists "blocked_own" on public.blocked_users;
create policy "blocked_own" on public.blocked_users
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

-- Conversations policies (member-scoped)
drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member" on public.conversations
  for select to anon, authenticated using (public.uchat_uid() = any(member_uids));
drop policy if exists "conversations_insert_member" on public.conversations;
create policy "conversations_insert_member" on public.conversations
  for insert to anon, authenticated with check (public.uchat_uid() = created_by and public.uchat_uid() = any(member_uids));
drop policy if exists "conversations_update_member" on public.conversations;
create policy "conversations_update_member" on public.conversations
  for update to anon, authenticated using (public.uchat_uid() = any(member_uids)) with check (public.uchat_uid() = any(member_uids));

-- Chat prefs policies
drop policy if exists "chat_prefs_own" on public.chat_prefs;
create policy "chat_prefs_own" on public.chat_prefs
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

-- Messages policies (member-scoped)
drop policy if exists "messages_select_member" on public.messages;
create policy "messages_select_member" on public.messages
  for select to anon, authenticated using (public.uchat_uid() = any(member_uids));
drop policy if exists "messages_insert_member" on public.messages;
create policy "messages_insert_member" on public.messages
  for insert to anon, authenticated with check (public.uchat_uid() = sender_id and public.uchat_uid() = any(member_uids));
drop policy if exists "messages_update_member" on public.messages;
create policy "messages_update_member" on public.messages
  for update to anon, authenticated using (public.uchat_uid() = any(member_uids)) with check (public.uchat_uid() = any(member_uids));

-- Channels policies
drop policy if exists "channels_select_public" on public.channels;
create policy "channels_select_public" on public.channels
  for select to anon, authenticated using (
    public.uchat_uid() is not null and (discoverable = true or public.uchat_uid() = owner_id)
  );
drop policy if exists "channels_insert_owner" on public.channels;
create policy "channels_insert_owner" on public.channels
  for insert to anon, authenticated with check (public.uchat_uid() = owner_id);
drop policy if exists "channels_update_authenticated" on public.channels;
create policy "channels_update_authenticated" on public.channels
  for update to anon, authenticated using (public.uchat_uid() is not null) with check (public.uchat_uid() is not null);

drop policy if exists "channel_posts_select_public" on public.channel_posts;
create policy "channel_posts_select_public" on public.channel_posts
  for select to anon, authenticated using (
    public.uchat_uid() is not null and (discoverable = true or public.uchat_uid() = author_id)
  );
drop policy if exists "channel_posts_insert_author" on public.channel_posts;
create policy "channel_posts_insert_author" on public.channel_posts
  for insert to anon, authenticated with check (public.uchat_uid() = author_id);
drop policy if exists "channel_posts_update_authenticated" on public.channel_posts;
create policy "channel_posts_update_authenticated" on public.channel_posts
  for update to anon, authenticated using (public.uchat_uid() is not null) with check (public.uchat_uid() is not null);

drop policy if exists "followed_channels_own" on public.followed_channels;
create policy "followed_channels_own" on public.followed_channels
  for all to anon, authenticated using (public.uchat_uid() = owner_id) with check (public.uchat_uid() = owner_id);

-- WebRTC Calls & ICE Candidates policies (participant-scoped)
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

-- Reports policies
drop policy if exists "reports_insert_own" on public.reports;
create policy "reports_insert_own" on public.reports
  for insert to anon, authenticated with check (public.uchat_uid() = reporter_id);

-- Enable Supabase Realtime publication safely (idempotent)
do $$
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
    exception when duplicate_object then
      null;
    end;
  end loop;
end $$;

-- Supabase Storage bucket for avatars, chat photos, voice notes, documents, and channel broadcasts
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
