/**
 * Key-value storage for everything bigger than the settings.
 *
 * IndexedDB in the browser (no 5 MB localStorage ceiling, which a few meal
 * photos in a conversation would hit), an in-memory map in tests. Values are
 * structured-cloned, so plain objects only.
 */

export function memoryBackend() {
  const map = new Map();
  return {
    async get(key) {
      return map.has(key) ? structuredClone(map.get(key)) : undefined;
    },
    async set(key, value) {
      map.set(key, structuredClone(value));
    },
    async delete(key) {
      map.delete(key);
    },
    async keys() {
      return [...map.keys()];
    },
  };
}

function request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export function indexedDbBackend(name = 'coach', storeName = 'kv') {
  let dbPromise;
  const db = () => {
    dbPromise ??= new Promise((resolve, reject) => {
      const open = indexedDB.open(name, 1);
      open.onupgradeneeded = () => open.result.createObjectStore(storeName);
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    return dbPromise;
  };
  const run = async (mode, fn) => {
    const tx = (await db()).transaction(storeName, mode);
    return request(fn(tx.objectStore(storeName)));
  };
  return {
    get: (key) => run('readonly', (s) => s.get(key)),
    set: (key, value) => run('readwrite', (s) => s.put(value, key)).then(() => undefined),
    delete: (key) => run('readwrite', (s) => s.delete(key)).then(() => undefined),
    keys: () => run('readonly', (s) => s.getAllKeys()),
  };
}

/** IndexedDB when it works, memory otherwise (private mode in some browsers). */
export async function openStore() {
  if (typeof indexedDB === 'undefined') return { backend: memoryBackend(), persistent: false };
  const backend = indexedDbBackend();
  try {
    await backend.get('__probe__');
    // Ask the browser not to evict the food log under storage pressure.
    globalThis.navigator?.storage?.persist?.().catch(() => {});
    return { backend, persistent: true };
  } catch {
    return { backend: memoryBackend(), persistent: false };
  }
}

export const KEYS = {
  bundle: 'bundle',
  nutrition: 'nutrition',
  conversation: 'conversation',
  memory: 'memory',
};
