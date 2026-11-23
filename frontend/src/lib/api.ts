/**
 * Cliente único para hablar con el backend a través del proxy de Next (/api/...).
 *
 * - Adjunta el token CSRF de la sesión en cada escritura.
 * - Convierte las respuestas de error en ApiError con el mensaje que manda el backend.
 * - Avisa por eventos de ventana cuando la sesión expiró (401) o el plan no alcanza (402),
 *   o la tienda está suspendida (423), para que el layout de cada sección decida qué mostrar.
 */

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
export type Body = string | URLSearchParams | FormData | undefined;

/** Firma que usan los componentes del panel para escribir en /api/owner. */
export type Send = (path: string, body?: Body, method?: Exclude<Method, "GET">) => Promise<Response>;

export const API_EVENTS = {unauthorized: "api:unauthorized", quota: "api:quota", suspended: "api:suspended"} as const;

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

const fallbackMessages: Record<number, string> = {
  400: "Revisa los datos ingresados.",
  401: "Tu sesión terminó. Vuelve a iniciar sesión.",
  402: "Tu plan actual no incluye esta función o alcanzaste su límite.",
  403: "No tienes permiso para esta acción.",
  404: "No encontramos lo que buscabas.",
  409: "Los datos cambiaron mientras trabajabas. Recarga e inténtalo otra vez.",
  423: "La tienda está suspendida. Escríbenos para reactivarla.",
  429: "Demasiados intentos. Espera un momento.",
  503: "El servicio no está disponible por ahora.",
};

/** Lee el mensaje de error del backend ({message} o {error}) o usa uno genérico. */
export async function errorMessage(res: Response, fallback?: string): Promise<string> {
  try {
    const body = await res.clone().json();
    const text = body?.message ?? body?.error;
    if (typeof text === "string" && text.trim() && !/^(Bad Request|Unauthorized|Forbidden|Not Found|Internal Server Error)$/.test(text)) return text;
  } catch {}
  return fallback ?? fallbackMessages[res.status] ?? "No se pudo completar la operación.";
}

function notify(res: Response, url: string) {
  if (typeof window === "undefined") return;
  // Los endpoints de sesión responden 401 como parte normal del login: no son "sesión expirada".
  if (res.status === 401 && !/\/(me|login|csrf|activate)(\?|$)/.test(url)) {
    window.dispatchEvent(new CustomEvent(API_EVENTS.unauthorized, {detail: url}));
  }
  if (res.status === 402) window.dispatchEvent(new CustomEvent(API_EVENTS.quota, {detail: url}));
  if (res.status === 423) window.dispatchEvent(new CustomEvent(API_EVENTS.suspended, {detail: url}));
}

/** fetch con los avisos globales de sesión y plan. Nunca usa caché. */
export async function apiFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, {cache: "no-store", ...init});
  notify(res, url);
  return res;
}

/** GET que devuelve JSON o lanza ApiError. */
export async function getJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const res = await apiFetch(url, init);
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return res.json() as Promise<T>;
}

export type ApiClient = {
  /** Ruta completa bajo el prefijo (por ejemplo "stores/x/catalog" → "/api/owner/stores/x/catalog"). */
  url: (path: string) => string;
  get: <T>(path: string, init?: RequestInit) => Promise<T>;
  send: Send;
  /** Escritura que devuelve JSON (o null si no hay cuerpo) y lanza ApiError si falla. */
  json: <T = unknown>(path: string, body?: unknown, method?: Exclude<Method, "GET">) => Promise<T>;
  csrf: () => Promise<string>;
};

export function createClient(prefix: string): ApiClient {
  const url = (path: string) => prefix + "/" + path.replace(/^\//, "");

  async function csrf() {
    // El token vive en la sesión y cambia al iniciar sesión, por eso se pide en cada escritura.
    const res = await fetch(url("csrf"), {cache: "no-store"});
    if (!res.ok) throw new ApiError(res.status, "No se pudo iniciar la conexión con el servidor.");
    return (await res.json()).token as string;
  }

  const send: Send = async (path, body, method = "POST") => {
    const headers = new Headers({"X-CSRF-TOKEN": await csrf()});
    if (typeof body === "string") headers.set("Content-Type", "application/json");
    return apiFetch(url(path), {method, headers, body});
  };

  return {
    url,
    csrf,
    send,
    get: (path, init) => getJson(url(path), init),
    async json(path, body, method = "POST") {
      const res = await send(path, body === undefined ? undefined : JSON.stringify(body), method);
      if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
      const text = await res.text();
      return (text ? JSON.parse(text) : null) as never;
    },
  };
}

export const ownerApi = createClient("/api/owner");
export const superadminApi = createClient("/api/superadmin");
export const publicApi = {
  url: (path: string) => "/api/public/" + path.replace(/^\//, ""),
};

/** Base del backend para llamadas desde el servidor de Next (server components y route handlers). */
export function backendBase(): string {
  return (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/, "");
}
