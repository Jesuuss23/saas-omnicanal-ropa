import {cn} from "@/lib/utils";

export function Skeleton({className, ...props}: React.ComponentProps<"div">) {
  return <div aria-hidden className={cn("animate-pulse rounded-md bg-muted", className)} {...props}/>;
}

export function Spinner({className, label = "Cargando…"}: {className?: string; label?: string}) {
  return <span role="status" className={cn("inline-flex items-center gap-2 text-sm text-muted-foreground", className)}>
    <span aria-hidden className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent"/>{label}
  </span>;
}
