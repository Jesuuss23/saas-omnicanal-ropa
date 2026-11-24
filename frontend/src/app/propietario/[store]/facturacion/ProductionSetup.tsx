"use client";

import {useState, type FormEvent} from "react";
import {CheckCircle2, Circle, FileKey2, KeyRound, ListOrdered, Rocket, ShieldAlert} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {cn, formatDate} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Setup = {issuerRuc: string | null; legalName: string | null; issuerReady: boolean; solUser: string | null; solReady: boolean;
  certificateSubject: string | null; certificateNotAfter: string | null; certificateReady: boolean; invoiceSeries: string | null;
  receiptSeries: string | null; seriesReady: boolean; status: "DRAFT" | "ACTIVE" | "DISABLED"; activatedAt: string | null;
  activatedBy: string | null; planIncludesProduction: boolean; revision: number};

/** Alta para emitir en producción: 4 pasos y activación explícita. Solo la ve el propietario. */
export default function ProductionSetup({store}: {store: string}) {
  const path = "stores/" + store + "/billing/production";
  const [refresh, setRefresh] = useState(0);
  const setup = useApiData<Setup>(ownerApi.url(path), refresh);
  const reload = () => setRefresh(r => r + 1);

  async function call(sub: string, body: unknown, method: "PUT" | "POST", ok: string) {
    try {
      await ownerApi.json(path + "/" + sub, body, method);
      toast.success(ok);
      reload();
      return true;
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "No se pudo guardar.");
      return false;
    }
  }

  if (setup.error) return <Alert variant="destructive">{setup.error.message}</Alert>;
  const s = setup.data;
  if (!s) return <Skeleton className="h-96"/>;
  const steps = [s.issuerReady, s.solReady, s.certificateReady, s.seriesReady];

  return <Card>
    <CardHeader className="flex-row items-start justify-between gap-4">
      <div className="grid gap-1"><CardTitle className="flex items-center gap-2"><Rocket className="size-5 text-primary"/>Emisión en producción</CardTitle>
        <CardDescription>Cuando lo actives, tus boletas y facturas tendrán validez ante SUNAT. Antes, todo queda en modo de prueba (Beta).</CardDescription></div>
      <Badge variant={s.status === "ACTIVE" ? "success" : "neutral"}>{s.status === "ACTIVE" ? "Activa" : s.status === "DISABLED" ? "Desactivada" : "Borrador"}</Badge>
    </CardHeader>
    <CardContent className="grid gap-6">
      <ol className="grid gap-2 sm:grid-cols-4" aria-label="Pasos">
        {["Datos del emisor", "Usuario SOL", "Certificado digital", "Series"].map((label, i) =>
          <li key={label} className={cn("flex items-center gap-2 rounded-lg border p-3 text-sm", steps[i] && "border-success/40 bg-success/5")}>
            {steps[i] ? <CheckCircle2 className="size-4 shrink-0 text-success"/> : <Circle className="size-4 shrink-0 text-muted-foreground"/>}
            <span><span className="text-muted-foreground">{i + 1}.</span> {label}</span></li>)}
      </ol>
      {!s.issuerReady ? <Alert>Primero guarda los datos del emisor (RUC, razón social y dirección) en el formulario de arriba.</Alert> : <>
        <SolStep key={"sol" + s.revision} setup={s} onSave={(user, password) => call("sol", {user, password, revision: s.revision}, "PUT", "Usuario SOL guardado")}/>
        <CertificateStep key={"cert" + s.revision} setup={s} onSave={(pfxBase64, password) => call("certificate", {pfxBase64, password, revision: s.revision}, "PUT", "Certificado validado")}/>
        <SeriesStep key={"series" + s.revision} setup={s} onSave={(invoice, receipt) => call("series", {invoice, receipt, revision: s.revision}, "PUT", "Series guardadas")}/>
        <Activation key={"act" + s.revision} setup={s} ready={steps.every(Boolean)}
          onActivate={ruc => call("activate", {ruc, revision: s.revision}, "POST", "Emisión en producción activada")}
          onDeactivate={() => call("deactivate", {revision: s.revision}, "POST", "Emisión en producción desactivada")}/>
      </>}
    </CardContent>
  </Card>;
}

function Step({icon: Icon, title, done, children}: {icon: typeof KeyRound; title: string; done: boolean; children: React.ReactNode}) {
  return <section className="grid gap-3 rounded-lg border p-4">
    <h3 className="flex items-center gap-2 font-medium"><Icon className="size-4 text-primary"/>{title}{done && <Badge variant="success">Listo</Badge>}</h3>
    {children}
  </section>;
}

