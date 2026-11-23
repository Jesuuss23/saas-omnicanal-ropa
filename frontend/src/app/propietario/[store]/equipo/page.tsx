"use client";

import {useState, type FormEvent} from "react";
import {Copy, MailCheck, RotateCw, UserPlus, Users} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {formatDate} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {useStore} from "../_panel/StoreContext";

type Role = "MANAGER" | "SELLER";
type Member = {id: string; email: string; fullName: string; role: Role; active: boolean; activated: boolean;
  invitationPending: boolean; lastLoginAt: string | null; createdAt: string};
type Team = {members: Member[]; used: number; limit: number | null};
type Invitation = {member: Member; link: string; emailed: boolean};

const roleLabels: Record<Role, {name: string; description: string}> = {
  MANAGER: {name: "Encargado", description: "Vende, gestiona catálogo, pedidos y WhatsApp, y configura apariencia, recojo y facturación."},
  SELLER: {name: "Vendedor", description: "Vende en el POS, atiende pedidos, clientes y WhatsApp."},
};

const selectClass = "h-10 w-full rounded-lg border border-input bg-background px-3 text-sm";

export default function TeamPage() {
  const {store} = useStore();
  const {data, error, reload} = useApiData<Team>(ownerApi.url("stores/" + store + "/team"));
  const [link, setLink] = useState<Invitation | null>(null);
  const full = data && data.limit !== null && data.used >= data.limit;

  return <>
    <PageHeader title="Equipo" description="Invita a tu equipo con su propio acceso. Cada persona ingresa con su correo y solo ve lo que su rol permite."
      actions={<InviteDialog store={store} disabled={!data || Boolean(full)} onInvited={invitation => { setLink(invitation); reload(); }}/>}/>
    {error && <Alert variant="destructive">{error.message}</Alert>}
    {data && <p className="mb-4 text-sm text-muted-foreground">
      {data.limit === null ? data.used + " usuarios activos (sin límite en tu plan)"
        : data.limit <= 1 ? "Tu plan actual no incluye usuarios adicionales. Mejóralo desde Plan para invitar a tu equipo."
        : data.used + " de " + data.limit + " usuarios del plan, contándote a ti."}
      {full && data.limit !== null && data.limit > 1 && " Para sumar a alguien más, desactiva a un miembro o mejora tu plan."}</p>}
    {link && <InvitationLink invitation={link} onClose={() => setLink(null)}/>}
    <Card className="p-0">
      {!data ? <div className="grid gap-2 p-4">{Array.from({length: 3}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>
        : !data.members.length ? <EmptyState icon={Users} title="Aún trabajas solo"
          description="Invita a un encargado o a tus vendedores para que atiendan el POS y WhatsApp con su propio acceso."/>
        : <Table><TableHeader><TableRow><TableHead>Persona</TableHead><TableHead>Rol</TableHead><TableHead>Estado</TableHead>
            <TableHead>Último ingreso</TableHead><TableHead className="text-right">Acciones</TableHead></TableRow></TableHeader>
          <TableBody>{data.members.map(m => <MemberRow key={m.id} store={store} member={m} onChanged={reload} onLink={setLink}/>)}</TableBody></Table>}
    </Card>
    <div className="mt-6 grid gap-3 sm:grid-cols-2">
      {(Object.keys(roleLabels) as Role[]).map(r => <Card key={r} className="p-4 text-sm">
        <p className="font-medium">{roleLabels[r].name}</p><p className="mt-1 text-muted-foreground">{roleLabels[r].description}</p></Card>)}
    </div>
  </>;
}

function MemberRow({store, member, onChanged, onLink}: {store: string; member: Member; onChanged: () => void; onLink: (i: Invitation) => void}) {
  const [busy, setBusy] = useState(false);
  const path = "stores/" + store + "/team/" + member.id;

  async function change(role: Role, active: boolean) {
    setBusy(true);
    try {
      await ownerApi.json(path, {role, active}, "PUT");
      toast.success(active === member.active ? "Rol actualizado" : active ? member.fullName + " puede volver a ingresar" : member.fullName + " ya no tiene acceso");
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError && e.status === 402 ? "Tu plan no admite más usuarios activos." : "No se pudo guardar el cambio.");
    } finally { setBusy(false); }
  }

  async function resend() {
    setBusy(true);
    try {
      onLink(await ownerApi.json<Invitation>(path + "/invitation"));
    } catch { toast.error("No se pudo generar el enlace."); }
    finally { setBusy(false); }
  }

  return <TableRow>
    <TableCell><span className="font-medium">{member.fullName}</span><span className="block text-xs text-muted-foreground">{member.email}</span></TableCell>
    <TableCell>
      <label className="sr-only" htmlFor={"role-" + member.id}>Rol de {member.fullName}</label>
      <select id={"role-" + member.id} value={member.role} disabled={busy || !member.active} onChange={e => void change(e.target.value as Role, member.active)}
        className="h-9 rounded-lg border border-input bg-background px-2 text-sm">
        {(Object.keys(roleLabels) as Role[]).map(r => <option key={r} value={r}>{roleLabels[r].name}</option>)}
      </select>
    </TableCell>
    <TableCell>{!member.active ? <Badge variant="neutral">Desactivado</Badge>
      : member.activated ? <Badge variant="success">Activo</Badge> : <Badge variant="warning">Invitación pendiente</Badge>}</TableCell>
    <TableCell className="text-sm text-muted-foreground">{member.lastLoginAt ? formatDate(member.lastLoginAt) : "Nunca"}</TableCell>
    <TableCell><div className="flex justify-end gap-2">
      {member.active && !member.activated && <Button size="sm" variant="outline" disabled={busy} onClick={resend}><RotateCw/>Reenviar invitación</Button>}
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => void change(member.role, !member.active)}>
        {member.active ? "Desactivar" : "Reactivar"}</Button>
    </div></TableCell>
  </TableRow>;
}

