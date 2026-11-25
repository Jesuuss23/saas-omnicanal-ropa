"use client";

import {CheckCircle2, Globe, Lock, ServerCog} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";

/** Vista previa de la configuración de dominio propio. Todavía no guarda nada: los controles están deshabilitados. */
export default function DomainPage() {
  const steps = [
    {icon: Globe, title: "Escribe tu dominio", text: "Por ejemplo tienda.tumarca.pe. Puede ser un subdominio o el dominio principal."},
    {icon: ServerCog, title: "Crea el registro DNS", text: "Te mostraremos un registro CNAME para agregar en tu proveedor de dominio."},
    {icon: Lock, title: "Certificado automático", text: "Cuando el DNS apunte a la plataforma, el certificado HTTPS se emite solo."},
    {icon: CheckCircle2, title: "Listo", text: "Tu tienda, los catálogos y los enlaces que comparte la IA usarán tu dominio."},
  ];
  return <>
    <PageHeader title="Dominio" description="Usa tu propio dominio para la tienda pública y los enlaces que se comparten por WhatsApp."/>
    <Alert>Esta sección está en preparación. Por ahora tu tienda funciona en la dirección de la plataforma y aquí no se guarda ningún cambio.</Alert>
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
      <Card>
        <CardHeader className="flex-row items-start justify-between gap-4">
          <div className="grid gap-1"><CardTitle>Dominio propio</CardTitle>
            <CardDescription>Disponible en los planes Pro y Premium.</CardDescription></div>
          <Badge variant="neutral">Próximamente</Badge>
        </CardHeader>
        <CardContent className="grid gap-4">
          <Field label="Dominio" htmlFor="domain" hint="Sin https:// ni barras al final.">
            <Input id="domain" placeholder="tienda.tumarca.pe" disabled/>
          </Field>
          <div className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
            <p className="font-medium text-foreground">Registro DNS</p>
            <dl className="mt-2 grid grid-cols-[6rem_minmax(0,1fr)] gap-x-3 gap-y-1 font-mono text-xs">
              <dt>Tipo</dt><dd>CNAME</dd><dt>Nombre</dt><dd>tienda</dd><dt>Valor</dt><dd>—</dd>
            </dl>
          </div>
          <div><Button disabled>Verificar dominio</Button></div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Cómo funcionará</CardTitle></CardHeader>
        <CardContent><ol className="grid gap-4">
          {steps.map(({icon: Icon, title, text}, i) => <li key={title} className="flex gap-3">
            <span className="grid size-8 shrink-0 place-items-center rounded-full bg-accent text-accent-foreground"><Icon className="size-4"/></span>
            <span className="grid gap-0.5"><span className="text-sm font-medium">{i + 1}. {title}</span>
              <span className="text-sm text-muted-foreground">{text}</span></span>
          </li>)}
        </ol></CardContent>
      </Card>
    </div>
  </>;
}
