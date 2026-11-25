"use client";

import Link from "next/link";
import {useState} from "react";
import {usePathname} from "next/navigation";
import {EmptyState} from "@/components/ui/empty-state";
import {Ban, CreditCard, ExternalLink, Globe, Home, Landmark, LogOut, MessageCircle, Package, Palette, Receipt, ShieldCheck,
  Bot, Megaphone, ShoppingBag, Sparkles, Store as StoreIcon, UserCog, Users} from "lucide-react";
import {ConsoleShell, type NavSection} from "@/components/console/ConsoleShell";
import {Button} from "@/components/ui/button";
import {roleAccess, useStore} from "./StoreContext";

export function StoreShell({onLogout, children}: {onLogout: () => Promise<void>; children: React.ReactNode}) {
  const {store, profile} = useStore();
  const [leaving, setLeaving] = useState(false);
  const base = "/propietario/" + store;
  const access = roleAccess[profile.role] ?? roleAccess.SELLER;
  const sections: NavSection[] = [
    {items: [{href: base, label: "Inicio", icon: Home, exact: true}]},
    {title: "Vender", items: [
      {href: base + "/pedidos", label: "Pedidos web", icon: ShoppingBag},
      {href: base + "/pos", label: "Punto de venta", icon: Receipt},
      {href: base + "/catalogo", label: "Catálogo", icon: Package},
      {href: base + "/clientes", label: "Clientes", icon: Users},
      {href: base + "/whatsapp", label: "WhatsApp", icon: MessageCircle},
    ]},
    ...(access.configure ? [{title: "Configurar", items: [
      {href: base + "/apariencia", label: "Apariencia", icon: Palette},
      {href: base + "/dominio", label: "Dominio", icon: Globe},
      {href: base + "/asistente", label: "Asistente IA", icon: Bot},
      {href: base + "/marketing", label: "Marketing", icon: Megaphone},
      ...(access.ownerOnly ? [{href: base + "/cobros", label: "Cobros", icon: CreditCard}] : []),
      {href: base + "/facturacion", label: "Facturación", icon: Landmark},
      ...(access.ownerOnly ? [
        {href: base + "/equipo", label: "Equipo", icon: UserCog},
        {href: base + "/plan", label: "Plan", icon: Sparkles},
        {href: base + "/cuenta", label: "Cuenta y seguridad", icon: ShieldCheck},
      ] : []),
    ]}] : []),
  ];
  // Si un miembro abre por enlace una sección que su rol no tiene, se explica en vez de mostrar errores 403.
  const pathname = usePathname();
  const section = pathname.slice(base.length).split("/")[1];
  const allowed = !section || ["comprobantes"].includes(section)
    || sections.some(s => s.items.some(i => i.href === base + "/" + section));
  return <ConsoleShell sections={sections} homeHref={base}
    banner={profile.suspendedReason && <div role="alert" className="flex items-start gap-3 bg-destructive px-4 py-3 text-sm text-destructive-foreground sm:px-6 lg:px-8">
      <Ban className="mt-0.5 size-4 shrink-0"/><p><strong>Tu tienda está suspendida.</strong> {profile.suspendedReason} Escríbenos para reactivarla.</p></div>}
    context={<div className="grid gap-2 rounded-lg border bg-background p-3">
      <p className="flex items-center gap-2 truncate font-medium"><StoreIcon className="size-4 shrink-0 text-primary"/>{profile.name}</p>
      <Link href={"/tienda/" + store} target="_blank" className="flex items-center gap-1 text-xs text-primary hover:underline">
        Ver tienda pública<ExternalLink className="size-3"/></Link>
    </div>}
    footer={<div className="grid gap-3 border-t pt-4">
      <div className="px-2"><p className="truncate text-sm text-muted-foreground" title={profile.email}>{profile.email}</p>
        <p className="text-xs text-muted-foreground">{access.label}</p></div>
      <Button variant="outline" className="w-full justify-start" loading={leaving}
        onClick={async () => { setLeaving(true); await onLogout(); setLeaving(false); }}>
        {!leaving && <LogOut/>}Cerrar sesión</Button>
    </div>}>
    {allowed ? children : <EmptyState icon={ShieldCheck} title="Esta sección es solo para el propietario"
      description="Tu rol no incluye esta parte del panel. Pide al propietario de la tienda que haga el cambio."/>}
  </ConsoleShell>;
}
