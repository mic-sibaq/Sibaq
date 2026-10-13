-- SIBAQ Push Subscriptions Table
-- Run this in the Supabase SQL Editor: https://supabase.com/dashboard/project/mtbppoyzcyarsiicisoj/sql

create table if not exists public.push_subscriptions (
  id         uuid primary key default gen_random_uuid(),
  endpoint   text not null unique,
  p256dh     text not null,
  auth       text not null,
  updated_at timestamptz default now()
);

-- Allow the anon role to insert/upsert their own subscription
alter table public.push_subscriptions enable row level security;

create policy "Anyone can subscribe"
  on public.push_subscriptions
  for insert
  with check (true);

create policy "Anyone can update their own subscription"
  on public.push_subscriptions
  for update
  using (true);

create policy "Service role can read all subscriptions"
  on public.push_subscriptions
  for select
  using (auth.role() = 'service_role');