function InviteDialog({store, disabled, onInvited}: {store: string; disabled: boolean; onInvited: (i: Invitation) => void}) {
  const [open, setOpen] = useState(false);
  const [role, setRole] = useState<Role>("SELLER");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      const invitation = await ownerApi.json<Invitation>("stores/" + store + "/team",
        {email: String(data.get("email")).trim(), fullName: String(data.get("fullName")).trim(), role});
      setOpen(false);
      onInvited(invitation);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? "Ese correo ya tiene acceso a esta tienda."
        : e instanceof ApiError && e.status === 402 ? "Tu plan no admite más usuarios. Mejóralo desde Plan."
        : "Revisa el nombre y el correo.");
    } finally { setBusy(false); }
  }

  return <Dialog open={open} onOpenChange={next => { setOpen(next); if (!next) setError(""); }}>
    <DialogTrigger asChild><Button disabled={disabled}><UserPlus/>Invitar persona</Button></DialogTrigger>
    <DialogContent>
      <DialogHeader><DialogTitle>Invitar al equipo</DialogTitle>
        <DialogDescription>Le enviaremos un enlace por correo para que cree su contraseña. Vence en 72 horas.</DialogDescription></DialogHeader>
      <form onSubmit={submit} className="grid gap-4">
        <Field label="Nombre" htmlFor="member-name"><Input id="member-name" name="fullName" maxLength={120} required disabled={busy} autoFocus/></Field>
        <Field label="Correo" htmlFor="member-email"><Input id="member-email" name="email" type="email" maxLength={254} required disabled={busy}/></Field>
        <Field label="Rol" htmlFor="member-role" hint={roleLabels[role].description}>
          <select id="member-role" value={role} onChange={e => setRole(e.target.value as Role)} disabled={busy} className={selectClass}>
            {(Object.keys(roleLabels) as Role[]).map(r => <option key={r} value={r}>{roleLabels[r].name}</option>)}
          </select>
        </Field>
        {error && <Alert variant="destructive">{error}</Alert>}
        <DialogFooter><Button type="submit" loading={busy}>Enviar invitación</Button></DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}

function InvitationLink({invitation, onClose}: {invitation: Invitation; onClose: () => void}) {
  const url = typeof window === "undefined" ? invitation.link : new URL(invitation.link, location.origin).href;
  return <Alert className="mb-4">
    <div className="grid gap-2">
      <p className="flex items-center gap-2 font-medium"><MailCheck className="size-4"/>
        {invitation.emailed ? "Enviamos la invitación a " + invitation.member.email + "." : "No pudimos enviar el correo. Comparte este enlace con " + invitation.member.fullName + "."}</p>
      <div className="flex flex-wrap gap-2">
        <Input readOnly value={url} aria-label="Enlace de invitación" className="min-w-0 flex-1 font-mono text-xs" onFocus={e => e.target.select()}/>
        <Button variant="outline" onClick={() => navigator.clipboard.writeText(url).then(() => toast.success("Enlace copiado"), () => toast.error("Copia el enlace manualmente"))}><Copy/>Copiar</Button>
        <Button variant="ghost" onClick={onClose}>Listo</Button>
      </div>
    </div>
  </Alert>;
}
