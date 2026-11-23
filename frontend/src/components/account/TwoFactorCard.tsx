"use client";
import {useState} from "react";
import {ShieldAlert, ShieldCheck} from "lucide-react";
import {CodeInput} from "@/components/auth/CodeInput";
import {RecoveryCodes, TwoFactorSetup} from "@/components/auth/TwoFactorSetup";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {Skeleton} from "@/components/ui/skeleton";
import {ApiError, type ApiClient} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";
import {limaDateTime} from "@/lib/utils";

export type MfaStatus = {enabled: boolean; confirmedAt: string | null; recoveryCodesLeft: number; setupPending: boolean};
type Mode = null | "setup" | "codes" | "disable";

/**
 * Tarjeta de verificación en dos pasos: activar con QR, regenerar códigos de recuperación y (si se permite) desactivar.
 * statusPath devuelve {mfa: MfaStatus}; base es la ruta de los endpoints mfa/*.
 */
export function TwoFactorCard({client, statusPath, base, allowDisable, required}: {
  client: ApiClient; statusPath: string; base: string; allowDisable?: boolean; required?: boolean;
}) {
  const {data, error, reload} = useApiData<{mfa: MfaStatus}>(client.url(statusPath));
  const [mode, setMode] = useState<Mode>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const status = data?.mfa;

  function close() { setMode(null); setCodes(null); setMessage(""); reload(); }

  async function withCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get("code"));
    setBusy(true); setMessage("");
    try {
      if (mode === "codes") setCodes((await client.json<{recoveryCodes: string[]}>(base + "/recovery-codes", {code})).recoveryCodes);
      else { await client.json(base + "/disable", {code}); close(); }
    } catch (err) { setMessage(err instanceof ApiError ? err.message : "No se pudo completar la operación."); }
    finally { setBusy(false); }
  }

  return <Card>
    <CardHeader className="flex-row items-start justify-between gap-4">
      <div className="grid gap-1.5">
        <CardTitle>Verificación en dos pasos</CardTitle>
        <CardDescription>Además de tu contraseña, se pide un código de tu app autenticadora (Google Authenticator, Authy, etc.).</CardDescription>
      </div>
      {status && (status.enabled ? <Badge variant="success"><ShieldCheck/>Activa</Badge> : <Badge variant="warning"><ShieldAlert/>Inactiva</Badge>)}
    </CardHeader>
    <CardContent className="grid gap-4">
      {error ? <Alert variant="destructive">{error.message} <Button variant="link" className="h-auto p-0" onClick={reload}>Reintentar</Button></Alert>
        : !status ? <Skeleton className="h-10 w-64"/>
        : status.enabled ? <>
          <p className="text-sm text-muted-foreground">
            Activa desde {status.confirmedAt ? limaDateTime.format(new Date(status.confirmedAt)) : "—"} · {status.recoveryCodesLeft} códigos de recuperación disponibles
          </p>
          {status.recoveryCodesLeft <= 3 && <Alert variant="warning">Te quedan pocos códigos de recuperación. Genera unos nuevos.</Alert>}
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => setMode("codes")}>Generar nuevos códigos</Button>
            <Button variant="outline" onClick={() => setMode("setup")}>Cambiar de celular</Button>
            {allowDisable && !required && <Button variant="ghost" className="text-destructive" onClick={() => setMode("disable")}>Desactivar</Button>}
          </div>
        </> : <div><Button onClick={() => setMode("setup")}><ShieldCheck/>Activar con código QR</Button></div>}
    </CardContent>

    <Dialog open={mode !== null} onOpenChange={open => { if (!open) close(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{mode === "setup" ? "Configurar app autenticadora" : mode === "codes" ? "Nuevos códigos de recuperación" : "Desactivar verificación en dos pasos"}</DialogTitle>
          <DialogDescription>{mode === "setup" ? "Si ya tenías otra app configurada, dejará de valer cuando confirmes esta."
            : mode === "codes" ? "Los códigos anteriores dejarán de servir." : "Tu cuenta quedará protegida solo con la contraseña."}</DialogDescription>
        </DialogHeader>
        {mode === "setup" ? <TwoFactorSetup client={client} base={base} onDone={close} doneLabel="Listo"/>
          : codes ? <RecoveryCodes codes={codes} onDone={close} doneLabel="Listo"/>
          : <form onSubmit={withCode} className="grid gap-3">
            <label htmlFor="confirm-code" className="text-sm font-medium">Confirma con un código de tu app</label>
            <CodeInput id="confirm-code" name="code" required autoFocus disabled={busy}/>
            {message && <Alert variant="destructive">{message}</Alert>}
            <Button type="submit" variant={mode === "disable" ? "destructive" : "default"} loading={busy}>
              {mode === "disable" ? "Desactivar" : "Generar códigos"}
            </Button>
          </form>}
      </DialogContent>
    </Dialog>
  </Card>;
}
