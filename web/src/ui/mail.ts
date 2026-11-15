import type { Account, Credentials, LetterDraft, MailError, Mailbag, SentLetter, SignedIn } from "../../../protocol/ts/protocol";

/** This browser's session token for letters; apart from the admin's password, which is a different login. */
const TOKEN = "lilguy-mail-token";
/** The name the last letter was signed with, offered for the next. */
const SIGNED = "lilguy-mail-from";

/**
 * Letters to him: anyone with an account (an email and a password, `host/.../Accounts.kt`) may write
 * one, which the host puts through his mail hatch. A panel of DOM over the room like the admin's
 * password box, opened from the "Write to him" button: signed out it signs in or makes an account,
 * signed in it writes a letter and lists the ones you sent, with whether he has opened them. It has
 * the keys while it's open; Escape closes it.
 */
export class Mail {
  private readonly button = el<HTMLButtonElement>("mail-open");
  private readonly panel = el<HTMLElement>("mail");
  private readonly auth = el<HTMLFormElement>("mail-auth");
  private readonly email = el<HTMLInputElement>("mail-email");
  private readonly password = el<HTMLInputElement>("mail-password");
  private readonly authNote = el<HTMLElement>("mail-auth-note");
  private readonly write = el<HTMLFormElement>("mail-write");
  private readonly who = el<HTMLElement>("mail-who");
  private readonly from = el<HTMLInputElement>("mail-from");
  private readonly body = el<HTMLTextAreaElement>("mail-body");
  private readonly writeNote = el<HTMLElement>("mail-write-note");
  private readonly sent = el<HTMLOListElement>("mail-sent");
  private token = "";
  private signingUp = false;
  private poll = 0;

