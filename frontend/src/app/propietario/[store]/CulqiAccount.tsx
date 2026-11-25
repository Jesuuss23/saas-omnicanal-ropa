"use client";

import {useState, type FormEvent} from "react";
import {Check, Copy, CreditCard, ExternalLink, KeyRound, Unplug} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {formatDate, limaDateTime} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Account = {available:boolean; linked:boolean; provider:string; publicKey:string|null; secretKeyHint:string|null;
  liveMode:boolean; status:string; lastError:string|null; linkedAt:string|null; webhookUrl:string|null};

const PK = /^pk_(test|live)_[A-Za-z0-9]{8,}$/;
const SK = /^sk_(test|live)_[A-Za-z0-9]{8,}$/;

/**
 * "Activar cobros en línea": el dueño pega las llaves de su propia cuenta Culqi. El backend las valida con Culqi,
 * guarda la secreta cifrada y los pagos de la tienda web llegan directo a esa cuenta.
 */
export default function CulqiAccount({store}:{store:string}) {
  const path = "stores/" + store + "/payments/culqi";
  const {data, error, mutate} = useApiData<Account>(ownerApi.url(path));
  const [editing, setEditing] = useState(false);
  const [publicKey, setPublicKey] = useState("");
  const [secretKey, setSecretKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const pk = publicKey.trim(), sk = secretKey.trim();
  const mismatch = PK.test(pk) && SK.test(sk) && pk.startsWith("pk_live_") !== sk.startsWith("sk_live_");

  async function save(event:FormEvent) {
    event.preventDefault();
    if (!PK.test(pk)) { setMessage("La llave pública empieza con pk_test_ o pk_live_."); return; }
    if (!SK.test(sk)) { setMessage("La llave secreta empieza con sk_test_ o sk_live_."); return; }
    if (mismatch) { setMessage("Las dos llaves deben ser del mismo modo: ambas de prueba o ambas de producción."); return; }
    setBusy(true); setMessage("");
    try {
      const account = await ownerApi.json<Account>(path, {publicKey:pk, secretKey:sk}, "PUT");
      mutate(account);
      setEditing(false); setPublicKey(""); setSecretKey("");
      toast.success("Cobros en línea activados", {description:account.liveMode ? "Modo producción: los pagos son reales." : "Modo prueba: usa tarjetas de prueba de Culqi."});
    } catch (e) {
      setMessage(e instanceof ApiError ? (e.status === 402 ? "Tu plan no incluye cobros en línea." : e.message) : "No se pudo guardar.");
    } finally { setBusy(false); }
  }

  async function disconnect() {
    if (!confirm("¿Desconectar Culqi? Tu tienda dejará de aceptar pagos con tarjeta.")) return;
    setBusy(true);
    try {
      await ownerApi.json(path, undefined, "DELETE");
      mutate({...data!, linked:false, publicKey:null, secretKeyHint:null, status:"NOT_LINKED", lastError:null, linkedAt:null});
      toast.success("Culqi desconectado");
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo desconectar."); }
    finally { setBusy(false); }
  }

  async function copy(text:string) {
    try { await navigator.clipboard.writeText(text); toast.success("URL copiada"); } catch { toast.error("No se pudo copiar"); }
  }

  const form = <form onSubmit={save} className="grid gap-4">
    <Field label="Llave pública" htmlFor="culqi-pk" hint="La ve el comprador en tu tienda web. Empieza con pk_test_ o pk_live_.">
      <Input id="culqi-pk" value={publicKey} onChange={e => setPublicKey(e.target.value)} autoComplete="off" spellCheck={false}
        placeholder="pk_live_…" maxLength={100} required className="font-mono"/></Field>
    <Field label="Llave secreta" htmlFor="culqi-sk" hint="Se guarda cifrada y nunca se vuelve a mostrar. Empieza con sk_test_ o sk_live_.">
      <Input id="culqi-sk" type="password" value={secretKey} onChange={e => setSecretKey(e.target.value)} autoComplete="new-password"
        spellCheck={false} placeholder="sk_live_…" maxLength={100} required className="font-mono"/></Field>
    {mismatch && <Alert variant="destructive">Una llave es de prueba y la otra de producción. Copia ambas del mismo entorno en Culqi.</Alert>}
    {message && <Alert variant="destructive">{message}</Alert>}
    <div className="flex flex-wrap gap-2">
      <Button type="submit" loading={busy}><KeyRound/>{data?.linked ? "Validar y actualizar llaves" : "Validar y activar cobros"}</Button>
      {editing && <Button type="button" variant="outline" disabled={busy} onClick={() => { setEditing(false); setMessage(""); }}>Cancelar</Button>}
    </div>
  </form>;

  return <Card>
    <CardHeader>
      <CardTitle className="flex flex-wrap items-center gap-2"><CreditCard className="size-5 text-primary"/>Cobros en línea con Culqi
        {data?.linked && <Badge variant={data.status === "ACTIVE" ? "success" : "destructive"}>{data.status === "ACTIVE" ? "Activo" : "Revisar"}</Badge>}
        {data?.linked && <Badge variant={data.liveMode ? "default" : "warning"}>{data.liveMode ? "Producción" : "Modo prueba"}</Badge>}
      </CardTitle>
      <CardDescription>Tus clientes pagan con tarjeta en tu tienda web y el dinero llega directo a tu cuenta Culqi. Nosotros no tocamos tu dinero.</CardDescription>
    </CardHeader>
    <CardContent className="grid gap-5">
      {error && <Alert variant="destructive" title="No se pudo consultar la conexión">{error.message}</Alert>}
      {!data && !error && <div className="grid gap-2"><Skeleton className="h-10"/><Skeleton className="h-10"/></div>}
      {data && !data.available && <Alert variant="destructive">Falta configurar el cifrado en el servidor (SECRETS_ENCRYPTION_KEY). Avísanos para activarlo.</Alert>}

      {data?.linked && !editing && <>
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div><dt className="text-muted-foreground">Llave pública</dt><dd className="break-all font-mono">{data.publicKey}</dd></div>
          <div><dt className="text-muted-foreground">Llave secreta</dt><dd className="font-mono">{data.secretKeyHint}</dd></div>
          <div><dt className="text-muted-foreground">Conectada</dt><dd>{formatDate(data.linkedAt, limaDateTime)}</dd></div>
        </dl>
        {data.status !== "ACTIVE" && data.lastError && <Alert variant="destructive" title="Culqi rechazó la conexión">{data.lastError}. Vuelve a pegar tus llaves.</Alert>}
        {!data.liveMode && <Alert>Estás en modo prueba: los pagos no son reales. Cuando Culqi active tu comercio, cambia a las llaves pk_live_ y sk_live_.</Alert>}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={busy} onClick={() => setEditing(true)}><KeyRound/>Actualizar llaves</Button>
          <Button variant="ghost" className="text-destructive" disabled={busy} onClick={() => void disconnect()}><Unplug/>Desconectar</Button>
        </div>
      </>}

      {data && (!data.linked || editing) && data.available && <>
        <ol className="grid gap-1.5 text-sm text-muted-foreground">
          <li><span className="font-medium text-foreground">1.</span> Entra a tu <a className="text-primary underline" href="https://mipanel.culqi.com" target="_blank" rel="noopener noreferrer">panel de Culqi <ExternalLink className="inline size-3"/></a> y abre Desarrollo → API Keys.</li>
          <li><span className="font-medium text-foreground">2.</span> Copia la llave pública y la secreta del mismo entorno (integración o producción).</li>
          <li><span className="font-medium text-foreground">3.</span> Pégalas aquí: las validamos con Culqi antes de activar los cobros.</li>
        </ol>
        {form}
      </>}

      {data?.webhookUrl && <div className="grid gap-1.5 rounded-lg border bg-muted/40 p-3 text-sm">
        <p className="font-medium">Webhook de respaldo (recomendado)</p>
        <p className="text-muted-foreground">En Culqi → Desarrollo → Eventos, agrega esta URL para los eventos <code>charge.creation.succeeded</code> y <code>refund.creation.succeeded</code>. Si el cliente cierra la página justo al pagar, el pedido igual queda pagado.</p>
        <div className="flex items-center gap-2">
          <code className="min-w-0 flex-1 truncate rounded bg-background px-2 py-1 text-xs">{data.webhookUrl}</code>
          <Button type="button" size="sm" variant="outline" onClick={() => void copy(data.webhookUrl!)}><Copy/>Copiar</Button>
        </div>
      </div>}
      {data?.linked && data.status === "ACTIVE" && !editing && <p className="flex items-center gap-1.5 text-sm text-success"><Check className="size-4"/>Tu tienda web ya muestra el botón «Pagar con tarjeta».</p>}
    </CardContent>
  </Card>;
}
