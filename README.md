# Sibaq

## Mentor progress portal

Open `mentor.html` and sign in with the mentor name assigned to the student's programmes and the existing mentor passkey. The portal shows assigned students' daily programme progress, supports date selection, lets mentors update completion and performance ratings, and provides a print-ready daily report.

Before using the portal, apply `mentor-portal-setup.sql` in the Supabase SQL Editor after the existing progression setup. This adds a read-only RPC that returns only programme assignments verified for the supplied mentor and passkey; it does not modify student records or replace existing progression functions.
