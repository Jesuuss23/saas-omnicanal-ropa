/**
 * Carga de Culqi Checkout (tokeniza la tarjeta) y Culqi 3DS (autenticación del banco) en el navegador.
 * Lo usan la suscripción del SaaS (llave del SaaS) y el checkout de cada tienda (llave pública de la tienda).
 */
export type CulqiToken = {id: string; email?: string};
export type CulqiCheckout = {
  publicKey: string; settings: (s: object) => void; options?: (o: object) => void; open: () => void; close: () => void;
  token?: CulqiToken | null; error?: {user_message?: string; merchant_message?: string} | null;
};
export type Parameters3DS = {eci?: string; xid?: string; cavv?: string; protocolVersion?: string; directoryServerTransactionId?: string};
export type Culqi3DSGlobal = {
  publicKey: string; settings: object; options: object;
  initAuthentication: (tokenId: string) => void; generateDevice?: () => Promise<string>; reset?: () => void;
};

declare global {
  interface Window { Culqi?: CulqiCheckout; culqi?: () => void; Culqi3DS?: Culqi3DSGlobal }
}

const scripts: Record<string, Promise<void>> = {};

function load(src: string, ready: () => boolean): Promise<void> {
  if (ready()) return Promise.resolve();
  scripts[src] ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = src;
    script.async = true;
    script.onload = () => ready() ? resolve() : reject(new Error("Culqi no cargó"));
    script.onerror = () => { delete scripts[src]; reject(new Error("No se pudo cargar Culqi")); };
    document.head.appendChild(script);
  });
  return scripts[src];
}

export async function loadCulqi(): Promise<CulqiCheckout> {
  await load("https://checkout.culqi.com/js/v4", () => Boolean(window.Culqi));
  return window.Culqi!;
}

export async function loadCulqi3DS(): Promise<Culqi3DSGlobal> {
  await load("https://3ds.culqi.com", () => Boolean(window.Culqi3DS));
  return window.Culqi3DS!;
}

/** Abre el formulario de tarjeta de Culqi y resuelve con el token (incluye el email que escribió el comprador). */
export async function tokenizeCard(publicKey: string, title: string, amountCents: number): Promise<CulqiToken> {
  const culqi = await loadCulqi();
  culqi.publicKey = publicKey;
  culqi.settings({title, currency: "PEN", amount: amountCents});
  culqi.options?.({lang: "auto", installments: false, paymentMethods: {tarjeta: true, yape: false, bancaMovil: false, agente: false, billetera: false, cuotealo: false}});
  return new Promise<CulqiToken>((resolve, reject) => {
    window.culqi = () => {
      if (culqi.token?.id) { const token = culqi.token; culqi.close(); resolve(token); }
      else reject(new Error(culqi.error?.user_message ?? "Culqi no pudo procesar la tarjeta."));
    };
    culqi.open();
  });
}

/**
 * Autenticación 3‑D Secure cuando Culqi responde REVIEW: abre la ventana del banco y resuelve con los parámetros
 * que se reenvían al backend para reintentar el cargo con el mismo token.
 */
export async function authenticate3DS(publicKey: string, tokenId: string, email: string, amountCents: number): Promise<Parameters3DS> {
  const c3ds = await loadCulqi3DS();
  c3ds.publicKey = publicKey;
  c3ds.settings = {charge: {totalAmount: amountCents, returnUrl: window.location.href, currency: "PEN"}, card: {email}};
  c3ds.options = {showModal: true, showLoading: true, showIcon: true, closeModalAction: () => window.postMessage({error: "cancelled"}, "*")};
  return new Promise<Parameters3DS>((resolve, reject) => {
    const listener = (event: MessageEvent) => {
      const data = event.data as {parameters3DS?: Parameters3DS; error?: unknown} | null;
      if (!data || typeof data !== "object") return;
      if (data.parameters3DS) { window.removeEventListener("message", listener); resolve(data.parameters3DS); }
      else if (data.error) { window.removeEventListener("message", listener); reject(new Error("No se completó la verificación del banco.")); }
    };
    window.addEventListener("message", listener, false);
    c3ds.initAuthentication(tokenId);
  });
}
