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
    icon:               data.icon  || "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 192 192'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='%234338ca'/><stop offset='1' stop-color='%237c3aed'/></linearGradient></defs><rect width='192' height='192' rx='40' fill='url(%23g)'/><text x='96' y='130' text-anchor='middle' font-family='Arial' font-weight='900' font-size='80' fill='white'>S</text></svg>",
    badge:              data.badge || "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 96 96'><rect width='96' height='96' rx='20' fill='%234338ca'/><text x='48' y='68' text-anchor='middle' font-family='Arial' font-weight='900' font-size='48' fill='white'>S</text></svg>",
    vibrate:            [300, 150, 300, 150, 600],  /* 3-pulse vibration for pocket/lock screen */
    requireInteraction: true,                       /* Stays on lock screen until user interacts */
    renotify:           true,                       /* Alert and vibrate for every incoming announcement */
    silent:             false,
    actions: [
      { action: "open", title: "Open SIBAQ 📢" }
    ],
    data: {
      url: data.url || self.registration.scope
    }
  };

  event.waitUntil(self.registration.showNotification(data.title, options));
});

/* ── Notification click: open student page ───────────────────────── */
self.addEventListener("notificationclick", event => {
  event.notification.close();
  const target = event.notification.data?.url || self.registration.scope;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true })
      .then(clients => {
        for (const client of clients) {
          if (client.url === target && "focus" in client) return client.focus();
        }
        if (self.clients.openWindow) return self.clients.openWindow(target);
      })
  );
});

/* ── Background sync (future-proof) ─────────────────────────────── */
self.addEventListener("sync", event => {
  if (event.tag === "sibaq-sync") {
    /* Reserved for future offline sync */
  }
});
