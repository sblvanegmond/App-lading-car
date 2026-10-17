/**
 * The connection to Claude.
 *
 * The app talks to the Anthropic API straight from the phone, with the
 * user's own API key, through the official SDK. The SDK is bundled into
 * vendor/anthropic-sdk.js at build time (coach/tools/build-sdk.js); when that
 * file is missing, for instance when running from a plain checkout, it is
 * loaded from a CDN at the same pinned version.
 */

import { requestOptions } from './coach.js';
import { KINDS } from './nutrition.js';

export const SDK_VERSION = '0.130.0';
const SDK_SOURCES = [
  new URL('../vendor/anthropic-sdk.js', import.meta.url).href,
  `https://cdn.jsdelivr.net/npm/@anthropic-ai/sdk@${SDK_VERSION}/+esm`,
];

let sdkPromise = null;

export function loadSdk() {
  sdkPromise ??= (async () => {
    let lastError;
    for (const source of SDK_SOURCES) {
      try {
        return await import(source);
      } catch (err) {
        lastError = err;
      }
    }
    sdkPromise = null;
    throw new Error(`De Claude-bibliotheek kon niet geladen worden (${lastError?.message ?? 'onbekend'}).`);
  })();
  return sdkPromise;
}

export async function createClient(apiKey) {
  const key = String(apiKey ?? '').trim();
  if (!key) throw new Error('Vul bij Instellingen je Claude API-sleutel in.');
  const sdk = await loadSdk();
  const Anthropic = sdk.default ?? sdk.Anthropic;
  // The key belongs to the user and never leaves their own device except to
  // the Anthropic API itself, which is the case the SDK flag is about.
  const client = new Anthropic({ apiKey: key, dangerouslyAllowBrowser: true, maxRetries: 2 });
  return {
    client,
    sdk,
    isApiError: (err) => err instanceof sdk.APIError,
    isBadRequest: (err) => err instanceof sdk.BadRequestError,
  };
}

export function describeError(err, sdk) {
  if (!err) return 'Onbekende fout.';
  if (err.name === 'AbortError' || (sdk && err instanceof sdk.APIUserAbortError)) return 'Gestopt.';
  if (sdk) {
    if (err instanceof sdk.AuthenticationError) return 'Claude weigert de API-sleutel. Controleer hem bij Instellingen.';
    if (err instanceof sdk.PermissionDeniedError) return 'Deze API-sleutel heeft geen toegang tot dit model.';
    if (err instanceof sdk.NotFoundError) return 'Dit model is niet beschikbaar voor je API-sleutel. Kies een ander model bij Instellingen.';
    if (err instanceof sdk.RateLimitError) return 'Even te veel verzoeken voor je API-account. Probeer het over een minuut opnieuw.';
    if (err instanceof sdk.BadRequestError) return `Claude kon het verzoek niet verwerken: ${err.message}`;
    if (err instanceof sdk.InternalServerError) return 'Claude is even overbelast of heeft een storing. Probeer het zo opnieuw.';
    if (err instanceof sdk.APIConnectionError) return 'Geen verbinding met Claude. Ben je online?';
  }
  return err.message || String(err);
}

/* ------------------------------------------------------------------ */
/* Estimating a meal                                                   */
/* ------------------------------------------------------------------ */

const NUMBER = { type: 'number' };

export const ESTIMATE_SCHEMA = {
  type: 'object',
  properties: {
    description: { type: 'string', description: 'Korte omschrijving met hoeveelheden, in het Nederlands' },
    kind: { type: 'string', enum: Object.keys(KINDS) },
    items: {
      type: 'array',
      items: {
        type: 'object',
        properties: { name: { type: 'string' }, amount: { type: 'string' }, kcal: NUMBER },
        required: ['name', 'amount', 'kcal'],
        additionalProperties: false,
      },
    },
    kcal: NUMBER,
    proteinG: NUMBER,
    carbsG: NUMBER,
    fatG: NUMBER,
    fluidMl: NUMBER,
    alcoholUnits: NUMBER,
    caffeineMg: NUMBER,
    confidence: { type: 'string', enum: ['laag', 'redelijk', 'hoog'] },
    note: { type: 'string', description: 'Eén korte zin over een aanname, of leeg' },
  },
  required: [
    'description',
    'kind',
    'items',
    'kcal',
    'proteinG',
    'carbsG',
    'fatG',
    'fluidMl',
    'alcoholUnits',
    'caffeineMg',
    'confidence',
    'note',
  ],
  additionalProperties: false,
};

const ESTIMATE_SYSTEM = `Je schat voedingswaarden voor een persoonlijk voedingslogboek.
- Ga uit van gangbare Nederlandse porties als de hoeveelheid ontbreekt (een boterham is 35 g, een plak kaas 20 g, een glas 250 ml, een kop koffie 150 ml).
- Geef totalen voor alles samen. fluidMl is het vocht uit drinken, soep, yoghurt en dergelijke; 0 als er niets bij zit. alcoholUnits in standaardglazen (10 g alcohol). caffeineMg 0 als er geen cafeïne in zit.
- Bij een foto: schat de porties op wat je ziet en noem een belangrijke aanname in note.
- Rond kcal af op tientallen en grammen op hele getallen. Wees eerlijk over onzekerheid via confidence.`;

/**
 * Turns "twee boterhammen met kaas en een appel" (and/or a photo) into an
 * entry with numbers, using structured outputs so the answer is always JSON.
 */
export async function estimateIntake({ client, model, text, image = null, time = '' }) {
  const content = [];
  if (image) content.push({ type: 'image', source: { type: 'base64', media_type: image.mediaType, data: image.data } });
  content.push({ type: 'text', text: `${text ? `Wat: ${text}` : 'Wat staat er op de foto?'}${time ? `\nTijd: ${time}` : ''}` });

  const options = requestOptions(model, { effort: 'low', updates: false });
  const response = await client.beta.messages.create({
    model,
    max_tokens: 8000,
    system: ESTIMATE_SYSTEM,
    messages: [{ role: 'user', content }],
    ...options,
    output_config: { ...options.output_config, format: { type: 'json_schema', schema: ESTIMATE_SCHEMA } },
  });

  if (response.stop_reason === 'refusal') throw new Error('Claude wilde hier geen schatting van maken.');
  if (response.stop_reason === 'max_tokens') throw new Error('De schatting werd afgebroken. Probeer een kortere omschrijving.');
  const block = response.content.find((b) => b.type === 'text');
  try {
    return JSON.parse(block?.text ?? '');
  } catch {
    throw new Error('De schatting kwam niet goed terug. Probeer het nog eens.');
  }
}

/** Phone photos are huge; Claude needs ~1500 px at most. Returns base64 JPEG. */
export async function prepareImage(file, { maxSide = 1568, quality = 0.82 } = {}) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const width = Math.round(bitmap.width * scale);
  const height = Math.round(bitmap.height * scale);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.getContext('2d').drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();
  const dataUrl = canvas.toDataURL('image/jpeg', quality);
  return { mediaType: 'image/jpeg', data: dataUrl.slice(dataUrl.indexOf(',') + 1), preview: dataUrl };
}
