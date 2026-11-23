"use client";
import {apiFetch} from "@/lib/api";

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
      {detail.status==="PENDING_PAYMENT" && token && <PayWithMercadoPago store={store} order={order} token={token}/>}
      {detail.status==="PAID" && <p>¡Gracias! Recibimos tu pago. La tienda te avisará cuando tu pedido esté listo.</p>}
      {detail.status==="EXPIRED" && <p>La reserva de estas prendas ha vencido.</p>}
      {detail.pickup && <section className="rounded border p-3">
        <h2 className="font-semibold">Punto de recojo del pedido</h2>
        <p>{detail.pickup.address}</p>
        <p>Horario de recojo: {detail.pickup.hours}</p>
        <a href={"https://www.google.com/maps/dir/?"+new URLSearchParams({api:"1",destination:detail.pickup.address})}
          target="_blank" rel="noopener noreferrer" className="text-teal-800 underline">Ver ruta al local en Google Maps</a>
      </section>}
      <OrderSummary order={detail} catalogPath={catalogPath}/>
      {catalogPath && <Link href={catalogPath} className="inline-block rounded bg-teal-800 px-4 py-2 text-white">
        Volver al catálogo de la tienda
      </Link>}
    </> : !error && <p>Cargando pedido…</p>}
    <button type="button" className="text-teal-800 underline" onClick={()=>setVersion(v=>v+1)}>Actualizar</button>
  </main>;
}

function PayWithMercadoPago({store,order,token}:{store:string;order:string;token:string}) {
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  async function pay() {
    setBusy(true);setError("");
    try {
      const res=await apiFetch("/api/public/stores/"+store+"/orders/"+order+"/mercadopago",{
        method:"POST",headers:{"X-Order-Token":token},cache:"no-store"
      });
      if (res.status===409) throw new Error("Esta tienda aún no recibe pagos en línea o falta confirmar el costo de delivery.");
      if (res.status===410) throw new Error("La reserva venció. Vuelve a armar tu carrito.");
      if (!res.ok) throw new Error("No se pudo iniciar el pago. Intenta nuevamente.");
      const {checkoutUrl}=await res.json();
      window.location.assign(checkoutUrl);
    } catch (err) {setError(err instanceof Error?err.message:"No se pudo iniciar el pago.");setBusy(false);}
  }
  return <div className="space-y-2">
    <button type="button" disabled={busy} onClick={()=>void pay()} className="rounded bg-sky-600 px-4 py-2 text-white">
      {busy?"Abriendo Mercado Pago…":"Pagar con Mercado Pago"}</button>
    {error && <p role="alert">{error}</p>}
  </div>;
}
