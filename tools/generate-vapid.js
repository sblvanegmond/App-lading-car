#!/usr/bin/env node
/**
 * Generate a VAPID key pair for web push.
 *
 *   npm run vapid
 *
 * The public key goes into the app (Instellingen, "Publieke VAPID-sleutel").
 * The private key goes into GitHub as the secret VAPID_PRIVATE_KEY and must
 * never be committed: anyone holding it can send notifications in your name.
 *
 * VAPID keys are a plain P-256 key pair. The public half is the uncompressed
 * point (0x04 ‖ X ‖ Y) and the private half is the 32-byte scalar, both in
 * base64url.
 */

import { generateKeyPairSync } from 'node:crypto';

function fromBase64Url(value) {
  return Buffer.from(value, 'base64url');
}

function toBase64Url(buffer) {
  return Buffer.from(buffer).toString('base64url');
}

export function generateVapidKeys() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pub = publicKey.export({ format: 'jwk' });
  const priv = privateKey.export({ format: 'jwk' });

  const x = fromBase64Url(pub.x);
  const y = fromBase64Url(pub.y);
  const d = fromBase64Url(priv.d);
  if (x.length !== 32 || y.length !== 32 || d.length !== 32) {
    throw new Error('Onverwachte sleutellengte; genereer opnieuw.');
  }

  return {
    publicKey: toBase64Url(Buffer.concat([Buffer.from([4]), x, y])),
    privateKey: toBase64Url(d),
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const keys = generateVapidKeys();
  console.log('');
  console.log('Publieke sleutel (in de app invullen bij "Pushmelding instellen"):');
  console.log(`  ${keys.publicKey}`);
  console.log('');
  console.log('Privésleutel (GitHub secret VAPID_PRIVATE_KEY, nooit committen):');
  console.log(`  ${keys.privateKey}`);
  console.log('');
  console.log('Zet in GitHub ook het secret VAPID_SUBJECT, bijvoorbeeld:');
  console.log('  mailto:jij@voorbeeld.nl');
  console.log('');
}
