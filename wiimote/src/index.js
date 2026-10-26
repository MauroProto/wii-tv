import { DurableObject } from "cloudflare:workers";

// Made up by the TV: the room and the pairing code go in its QR code, the key never leaves it.
const ROOM = /^[a-z2-7]{16}$/;
const PAIR = /^[a-z2-7]{16}$/;
const KEY = /^[0-9a-f]{32}$/;
const TOKEN = /^[0-9a-f]{32}$/;
const BUTTONS = new Set(["a", "b", "plus", "minus", "home", "up", "down", "left", "right", "one", "two"]);
// Four remotes, as on the console; each gets the light of its player number.
const MAX_PHONES = 4;
// Phones a TV remembers; the one paired longest ago is forgotten first.
const MAX_TOKENS = 16;
// A pairing code works only while the TV shows it, and never for longer than this: a QR code seen in a video or a
// photo must not let anyone in later.
const PAIR_MS = 10 * 60_000;
const OPEN = 1;
// Both ends ping every 20 to 25 s; one silent this long has gone without closing (a phone that lost its network).
const STALE_MS = 70_000;

/**
 * One TV and up to four phones. A phone gets in with the pairing code from the TV's QR code, while the TV shows it,
 * and is then given a token of its own for the next times. Phones send JSON; the TV gets short lines it can read
 * without a JSON parser:
 *   m <dx> <dy>       the hand moved, in px of a 1920-wide screen
 *   b <button> <0|1>  a button went up or down
 *   t <0|1> <text>    text to type; 1 presses Enter after it
 *   p <count>         how many phones are connected
 * The TV answers "r" (a rumble, when the hand lands on something) and "k 0|1" (whether typing goes anywhere).
 */
export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Keep-alives are answered without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const q = new URL(request.url).searchParams;
    const tv = q.get("role") === "tv";
    const saved = await this.ctx.storage.get(["key", "pair", "pairAt", "tokens"]);
    for (const w of this.ctx.getWebSockets()) if (this.stale(w)) w.close(4001, "stale");
    const phones = this.open("phone");
    let token = null, player = 0, pairing = false;
    if (tv) {
      const key = q.get("key") ?? "";
      if (!KEY.test(key)) return new Response("forbidden", { status: 403 });
      if (saved.get("key") === undefined) await this.ctx.storage.put("key", key);
      else if (saved.get("key") !== key) return new Response("forbidden", { status: 403 });
      const pair = q.get("pair") ?? "";
      pairing = PAIR.test(pair);
      if (pairing) await this.ctx.storage.put({ pair, pairAt: Date.now() });
      else await this.ctx.storage.delete(["pair", "pairAt"]);
    } else {
      const tokens = saved.get("tokens") ?? [];
      const asked = q.get("token") ?? "";
      const pair = q.get("pair") ?? "";
      const known = TOKEN.test(asked) && tokens.includes(asked);
      const paired = !known && PAIR.test(pair) && saved.get("pair") === pair
        && Date.now() - (saved.get("pairAt") ?? 0) < PAIR_MS;
      if (!known && !paired) return refuse("pair");
      const taken = new Set(phones.map((w) => w.deserializeAttachment()?.player));
      for (let p = 1; p <= MAX_PHONES && !player; p++) if (!taken.has(p)) player = p;
      if (!player) return refuse("full");
      token = known ? asked : hex(16);
      if (paired) await this.ctx.storage.put("tokens", [...tokens, token].slice(-MAX_TOKENS));
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [tv ? "tv" : "phone"]);
    server.serializeAttachment(tv ? { tv: true, kb: false, pairing, since: Date.now() } : { player, since: Date.now() });
    if (tv) {
      for (const old of this.open("tv")) if (old !== server) old.close(4000, "replaced");
      server.send(`p ${phones.length}`);
      this.toPhones({ tv: true, kb: false });
    } else {
      const tvs = this.open("tv");
      const kb = tvs.some((w) => w.deserializeAttachment()?.kb);
      server.send(JSON.stringify({ tv: tvs.length > 0, kb, player, token }));
      this.toTv(`p ${phones.length + 1}`);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  open(tag, except) {
    return this.ctx.getWebSockets(tag).filter((w) => w !== except && w.readyState === OPEN && !this.stale(w));
  }

  stale(w) {
    const pinged = this.ctx.getWebSocketAutoResponseTimestamp(w);
    const last = Math.max(pinged ? pinged.getTime() : 0, w.deserializeAttachment()?.since ?? 0);
    return Date.now() - last > STALE_MS;
  }

  toTv(line) {
    for (const w of this.open("tv")) {
      try {
        w.send(line);
      } catch {}
    }
  }

  toPhones(message) {
    const s = JSON.stringify(message);
    for (const w of this.open("phone")) {
      try {
        w.send(s);
      } catch {}
    }
  }

  webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 2048) return;
    const at = ws.deserializeAttachment() ?? {};
    if (at.tv) {
      if (message === "r") {
        this.toPhones({ r: 1 });
      } else if (message === "k 0" || message === "k 1") {
        const kb = message === "k 1";
        ws.serializeAttachment({ ...at, kb });
        this.toPhones({ kb });
      }
      return;
    }
    let m;
    try {
      m = JSON.parse(message);
    } catch {
      return;
    }
    if (!m || typeof m !== "object") return;
    if (Array.isArray(m.m) && m.m.length === 2) {
      const [x, y] = m.m.map(Number);
      if (Number.isFinite(x) && Number.isFinite(y)) this.toTv(`m ${clamp(x)} ${clamp(y)}`);
    } else if (typeof m.b === "string" && BUTTONS.has(m.b)) {
      this.toTv(`b ${m.b} ${m.d ? 1 : 0}`);
    } else if (typeof m.t === "string") {
      // One line: the TV reads a message as a single line, and a page's input would take a newline as Enter.
      const t = m.t.replace(/[\u0000-\u001f\u007f\u2028\u2029]+/g, " ").slice(0, 500);
      if (t || m.e) this.toTv(`t ${m.e ? 1 : 0} ${t}`);
    }
  }

  async webSocketClose(ws) {
    // Answers the close, or the other end waits for it until it gives up.
    try {
      ws.close();
    } catch {}
    await this.gone(ws);
  }

  async webSocketError(ws) {
    await this.gone(ws);
  }

  async gone(ws) {
    const at = ws.deserializeAttachment() ?? {};
    if (at.tv) {
      // A TV replaced by its own new connection leaves the phones, and the pairing, to the new one.
      if (this.open("tv", ws).length) return;
      this.toPhones({ tv: false, kb: false });
      if (at.pairing) await this.ctx.storage.delete(["pair", "pairAt"]);
    } else {
      this.toTv(`p ${this.open("phone", ws).length}`);
    }
  }
}

/** Opens the socket only to say why the phone cannot come in, which a refused upgrade would not tell it. */
function refuse(reason) {
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();
  server.send(JSON.stringify({ error: reason }));
  server.close(4003, reason);
  return new Response(null, { status: 101, webSocket: client });
}

function hex(bytes) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function clamp(v) {
  return (Math.round(Math.max(-2000, Math.min(2000, v)) * 10) / 10).toString();
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const room = url.pathname.match(/^\/ws\/([^/]+)$/);
    if (room) {
      if (!ROOM.test(room[1])) return new Response("not found", { status: 404 });
      if (request.headers.get("Upgrade") !== "websocket") return new Response("websocket only", { status: 426 });
      return env.ROOMS.get(env.ROOMS.idFromName(room[1])).fetch(request);
    }
    return env.ASSETS.fetch(request);
  },
};
