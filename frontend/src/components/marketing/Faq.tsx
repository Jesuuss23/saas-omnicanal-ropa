import {ChevronDown} from "lucide-react";

export function Faq({items}: {items: [string, React.ReactNode][]}) {
  return <div className="divide-y rounded-2xl border bg-card shadow-card">
    {items.map(([q, a]) => <details key={q} className="group px-6 py-5">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-4 font-medium">{q}
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180"/></summary>
      <div className="mt-3 text-sm leading-relaxed text-muted-foreground">{a}</div>
    </details>)}
  </div>;
}