  constructor(private readonly life: string, broadcast: boolean) {
    this.token = load(TOKEN);
    this.from.value = load(SIGNED);
    if (broadcast) return;
    this.button.hidden = false;
    this.button.addEventListener("click", () => this.show());
    el("mail-close").addEventListener("click", () => this.close());
    el("mail-switch").addEventListener("click", () => this.mode(!this.signingUp));
    el("mail-signout").addEventListener("click", () => void this.signOut());
    this.auth.addEventListener("submit", (e) => {
      e.preventDefault();
      void this.signIn();
    });
    this.write.addEventListener("submit", (e) => {
      e.preventDefault();
      void this.send();
    });
    this.body.addEventListener("input", () => this.count());
    // The room's keys stay out of the panel; Escape closes it.
    this.panel.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        this.close();
      }
      e.stopPropagation();
    });
  }

  get open(): boolean {
    return !this.panel.hidden;
  }

  async show() {
    this.panel.hidden = false;
    this.button.hidden = true;
    if (this.token && (await this.account())) return this.signedIn();
    this.signedOut();
  }

  close() {
    this.panel.hidden = true;
    this.button.hidden = false;
    window.clearInterval(this.poll);
    (document.activeElement as HTMLElement | null)?.blur();
  }

  private signedOut() {
    this.write.hidden = true;
    this.auth.hidden = false;
    this.mode(this.signingUp);
    this.email.focus();
  }

  private async signedIn() {
    this.auth.hidden = true;
    this.write.hidden = false;
    this.writeNote.textContent = "";
    await this.letters();
    (this.from.value ? this.body : this.from).focus();
    // Whether he has opened them changes while the panel is up.
    window.clearInterval(this.poll);
    this.poll = window.setInterval(() => document.hidden || void this.letters(), POLL_MS);
  }

  private mode(signingUp: boolean) {
    this.signingUp = signingUp;
    el("mail-auth-title").textContent = signingUp ? "MAKE AN ACCOUNT TO WRITE TO HIM" : "SIGN IN TO WRITE TO HIM";
    el("mail-auth-go").textContent = signingUp ? "Make account" : "Sign in";
    el("mail-switch").textContent = signingUp ? "I have an account" : "Make an account";
    this.password.autocomplete = signingUp ? "new-password" : "current-password";
    this.authNote.textContent = signingUp ? "An email and a password of 8 or more characters. He never sees your email." : "";
  }

  private async signIn() {
    const c: Credentials = { email: this.email.value.trim(), password: this.password.value };
    if (!c.email || !c.password) return;
    this.authNote.textContent = this.signingUp ? "making your account..." : "signing in...";
    const r = await call<SignedIn>(this.signingUp ? "/v1/accounts" : "/v1/sessions", "POST", "", c);
    if (!r.ok) {
      this.authNote.textContent = r.error;
      return;
    }
    this.token = r.value.token;
    save(TOKEN, this.token);
    this.password.value = "";
    this.who.textContent = r.value.email;
    await this.signedIn();
  }

  private async signOut() {
    await call<unknown>("/v1/sessions", "DELETE", this.token);
    this.token = "";
    save(TOKEN, "");
    window.clearInterval(this.poll);
    this.signedOut();
  }

  /** Who the token signs in as; a token that no longer works is forgotten. */
  private async account(): Promise<boolean> {
    const r = await call<Account>("/v1/account", "GET", this.token);
    if (r.ok) this.who.textContent = r.value.email;
    else if (r.status === 401) save(TOKEN, (this.token = ""));
    return r.ok;
  }

  private async letters() {
    const r = await call<Mailbag>(`/v1/lives/${encodeURIComponent(this.life)}/letters`, "GET", this.token);
    if (r.ok) this.fill(r.value);
    else if (r.status === 401) this.signedOut();
  }

  private async send() {
    const d: LetterDraft = { from: this.from.value.trim(), body: this.body.value.trim() };
    if (!d.body) return;
    this.writeNote.textContent = "sending...";
    const r = await call<Mailbag>(`/v1/lives/${encodeURIComponent(this.life)}/letters`, "POST", this.token, d);
    if (!r.ok) {
      this.writeNote.textContent = r.error;
      if (r.status === 401) this.signedOut();
      return;
    }
    save(SIGNED, d.from);
    this.body.value = "";
    this.fill(r.value);
    this.writeNote.textContent = "Sent. It goes through his mail hatch, and he opens it when he gets to it.";
  }

  private max = 1000;
  private left = 0;

  private fill(bag: Mailbag) {
    this.max = bag.max_length;
    this.left = bag.left;
    this.body.maxLength = bag.max_length;
    this.count();
    this.sent.replaceChildren(...bag.letters.map(row));
    el("mail-sent-title").hidden = bag.letters.length === 0;
  }

  private count() {
    const n = this.body.value.length;
    el("mail-count").textContent = `${n}/${this.max} · ${this.left} more today`;
    (el("mail-send") as HTMLButtonElement).disabled = this.left <= 0;
  }
}

const POLL_MS = 10_000;

const STATUS: Record<string, string> = { waiting: "waiting for his run to start", delivered: "at his mail hatch", opened: "he opened it" };

function row(l: SentLetter): HTMLLIElement {
  const li = document.createElement("li");
  const head = document.createElement("div");
  head.className = "mail-status " + l.status;
  head.textContent = `${new Date(l.sent_at).toLocaleString()} · signed ${l.from} · ${STATUS[l.status] ?? l.status}`;
  const text = document.createElement("p");
  text.textContent = l.body;
  li.append(head, text);
  return li;
}

type Result<T> = { ok: true; value: T } | { ok: false; status: number; error: string };

async function call<T>(path: string, method: string, token: string, body?: unknown): Promise<Result<T>> {
  try {
    const r = await fetch(path, {
      method,
      headers: { ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await r.text();
    if (r.ok) return { ok: true, value: (text ? JSON.parse(text) : null) as T };
    let error = `${r.status}`;
    try {
      error = (JSON.parse(text) as MailError).error;
    } catch {
      // Not ours (a proxy's page): the status says enough.
    }
    return { ok: false, status: r.status, error };
  } catch (e) {
    return { ok: false, status: 0, error: e instanceof Error ? e.message : String(e) };
  }
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}

function load(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

function save(key: string, value: string) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    // Not kept: sign in each visit.
  }
}
