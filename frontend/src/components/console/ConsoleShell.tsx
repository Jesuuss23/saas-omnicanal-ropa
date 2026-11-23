"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";
import {useState} from "react";
import {Menu, X, type LucideIcon} from "lucide-react";
import {Logo} from "@/components/auth/AuthShell";
import {brand} from "@/lib/brand";
import {cn} from "@/lib/utils";

export type NavItem = {href: string; label: string; icon: LucideIcon; exact?: boolean; badge?: React.ReactNode};
export type NavSection = {title?: string; items: NavItem[]};

function isActive(pathname: string, item: NavItem) {
  return item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(item.href + "/");
}

function Nav({sections, pathname, onNavigate}: {sections: NavSection[]; pathname: string; onNavigate?: () => void}) {
  return <nav className="grid gap-6" aria-label="Navegación principal">
    {sections.map((section, i) => <div key={section.title ?? i} className="grid gap-1">
      {section.title && <p className="px-3 pb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{section.title}</p>}
      {section.items.map(item => {
        const active = isActive(pathname, item);
        return <Link key={item.href} href={item.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
          className={cn("flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
            active ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground")}>
          <item.icon className="size-4 shrink-0" aria-hidden/>
          <span className="flex-1 truncate">{item.label}</span>
          {item.badge}
        </Link>;
      })}
    </div>)}
  </nav>;
}

/**
 * Estructura común de los paneles (superadmin y tienda): barra lateral fija en escritorio y
 * menú desplegable en móvil. `context` va arriba del menú (por ejemplo, el nombre de la tienda)
 * y `footer` al pie (cuenta y cierre de sesión).
 */
export function ConsoleShell({sections, context, footer, banner, children, homeHref = "/"}: {
  sections: NavSection[]; homeHref?: string; context?: React.ReactNode; footer?: React.ReactNode; banner?: React.ReactNode; children: React.ReactNode;
}) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const brandLink = <Link href={homeHref} className="flex items-center gap-2 font-semibold tracking-tight"><Logo className="size-7"/>{brand.name}</Link>;
  return <div className="min-h-dvh bg-background lg:grid lg:grid-cols-[16rem_minmax(0,1fr)] print:block">
    <div className="hidden border-r bg-card lg:block print:hidden"><aside className="sticky top-0 flex h-dvh flex-col gap-6 overflow-y-auto px-4 py-5">
      <div className="px-2">{brandLink}</div>
      {context}
      <div className="flex-1"><Nav sections={sections} pathname={pathname}/></div>
      {footer}
    </aside></div>
    <header className="sticky top-0 z-40 flex items-center print:hidden justify-between border-b bg-card/95 px-4 py-3 backdrop-blur lg:hidden">
      {brandLink}
      <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open} aria-controls="mobile-nav"
        className="rounded-lg p-2 hover:bg-muted" aria-label={open ? "Cerrar menú" : "Abrir menú"}>
        {open ? <X className="size-5"/> : <Menu className="size-5"/>}
      </button>
    </header>
    {open && <div id="mobile-nav" className="fixed inset-x-0 top-[57px] bottom-0 z-30 grid content-start gap-6 overflow-y-auto bg-card px-4 py-5 lg:hidden">
      {context}
      <Nav sections={sections} pathname={pathname} onNavigate={() => setOpen(false)}/>
      {footer}
    </div>}
    <div className="min-w-0">
      {banner}
      <main className="mx-auto grid w-full max-w-7xl grid-cols-[minmax(0,1fr)] gap-6 px-4 py-6 sm:px-6 lg:px-8 lg:py-8">{children}</main>
    </div>
  </div>;
}

export function PageHeader({title, description, actions, eyebrow}: {
  title: React.ReactNode; description?: React.ReactNode; actions?: React.ReactNode; eyebrow?: React.ReactNode;
}) {
  return <div className="flex flex-wrap items-end justify-between gap-4">
    <div className="grid min-w-0 gap-1">
      {eyebrow && <div className="text-sm text-muted-foreground">{eyebrow}</div>}
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{title}</h1>
      {description && <p className="max-w-2xl text-muted-foreground">{description}</p>}
    </div>
    {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
  </div>;
}
