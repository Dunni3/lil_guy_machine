import type { Command, CommandResult } from "../../../protocol/ts/protocol";

/** The same key as the host's debug page, so signing in on one signs in on the other (same origin). */
const KEY = "lilguy-admin-password";

/**
 * Whether this viewer is the admin, and sending commands as one. v0's admin login is one password
 * (`AdminAuth.kt`), sent as a bearer header; `/auth/me` says whether it's right. The browser keeps
 * only a password that worked. Everything here is a fetch, so nothing waits on it.
 */
export class Admin {
  admin = false;
  /** How the last command went ("pause: ok", "restart: admin only"), for the RUN tab. */
  result: string | null = null;
  private password = "";

  constructor(private readonly life: string) {
    try {
      this.password = localStorage.getItem(KEY) ?? "";
    } catch {
      // Storage blocked: sign in each visit.
    }
    if (this.password) void this.check(this.password);
  }

  /** Signs in with [password]: true when it's the admin's, and then it's kept. */
  async check(password: string): Promise<boolean> {
    let ok = false;
    try {
      const r = await fetch("/auth/me", { headers: bearer(password) });
      ok = ((await r.json()) as { admin: boolean }).admin === true;
    } catch {
      ok = false;
    }
    this.admin = ok;
    this.password = ok ? password : "";
    remember(this.password);
    return ok;
  }

  signOut() {
    this.admin = false;
    this.password = "";
    this.result = null;
    remember("");
  }

  async send(command: Command) {
    if (!this.admin) return;
    this.result = `${command.type}: sending`;
    try {
      const r = await fetch(`/v1/lives/${encodeURIComponent(this.life)}/commands`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...bearer(this.password) },
        body: JSON.stringify(command),
      });
      const body = (await r.json()) as CommandResult;
      this.result = `${command.type}: ${body.ok ? "ok" : body.error ?? r.status}`;
      // The password changed under us: back to watching.
      if (r.status === 401) this.signOut();
    } catch (e) {
      this.result = `${command.type}: ${e instanceof Error ? e.message : String(e)}`;
    }
  }
}

const bearer = (password: string) => ({ Authorization: `Bearer ${password.trim()}` });

function remember(password: string) {
  try {
    if (password) localStorage.setItem(KEY, password);
    else localStorage.removeItem(KEY);
  } catch {
    // Not kept.
  }
}

/**
 * The password box, the one piece of DOM over the room, shown only while signing in: Enter signs
 * in, Escape gives up. It has the keys while it's open. [done] says whether it worked.
 */
export class SignIn {
  private readonly form = document.getElementById("signin") as HTMLFormElement;
  private readonly input = document.getElementById("password") as HTMLInputElement;
  private readonly note = document.getElementById("signin-note") as HTMLElement;
  private done: (ok: boolean) => void = () => {};

  constructor(private readonly admin: Admin) {
    this.form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const password = this.input.value.trim();
      if (!password) return;
      this.note.textContent = "checking...";
      if (await this.admin.check(password)) this.close(true);
      else {
        this.note.textContent = "That isn't the password. Type it again · Escape gives up";
        this.input.value = "";
      }
    });
    this.input.addEventListener("keydown", (e) => {
      if (e.key === "Escape" || e.key === "BrowserBack" || e.key === "GoBack") {
        e.preventDefault();
        this.close(false);
      }
      // The room's keys stay out of the box.
      e.stopPropagation();
    });
  }

  get open(): boolean {
    return !this.form.hidden;
  }

  show(done: (ok: boolean) => void) {
    this.done = done;
    this.input.value = "";
    this.note.textContent = "Enter signs in · Escape gives up";
    this.form.hidden = false;
    this.input.focus();
  }

  private close(ok: boolean) {
    this.form.hidden = true;
    this.input.blur();
    this.done(ok);
  }
}
