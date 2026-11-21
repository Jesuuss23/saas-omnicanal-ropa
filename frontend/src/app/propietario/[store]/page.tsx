"use client";
import {useEffect,useState,type FormEvent} from "react";
import PointOfSale from "./PointOfSale";
import FiscalIssuer from "./FiscalIssuer";
import Catalog from "./Catalog";
import {useParams} from "next/navigation";

type Profile = {id:string; name:string; email:string};

async function send(path:string, body?:URLSearchParams|string, method: "POST" | "PUT" = "POST") {
  const csrf = await fetch("/api/owner/csrf",{cache:"no-store"});
  if (!csrf.ok) throw new Error();
  const headers = new Headers({"X-CSRF-TOKEN":(await csrf.json()).token});
  if (typeof body === "string") headers.set("Content-Type","application/json");
  return fetch("/api/owner/"+path,{method,headers,body});
}

export default function Owner() {
  const {store} = useParams<{store:string}>();
  const [token,setToken] = useState("");
  const [profile,setProfile] = useState<Profile|null>(null);
  const [message,setMessage] = useState("");
  const [loading,setLoading] = useState(true);
  const [busy,setBusy] = useState(false);
  useEffect(()=>{
    let current = true;
    const invitation = location.hash.slice(1);
    setToken(invitation);
    setLoading(true); setProfile(null);
    fetch("/api/owner/me",{cache:"no-store"}).then(async res=>{
      if (res.ok) {
        const data:Profile = await res.json();
        if (current && data.id === store && !invitation) setProfile(data);
      }
    }).catch(()=>{if(current) setMessage("No se pudo consultar la sesión.");})
      .finally(()=>{if(current) setLoading(false);});
    return ()=>{current=false;};
  },[store]);

  async function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const password = String(new FormData(form).get("password"));
    setBusy(true); setMessage("");
    try {
      if (token) {
        const res = await send("activate",JSON.stringify({store,token,password}));
        if (!res.ok) {setMessage("Verifica la contraseña y la vigencia de la invitación."); return;}
        history.replaceState(null,"",location.pathname);
        setToken(""); form.reset(); setMessage("Cuenta activada. Ingresa con tu contraseña.");
      } else {
        const res = await send("login",new URLSearchParams({username:store,password}));
        if (!res.ok) {setMessage("Acceso denegado."); return;}
        const session = await fetch("/api/owner/me",{cache:"no-store"});
        if (!session.ok) throw new Error();
        setProfile(await session.json());
      }
    } catch {setMessage("No se pudo confirmar la operación.");}
    finally {setBusy(false);}
  }

  async function logout() {
    setBusy(true); setMessage("");
    try {if (!(await send("logout")).ok) throw new Error(); setProfile(null);}
    catch {setMessage("No se pudo cerrar la sesión.");}
    finally {setBusy(false);}
  }

  return <main className="grid min-h-screen place-items-center bg-stone-50 p-6 text-stone-900">
    <section className={`w-full ${profile ? "max-w-5xl" : "max-w-md"} rounded-xl border border-stone-200 bg-white p-8`}>
    <h1 className="text-2xl font-semibold">{profile?.name || (token ? "Activar acceso de propietario" : "Acceso del propietario")}</h1>
    {loading ? <p>Cargando…</p> : profile ? <><p className="my-4">{profile.email}</p>
    <button disabled={busy} onClick={logout} className="text-teal-800 underline">Cerrar sesión</button><FiscalIssuer key={"issuer-"+profile.id} store={profile.id} send={send}/><PointOfSale key={"pos-" + profile.id} store={profile.id} send={send}/><Catalog key={profile.id} store={profile.id} send={send}/></> :
    <form onSubmit={submit} className="mt-5 space-y-4"><label className="block">{token ? "Crea una contraseña de al menos 12 caracteres" : "Contraseña"}
    <input className="mt-2 w-full rounded-lg border border-stone-300 p-3" type="password" name="password" required minLength={token ? 12 : undefined}
      autoComplete={token ? "new-password" : "current-password"}/></label>
    <button disabled={busy} className="w-full rounded-lg bg-teal-800 p-3 text-white">{token ? "Activar cuenta" : "Ingresar"}</button></form>}
    <p role="status" className="mt-3 text-sm">{message}</p></section></main>;
}
