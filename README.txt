SIBAQ PERMANENT STUDENT QR SYSTEM — SETUP GUIDE

WHAT THIS PACKAGE DOES
- Groups rows by Admission Number (case-insensitive) so the same student in multiple programs gets ONE QR.
- The student profile shows Admission Number, Name, Mentor, Programs, Program Codes and last-updated time.
- Student profile and admin assessment dossier photos load from the public Supabase Storage bucket `Images`; name each PNG `<Admission Number>.png` (for example, `940.png`).
- QR URLs contain only the admission number, e.g. https://mic-sibaq.vercel.app/student.html?id=123. They do not contain the profile details.
- After the profile is updated online, the same printed QR shows the latest details.
- Existing Control Room and Live Wall functionality is retained in admin.html and live-wall.html.

FILES
- admin.html: Control Room + QR generator + online profile editor.
- live-wall.html: existing Live Wall.
- student.html: live online student profile.
- supabase-config.js: add your Supabase project URL and anon/publishable key here.
- supabase-setup.sql: SQL to create table and permissions.

STEP 1 — CREATE SUPABASE PROJECT
1. Visit https://supabase.com and create/sign in to your account.
2. Click New project. Give it a name such as SIBAQ Student Profiles, choose a strong database password, and select a region near you.
3. Wait until the project is ready.

STEP 2 — CREATE THE TABLE
1. Open your project and go to SQL Editor.
2. Choose New query.
3. Open supabase-setup.sql from this package, copy all its contents, paste into the SQL Editor, and click Run.
4. Confirm the query finishes successfully.
   If the table already exists, run the updated script again; it adds the per-programme mentor column and persistent programme-code mapping table, and converts a legacy single-text `programs` column to a text array while preserving its existing values.

STEP 3 — CREATE AN ADMIN LOGIN
1. In Supabase open Authentication → Users.
2. Click Add user / Create new user. Enter an admin email and a strong password. Confirm the user if Supabase offers that option.
3. In Authentication settings, disable public sign-ups so strangers cannot create accounts. Only the account(s) you create should be able to edit profiles.
4. Do not share the admin password.

STEP 4 — SET UP THE WEBSITE CONFIG
1. Open supabase-config.js in a text editor.
2. In Supabase, open Project Settings → API (or Connect). Copy the Project URL and the anon/public/publishable key.
3. Replace YOUR_SUPABASE_PROJECT_URL with the Project URL, and YOUR_SUPABASE_ANON_OR_PUBLISHABLE_KEY with the anon/publishable key.
4. Do NOT use the service_role key or any secret key in this file. Browser files are public.

STEP 5 — DEPLOY TO VERCEL
1. Put admin.html, live-wall.html, student.html, and supabase-config.js in the same folder/repository. Include the rest of your current site files too if you use them.
2. Make sure the deployed public root contains student.html and supabase-config.js.
3. Deploy to the existing Vercel project https://mic-sibaq.vercel.app (or your correct project URL). If you use GitHub integration, commit and push the changed files; otherwise upload/redeploy the project using your existing Vercel workflow.
4. Open https://mic-sibaq.vercel.app/student.html in a browser. It may show a message asking for a QR/admission number; that is expected without ?id=...

STEP 6 — UPLOAD STUDENTS AND MAKE QR CODES
1. Open https://mic-sibaq.vercel.app/admin.html.
2. Import the CSV in the normal student section. Required columns: Admission No, Name, Program Code, Program. Mentor is optional; use a Mentor, Mentor Name, Program Mentor, or Programme Mentor column to assign mentors by Program Code. Rows with the same Program Code share that mentor assignment.
3. Scroll to Student QR Code Generator. Enter the admin email/password and click Sign in.
4. To assign mentors separately, upload a second CSV in the Online Student Database section with `Program Code` and `Mentor` columns. One row per program code is enough; matching online profiles are updated, and saved assignments are reused during future student uploads.
5. Click Upload / Update Unique Students. The system groups all program rows for each admission number and uploads one combined profile per student.
6. Click Generate One QR per Student. Confirm the count equals the number of unique admission numbers (not the number of program rows).
7. Download the ZIP or print the QR sheet. Test a QR with a phone before printing all cards.

STEP 7 — EDIT AFTER PRINTING
1. Open the Control Room and sign in.
2. Click Load Online Profiles.
3. Edit Name, Mentor, Programs (one per line), Program Codes (one per line), or Mentors per program (one per line in the same order as the program/code lists), then click Save Profile.
4. The existing QR code remains unchanged. Scan it again to see the updated database record.
5. If the student participates in additional programs, edit the program and code lists here. Avoid re-uploading an older CSV afterward unless you want its values to replace the edited profile.

IMPORTANT NOTES
- Admission Number must be unique and stable. If the same student has different admission numbers in different files, the system cannot know they are the same person; correct the admission number in the source CSV first.
- Public QR profiles can be viewed by anyone who scans the QR. Do not put private/sensitive information in these fields.
- Photos in the public `Images` bucket are also publicly accessible. Only upload student photos when you have appropriate permission.
- Anyone who can sign in to a Supabase account can edit the table under these policies. Keep public sign-ups disabled and only create accounts for trusted administrators.
- The admin/live-wall localStorage and BroadcastChannel features are browser/device-local. This package adds online syncing specifically for student profiles and QR codes; it does not automatically make every Control Room feature sync between devices.
- If your Supabase menu labels differ slightly, use Project Settings → API/Connect for URL/key and Authentication → Users for admin account creation.