function SolStep({setup, onSave}: {setup: Setup; onSave: (user: string, password: string) => Promise<boolean>}) {
  const [user, setUser] = useState(setup.solUser ?? "");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) { e.preventDefault(); setBusy(true); await onSave(user, password); setBusy(false); }
  return <Step icon={KeyRound} title="2. Usuario secundario SOL" done={setup.solReady}>
    <p className="text-sm text-muted-foreground">Crea en SUNAT Operaciones en Línea un usuario secundario con permiso de emisión electrónica. La clave se guarda cifrada y no se vuelve a mostrar.</p>
    <form onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
      <Field label="Usuario (sin el RUC)" htmlFor="sol-user"><Input id="sol-user" value={user} onChange={e => setUser(e.target.value)} maxLength={20} required autoComplete="off"/></Field>
      <Field label={setup.solReady ? "Nueva clave SOL" : "Clave SOL"} htmlFor="sol-pass"><Input id="sol-pass" type="password" value={password} onChange={e => setPassword(e.target.value)} maxLength={50} required autoComplete="new-password"/></Field>
      <Button type="submit" variant="outline" loading={busy}>Guardar</Button>
    </form>
  </Step>;
}

function CertificateStep({setup, onSave}: {setup: Setup; onSave: (pfx: string, password: string) => Promise<boolean>}) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    if (file.size > 64 * 1024) { toast.error("El certificado pesa más de 64 KB."); return; }
    setBusy(true);
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = "";
    bytes.forEach(b => { binary += String.fromCharCode(b); });
    await onSave(btoa(binary), password);
    setBusy(false);
  }
  return <Step icon={FileKey2} title="3. Certificado digital" done={setup.certificateReady}>
    {setup.certificateSubject && <p className="text-sm">Cargado: <span className="break-all text-muted-foreground">{setup.certificateSubject}</span>
      {setup.certificateNotAfter && <> · vence el {formatDate(setup.certificateNotAfter)}</>}</p>}
    <form onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
      <Field label="Archivo .pfx o .p12" htmlFor="cert-file"><Input id="cert-file" type="file" accept=".pfx,.p12,application/x-pkcs12" required onChange={e => setFile(e.target.files?.[0] ?? null)}/></Field>
      <Field label="Clave del certificado" htmlFor="cert-pass"><Input id="cert-pass" type="password" value={password} onChange={e => setPassword(e.target.value)} maxLength={100} autoComplete="new-password"/></Field>
      <Button type="submit" variant="outline" loading={busy}>Validar y guardar</Button>
    </form>
  </Step>;
}

function SeriesStep({setup, onSave}: {setup: Setup; onSave: (invoice: string, receipt: string) => Promise<boolean>}) {
  const [invoice, setInvoice] = useState(setup.invoiceSeries ?? "F001");
  const [receipt, setReceipt] = useState(setup.receiptSeries ?? "B001");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) { e.preventDefault(); setBusy(true); await onSave(invoice, receipt); setBusy(false); }
  return <Step icon={ListOrdered} title="4. Series de comprobantes" done={setup.seriesReady}>
    <form onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
      <Field label="Serie de facturas" htmlFor="ser-f"><Input id="ser-f" value={invoice} onChange={e => setInvoice(e.target.value.toUpperCase())} maxLength={4} pattern="F[A-Z0-9]{3}" required/></Field>
      <Field label="Serie de boletas" htmlFor="ser-b"><Input id="ser-b" value={receipt} onChange={e => setReceipt(e.target.value.toUpperCase())} maxLength={4} pattern="B[A-Z0-9]{3}" required/></Field>
      <Button type="submit" variant="outline" loading={busy}>Guardar</Button>
    </form>
  </Step>;
}

function Activation({setup, ready, onActivate, onDeactivate}: {setup: Setup; ready: boolean; onActivate: (ruc: string) => Promise<boolean>; onDeactivate: () => Promise<boolean>}) {
  const [ruc, setRuc] = useState("");
  const [busy, setBusy] = useState(false);
  if (setup.status === "ACTIVE") return <section className="grid gap-3 rounded-lg border border-success/40 bg-success/5 p-4">
    <p className="text-sm">Activada el {formatDate(setup.activatedAt)}{setup.activatedBy ? " por " + setup.activatedBy : ""}. Cambiar cualquier paso la vuelve a borrador.</p>
    <div><Button variant="outline" loading={busy} onClick={async () => { setBusy(true); await onDeactivate(); setBusy(false); }}>Desactivar</Button></div>
  </section>;
  return <section className="grid gap-3 rounded-lg border border-warning/40 bg-warning/5 p-4">
    <h3 className="flex items-center gap-2 font-medium"><ShieldAlert className="size-4 text-warning-foreground"/>Activar emisión real</h3>
    {!setup.planIncludesProduction && <p className="text-sm">Tu plan actual no incluye facturación en producción.</p>}
    <p className="text-sm text-muted-foreground">Desde ese momento cada comprobante se informa a SUNAT y no se puede borrar, solo anular con nota de crédito. Escribe tu RUC para confirmar.</p>
    <form className="flex flex-wrap items-end gap-3" onSubmit={async e => { e.preventDefault(); setBusy(true); await onActivate(ruc); setBusy(false); }}>
      <Field label={"RUC " + (setup.issuerRuc ?? "")} htmlFor="act-ruc"><Input id="act-ruc" value={ruc} onChange={e => setRuc(e.target.value.replace(/\D/g, ""))} inputMode="numeric" maxLength={11} required className="max-w-48"/></Field>
      <Button type="submit" loading={busy} disabled={!ready || !setup.planIncludesProduction || ruc !== setup.issuerRuc}>Activar producción</Button>
    </form>
  </section>;
}
