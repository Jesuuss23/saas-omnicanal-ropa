"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState,type FormEvent} from "react";

type Settings={enabled:boolean;address:string;hours:string;revision:number;freeDeliveryMinimum:number|null;latitude:number|null;longitude:number|null;perKm:number|null};
type Props={store:string;send:Send};

export default function PickupSettings({store,send}:Props) {
  const path="stores/"+store+"/pickup";
  const [open,setOpen]=useState(false);
  const [settings,setSettings]=useState<Settings|null>(null);
  const [version,setVersion]=useState(0);
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState("");
  useEffect(()=>{
    if (!open) return;
    const controller=new AbortController();
    apiFetch(ownerApi.url(path),{signal:controller.signal}).then(async res=>{
      if (!res.ok) throw new Error();
      const data:Settings=await res.json();
      if (!controller.signal.aborted) setSettings(data);
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo cargar la configuración.");});
    return ()=>controller.abort();
  },[open,path,version]);

  async function save(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!settings || busy) return;
    setBusy(true);setNotice("");
    try {
      const res=await send(path,JSON.stringify(settings),"PUT");
      if (res.status===409) throw new Error("Otra sesión modificó el recojo. Recarga los datos para continuar.");
      if (!res.ok) throw new Error("No se pudo guardar. Revisa la dirección y el horario.");
      setSettings(await res.json());
      setNotice("Configuración guardada.");
    } catch (err) {setNotice(err instanceof Error?err.message:"No se pudo guardar.");}
    finally {setBusy(false);}
  }

  return <details className="my-4 rounded-xl border p-4" onToggle={e=>{const next=e.currentTarget.open; if (next) {setSettings(null);setNotice("");} setOpen(next);}}>
    <summary className="cursor-pointer font-semibold">Recojo y delivery</summary>
    {open && <>
      {settings && <form onSubmit={save} className="my-3 grid max-w-lg gap-2">
        <fieldset disabled={busy} className="grid gap-2">
          <label><input type="checkbox" checked={settings.enabled} onChange={e=>setSettings({...settings,enabled:e.target.checked})}/> Publicar punto de recojo en el catálogo</label>
          <label>Dirección<input value={settings.address} required={settings.enabled} maxLength={250}
            onChange={e=>setSettings({...settings,address:e.target.value})} className="block w-full rounded border p-2"/></label>
          <label>Horario de recojo<input value={settings.hours} required={settings.enabled} maxLength={160}
            onChange={e=>setSettings({...settings,hours:e.target.value})} className="block w-full rounded border p-2"/></label>
          <label>Compra mínima para delivery gratis (S/)<input type="number" min="0" max="99999999.99" step="0.01"
            defaultValue={settings.freeDeliveryMinimum ?? ""} key={settings.revision}
            onChange={e=>setSettings({...settings,freeDeliveryMinimum:e.target.value===""?null:e.target.valueAsNumber})}
            className="block w-full rounded border p-2"/></label>
          <p className="text-sm">Deja el campo vacío para desactivar el beneficio. Con cero, todo pedido con delivery califica.</p>
          <p>Para cobrar por distancia en línea recta, completa el origen de despacho y la tarifa. Deja los tres campos vacíos para desactivarla.</p>
          {(["latitude","longitude","perKm"] as const).map(field=><label key={field}>
            {{latitude:"Latitud de tienda o almacén",longitude:"Longitud de tienda o almacén",perKm:"Tarifa por km (S/)"}[field]}
            <input type="number" step={field==="perKm"?"0.01":"0.000001"} min={field==="perKm"?0.01:field==="latitude"?-90:-180}
              max={field==="perKm"?999999.99:field==="latitude"?90:180} defaultValue={settings[field] ?? ""} key={settings.revision}
              onChange={e=>setSettings({...settings,[field]:e.target.value===""?null:e.target.valueAsNumber})} className="block w-full rounded border p-2"/>
          </label>)}
          <button className="w-fit rounded bg-teal-800 px-3 py-2 text-white">Guardar</button>
        </fieldset>
      </form>}
      <button type="button" disabled={busy} onClick={()=>{setSettings(null);setNotice("");setVersion(v=>v+1);}} className="underline">Recargar datos guardados</button>
      <p role="status">{notice}</p>
    </>}
  </details>;
}
