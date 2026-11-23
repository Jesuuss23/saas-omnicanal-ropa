"use client";

import {useRef, useState, type FormEvent} from "react";
import {Plus} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {toast} from "@/components/ui/toaster";
import {errorMessage, superadminApi} from "@/lib/api";

/** Alta manual de una tienda (pilotos). El identificador se fija por intento, así reintentar no duplica. */
export function NewStoreDialog({defaultOpen = false, onCreated}: {defaultOpen?: boolean; onCreated: (id: string) => void}) {
  const [open, setOpen] = useState(defaultOpen);
  const registration = useRef("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      registration.current ||= crypto.randomUUID();
      const res = await superadminApi.send("tenants", JSON.stringify({id: registration.current,
        name: String(data.get("name")).trim(), ownerEmail: String(data.get("ownerEmail")).trim()}));
      if (!res.ok) {
        setError(res.status === 409 ? "Este registro ya se procesó con otros datos. Revisa la lista de tiendas."
          : await errorMessage(res, "Revisa el nombre y el correo."));
        return;
      }
      const store = await res.json();
      toast.success("Tienda " + store.name + " registrada", {description: "Desde su ficha puedes enviar la invitación al propietario."});
      registration.current = "";
      setOpen(false);
      onCreated(store.id);
    } catch {
      setError("No se pudo confirmar el registro. Puedes reintentar con los mismos datos.");
    } finally {
      setBusy(false);
    }
  }

  return <Dialog open={open} onOpenChange={next => { setOpen(next); if (!next) setError(""); }}>
    <DialogTrigger asChild><Button><Plus/>Nueva tienda</Button></DialogTrigger>
    <DialogContent>
      <DialogHeader><DialogTitle>Registrar tienda</DialogTitle>
        <DialogDescription>Empieza con 14 días de prueba. Luego envía la invitación al propietario desde la ficha.</DialogDescription></DialogHeader>
      <form onSubmit={submit} className="grid gap-4">
        <Field label="Nombre de la tienda" htmlFor="store-name"><Input id="store-name" name="name" maxLength={100} required disabled={busy} autoFocus/></Field>
        <Field label="Correo del propietario" htmlFor="store-owner"><Input id="store-owner" name="ownerEmail" type="email" maxLength={254} required disabled={busy}/></Field>
        {error && <Alert variant="destructive">{error}</Alert>}
        <DialogFooter><Button type="submit" loading={busy}>Registrar tienda</Button></DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}
