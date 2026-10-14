# SIBAQ Android Device Screening

This is a separate Android Studio project for organization-owned, fully managed Android devices. It performs an administrator-triggered scan against a fixed package-name allowlist and can submit only matching app names/package IDs and basic device details to Supabase.

It does not capture screen contents, inspect activity in other apps, or detect whether screen mirroring is currently active. An installed-app match is an indicator to review, not proof of misuse. The app does not send the full installed-app list.

## Configure Supabase

1. Run `android-device-screening-setup.sql` from the repository root in the Supabase SQL editor.
2. In Supabase Auth, provision approved accounts and set trusted `app_metadata.role` values:
   - `device_reporter` can submit reports.
   - `admin` can submit reports and read reports in `devices.html`.
   Set these roles through a trusted administrator process, never from client-editable user metadata.
3. Create `android-screening/local.properties` (it is git-ignored) with the public project settings:

   ```properties
   SIBAQ_SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
   SIBAQ_SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
   ```

   The anon key is not a secret. Never put a Supabase service-role key in the Android app.
4. Open `android-screening` in Android Studio, install Android SDK 35, and build/install the `app` module.
5. Install and provision the app using your Android Enterprise/MDM enrollment process as the fully managed device owner. For a test device, Android's `dpm set-device-owner` provisioning requires a clean device and is intended for device-owner setup; follow the Android version and MDM vendor's current setup requirements.

The app will refuse to scan unless Android confirms it is the device owner. Reports are inserted using an authenticated Supabase session; database row-level security permits only `device_reporter`/`admin` accounts to submit and only `admin` accounts to read. The dashboard report panel requires an authenticated admin account.

## Screening list

The starter allowlist in `app/src/main/java/org/sibaq/screening/MainActivity.kt` checks selected remote-support tools by package ID. Verify package IDs against the apps deployed in your organization and maintain the list as needed. Android package visibility and app-store distribution rules apply to `QUERY_ALL_PACKAGES`; distribute privately through your MDM/managed Google Play channel and review applicable policies.
