"use client";
import {API_EVENTS,apiFetch,ownerApi} from "@/lib/api";
import {useEffect,useState,useSyncExternalStore,type FormEvent} from "react";
import Link from "next/link";
import ContactsCrm from "./ContactsCrm";
import WhatsAppChannels from "./WhatsAppChannels";
import SubscriptionPlan from "./SubscriptionPlan";
import MercadoPagoAccount from "./MercadoPagoAccount";
import PointOfSale from "./PointOfSale";
import FiscalIssuer from "./FiscalIssuer";
import CategoryResources from "./CategoryResources";
import PickupSettings from "./PickupSettings";
import WebOrders from "./WebOrders";
import Catalog from "./Catalog";
import {useParams,useRouter} from "next/navigation";
import {AuthShell} from "@/components/auth/AuthShell";
import {OwnerLoginForm} from "@/components/auth/OwnerLoginForm";
import {TwoFactorCard} from "@/components/account/TwoFactorCard";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Spinner} from "@/components/ui/skeleton";

type Profile = {id:string; name:string; email:string};

const send = ownerApi.send;

function subscribeHash(onChange:()=>void) {
  window.addEventListener("hashchange",onChange);
  return ()=>window.removeEventListener("hashchange",onChange);
}

export default function Owner() {
  const {store} = useParams<{store:string}>();
  return <OwnerPanel key={store} store={store}/>;
}

function OwnerPanel({store}:{store:string}) {
  // El enlace de invitación trae el token en el fragmento (#token), que nunca viaja al servidor.
  const token = useSyncExternalStore(subscribeHash,()=>location.hash.slice(1),()=>"");
  const [profile,setProfile] = useState<Profile|null>(null);
  const [message,setMessage] = useState("");
  const [loading,setLoading] = useState(true);
  const [busy,setBusy] = useState(false);
  const router = useRouter();
  useEffect(()=>{
    let current = true;
    const invitation = location.hash.slice(1);
    apiFetch(ownerApi.url("me")).then(async res=>{
      if (res.ok) {
        const data:Profile = await res.json();
        if (current && data.id === store && !invitation) setProfile(data);
      }
    }).catch(()=>{if(current) setMessage("No se pudo consultar la sesión.");})
      .finally(()=>{if(current) setLoading(false);});
    return ()=>{current=false;};
  },[store]);
  useEffect(()=>{
    const expired = ()=>{setProfile(null); setMessage("Tu sesión terminó. Vuelve a ingresar.");};
    window.addEventListener(API_EVENTS.unauthorized,expired);
    return ()=>window.removeEventListener(API_EVENTS.unauthorized,expired);
  },[]);

  async function activate(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const password = String(new FormData(form).get("password"));
    setBusy(true); setMessage("");
    try {
      const res = await send("activate",JSON.stringify({store,token,password}));
      if (!res.ok) {setMessage("Verifica la contraseña y la vigencia de la invitación."); return;}
      history.replaceState(null,"",location.pathname);
      window.dispatchEvent(new HashChangeEvent("hashchange"));
      form.reset(); setMessage("Cuenta activada. Ingresa con tu correo y tu nueva contraseña.");
    } catch {setMessage("No se pudo confirmar la operación.");}
    finally {setBusy(false);}
  }

  async function signedIn(target:string) {
    if (target !== store) {router.push("/propietario/"+encodeURIComponent(target)); return;}
    try {
      const session = await apiFetch(ownerApi.url("me"));
      if (!session.ok) throw new Error();
      setMessage(""); setProfile(await session.json());
    } catch {setMessage("No se pudo abrir la sesión.");}
  }

  async function logout() {
    setBusy(true); setMessage("");
    try {if (!(await send("logout")).ok) throw new Error(); setProfile(null);}
    catch {setMessage("No se pudo cerrar la sesión.");}
    finally {setBusy(false);}
  }

  if (loading) return <main className="grid min-h-dvh place-items-center"><Spinner label="Cargando tu tienda…"/></main>;

  if (!profile) return token
    ? <AuthShell eyebrow="Invitación" title="Activa tu acceso" description="Crea la contraseña con la que administrarás tu tienda.">
      <form onSubmit={activate} className="grid gap-5">
        <Field label="Nueva contraseña" htmlFor="new-password" hint="Al menos 12 caracteres. Usa una frase que solo tú conozcas.">
          <Input id="new-password" type="password" name="password" required minLength={12} maxLength={72} autoComplete="new-password" disabled={busy}/>
        </Field>
        {message && <Alert>{message}</Alert>}
        <Button size="lg" type="submit" loading={busy}>Activar cuenta</Button>
      </form>
    </AuthShell>
    : <AuthShell eyebrow="Panel de tu tienda" title="Ingresa a tu tienda" description="Usa el correo con el que registraste tu tienda.">
      {message && <Alert className="mb-5">{message}</Alert>}
      <OwnerLoginForm store={store} onSuccess={target=>void signedIn(target)}/>
    </AuthShell>;

  return <main className="grid min-h-screen place-items-center bg-stone-50 p-6 text-stone-900">
    <section className="w-full max-w-5xl rounded-xl border border-stone-200 bg-white p-8">
    <h1 className="text-2xl font-semibold">{profile.name}</h1>
    <p className="my-4">{profile.email}</p>
    <Link className="mr-5 text-teal-800 underline" href={"/tienda/"+profile.id}>Ver catálogo público</Link>
    <button disabled={busy} onClick={logout} className="text-teal-800 underline">Cerrar sesión</button>
    <div className="mt-6"><TwoFactorCard client={ownerApi} statusPath="security" base="security/mfa" allowDisable/></div>
    <PickupSettings key={"pickup-"+profile.id} store={profile.id} send={send}/><WebOrders key={"orders-"+profile.id} store={profile.id} send={send}/><SubscriptionPlan key={"plan-"+profile.id} store={profile.id} send={send}/><MercadoPagoAccount key={"mp-"+profile.id} store={profile.id} send={send}/><WhatsAppChannels key={"wa-"+profile.id} store={profile.id} send={send}/><ContactsCrm key={"crm-"+profile.id} store={profile.id} send={send}/><FiscalIssuer key={"issuer-"+profile.id} store={profile.id} send={send}/><PointOfSale key={"pos-" + profile.id} store={profile.id} send={send}/><CategoryResources key={"resources-"+profile.id} store={profile.id} send={send}/><Catalog key={profile.id} store={profile.id} send={send}/>
    <p role="status" className="mt-3 text-sm">{message}</p></section></main>;
}
