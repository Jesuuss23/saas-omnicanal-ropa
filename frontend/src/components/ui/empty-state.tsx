import type {LucideIcon} from "lucide-react";
import {cn} from "@/lib/utils";

export function EmptyState({icon: Icon, title, description, action, className}: {
  icon?: LucideIcon; title: string; description?: React.ReactNode; action?: React.ReactNode; className?: string;
}) {
  return <div className={cn("flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-12 text-center", className)}>
    {Icon && <span className="grid size-12 place-items-center rounded-full bg-accent text-accent-foreground"><Icon className="size-6" aria-hidden/></span>}
    <div className="grid gap-1"><p className="font-medium">{title}</p>
      {description && <p className="max-w-sm text-sm text-muted-foreground">{description}</p>}</div>
    {action}
  </div>;
}
