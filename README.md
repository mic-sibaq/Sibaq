# Sibaq

## Mentor progress portal

Open `mentor.html` and select the mentor name from the login dropdown, then enter the existing mentor passkey. The portal shows assigned students' daily programme progress, supports date selection, lets mentors update completion and performance ratings, and provides a print-ready daily report.

Before using the portal, apply the latest `mentor-portal-setup.sql` in the Supabase SQL Editor after the existing progression setup. It provides a mentor-name dropdown and a read-only RPC that returns only programme assignments verified for the supplied mentor and passkey; it does not modify student records or replace existing progression functions.
