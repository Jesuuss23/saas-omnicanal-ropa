import Link from "next/link";
import {CheckCircle2} from "lucide-react";
import {brand} from "@/lib/brand";

const points = [
  "Catálogo web, WhatsApp y tienda física con el mismo stock",
  "Pagos con Mercado Pago y comprobantes SUNAT",
  "Acceso protegido con verificación en dos pasos",
];

/** Pantalla dividida para ingreso y enrolamiento: marca a la izquierda, formulario a la derecha. */
export function AuthShell({eyebrow, title, description, children, aside}: {
  eyebrow?: string; title: string; description?: React.ReactNode; children: React.ReactNode; aside?: React.ReactNode;
}) {
  return <main className="grid min-h-dvh lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
    <aside className="relative hidden overflow-hidden bg-brand-950 p-12 text-white lg:flex lg:flex-col lg:justify-between">
      <div aria-hidden className="pointer-events-none absolute -right-24 -top-24 size-96 rounded-full bg-brand-500/25 blur-3xl"/>
      <div aria-hidden className="pointer-events-none absolute -bottom-32 -left-16 size-96 rounded-full bg-brand-700/40 blur-3xl"/>
      <Link href="/" className="relative flex items-center gap-2 text-lg font-semibold tracking-tight">
        <Logo/> {brand.name}
      </Link>
      <div className="relative grid gap-8">
        {aside ?? <>
          <p className="max-w-md text-4xl font-semibold leading-tight tracking-tight">Tu tienda de ropa, en todos los canales.</p>
          <ul className="grid gap-3 text-white/80">
            {points.map(p => <li key={p} className="flex items-start gap-3"><CheckCircle2 className="mt-0.5 size-5 shrink-0 text-brand-100"/>{p}</li>)}
          </ul>
        </>}
      </div>
      <p className="relative text-sm text-white/50">© {new Date().getFullYear()} {brand.name}</p>
    </aside>
    <section className="flex flex-col justify-center px-4 py-10 sm:px-10">
      <div className="mx-auto w-full max-w-md">
        <Link href="/" className="mb-10 flex items-center gap-2 font-semibold text-primary lg:hidden"><Logo/> {brand.name}</Link>
        {eyebrow && <p className="text-sm font-medium text-primary">{eyebrow}</p>}
        <h1 className="mt-1 text-3xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-2 text-muted-foreground">{description}</p>}
        <div className="mt-8">{children}</div>
      </div>
    </section>
  </main>;
}

export function Logo({className = "size-7"}: {className?: string}) {
  return <svg viewBox="0 0 32 32" className={className} aria-hidden>
    <rect width="32" height="32" rx="9" fill="currentColor" className="text-brand-500"/>
    <path d="M10 12.5 16 9l6 3.5v7L16 23l-6-3.5z" fill="none" stroke="white" strokeWidth="2.2" strokeLinejoin="round"/>
    <path d="m10 12.5 6 3.5 6-3.5M16 16v7" fill="none" stroke="white" strokeWidth="2.2" strokeLinejoin="round"/>
  </svg>;
}
