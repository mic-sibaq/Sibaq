-- SIBAQ Android Device Screening & Live Monitoring Supabase Setup Script
-- Run this script in your Supabase SQL Editor (https://supabase.com/dashboard/project/_/sql)

-- 1. Create active_devices table for live cloud tracking
CREATE TABLE IF NOT EXISTS public.active_devices (
    device_id TEXT PRIMARY KEY,
    custom_name TEXT DEFAULT '',
    device_label TEXT DEFAULT '',
    ip_address TEXT DEFAULT '',
    port INT DEFAULT 8080,
    battery_level INT DEFAULT 0,
    fps INT DEFAULT 0,
    is_online BOOLEAN DEFAULT true,
    last_seen_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Enable Row Level Security (RLS)
ALTER TABLE public.active_devices ENABLE ROW LEVEL SECURITY;

-- 3. RLS Policy: Allow anonymous devices to register & update heartbeats using anon key
DROP POLICY IF EXISTS "Allow anonymous device heartbeats" ON public.active_devices;
CREATE POLICY "Allow anonymous device heartbeats" ON public.active_devices
    FOR ALL
    USING (true)
    WITH CHECK (true);

-- 4. Enable Realtime on active_devices table
ALTER PUBLICATION supabase_realtime ADD TABLE public.active_devices;
