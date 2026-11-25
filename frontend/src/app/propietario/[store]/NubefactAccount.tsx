"use client";

import {useState, type FormEvent} from "react";
import {Check, ExternalLink, FileCheck2, KeyRound, RefreshCw, Unplug} from "lucide-react";
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

type Account = {available:boolean; linked:boolean; provider:string; apiUrl:string|null; tokenHint:string|null;
  environment:"BETA"|"PRODUCTION"; invoiceSeries:string|null; receiptSeries:string|null; status:string;
  lastError:string|null; verifiedAt:string|null};

const URL_RE = /^https:\/\/([a-z0-9-]+\.)*nubefact\.com(\/[A-Za-z0-9_.-]+)+\/?$/;

/**
 * Conexión con Nubefact: el dueño pega la RUTA y el TOKEN de su cuenta (API - Integración). El backend los prueba
 * con Nubefact, guarda el token cifrado y el POS emite boletas y facturas con esa cuenta.
 */
export default function NubefactAccount({store}:{store:string}) {
  const path = "stores/" + store + "/billing/nubefact";
  const {data, error, mutate, reload} = useApiData<Account>(ownerApi.url(path));
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({apiUrl:"", token:"", environment:"BETA", invoiceSeries:"", receiptSeries:""});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  function edit() {
    setForm({apiUrl:data?.apiUrl ?? "", token:"", environment:data?.environment ?? "BETA",
      invoiceSeries:data?.invoiceSeries ?? "", receiptSeries:data?.receiptSeries ?? ""});
    setMessage(""); setEditing(true);
  }

  async function save(event:FormEvent) {
    event.preventDefault();
    const apiUrl = form.apiUrl.trim();
    if (!URL_RE.test(apiUrl)) { setMessage("La RUTA es la URL https que muestra Nubefact en API - Integración."); return; }
    if (!data?.linked && !form.token.trim()) { setMessage("Pega el TOKEN de tu cuenta de Nubefact."); return; }
    setBusy(true); setMessage("");
    try {
      const account = await ownerApi.json<Account>(path, {...form, apiUrl, token:form.token.trim()}, "PUT");
      mutate(account);
      setEditing(false);
      toast.success("Nubefact conectado", {description:account.environment === "BETA" ? "Ambiente de pruebas: los comprobantes no tienen validez." : "Producción: los comprobantes son reales."});
    } catch (e) {
      setMessage(e instanceof ApiError ? (e.status === 402 ? "Tu plan no incluye facturación en producción." : e.message) : "No se pudo guardar.");
    } finally { setBusy(false); }
  }

  async function test() {
    setBusy(true);
    try {
      mutate(await ownerApi.json<Account>(path + "/test", {}, "POST"));
      toast.success("Conexión con Nubefact correcta");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "No se pudo probar la conexión.");
      reload();
    } finally { setBusy(false); }
  }

  async function disconnect() {
    if (!confirm("¿Desconectar Nubefact? El POS dejará de emitir boletas y facturas.")) return;
    setBusy(true);
    try {
      await ownerApi.json(path, undefined, "DELETE");
      mutate({...data!, linked:false, apiUrl:null, tokenHint:null, status:"NOT_LINKED", lastError:null, verifiedAt:null});
      toast.success("Nubefact desconectado");
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo desconectar."); }
    finally { setBusy(false); }
  }

  const set = (key:keyof typeof form) => (e:{target:{value:string}}) => setForm(f => ({...f, [key]:e.target.value}));

  return <Card>
    <CardHeader>
      <CardTitle className="flex flex-wrap items-center gap-2"><FileCheck2 className="size-5 text-primary"/>Conexión con Nubefact
        {data?.linked && <Badge variant={data.status === "ACTIVE" ? "success" : "destructive"}>{data.status === "ACTIVE" ? "Conectado" : "Revisar"}</Badge>}
        {data?.linked && <Badge variant={data.environment === "PRODUCTION" ? "default" : "warning"}>{data.environment === "PRODUCTION" ? "Producción" : "Beta (pruebas)"}</Badge>}
      </CardTitle>
      <CardDescription>Tus boletas y facturas se emiten con tu propia cuenta de Nubefact, que las envía a SUNAT.</CardDescription>
    </CardHeader>
    <CardContent className="grid gap-5">
      {error && <Alert variant="destructive" title="No se pudo consultar la conexión">{error.message}</Alert>}
      {!data && !error && <div className="grid gap-2"><Skeleton className="h-10"/><Skeleton className="h-10"/></div>}
      {data && !data.available && <Alert variant="destructive">Falta configurar el cifrado en el servidor (SECRETS_ENCRYPTION_KEY).</Alert>}

      {data?.linked && !editing && <>
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div><dt className="text-muted-foreground">RUTA</dt><dd className="break-all font-mono">{data.apiUrl}</dd></div>
          <div><dt className="text-muted-foreground">TOKEN</dt><dd className="font-mono">{data.tokenHint}</dd></div>
          <div><dt className="text-muted-foreground">Series</dt><dd className="font-mono">Factura {data.invoiceSeries} · Boleta {data.receiptSeries}</dd></div>
          <div><dt className="text-muted-foreground">Última verificación</dt><dd>{formatDate(data.verifiedAt, limaDateTime)}</dd></div>
        </dl>
        {data.status !== "ACTIVE" && data.lastError && <Alert variant="destructive" title="Nubefact rechazó la conexión">{data.lastError}</Alert>}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" loading={busy} onClick={() => void test()}><RefreshCw/>Probar conexión</Button>
          <Button variant="outline" disabled={busy} onClick={edit}><KeyRound/>Cambiar llaves o series</Button>
          <Button variant="ghost" className="text-destructive" disabled={busy} onClick={() => void disconnect()}><Unplug/>Desconectar</Button>
        </div>
        {data.status === "ACTIVE" && <p className="flex items-center gap-1.5 text-sm text-success"><Check className="size-4"/>El POS ya puede emitir boletas y facturas.</p>}
      </>}

      {data && (!data.linked || editing) && data.available && <>
        <ol className="grid gap-1.5 text-sm text-muted-foreground">
          <li><span className="font-medium text-foreground">1.</span> Entra a <a className="text-primary underline" href="https://www.nubefact.com" target="_blank" rel="noopener noreferrer">Nubefact <ExternalLink className="inline size-3"/></a> y abre API - Integración.</li>
          <li><span className="font-medium text-foreground">2.</span> Copia la RUTA y el TOKEN. Para pruebas usa una cuenta DEMO (beta).</li>
          <li><span className="font-medium text-foreground">3.</span> Escribe las series que tienes habilitadas en Nubefact.</li>
        </ol>
        <form onSubmit={save} className="grid gap-4">
          <Field label="RUTA" htmlFor="nf-url" hint="Ejemplo: https://api.nubefact.com/api/v1/…">
            <Input id="nf-url" value={form.apiUrl} onChange={set("apiUrl")} autoComplete="off" spellCheck={false} maxLength={300} required className="font-mono"/></Field>
          <Field label="TOKEN" htmlFor="nf-token" hint={data.linked ? "Déjalo vacío para conservar el guardado." : "Se guarda cifrado y no se vuelve a mostrar."}>
            <Input id="nf-token" type="password" value={form.token} onChange={set("token")} autoComplete="new-password" spellCheck={false}
              maxLength={200} required={!data.linked} className="font-mono"/></Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Ambiente" htmlFor="nf-env">
              <select id="nf-env" value={form.environment} onChange={set("environment")} className="h-10 w-full rounded-lg border border-border bg-background px-3 text-sm">
                <option value="BETA">Beta (pruebas)</option><option value="PRODUCTION">Producción</option>
              </select></Field>
            <Field label="Serie de facturas" htmlFor="nf-f" hint="4 caracteres, empieza con F">
              <Input id="nf-f" value={form.invoiceSeries} onChange={set("invoiceSeries")} maxLength={4} required className="font-mono uppercase" placeholder="FFF1"/></Field>
            <Field label="Serie de boletas" htmlFor="nf-b" hint="4 caracteres, empieza con B">
              <Input id="nf-b" value={form.receiptSeries} onChange={set("receiptSeries")} maxLength={4} required className="font-mono uppercase" placeholder="BBB1"/></Field>
          </div>
          {message && <Alert variant="destructive">{message}</Alert>}
          <div className="flex flex-wrap gap-2">
            <Button type="submit" loading={busy}><KeyRound/>Probar y guardar</Button>
            {editing && <Button type="button" variant="outline" disabled={busy} onClick={() => { setEditing(false); setMessage(""); }}>Cancelar</Button>}
          </div>
        </form>
      </>}
    </CardContent>
  </Card>;
}
