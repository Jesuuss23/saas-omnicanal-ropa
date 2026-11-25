"use client";
import {apiFetch,errorMessage} from "@/lib/api";
import {authenticate3DS, tokenizeCard, type Parameters3DS} from "@/lib/culqi";

import {useEffect,useState,type CSSProperties} from "react";
import Link from "next/link";
import {useParams} from "next/navigation";
import {CheckCircle2, Clock, CreditCard, Lock, MapPin, MessageCircle, RefreshCw, ShoppingBag, XCircle} from "lucide-react";
import {getOrderAmounts} from "@/components/OrderAmounts";
import {type Order} from "../../OrderSummary";
import {brandStyle, whatsappLink} from "../../brand";

const statuses={PENDING_PAYMENT:"Pendiente de pago",PAID:"Pagado",EXPIRED:"Vencido",CANCELLED:"Cancelado"};
const limaDate=new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",hour:"2-digit",minute:"2-digit"});
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const tones={
  PENDING_PAYMENT:{icon:Clock,chip:"bg-amber-100 text-amber-800",text:"Completa el pago para confirmar tu pedido."},
  PAID:{icon:CheckCircle2,chip:"bg-emerald-100 text-emerald-700",text:"¡Gracias! Recibimos tu pago. La tienda te avisará cuando tu pedido esté listo."},
  EXPIRED:{icon:XCircle,chip:"bg-muted text-muted-foreground",text:"La reserva de estas prendas venció. Puedes volver a armar tu carrito."},
  CANCELLED:{icon:XCircle,chip:"bg-muted text-muted-foreground",text:"Este pedido fue cancelado."},
};
type Detail=Pick<Order,"items"|"total"|"freeDelivery"|"deliveryFee"|"method"> & {
  status:keyof typeof statuses;
  reservationExpiresAt?:string;
  pickup?:{address:string;hours:string}|null;
  store?:{name:string;primaryColor:string|null;logoVersion:number|null;whatsappPhone:string|null}|null;
};

export default function OrderTracking() {
  const {store,order}=useParams<{store:string;order:string}>();
  return <TrackingView key={store+"/"+order} store={store} order={order}/>;
}

function TrackingView({store,order}:{store:string;order:string}) {
  const [token,setToken]=useState<string|null>(null);
  useEffect(()=>{
    const readToken=()=>setToken(window.location.hash.slice(1));
    readToken();
    window.addEventListener("hashchange",readToken);
    return ()=>window.removeEventListener("hashchange",readToken);
  },[]);
  return <OrderDetails key={token} token={token} store={store} order={order}/>;
}

