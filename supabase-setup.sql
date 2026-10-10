-- SIBAQ student profile table. Run this whole script in Supabase SQL Editor.
create table if not exists public.student_profiles (
  admission_no text primary key,
  name text not null default '',
  mentor text not null default '',
  programs text[] not null default '{}',
  program_codes text[] not null default '{}',
  program_mentors text[] not null default '{}',
  updated_at timestamptz not null default now()
);
alter table public.student_profiles
  add column if not exists program_mentors text[] not null default '{}';
alter table public.student_profiles enable row level security;
drop policy if exists "Public can read student profiles" on public.student_profiles;
create policy "Public can read student profiles" on public.student_profiles for select to anon, authenticated using (true);
drop policy if exists "Signed-in admins can insert student profiles" on public.student_profiles;
create policy "Signed-in admins can insert student profiles" on public.student_profiles for insert to authenticated with check (true);
drop policy if exists "Signed-in admins can update student profiles" on public.student_profiles;
create policy "Signed-in admins can update student profiles" on public.student_profiles for update to authenticated using (true) with check (true);
drop policy if exists "Signed-in admins can delete student profiles" on public.student_profiles;
create policy "Signed-in admins can delete student profiles" on public.student_profiles for delete to authenticated using (true);
