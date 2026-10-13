// @ts-nocheck
/**
 * SIBAQ Arts Fest — Supabase Edge Function: send-push
 *
 * Sends Web Push notifications to all subscribed candidate phones.
 * Called by admin-fixed.html whenever an announcement is broadcast.
 *
 * Deploy:
 *   1. Install Supabase CLI: npm install -g supabase
 *   2. Login: supabase login
 *   3. Link project: supabase link --project-ref mtbppoyzcyarsiicisoj
 *   4. Set secrets:
 *        supabase secrets set VAPID_PRIVATE_KEY="IhmCfxdj_6xUKjQVu5Y07DxtGKgiaXb1fgotHey9cME"
 *        supabase secrets set VAPID_EMAIL="mailto:admin@sibaq.org"
 *   5. Deploy: supabase functions deploy send-push --no-verify-jwt
 */

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

declare const Deno: any;

/* ── VAPID config ─────────────────────────────────────────────────── */
const VAPID_PUBLIC_KEY  = "BJOwzWvQe5HXBH-DkTjJNG3HpIXKdYmjTl6G9PlEiMoabg_mvUPILPstkRF-sbLNG8ieNoGOLJ4Qq4AmC3XTjwk";
const VAPID_PRIVATE_KEY = (typeof Deno !== "undefined" && Deno.env ? Deno.env.get("VAPID_PRIVATE_KEY") : "") ?? "";
const VAPID_EMAIL       = (typeof Deno !== "undefined" && Deno.env ? Deno.env.get("VAPID_EMAIL") : "mailto:admin@sibaq.org") ?? "mailto:admin@sibaq.org";

/* ── CORS headers ────────────────────────────────────────────────── */
const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin":  "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, apikey",
};

/* ── Helpers ─────────────────────────────────────────────────────── */
function b64url(buf: ArrayBuffer | Uint8Array): string {
  const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let binary = "";
  for (let i = 0; i < u8.length; i++) {
    binary += String.fromCharCode(u8[i]);
  }
  return btoa(binary)
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}

function fromB64url(str: string): Uint8Array {
  const pad = "=".repeat((4 - str.length % 4) % 4);
  const b64 = (str + pad).replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(b64), c => c.charCodeAt(0));
}

async function importVapidKeys() {
  const rawPriv = fromB64url(VAPID_PRIVATE_KEY);
  const rawPub  = fromB64url(VAPID_PUBLIC_KEY);

  /* Build a JWK from the raw bytes */
  const x   = rawPub.slice(1, 33);
  const y   = rawPub.slice(33, 65);
  const jwk = {
    kty: "EC", crv: "P-256",
    x: b64url(x), y: b64url(y),
    d: b64url(rawPriv),
  };

  const privateKey = await crypto.subtle.importKey(
    "jwk", jwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false, ["sign"]
  );
  return { privateKey, rawPub };
}

async function makeVapidAuthHeader(endpoint: string, privateKey: CryptoKey, rawPub: Uint8Array): Promise<string> {
  const url     = new URL(endpoint);
  const aud     = `${url.protocol}//${url.host}`;
  const exp     = Math.floor(Date.now() / 1000) + 12 * 3600;

  const header  = b64url(new TextEncoder().encode(JSON.stringify({ typ: "JWT", alg: "ES256" })));
  const payload = b64url(new TextEncoder().encode(JSON.stringify({ aud, exp, sub: VAPID_EMAIL })));
  const signed  = `${header}.${payload}`;

  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(signed)
  );

  const jwt = `${signed}.${b64url(sig)}`;
  return `vapid t=${jwt},k=${VAPID_PUBLIC_KEY}`;
}

/* ── Encrypt the push message (RFC 8291 / ece) ───────────────────── */
async function encryptPayload(
  plaintext: string,
  p256dhB64: string,
  authB64:   string
): Promise<{ ciphertext: Uint8Array; salt: Uint8Array; serverPubRaw: Uint8Array }> {
  const enc      = new TextEncoder();
  const subPub   = fromB64url(p256dhB64);    // client public key (65 bytes)
  const authSec  = fromB64url(authB64);       // auth secret (16 bytes)

  /* Generate ephemeral server EC key pair */
  const serverKP = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]
  );
  const serverPubRaw = new Uint8Array(
    await crypto.subtle.exportKey("raw", serverKP.publicKey)
  );

  /* Import client public key */
  const clientPub = await crypto.subtle.importKey(
    "raw", subPub, { name: "ECDH", namedCurve: "P-256" }, false, []
  );

  /* ECDH shared secret */
  const ecdhBits = await crypto.subtle.deriveBits(
    { name: "ECDH", public: clientPub }, serverKP.privateKey, 256
  );

  const salt = crypto.getRandomValues(new Uint8Array(16));

  /* HKDF extract + expand (RFC 8291) */
  async function hkdf(ikm: BufferSource, salt2: BufferSource, info: Uint8Array, length: number): Promise<Uint8Array> {
    const key  = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: salt2, info }, key, length * 8);
    return new Uint8Array(bits);
  }

  const prk = await hkdf(ecdhBits, authSec, enc.encode("Content-Encoding: auth\0"), 32);

  const context = new Uint8Array([
    ...enc.encode("P-256\0"),
    0, 65, ...subPub,
    0, 65, ...serverPubRaw,
  ]);

  const cek   = await hkdf(prk, salt, new Uint8Array([...enc.encode("Content-Encoding: aesgcm\0"), ...context]), 16);
  const nonce = await hkdf(prk, salt, new Uint8Array([...enc.encode("Content-Encoding: nonce\0"), ...context]), 12);

  /* AES-128-GCM encrypt */
  const aesKey = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, ["encrypt"]);
  const plainBuf = new Uint8Array([0, 0, ...enc.encode(plaintext)]); // 2-byte padding prefix
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, aesKey, plainBuf);

  return { ciphertext: new Uint8Array(encrypted), salt, serverPubRaw };
}

