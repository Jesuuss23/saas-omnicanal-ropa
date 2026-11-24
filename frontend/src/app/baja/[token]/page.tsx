"use client";

import {useState} from "react";
import {useParams} from "next/navigation";
import {CheckCircle2} from "lucide-react";
import {AuthShell} from "@/components/auth/AuthShell";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {apiFetch, publicApi} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";

type Preferences = {storeName: string; email: string | null; emailOptIn: boolean; whatsappOptIn: boolean};

/** Baja de comunicaciones desde el enlace del correo. Pide confirmar: abrir el enlace por sí solo no da de baja. */
export default function Unsubscribe() {
  const {token} = useParams<{token: string}>();
  const url = publicApi.url("unsubscribe/" + encodeURIComponent(token));
  const prefs = useApiData<Preferences>(url);
  const [done, setDone] = useState<Preferences | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const current = done ?? prefs.data;
  const subscribed = current && (current.emailOptIn || current.whatsappOptIn);

  async function confirm() {
    setBusy(true); setError("");
    try {
      const res = await apiFetch(url, {method: "POST", headers: {"Content-Type": "application/json"}, body: "{}"});
      if (!res.ok) throw new Error();
      setDone(await res.json());
    } catch { setError("No pudimos registrar tu baja. Intenta de nuevo en un momento."); }
    finally { setBusy(false); }
  }

  return <AuthShell eyebrow="Comunicaciones" title={current ? "Novedades de " + current.storeName : "Darte de baja"}
    description={current?.email ? "Para " + current.email : "Deja de recibir campañas de esta tienda."}>
    {prefs.error && <Alert variant="destructive">Este enlace no es válido o ya no existe.</Alert>}
    {current && (subscribed ? <div className="grid gap-4">
      <p className="text-sm text-muted-foreground">Dejarás de recibir ofertas por correo y WhatsApp de {current.storeName}. Seguirás recibiendo los avisos de tus pedidos.</p>
      {error && <Alert variant="destructive">{error}</Alert>}
      <Button onClick={confirm} loading={busy}>Confirmar baja</Button>
    </div> : <p className="flex items-start gap-2 text-sm"><CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success"/>
      Listo. Ya no recibirás campañas de {current.storeName}. Si cambias de opinión, actívalas desde tu cuenta en la tienda.</p>)}
  </AuthShell>;
}
