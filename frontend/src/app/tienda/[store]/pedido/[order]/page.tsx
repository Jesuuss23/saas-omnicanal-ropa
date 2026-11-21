"use client";

import {useEffect,useState} from "react";
import {useParams} from "next/navigation";
import OrderSummary, {type Order} from "../../OrderSummary";

const statuses={PENDING_PAYMENT:"Pendiente de pago",EXPIRED:"Vencido",CANCELLED:"Cancelado"};
type Detail=Pick<Order,"items"|"total"|"freeDelivery"|"deliveryFee"|"method"> & {
  status:keyof typeof statuses;
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
  const [error,setError]=useState("");
  const [version,setVersion]=useState(0);

  useEffect(()=>{
    if (token===null) return;
    if (!/^[a-f0-9]{64}$/.test(token)) {
      setError("Abre el enlace privado completo de tu pedido.");
      return;
    }
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
        const res=await fetch("/api/public/stores/"+store+"/orders/"+order,{
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
  },[store,order,token,version]);

  return <main className="mx-auto max-w-xl space-y-3 px-5 py-10">
    <h1 className="text-2xl font-semibold">Consulta de pedido</h1>
    {error && <p role="alert">{error}{detail && " Se muestran los últimos datos recibidos."}</p>}
    {detail ? <>
      <p className="break-all">Pedido {order}</p>
      <p className="font-semibold">{statuses[detail.status]}</p>
      {detail.pickup && <section className="rounded border p-3">
        <h2 className="font-semibold">Punto de recojo del pedido</h2>
        <p>{detail.pickup.address}</p>
        <p>Horario de recojo: {detail.pickup.hours}</p>
        <a href={"https://www.google.com/maps/dir/?"+new URLSearchParams({api:"1",destination:detail.pickup.address})}
          target="_blank" rel="noopener noreferrer" className="text-teal-800 underline">Ver ruta al local en Google Maps</a>
      </section>}
      <OrderSummary order={detail}/>
    </> : !error && <p>Cargando pedido…</p>}
    <button type="button" className="text-teal-800 underline" onClick={()=>setVersion(v=>v+1)}>Actualizar</button>
  </main>;
}
