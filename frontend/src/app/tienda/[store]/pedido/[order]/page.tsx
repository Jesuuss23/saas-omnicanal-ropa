"use client";

import {useEffect,useState} from "react";
import {useParams} from "next/navigation";
import OrderSummary, {type Order} from "../../OrderSummary";

const statuses={PENDING_PAYMENT:"Pendiente de pago",EXPIRED:"Vencido",CANCELLED:"Cancelado"};
type Detail=Pick<Order,"items"|"total"> & {status:keyof typeof statuses};

export default function OrderTracking() {
  const {store,order}=useParams<{store:string;order:string}>();
  const [detail,setDetail]=useState<Detail|null>(null);
  const [error,setError]=useState("");
  const [version,setVersion]=useState(0);

  useEffect(()=>{
    const controller=new AbortController();
    const refresh=()=>setVersion(v=>v+1);
    window.addEventListener("hashchange",refresh);
    setDetail(null);
    setError("");
    async function load() {
      const token=window.location.hash.slice(1);
      if (!/^[a-f0-9]{64}$/.test(token)) {setError("Abre el enlace privado completo de tu pedido.");return;}
      try {
        const res=await fetch("/api/public/stores/"+store+"/orders/"+order,{
          headers:{"X-Order-Token":token},cache:"no-store",signal:controller.signal
        });
        if (!res.ok) throw new Error(res.status===404?"El enlace no corresponde a un pedido.":"No se pudo consultar el pedido.");
        const data:Detail=await res.json();
        if (!controller.signal.aborted) setDetail(data);
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error?err.message:"No se pudo consultar el pedido.");
      }
    }
    void load();
    return ()=>{controller.abort();window.removeEventListener("hashchange",refresh);};
  },[store,order,version]);

  return <main className="mx-auto max-w-xl space-y-3 px-5 py-10">
    <h1 className="text-2xl font-semibold">Consulta de pedido</h1>
    {error ? <p role="alert">{error}</p> : detail ? <>
      <p className="break-all">Pedido {order}</p>
      <p className="font-semibold">{statuses[detail.status]}</p>
      <OrderSummary order={detail}/>
    </> : <p>Cargando pedido…</p>}
    <button type="button" className="text-teal-800 underline" onClick={()=>setVersion(v=>v+1)}>Actualizar</button>
  </main>;
}
