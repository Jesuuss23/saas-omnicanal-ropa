"use client";

import {useEffect} from "react";
import {useRouter} from "next/navigation";
import {Activity, CreditCard, LayoutDashboard, Layers, ScrollText, ShieldCheck, Store} from "lucide-react";
import {ConsoleShell, type NavSection} from "@/components/console/ConsoleShell";
import {API_EVENTS} from "@/lib/api";
import Logout from "../Logout";

const sections: NavSection[] = [
  {items: [
    {href: "/superadmin", label: "Resumen", icon: LayoutDashboard, exact: true},
    {href: "/superadmin/tiendas", label: "Tiendas", icon: Store},
  ]},
  {title: "Negocio", items: [
    {href: "/superadmin/planes", label: "Planes", icon: Layers},
    {href: "/superadmin/pagos", label: "Pagos", icon: CreditCard},
  ]},
  {title: "Control", items: [
    {href: "/superadmin/auditoria", label: "Auditoría", icon: ScrollText},
    {href: "/superadmin/seguridad", label: "Seguridad", icon: ShieldCheck},
  ]},
];

export function SuperadminShell({email, children}: {email: string; children: React.ReactNode}) {
  const router = useRouter();
  useEffect(() => {
    const expired = () => router.replace("/superadmin/login");
    window.addEventListener(API_EVENTS.unauthorized, expired);
    return () => window.removeEventListener(API_EVENTS.unauthorized, expired);
  }, [router]);
  return <ConsoleShell sections={sections} homeHref="/superadmin"
    context={<div className="flex items-center gap-2 rounded-lg bg-brand-950 px-3 py-2 text-sm text-white">
      <Activity className="size-4 text-brand-100" aria-hidden/>Consola de plataforma</div>}
    footer={<div className="grid gap-3 border-t pt-4">
      <p className="truncate px-2 text-sm text-muted-foreground" title={email}>{email}</p>
      <Logout/>
    </div>}>
    {children}
  </ConsoleShell>;
}