function OrderDetails({store,order,token}:{store:string;order:string;token:string|null}) {
  const [detail,setDetail]=useState<Detail|null>(null);
  const [loadError,setError]=useState("");
  const [version,setVersion]=useState(0);
  const invalidToken=token!==null && !/^[a-f0-9]{64}$/.test(token);
  const error=invalidToken ? "Abre el enlace privado completo de tu pedido." : loadError;

  useEffect(()=>{
    if (token===null) return;
    if (invalidToken) return;
    const headers={"X-Order-Token":token};
    const controller=new AbortController();
    let timer:number|undefined;
    let busy=false;
    let finished=false;

    async function load() {
      window.clearTimeout(timer);
      if (busy || finished || document.hidden || controller.signal.aborted) return;
      busy=true;
      try {
        const res=await apiFetch("/api/public/stores/"+store+"/orders/"+order,{
          headers,cache:"no-store",signal:controller.signal
        });
        if (controller.signal.aborted) return;
        if (res.status===404) {
          finished=true;
          setDetail(null);
          throw new Error("El enlace no corresponde a un pedido.");
        }
        if (!res.ok) throw new Error("No se pudo actualizar el pedido.");
        const data:Detail=await res.json();
        if (controller.signal.aborted) return;
        finished=data.status!=="PENDING_PAYMENT";
        setDetail(data);
        setError("");
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error?err.message:"No se pudo consultar el pedido.");
      } finally {
        busy=false;
        if (!finished && !document.hidden && !controller.signal.aborted)
          timer=window.setTimeout(load,30000);
      }
    }
    void load();
    document.addEventListener("visibilitychange",load);
    return ()=>{
      controller.abort();
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange",load);
    };
  },[store,order,token,invalidToken,version]);

  const catalogPath=detail && (detail.status==="EXPIRED" || detail.status==="CANCELLED")
    ? "/tienda/"+encodeURIComponent(store) : undefined;
  const brand=detail?.store;
  const theme=brandStyle(brand ? {primaryColor:brand.primaryColor ?? "", tagline:null, description:null, whatsappPhone:null, instagram:null, logoVersion:null} : null) as CSSProperties;
  const logo=brand?.logoVersion ? "/api/public/stores/"+store+"/logo?v="+brand.logoVersion : null;
  const amounts=detail ? getOrderAmounts({merchandise:detail.total,method:detail.method,freeDelivery:detail.freeDelivery,fee:detail.deliveryFee}) : null;
  const payable=detail ? detail.total+(detail.freeDelivery ? 0 : detail.deliveryFee ?? 0) : 0;
  const whatsapp=whatsappLink(brand?.whatsappPhone,"Hola "+(brand?.name || "")+", tengo una consulta sobre mi pedido "+order.slice(0,8).toUpperCase()+".");
  const tone=detail ? tones[detail.status] : tones.PENDING_PAYMENT;
  const StatusIcon=tone.icon;

  return <div style={theme} className="min-h-dvh bg-muted/40 text-foreground">
    <header className="bg-[var(--store)] text-[var(--store-fg)]">
      <div className="mx-auto flex max-w-2xl items-center gap-3 px-4 py-4">
        {/* eslint-disable-next-line @next/next/no-img-element -- logo servido por el backend */}
        {logo ? <img src={logo} alt="" className="size-10 rounded-full bg-white object-contain p-0.5"/> :
          <span className="grid size-10 place-items-center rounded-full bg-white/20"><ShoppingBag className="size-5"/></span>}
        <div className="min-w-0">
          <p className="truncate font-semibold">{brand?.name || "Tu pedido"}</p>
          <p className="text-xs opacity-80">Pedido #{order.slice(0,8).toUpperCase()}</p>
        </div>
      </div>
    </header>

    <main className="mx-auto grid max-w-2xl gap-4 px-4 py-5">
      <h1 className="sr-only">Consulta de pedido</h1>
      {error && <p role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">{error}{detail && " Se muestran los últimos datos recibidos."}</p>}
      {!detail && !error && <div className="grid gap-3"><div className="h-28 animate-pulse rounded-2xl bg-card"/><div className="h-48 animate-pulse rounded-2xl bg-card"/></div>}

      {detail && <>
        <section className="rounded-2xl border bg-card p-5 shadow-sm">
          <div className="flex items-start gap-3">
            <span className={"grid size-11 shrink-0 place-items-center rounded-full "+tone.chip}><StatusIcon className="size-5"/></span>
            <div className="min-w-0 flex-1">
              <p className="text-lg font-semibold">{statuses[detail.status]}</p>
              <p className="text-sm text-muted-foreground">{tone.text}</p>
              {detail.status==="PENDING_PAYMENT" && detail.reservationExpiresAt && <p className="mt-2 text-sm">
                Reservado hasta las <time dateTime={detail.reservationExpiresAt} className="font-medium">{limaDate.format(new Date(detail.reservationExpiresAt))}</time> <Countdown until={detail.reservationExpiresAt}/>
              </p>}
            </div>
          </div>
        </section>

        {detail.status==="PENDING_PAYMENT" && token && <PayWithCulqi store={store} order={order} token={token} total={payable}
          canPay={amounts?.total!==null} title={brand?.name ? "Pedido en "+brand.name : "Pago de tu pedido"} color={brand?.primaryColor} onPaid={()=>setVersion(v=>v+1)}/>}

        <section className="rounded-2xl border bg-card shadow-sm" aria-labelledby="items-title">
          <h2 id="items-title" className="border-b px-5 py-3 font-semibold">Tu compra</h2>
          <ul aria-label="Prendas del pedido" className="divide-y">
            {(detail.items ?? []).map(item=><li key={item.variant} className="flex gap-3 px-5 py-3">
              <div className="min-w-0 flex-1">
                <p className="font-medium">{item.name}</p>
                <p className="text-sm text-muted-foreground">Talla {item.size}{item.color && " · "+item.color} · {item.quantity} × {money.format(item.unitPrice)}</p>
                {catalogPath && <Link prefetch={false} href={{pathname:catalogPath,query:{variant:item.variant}}} className="text-sm text-[var(--store)] underline">Ver disponibilidad</Link>}
              </div>
              <p className="shrink-0 font-medium tabular-nums">{money.format(Math.round(item.unitPrice*100)*item.quantity/100)}</p>
            </li>)}
          </ul>
          <dl className="grid gap-1.5 border-t px-5 py-4 text-sm">
            <div className="flex justify-between"><dt className="text-muted-foreground">Prendas</dt><dd className="tabular-nums">{money.format(detail.total)}</dd></div>
            {amounts?.label && <div className="flex justify-between"><dt className="text-muted-foreground">{detail.method==="PICKUP" ? "Entrega" : "Envío"}</dt><dd>{amounts.label.replace(/^Envío /,"")}</dd></div>}
            {amounts?.total!==null && amounts?.total!==undefined && <div className="mt-1 flex justify-between border-t pt-2 text-base font-semibold"><dt>Total</dt><dd className="tabular-nums">{money.format(amounts.total)}</dd></div>}
          </dl>
        </section>

        {detail.pickup && <section className="rounded-2xl border bg-card p-5 shadow-sm">
          <h2 className="flex items-center gap-2 font-semibold"><MapPin className="size-4 text-[var(--store)]"/>Recojo en tienda</h2>
          <p className="mt-1 text-sm">{detail.pickup.address}</p>
          <p className="text-sm text-muted-foreground">Horario: {detail.pickup.hours}</p>
          <a href={"https://www.google.com/maps/dir/?"+new URLSearchParams({api:"1",destination:detail.pickup.address})}
            target="_blank" rel="noopener noreferrer" className="mt-2 inline-block text-sm font-medium text-[var(--store)] underline">Ver ruta al local en Google Maps</a>
        </section>}

        <div className="flex flex-wrap items-center justify-center gap-3 pt-1">
          {whatsapp && <a href={whatsapp} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-full border bg-card px-4 py-2 text-sm font-medium"><MessageCircle className="size-4"/>Escribir a la tienda</a>}
          {catalogPath && <Link href={catalogPath} className="rounded-full bg-[var(--store)] px-4 py-2 text-sm font-medium text-[var(--store-fg)]">Volver al catálogo</Link>}
          <button type="button" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground underline" onClick={()=>setVersion(v=>v+1)}><RefreshCw className="size-3.5"/>Actualizar</button>
        </div>
      </>}
      {!detail && error && <button type="button" className="text-sm underline" onClick={()=>setVersion(v=>v+1)}>Reintentar</button>}
    </main>
  </div>;
}

/** Tiempo que queda de la reserva, en vivo. */
function Countdown({until}:{until:string}) {
  const [now,setNow]=useState(()=>Date.now());
  useEffect(()=>{ const t=window.setInterval(()=>setNow(Date.now()),1000); return ()=>window.clearInterval(t); },[]);
  const left=Math.max(0,new Date(until).getTime()-now);
  if (left<=0) return null;
  const m=Math.floor(left/60000), sec=Math.floor(left/1000)%60;
  return <span className="ml-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium tabular-nums text-amber-900">quedan {m}:{String(sec).padStart(2,"0")}</span>;
}

type PaymentConfig={enabled:boolean;publicKey:string|null;liveMode:boolean};
type PayResult={status:"PAID"|"REQUIRES_3DS"|"DECLINED";message?:string|null};

/**
 * Pago con tarjeta usando la cuenta Culqi de la tienda: Culqi.js tokeniza la tarjeta con la llave pública de la tienda
 * y el backend cobra con su llave secreta. Si el banco pide 3‑D Secure, se autentica y se reintenta con el mismo token.
 */
function PayWithCulqi({store,order,token,total,canPay,title,color,onPaid}:{store:string;order:string;token:string;total:number;canPay:boolean;title:string;color?:string|null;onPaid:()=>void}) {
  const [config,setConfig]=useState<PaymentConfig|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const [notice,setNotice]=useState("");

  useEffect(()=>{
    const controller=new AbortController();
    apiFetch("/api/public/stores/"+store+"/payment-config",{cache:"no-store",signal:controller.signal})
      .then(async r=>{ if (!r.ok) throw new Error(); const body=await r.json() as PaymentConfig; if (!controller.signal.aborted) setConfig(body); })
      .catch(()=>{ if (!controller.signal.aborted) setConfig({enabled:false,publicKey:null,liveMode:false}); });
    return ()=>controller.abort();
  },[store]);

  async function charge(tokenId:string,email:string,authentication3DS?:Parameters3DS):Promise<PayResult> {
    const res=await apiFetch("/api/public/stores/"+store+"/orders/"+order+"/culqi/pay",{
      method:"POST",headers:{"X-Order-Token":token,"Content-Type":"application/json"},cache:"no-store",
      body:JSON.stringify({tokenId,email,authentication3DS:authentication3DS ?? null})
    });
    if (res.status===409) throw new Error("La tienda aún no recibe pagos en línea o falta confirmar el costo de delivery.");
    if (res.status===410) throw new Error("La reserva venció. Vuelve a armar tu carrito.");
    if (res.status===400) throw new Error(await errorMessage(res,"Revisa los datos de la tarjeta."));
    if (!res.ok) throw new Error("No se pudo procesar el pago. Intenta nuevamente.");
    return await res.json() as PayResult;
  }

  async function pay() {
    if (!config?.publicKey) return;
    setBusy(true);setError("");setNotice("");
    const cents=Math.round(total*100);
    try {
      const card=await tokenizeCard(config.publicKey,title,cents,color && /^#[0-9a-f]{6}$/i.test(color) ? color : undefined);
      const email=card.email ?? "";
      let result=await charge(card.id,email);
      if (result.status==="REQUIRES_3DS") {
        setNotice(result.message || "Tu banco pide confirmar la compra.");
        const auth=await authenticate3DS(config.publicKey,card.id,email,cents);
        result=await charge(card.id,email,auth);
      }
      if (result.status==="PAID") { setNotice("¡Pago aprobado!"); onPaid(); return; }
      setError(result.message || "La tarjeta fue rechazada. Prueba con otra tarjeta.");
    } catch (err) {setError(err instanceof Error?err.message:"No se pudo completar el pago.");}
    finally {setBusy(false);}
  }

  if (!config) return <div className="h-36 animate-pulse rounded-2xl bg-card"/>;
  if (!config.enabled) return <section className="flex items-start gap-3 rounded-2xl border bg-card p-5 text-sm shadow-sm">
    <MessageCircle className="mt-0.5 size-5 shrink-0 text-[var(--store)]"/>
    <p>Esta tienda coordina el pago contigo por WhatsApp. Te escribirán para confirmarlo.</p>
  </section>;
  return <section aria-labelledby="pay-title" className="rounded-2xl border-2 border-[var(--store)] bg-card p-5 shadow-sm">
    <div className="flex items-end justify-between gap-3">
      <div>
        <h2 id="pay-title" className="text-sm font-medium text-muted-foreground">Total a pagar</h2>
        <p className="text-3xl font-bold tabular-nums">{money.format(total)}</p>
      </div>
      {!config.liveMode && <span className="rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-900">Modo prueba</span>}
    </div>
    <button type="button" disabled={busy || !canPay} onClick={()=>void pay()}
      className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl bg-[var(--store)] px-5 py-3.5 text-base font-semibold text-[var(--store-fg)] shadow-sm transition hover:brightness-95 disabled:opacity-60">
      <CreditCard className="size-5"/>{busy ? "Procesando pago…" : "Pagar con tarjeta"}</button>
    {!canPay && <p className="mt-2 text-sm text-muted-foreground">La tienda está calculando el costo de envío. Podrás pagar apenas lo confirme.</p>}
    <p className="mt-3 flex items-center justify-center gap-1.5 text-xs text-muted-foreground"><Lock className="size-3.5"/>Pago seguro con Culqi · Visa, Mastercard, Amex y Diners</p>
    {notice && <p role="status" className="mt-3 rounded-lg bg-emerald-50 p-2.5 text-center text-sm text-emerald-800">{notice}</p>}
    {error && <p role="alert" className="mt-3 rounded-lg bg-destructive/10 p-2.5 text-center text-sm text-destructive">{error}</p>}
  </section>;
}
