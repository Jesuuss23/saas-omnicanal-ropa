export function LegalPage({title, updated, children}: {title: string; updated: string; children: React.ReactNode}) {
  return <article className="mx-auto max-w-3xl px-4 py-20 sm:px-6">
    <h1 className="text-4xl font-semibold tracking-tight">{title}</h1>
    <p className="mt-2 text-sm text-muted-foreground">Última actualización: {updated}</p>
    <div className="mt-10 grid gap-6 leading-relaxed text-muted-foreground [&_h2]:mt-4 [&_h2]:text-lg [&_h2]:font-semibold [&_h2]:text-foreground [&_ul]:list-disc [&_ul]:pl-6">{children}</div>
  </article>;
}
