/**
 * Decrypting the data the sync job publishes.
 *
 * Mirror of coach/sync/crypto_box.py. The envelope is plain JSON:
 *   { format: 'coach-enc', v: 1, t, kdf: 'PBKDF2-SHA256', iter, salt, iv, z: 'gzip', ct }
 * with ct = AES-256-GCM(gzip(JSON)), key = PBKDF2-SHA256(passphrase, salt, iter).
 *
 * Works the same in the browser and in Node (tests), as both have WebCrypto,
 * DecompressionStream and atob/btoa.
 */

export const FORMAT = 'coach-enc';
export const VERSION = 1;
export const ITERATIONS = 600000;
export const MIN_KEY_LENGTH = 16;

export class CryptoError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'CryptoError';
  }
}

export function normaliseKey(passphrase) {
  const key = String(passphrase ?? '').trim();
  if (key.length < MIN_KEY_LENGTH) {
    throw new CryptoError(`De synchronisatiesleutel moet minstens ${MIN_KEY_LENGTH} tekens lang zijn.`);
  }
  return key;
}

function fromBase64(text) {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function toBase64(bytes) {
  let raw = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    raw += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(raw);
}

async function transform(bytes, stream) {
  const piped = new Blob([bytes]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(piped).arrayBuffer());
}

async function deriveKey(passphrase, salt, iterations, usage) {
  const subtle = globalThis.crypto.subtle;
  const base = await subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations, hash: 'SHA-256' },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    [usage],
  );
}

export function isEnvelope(value) {
  return Boolean(value && typeof value === 'object' && value.format === FORMAT);
}

export async function decryptEnvelope(envelope, passphrase) {
  const key = normaliseKey(passphrase);
  if (!isEnvelope(envelope)) throw new CryptoError('Dit is geen versleuteld coachbestand.');
  if (envelope.v !== VERSION) throw new CryptoError(`Onbekende versie ${envelope.v} van het databestand.`);

  let plain;
  try {
    const aes = await deriveKey(key, fromBase64(envelope.salt), Number(envelope.iter), 'decrypt');
    plain = new Uint8Array(
      await globalThis.crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(envelope.iv) }, aes, fromBase64(envelope.ct)),
    );
  } catch (err) {
    throw new CryptoError('Ontsleutelen mislukt. Klopt de synchronisatiesleutel?', { cause: err });
  }
  if (envelope.z === 'gzip') plain = await transform(plain, new DecompressionStream('gzip'));
  return JSON.parse(new TextDecoder().decode(plain));
}

/** The app only decrypts; encrypting exists for tests and parity with Python. */
export async function encryptEnvelope(value, passphrase, { iterations = ITERATIONS, now = new Date() } = {}) {
  const key = normaliseKey(passphrase);
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(16));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const packed = await transform(new TextEncoder().encode(JSON.stringify(value)), new CompressionStream('gzip'));
  const aes = await deriveKey(key, salt, iterations, 'encrypt');
  const ct = new Uint8Array(await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aes, packed));
  return {
    format: FORMAT,
    v: VERSION,
    t: now.toISOString().replace(/\.\d{3}Z$/, 'Z'),
    kdf: 'PBKDF2-SHA256',
    iter: iterations,
    salt: toBase64(salt),
    iv: toBase64(iv),
    z: 'gzip',
    ct: toBase64(ct),
  };
}

/** A random key for the user to put in GitHub: 24 url-safe characters, 144 bits. */
export function generateKey() {
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(18));
  return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_');
}