/* ── Send one push notification ──────────────────────────────────── */
async function sendPush(
  sub: { endpoint: string; p256dh: string; auth: string },
  payloadJson: string,
  privateKey: CryptoKey,
  rawPub: Uint8Array
): Promise<{ ok: boolean; status?: number; error?: string }> {
  try {
    const { ciphertext, salt, serverPubRaw } = await encryptPayload(
      payloadJson, sub.p256dh, sub.auth
    );

    const vapidHeader = await makeVapidAuthHeader(sub.endpoint, privateKey, rawPub);

    const res = await fetch(sub.endpoint, {
      method:  "POST",
      headers: {
        "Authorization":     vapidHeader,
        "Content-Type":      "application/octet-stream",
        "Content-Encoding":  "aesgcm",
        "Encryption":        `salt=${b64url(salt)}`,
        "Crypto-Key":        `dh=${b64url(serverPubRaw)}`,
        "TTL":               "86400",
        "Content-Length":    String(ciphertext.byteLength),
      },
      body: ciphertext,
    });

    return { ok: res.ok, status: res.status };
  } catch (err) {
    return { ok: false, error: String(err) };
  }
}

/* ── Main handler ────────────────────────────────────────────────── */
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS });
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405, headers: CORS });
  }

  /* Validate VAPID private key is configured */
  if (!VAPID_PRIVATE_KEY) {
    return new Response(
      JSON.stringify({ error: "VAPID_PRIVATE_KEY secret is not set. Run: supabase secrets set VAPID_PRIVATE_KEY=..." }),
      { status: 500, headers: { ...CORS, "Content-Type": "application/json" } }
    );
  }

  let body: { text?: string; label?: string; studentCall?: Record<string, string> } = {};
  try { body = await req.json(); } catch (_) { /* empty body is fine */ }

  /* Supabase client with service role key (set automatically by runtime) */
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey  = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const db = createClient(supabaseUrl, serviceKey);

  /* Fetch all push subscriptions */
  const { data: subs, error: dbErr } = await db
    .from("push_subscriptions")
    .select("endpoint, p256dh, auth");

  if (dbErr) {
    return new Response(
      JSON.stringify({ error: dbErr.message }),
      { status: 500, headers: { ...CORS, "Content-Type": "application/json" } }
    );
  }

  if (!subs || subs.length === 0) {
    return new Response(
      JSON.stringify({ sent: 0, total: 0, message: "No subscriptions registered yet." }),
      { status: 200, headers: { ...CORS, "Content-Type": "application/json" } }
    );
  }

  /* Build notification payload */
  const notifTitle = body.studentCall
    ? `📢 ${body.label || "Student Called"}`
    : `📢 ${body.label || "SIBAQ Announcement"}`;

  const notifBody = body.studentCall
    ? `${body.studentCall.name} — ${body.studentCall.program} (${body.studentCall.programCode})`
    : (body.text || "New announcement from the Control Room.");

  const payloadJson = JSON.stringify({
    title: notifTitle,
    body:  notifBody,
    tag:   "sibaq-announcement",
  });

  /* Import VAPID keys once */
  const { privateKey, rawPub } = await importVapidKeys();

  /* Send to all subscribers in parallel */
  const results = await Promise.all(
    subs.map((sub: { endpoint: string; p256dh: string; auth: string }) =>
      sendPush(sub, payloadJson, privateKey, rawPub)
    )
  );

  /* Clean up expired subscriptions (410 Gone / 404 Not Found) */
  const expired = subs
    .filter((_: any, i: number) => results[i].status === 410 || results[i].status === 404)
    .map((s: any) => s.endpoint);

  if (expired.length > 0) {
    await db.from("push_subscriptions").delete().in("endpoint", expired);
  }

  const sent   = results.filter((r: any) => r.ok).length;
  const failed = results.filter((r: any) => !r.ok).length;

  return new Response(
    JSON.stringify({ sent, failed, total: subs.length, expired: expired.length }),
    { status: 200, headers: { ...CORS, "Content-Type": "application/json" } }
  );
});
