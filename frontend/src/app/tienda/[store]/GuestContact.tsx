"use client";

import {useEffect,useState,type FormEvent} from "react";

export default function GuestContact({store,reservation,disabled}:{store:string;reservation:string;disabled:boolean}) {
  const url="/api/public/stores/"+store+"/reservation/guest";
  const [name,setName]=useState("");
  const [phone,setPhone]=useState("");
  const [ready,setReady]=useState(false);
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState("");
  const [retry,setRetry]=useState(0);

  useEffect(()=>{
    const controller=new AbortController();
    setReady(false);
    setMessage("");
    async function load() {
      try {
        const response=await fetch(url,{headers:{"X-Reservation-Key":reservation},cache:"no-store",signal:controller.signal});
        if (!response.ok) throw new Error();
        const contact=await response.json();
        if (!controller.signal.aborted) {
          setName(contact.name ?? "");
          setPhone(contact.phone ?? "");
          setReady(true);
        }
      } catch {
        if (!controller.signal.aborted) setMessage("No se pudo consultar el contacto de la reserva.");
      }
    }
    void load();
    return ()=>controller.abort();
  },[url,reservation,retry]);

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const response=await fetch(url,{method:"PUT",headers:{"Content-Type":"application/json","X-Reservation-Key":reservation},
        body:JSON.stringify({name:name.trim(),phone:phone.replace(/[\s()-]/g,"")})});
      if (!response.ok) {
        setMessage(response.status===410?"La reserva ya no está activa.":response.status===400?"Revisa el nombre y el teléfono.":"No se pudo guardar el contacto.");
        return;
      }
      const contact=await response.json();
      setName(contact.name);
      setPhone(contact.phone);
      setMessage("Datos de invitado guardados para esta reserva.");
    } catch {setMessage("No se pudo confirmar el guardado. Puedes reintentar.");}
    finally {setBusy(false);}
  }

  return <form onSubmit={save} className="my-4 grid max-w-md gap-3">
    <fieldset disabled={disabled || busy || !ready} className="grid gap-2">
      <legend className="font-semibold">Continuar como invitado</legend>
      <label>Nombre <input autoComplete="name" required maxLength={150} value={name}
        onChange={e=>{setName(e.target.value);setMessage("");}} className="block w-full rounded border p-2"/></label>
      <label>Teléfono de contacto <input type="tel" autoComplete="tel" required maxLength={30} value={phone}
        onChange={e=>{setPhone(e.target.value);setMessage("");}} className="block w-full rounded border p-2"/></label>
      <button className="w-fit rounded bg-teal-800 px-3 py-2 text-white">{busy?"Guardando…":"Guardar contacto"}</button>
    </fieldset>
    <p role="status">{message}</p>
    {!ready && message && <button type="button" onClick={()=>setRetry(n=>n+1)} className="w-fit underline">Reintentar consulta</button>}
  </form>;
}
