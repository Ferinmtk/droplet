// IndexedDB: this device's key, the computers it's paired with, messages and received files.
// Everything stays on this device.

const DB = "droplet";
const VERSION = 1;
let dbp = null;

function db() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB, VERSION);
      req.onupgradeneeded = () => {
        const d = req.result;
        d.createObjectStore("kv");
        d.createObjectStore("peers", { keyPath: "fp" });
        const m = d.createObjectStore("messages", { keyPath: "id" });
        m.createIndex("fp", "fp");
        const f = d.createObjectStore("files", { keyPath: "id" });
        f.createIndex("fp", "fp");
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbp;
}

function wrap(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function store(name, mode = "readonly") {
  return (await db()).transaction(name, mode).objectStore(name);
}

export const kv = {
  get: async (k) => wrap((await store("kv")).get(k)),
  set: async (k, v) => wrap((await store("kv", "readwrite")).put(v, k)),
};

export const peers = {
  all: async () => wrap((await store("peers")).getAll()),
  get: async (fp) => wrap((await store("peers")).get(fp)),
  put: async (p) => wrap((await store("peers", "readwrite")).put(p)),
  remove: async (fp) => wrap((await store("peers", "readwrite")).delete(fp)),
};

export const messages = {
  forPeer: async (fp) => {
    const all = await wrap((await store("messages")).index("fp").getAll(fp));
    return all.sort((a, b) => a.ts - b.ts);
  },
  get: async (id) => wrap((await store("messages")).get(id)),
  put: async (m) => wrap((await store("messages", "readwrite")).put(m)),
};

export const files = {
  forPeer: async (fp) => {
    const all = await wrap((await store("files")).index("fp").getAll(fp));
    return all.sort((a, b) => b.ts - a.ts);
  },
  get: async (id) => wrap((await store("files")).get(id)),
  put: async (f) => wrap((await store("files", "readwrite")).put(f)),
  remove: async (id) => wrap((await store("files", "readwrite")).delete(id)),
};
