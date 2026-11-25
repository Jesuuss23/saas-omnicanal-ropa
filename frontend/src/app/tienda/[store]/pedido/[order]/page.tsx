"use client";
import {apiFetch,errorMessage} from "@/lib/api";
import {authenticate3DS, tokenizeCard, type Parameters3DS} from "@/lib/culqi";

import {useEffect,useState} from "react";
import Link from "next/link";
import {useParams} from "next/navigation";
import OrderSummary, {type Order} from "../../OrderSummary";

const statuses={PENDING_PAYMENT:"Pendiente de pago",PAID:"Pagado",EXPIRED:"Vencido",CANCELLED:"Cancelado"};
const limaDate=new Intl.DateTimeFormat("es-PE",{
  timeZone:"America/Lima",dateStyle:"long",timeStyle:"medium"
});
type Detail=Pick<Order,"items"|"total"|"freeDelivery"|"deliveryFee"|"method"> & {
  status:keyof typeof statuses;
  reservationExpiresAt?:string;
  pickup?:{address:string;hours:string}|null;
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

  return <main className="mx-auto max-w-xl space-y-3 px-5 py-10">
    <h1 className="text-2xl font-semibold">Consulta de pedido</h1>
    {error && <p role="alert">{error}{detail && " Se muestran los últimos datos recibidos."}</p>}
    {detail ? <>
      <p className="break-all">Pedido {order}</p>
      <p className="font-semibold">{statuses[detail.status]}</p>
      {detail.status==="PENDING_PAYMENT" && detail.reservationExpiresAt && <p>
        Tus prendas están reservadas hasta el <time dateTime={detail.reservationExpiresAt}>
          {limaDate.format(new Date(detail.reservationExpiresAt))}
        </time> (hora de Lima).
      </p>}
      {detail.status==="PENDING_PAYMENT" && token && <PayWithCulqi store={store} order={order} token={token} total={detail.total+(detail.freeDelivery ? 0 : detail.deliveryFee ?? 0)} onPaid={()=>setVersion(v=>v+1)}/>}
      {detail.status==="PAID" && <p>¡Gracias! Recibimos tu pago. La tienda te avisará cuando tu pedido esté listo.</p>}
      {detail.status==="EXPIRED" && <p>La reserva de estas prendas ha vencido.</p>}
      {detail.pickup && <section className="rounded border p-3">
        <h2 className="font-semibold">Punto de recojo del pedido</h2>
        <p>{detail.pickup.address}</p>
        <p>Horario de recojo: {detail.pickup.hours}</p>
        <a href={"https://www.google.com/maps/dir/?"+new URLSearchParams({api:"1",destination:detail.pickup.address})}
          target="_blank" rel="noopener noreferrer" className="text-primary underline">Ver ruta al local en Google Maps</a>
      </section>}
      <OrderSummary order={detail} catalogPath={catalogPath}/>
      {catalogPath && <Link href={catalogPath} className="inline-block rounded-full bg-primary px-4 py-2 text-primary-foreground">
        Volver al catálogo de la tienda
      </Link>}
    </> : !error && <p>Cargando pedido…</p>}
    <button type="button" className="text-primary underline" onClick={()=>setVersion(v=>v+1)}>Actualizar</button>
  </main>;
}

type PaymentConfig={enabled:boolean;publicKey:string|null;liveMode:boolean};
type PayResult={status:"PAID"|"REQUIRES_3DS"|"DECLINED";message?:string|null};

/**
 * Pago con tarjeta usando la cuenta Culqi de la tienda: Culqi.js tokeniza la tarjeta con la llave pública de la tienda
 * y el backend cobra con su llave secreta. Si el banco pide 3‑D Secure, se autentica y se reintenta con el mismo token.
 */
function PayWithCulqi({store,order,token,total,onPaid}:{store:string;order:string;token:string;total:number;onPaid:()=>void}) {
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
      const card=await tokenizeCard(config.publicKey,"Pago de tu pedido",cents);
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

  if (!config) return <p className="text-sm text-muted-foreground">Consultando medios de pago…</p>;
  if (!config.enabled) return <p className="rounded border p-3 text-sm">Esta tienda coordina el pago contigo por WhatsApp. Te escribirán para confirmarlo.</p>;
  return <div className="space-y-2">
    <button type="button" disabled={busy} onClick={()=>void pay()}
      className="rounded-full bg-primary px-5 py-2.5 font-medium text-primary-foreground disabled:opacity-50">
      {busy?"Procesando pago…":"Pagar con tarjeta"}</button>
    {!config.liveMode && <p className="text-xs text-muted-foreground">Modo prueba de la tienda: usa una tarjeta de prueba de Culqi.</p>}
    <p className="text-xs text-muted-foreground">Pago seguro con Culqi. El cobro va directo a la tienda.</p>
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert" className="text-destructive">{error}</p>}
  </div>;
}
