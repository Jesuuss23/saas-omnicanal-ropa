"use client";

import {useState, type FormEvent} from "react";
import {Ban, CalendarClock} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger} from "@/components/ui/dialog";
import {Input, NativeSelect, Textarea} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {toast} from "@/components/ui/toaster";
import {ApiError, superadminApi} from "@/lib/api";
import {type PlanRow, type TenantDetail} from "@/lib/superadmin";
import {money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

/** Kill switch: bloquea el panel y la tienda pública con un motivo que ve el propietario. */
export function SuspendDialog({id, name, onDone}: {id: string; name: string; onDone: (d: TenantDetail) => void}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const reason = String(new FormData(event.currentTarget).get("reason")).trim();
    setBusy(true); setError("");
    try {
      onDone(await superadminApi.json<TenantDetail>("tenants/" + id + "/suspend", {reason}));
      toast.success(name + " quedó suspendida");
      setOpen(false);
    } catch (e) { setError(e instanceof ApiError ? e.message : "No se pudo suspender."); }
    finally { setBusy(false); }
  }
  return <Dialog open={open} onOpenChange={next => { setOpen(next); setError(""); }}>
    <DialogTrigger asChild><Button variant="destructive"><Ban/>Suspender</Button></DialogTrigger>
    <DialogContent>
      <DialogHeader><DialogTitle>Suspender {name}</DialogTitle>
        <DialogDescription>El propietario podrá entrar pero verá el motivo; el panel y la tienda pública dejarán de funcionar. Puedes reactivarla cuando quieras.</DialogDescription></DialogHeader>
      <form onSubmit={submit} className="grid gap-4">
        <Field label="Motivo" htmlFor="suspend-reason" hint="El propietario verá este texto."><Textarea id="suspend-reason" name="reason" maxLength={300} required/></Field>
        {error && <Alert variant="destructive">{error}</Alert>}
        <DialogFooter><Button type="submit" variant="destructive" loading={busy}>Suspender tienda</Button></DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}

type Action = "EXTEND_TRIAL" | "ACTIVATE" | "CANCEL";

/** Ajustes manuales: extender la prueba, activar un plan pagado fuera de Culqi o cancelar. */
export function SubscriptionDialog({id, detail, onDone}: {id: string; detail: TenantDetail; onDone: (d: TenantDetail) => void}) {
  const [open, setOpen] = useState(false);
  const [action, setAction] = useState<Action>(detail.store.status === "ACTIVE" ? "ACTIVATE" : "EXTEND_TRIAL");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const {data: plans} = useApiData<PlanRow[]>(open ? superadminApi.url("plans") : null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const body: Record<string, unknown> = {action, reason: String(f.get("reason")).trim()};
    if (action === "EXTEND_TRIAL") body.days = Number(f.get("days"));
    if (action === "ACTIVATE") { body.planCode = f.get("planCode"); body.months = Number(f.get("months")); }
    setBusy(true); setError("");
    try {
      onDone(await superadminApi.json<TenantDetail>("tenants/" + id + "/subscription", body, "PUT"));
      toast.success("Suscripción actualizada");
      setOpen(false);
    } catch (e) { setError(e instanceof ApiError ? e.message : "No se pudo actualizar."); }
    finally { setBusy(false); }
  }

  return <Dialog open={open} onOpenChange={next => { setOpen(next); setError(""); }}>
    <DialogTrigger asChild><Button variant="outline"><CalendarClock/>Ajustar suscripción</Button></DialogTrigger>
    <DialogContent>
      <DialogHeader><DialogTitle>Ajustar suscripción</DialogTitle>
        <DialogDescription>Queda registrado en el historial de la tienda y en la auditoría.</DialogDescription></DialogHeader>
      <form onSubmit={submit} className="grid gap-4">
        <Field label="Acción" htmlFor="sub-action">
          <NativeSelect id="sub-action" value={action} onChange={e => setAction(e.target.value as Action)}>
            <option value="EXTEND_TRIAL">Extender la prueba gratis</option>
            <option value="ACTIVATE">Activar un plan (pago por transferencia u otro medio)</option>
            <option value="CANCEL">Cancelar la suscripción</option>
          </NativeSelect>
        </Field>
        {action === "EXTEND_TRIAL" && <Field label="Días adicionales" htmlFor="sub-days">
          <Input id="sub-days" name="days" type="number" min={1} max={90} defaultValue={7} required/></Field>}
        {action === "ACTIVATE" && <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
          <Field label="Plan" htmlFor="sub-plan"><NativeSelect id="sub-plan" name="planCode" required defaultValue={detail.store.planCode ?? ""}>
            <option value="" disabled>Elige un plan</option>
            {plans?.filter(p => p.priceCents > 0).map(p => <option key={p.code} value={p.code}>{p.name} · {money.format(p.priceCents / 100)}</option>)}
          </NativeSelect></Field>
          <Field label="Meses" htmlFor="sub-months"><Input id="sub-months" name="months" type="number" min={1} max={24} defaultValue={1} required/></Field>
        </div>}
        {action === "CANCEL" && <Alert variant="warning">La tienda pierde las capacidades del plan. Puede volver a suscribirse desde su panel.</Alert>}
        <Field label="Motivo" htmlFor="sub-reason"><Input id="sub-reason" name="reason" maxLength={200} required placeholder="Ej.: piloto aprobado, pago por Yape"/></Field>
        {error && <Alert variant="destructive">{error}</Alert>}
        <DialogFooter><Button type="submit" loading={busy} variant={action === "CANCEL" ? "destructive" : "default"}>Guardar</Button></DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}
