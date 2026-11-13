// api.js: every call from your pages to your API, with the dev sign-in token attached.
//
//   const deal = await api.get("/api/deals/deals/" + id);
//   await api.post("/api/payments/desk/top-ups", { studentId, amountCents }, { idempotencyKey });
//
// Errors: a failed call throws an ApiError carrying the Problem's status, code and field errors,
// so a page can show the right message:  catch (e) { if (e.code === "DEAL_FULL") ... }
//
// The token: the dev sign-in page stores it in this browser (localStorage "groupbuy.token"); the
// gateway serves your pages and the API from one origin, so every page can read it.

const TOKEN_KEY = "groupbuy.token";
const SIGN_IN_PAGE = "/signin/";

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.status = status;
    this.code = problem.code || "HTTP_" + status;
    this.errors = problem.errors || [];   // [{ field, message }] for VALIDATION_FAILED
    this.problem = problem;
  }
}

export const token = () => localStorage.getItem(TOKEN_KEY);

/** The signed-in user from the token: { userId, role, restaurantId, expiresAt }, or null. */
export function currentUser() {
  const t = token();
  if (!t) return null;
  try {
    const claims = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0))));
    if (claims.exp && claims.exp * 1000 < Date.now()) return null;
    return { userId: claims.sub, role: claims.role, restaurantId: claims.restaurantId || null, name: claims.name || null, expiresAt: new Date(claims.exp * 1000) };
  } catch {
    return null;
  }
}

export function signInUrl() {
  return SIGN_IN_PAGE + "?return=" + encodeURIComponent(location.pathname + location.search);
}

export function signOut() {
  localStorage.removeItem(TOKEN_KEY);
  location.href = signInUrl();
}

/** A new key for one action: make it when the form opens, reuse it if the user clicks twice. */
export const newIdempotencyKey = () => crypto.randomUUID();

async function call(method, path, body, options = {}) {
  const headers = { Accept: "application/json, application/problem+json" };
  const t = token();
  if (t) headers.Authorization = "Bearer " + t;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, { code: "NETWORK", detail: "The service could not be reached. Is it running, and is the gateway routing to it?" });
  }
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    if (response.status === 401 && options.redirectOn401 !== false) {
      location.href = signInUrl();
    }
    throw new ApiError(response.status, data && typeof data === "object" ? data : { detail: text });
  }
  return data;
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return text; }
}

export const api = {
  get: (path, options) => call("GET", path, undefined, options),
  post: (path, body, options) => call("POST", path, body ?? {}, options),
  put: (path, body, options) => call("PUT", path, body ?? {}, options),
  delete: (path, options) => call("DELETE", path, undefined, options),
};

/** Euro text from cents, for screens: euro(450) is "€4.50". */
export const euro = (cents) => "€" + (cents / 100).toFixed(2);

/** Irish time from a UTC instant, for screens (spec B, rule 4): irishTime("2026-11-14T12:00:00Z") is "14 Nov, 12:00". */
export const irishTime = (iso) =>
  new Date(iso).toLocaleString("en-IE", { timeZone: "Europe/Dublin", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

/** Puts a Problem's field errors next to the form fields with the same name, and its message in .notice.bad. */
export function showProblem(form, error) {
  form.querySelectorAll(".field-error").forEach((e) => e.remove());
  for (const { field, message } of error.errors || []) {
    const input = form.querySelector(`[name="${CSS.escape(field)}"]`);
    if (input) input.insertAdjacentHTML("afterend", `<span class="field-error">${escapeHtml(message)}</span>`);
  }
  const notice = form.querySelector(".notice.bad");
  if (notice) {
    notice.textContent = error.message;
    notice.hidden = false;
  }
}

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
