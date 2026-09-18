/**
 * Web push subscription.
 *
 * The app itself cannot send a push message; something has to be awake at
 * the right moment. Here the sender is `tools/notify.js`, running as a
 * scheduled GitHub Action. This module only takes care of the browser half:
 * asking permission and producing the subscription that the sender needs.
 */

/**
 * The push API wants the VAPID key as raw bytes, while it is published as
 * base64url text.
 */
export function urlBase64ToUint8Array(base64String) {
  const trimmed = String(base64String ?? '').trim();
  if (!trimmed) throw new Error('Geen publieke sleutel ingevuld.');
  const padding = '='.repeat((4 - (trimmed.length % 4)) % 4);
  const base64 = (trimmed + padding).replace(/-/g, '+').replace(/_/g, '/');
  let raw;
  try {
    raw = atob(base64);
  } catch {
    throw new Error('De publieke sleutel is geen geldige base64url-tekst.');
  }
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
  if (bytes.length !== 65 || bytes[0] !== 4) {
    throw new Error('Deze sleutel heeft niet de vorm van een VAPID-sleutel (65 bytes, begint met 0x04).');
  }
  return bytes;
}

/** What this device can and cannot do, in plain terms. */
export function pushSupport(nav = globalThis.navigator, win = globalThis) {
  const hasServiceWorker = Boolean(nav && 'serviceWorker' in nav);
  const hasPushManager = Boolean(win && 'PushManager' in win);
  const hasNotification = Boolean(win && 'Notification' in win);
  const standalone =
    Boolean(nav?.standalone) ||
    Boolean(win?.matchMedia?.('(display-mode: standalone)')?.matches);
  const iOSLike = /iPad|iPhone|iPod/.test(nav?.userAgent ?? '') ||
    (nav?.platform === 'MacIntel' && (nav?.maxTouchPoints ?? 0) > 1);

  let reason = null;
  if (!hasServiceWorker || !hasPushManager || !hasNotification) {
    reason = iOSLike && !standalone
      ? 'Zet de app eerst op je beginscherm. Safari staat pushmeldingen alleen toe voor een geïnstalleerde app.'
      : 'Deze browser ondersteunt geen pushmeldingen. Gebruik de agenda-knop.';
  } else if (iOSLike && !standalone) {
    reason = 'Zet de app eerst op je beginscherm, anders weigert iOS de aanmelding.';
  }

  return { supported: reason === null, standalone, iOSLike, reason };
}


/**
 * `navigator.serviceWorker.ready` never resolves when no service worker is
 * registered, which is exactly what happens over plain http on a local
 * network. Waiting on it forever would stall the rest of the app, so every
 * caller here goes through this bounded wait.
 */
export async function readyRegistration(timeoutMs = 4000) {
  if (!('serviceWorker' in navigator)) return null;
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve(null), timeoutMs);
  });
  try {
    return await Promise.race([navigator.serviceWorker.ready, timeout]);
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Ask permission and register with the browser's push service.
 *
 * @returns {Promise<object>} the subscription, as the sender needs it
 */
export async function subscribeToPush(vapidPublicKey) {
  const support = pushSupport();
  if (!support.supported) throw new Error(support.reason);

  const applicationServerKey = urlBase64ToUint8Array(vapidPublicKey);

  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    throw new Error('Meldingen zijn geweigerd. Zet ze aan in de instellingen van je telefoon.');
  }

  const registration = await readyRegistration();
  if (!registration) {
    throw new Error(
      'De service worker draait niet. Open de app via https of via localhost, ' +
        'niet via een gewoon http-adres in je netwerk.',
    );
  }
  const existing = await registration.pushManager.getSubscription();
  if (existing) {
    // A subscription made with a different key can never be delivered to.
    if (sameKey(existing.options?.applicationServerKey, applicationServerKey)) {
      return existing.toJSON();
    }
    await existing.unsubscribe();
  }

  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey,
  });
  return subscription.toJSON();
}

function sameKey(a, b) {
  if (!a || !b) return false;
  const left = new Uint8Array(a);
  if (left.length !== b.length) return false;
  return left.every((byte, i) => byte === b[i]);
}

export async function unsubscribeFromPush() {
  const registration = await readyRegistration();
  const existing = await registration?.pushManager?.getSubscription();
  if (!existing) return false;
  return existing.unsubscribe();
}

export async function currentSubscription() {
  if (!('PushManager' in globalThis)) return null;
  const registration = await readyRegistration();
  const existing = await registration?.pushManager?.getSubscription();
  return existing ? existing.toJSON() : null;
}
