/* SIBAQ Arts Fest — Service Worker
   Handles Web Push Notifications for candidates on their phones.
   This file must be served from the root of the site (same origin). */

const VAPID_PUBLIC_KEY = "BJOwzWvQe5HXBH-DkTjJNG3HpIXKdYmjTl6G9PlEiMoabg_mvUPILPstkRF-sbLNG8ieNoGOLJ4Qq4AmC3XTjwk";

/* ── Installation & Activation ───────────────────────────────────── */
self.addEventListener("install",  () => self.skipWaiting());
self.addEventListener("activate", e  => e.waitUntil(self.clients.claim()));

/* ── Push event: show notification (even when browser is closed / screen locked) ── */
self.addEventListener("push", event => {
  let data = {
    title: "📢 SIBAQ Announcement",
    body:  "New announcement from the Control Room.",
    tag:   "sibaq-announcement-" + Date.now()
  };
  try {
    if (event.data) {
      const parsed = event.data.json();
      data = { ...data, ...parsed };
    }
  } catch (_) {
    try {
      if (event.data) data.body = event.data.text();
    } catch (_) {}
  }

  const options = {
    body:               data.body,
    tag:                data.tag || ("sibaq-announcement-" + Date.now()),
    icon:               "icon-192.png",
    badge:              "icon-192.png",
    vibrate:            [500, 200, 500, 200, 800],  /* High-urgency pulse to trigger floating heads-up banner */
    requireInteraction: true,                       /* Stays on lock screen / desktop until user interacts */
    renotify:           true,                       /* Alert and vibrate for every incoming announcement */
    silent:             false,
    actions: [
      { action: "open", title: "View Announcement 📢" },
      { action: "dismiss", title: "✕ Dismiss" }
    ],
    data: {
      url:         data.url || "student.html",
      title:       data.title,
      body:        data.body,
      studentCall: data.studentCall || null,
      time:        Date.now()
    }
  };

  event.waitUntil(self.registration.showNotification(data.title, options));
});

/* ── Notification click: open student page and trigger popup on mobile ──────────────── */
self.addEventListener("notificationclick", event => {
  event.notification.close();
  if (event.action === "dismiss") return;

  const notifData   = event.notification.data || {};
  const notifBody   = event.notification.body || notifData.body || "";
  const notifTitle  = event.notification.title || notifData.title || "SIBAQ Announcement";
  const studentCall = notifData.studentCall || null;
  const baseUrl     = (notifData.url && !notifData.url.endsWith("/")) ? notifData.url : "student.html";

  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true })
      .then(clients => {
        // If a window is already open, post message and focus
        for (const client of clients) {
          client.postMessage({
            type:        "sibaq-open-announcement",
            title:       notifTitle,
            body:        notifBody,
            studentCall: studentCall,
            time:        Date.now()
          });
          if ("focus" in client) return client.focus();
        }

        // If closed (Chrome cleared or screen locked), open student page with announcement query params
        if (self.clients.openWindow) {
          const targetUrl = new URL(baseUrl, self.registration.scope);
          targetUrl.searchParams.set("announcement", notifBody);
          targetUrl.searchParams.set("announcement_title", notifTitle);
          if (studentCall) {
            targetUrl.searchParams.set("student_name", studentCall.name || "");
            targetUrl.searchParams.set("student_adNo", studentCall.adNo || "");
            targetUrl.searchParams.set("student_program", studentCall.program || "");
            targetUrl.searchParams.set("student_code", studentCall.programCode || "");
          }
          return self.clients.openWindow(targetUrl.toString());
        }
      })
  );
});

/* ── Background sync (future-proof) ─────────────────────────────── */
self.addEventListener("sync", event => {
  if (event.tag === "sibaq-sync") {
    /* Reserved for future offline sync */
  }
});
