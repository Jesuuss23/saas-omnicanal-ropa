"use client";

import {useState} from "react";
import {Bot} from "lucide-react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {toast} from "@/components/ui/toaster";
import {superadminApi} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";

/** Interruptor global de la IA vendedora (queda en la auditoría). */
export function PlatformAiSwitch() {
  const {data, mutate} = useApiData<{enabled: boolean}>(superadminApi.url("platform/ai"));
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!data) return null;

  async function toggle() {
    setBusy(true);
    try {
      mutate(await superadminApi.json<{enabled: boolean}>("platform/ai", {enabled: !data!.enabled}, "PUT"));
      toast.success(data!.enabled ? "IA apagada en todas las tiendas" : "IA encendida");
      setConfirm(false);
    } catch { toast.error("No se pudo cambiar."); }
    finally { setBusy(false); }
  }

  return <>
    <Button variant={data.enabled ? "outline" : "destructive"} onClick={() => setConfirm(true)}>
      <Bot/>{data.enabled ? "IA activa" : "IA apagada"}</Button>
    <Dialog open={confirm} onOpenChange={setConfirm}>
      <DialogContent>
        <DialogHeader><DialogTitle>{data.enabled ? "¿Apagar la IA en todas las tiendas?" : "¿Encender la IA?"}</DialogTitle>
          <DialogDescription>{data.enabled ? "Las tiendas dejan de recibir respuestas automáticas al instante. Los mensajes siguen llegando a sus bandejas."
            : "Cada tienda vuelve a responder con IA según su propia configuración."}</DialogDescription></DialogHeader>
        <DialogFooter><Button variant="ghost" onClick={() => setConfirm(false)}>Cancelar</Button>
          <Button variant={data.enabled ? "destructive" : "default"} loading={busy} onClick={toggle}>{data.enabled ? "Apagar IA" : "Encender IA"}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  </>;
}
