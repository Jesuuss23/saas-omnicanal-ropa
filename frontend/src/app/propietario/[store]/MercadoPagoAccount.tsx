"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState} from "react";

type Account = {available:boolean;linked:boolean;providerUserId:number|null;liveMode:boolean;status:string;
  lastError:string|null;linkedAt:string|null;tokenExpiresAt:string|null};

const results:Record<string,string> = {
  linked:"Tu cuenta de Mercado Pago quedó vinculada.", cancelled:"Cancelaste la vinculación.",
  error:"No se pudo vincular la cuenta. Intenta nuevamente.",
};

/** El dueño vincula su propia cuenta de Mercado Pago: los pagos de su tienda llegan directo a esa cuenta. */
export default function MercadoPagoAccount({store,send}:{store:string;send:Send}) {
  const path = "stores/"+store+"/payments/mercadopago";
  const [account,setAccount] = useState<Account|null>(null);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);
  const [version,setVersion] = useState(0);

  useEffect(()=>{
    const controller = new AbortController();
    apiFetch(ownerApi.url(path),{signal:controller.signal}).then(async r=>{
      if (!r.ok) throw new Error();
      const body:Account = await r.json();
      if (controller.signal.aborted) return;
      setAccount(body);
      // Resultado del retorno desde Mercado Pago (?mercadopago=linked|cancelled|error).
      const params = new URLSearchParams(location.search);
      const result = params.get("mercadopago");
      if (result && results[result]) {
        setNotice(results[result]);
        params.delete("mercadopago");
        history.replaceState(null,"",location.pathname+(params.size?"?"+params:"")+location.hash);
      }
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo consultar Mercado Pago.");});
    return ()=>controller.abort();
  },[path,version]);

  async function link() {
    setBusy(true);setNotice("");
    try {
      const res = await send(path+"/authorize","{}");
      if (res.status===402) throw new Error("Tu plan no incluye cobros con Mercado Pago.");
      if (!res.ok) throw new Error("No se pudo iniciar la vinculación.");
      const {url} = await res.json();
      window.location.assign(url);
    } catch (err) {setNotice(err instanceof Error ? err.message : "Error al vincular."); setBusy(false);}
  }

  async function unlink() {
    if (!confirm("¿Desvincular Mercado Pago? Tu tienda dejará de aceptar pagos en línea.")) return;
    setBusy(true);setNotice("");
    try {
      const res = await send(path,undefined,"DELETE");
      if (!res.ok) throw new Error();
      setVersion(v=>v+1);
    } catch {setNotice("No se pudo desvincular.");}
    finally {setBusy(false);}
  }

  return <section className="mt-6 rounded-xl border border-stone-200 p-5">
    <h2 className="font-semibold">Cobros en línea con Mercado Pago</h2>
    {notice && <p role="status" className="my-2">{notice}</p>}
    {!account ? <p className="my-2">Consultando…</p> : account.linked ? <>
      <p className="my-2">Cuenta vinculada (usuario {account.providerUserId}{account.liveMode ? "" : ", modo prueba"}).
        {account.status==="ERROR" && account.lastError && <span className="text-red-700"> {account.lastError}</span>}</p>
      <button type="button" disabled={busy} className="text-red-700 underline" onClick={()=>void unlink()}>Desvincular</button>
    </> : <>
      <p className="my-2 text-sm text-stone-600">Vincula tu cuenta para que el checkout de tu tienda cobre con Mercado Pago. El dinero llega directamente a tu cuenta.</p>
      <button type="button" disabled={busy || !account.available} className="rounded-lg bg-teal-800 px-4 py-2 text-white disabled:opacity-50"
        onClick={()=>void link()}>Vincular Mercado Pago</button>
      {!account.available && <p className="mt-2 text-sm text-stone-600">La integración aún no está configurada en el servidor.</p>}
    </>}
  </section>;
}
