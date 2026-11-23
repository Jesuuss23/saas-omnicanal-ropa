"use client";

import Link from "next/link";
import {useState} from "react";
import {Ban, CreditCard, ExternalLink, Home, Landmark, LogOut, MessageCircle, Package, Palette, Receipt, ShieldCheck,
  ShoppingBag, Sparkles, Store as StoreIcon, Users} from "lucide-react";
import {ConsoleShell, type NavSection} from "@/components/console/ConsoleShell";
import {Button} from "@/components/ui/button";
import {useStore} from "./StoreContext";

export function StoreShell({onLogout, children}: {onLogout: () => Promise<void>; children: React.ReactNode}) {
  const {store, profile} = useStore();
  const [leaving, setLeaving] = useState(false);
  const base = "/propietario/" + store;
  const sections: NavSection[] = [
    {items: [{href: base, label: "Inicio", icon: Home, exact: true}]},
    {title: "Vender", items: [
      {href: base + "/pedidos", label: "Pedidos web", icon: ShoppingBag},
      {href: base + "/pos", label: "Punto de venta", icon: Receipt},
      {href: base + "/catalogo", label: "Catálogo", icon: Package},
      {href: base + "/clientes", label: "Clientes", icon: Users},
      {href: base + "/whatsapp", label: "WhatsApp", icon: MessageCircle},
    ]},
    {title: "Configurar", items: [
      {href: base + "/apariencia", label: "Apariencia", icon: Palette},
      {href: base + "/cobros", label: "Cobros", icon: CreditCard},
      {href: base + "/facturacion", label: "Facturación", icon: Landmark},
      {href: base + "/plan", label: "Plan", icon: Sparkles},
      {href: base + "/cuenta", label: "Cuenta y seguridad", icon: ShieldCheck},
    ]},
  ];
  return <ConsoleShell sections={sections} homeHref={base}
    banner={profile.suspendedReason && <div role="alert" className="flex items-start gap-3 bg-destructive px-4 py-3 text-sm text-destructive-foreground sm:px-6 lg:px-8">
      <Ban className="mt-0.5 size-4 shrink-0"/><p><strong>Tu tienda está suspendida.</strong> {profile.suspendedReason} Escríbenos para reactivarla.</p></div>}
    context={<div className="grid gap-2 rounded-lg border bg-background p-3">
      <p className="flex items-center gap-2 truncate font-medium"><StoreIcon className="size-4 shrink-0 text-primary"/>{profile.name}</p>
      <Link href={"/tienda/" + store} target="_blank" className="flex items-center gap-1 text-xs text-primary hover:underline">
        Ver tienda pública<ExternalLink className="size-3"/></Link>
    </div>}
    footer={<div className="grid gap-3 border-t pt-4">
      <p className="truncate px-2 text-sm text-muted-foreground" title={profile.email}>{profile.email}</p>
      <Button variant="outline" className="w-full justify-start" loading={leaving}
        onClick={async () => { setLeaving(true); await onLogout(); setLeaving(false); }}>
        {!leaving && <LogOut/>}Cerrar sesión</Button>
    </div>}>
    {children}
  </ConsoleShell>;
}
