"use client";
import {useState, type FormEvent} from "react";
import {apiFetch,superadminApi} from "@/lib/api";

type Store = {id: string; name: string; email: string; active: boolean};

export default function OwnerInvitations() {
  const [stores,setStores] = useState<Store[]>([]);
  const [link,setLink] = useState("");
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);

  async function find(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = String(new FormData(event.currentTarget).get("email"));
    setBusy(true); setLink(""); setMessage(""); setStores([]);
    try {
      const res = await apiFetch(superadminApi.url("owners?email=" + encodeURIComponent(email)));
      if (!res.ok) throw new Error();
      const data: Store[] = await res.json();
      setStores(data);
      if (!data.length) setMessage("No hay tiendas con ese propietario.");
    } catch { setMessage("No se pudo consultar el propietario."); }
    finally { setBusy(false); }
  }

  async function invite(store: Store) {
    setBusy(true); setLink(""); setMessage("");
    try {
      const res = await superadminApi.send("owners/" + store.id + "/invitation");
      if (!res.ok) throw new Error();
      const data = await res.json();
      setLink(location.origin + "/propietario/" + store.id + "#" + data.token);
      setMessage("Entrega este enlace a " + store.email + ". Vence en 24 horas. Generar otro invalida el anterior.");
    } catch { setMessage("No se pudo generar la invitación."); }
    finally { setBusy(false); }
  }

  return <div className="mt-6 border-t border-stone-200 pt-5">
    <h3 className="font-semibold">Acceso del propietario</h3>
    <form onSubmit={find} className="my-3 flex flex-wrap gap-3">
      <input aria-label="Correo asignado" placeholder="Correo asignado" name="email" type="email" required className="rounded border px-3 py-2"/>
      <button disabled={busy} className="text-teal-800 underline">Buscar tiendas</button>
    </form>
    {stores.map(store => <div key={store.id} className="flex items-center justify-between gap-3 py-1">
      <span>{store.name}</span>{store.active ? <span>Acceso activo</span> :
      <button disabled={busy} onClick={()=>invite(store)} className="text-teal-800 underline">Generar invitación</button>}</div>)}
    <p role="status" className="my-2 text-sm">{message}</p>
    {link && <input aria-label="Enlace privado de activación" value={link} readOnly onFocus={e=>e.target.select()} className="w-full rounded border p-2"/>}
  </div>;
}
