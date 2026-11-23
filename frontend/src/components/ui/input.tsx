import {cn} from "@/lib/utils";

export const fieldClass = "flex w-full rounded-lg border border-input bg-card px-3 text-sm shadow-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive";

export function Input({className, ...props}: React.ComponentProps<"input">) {
  return <input className={cn(fieldClass, "h-10", className)} {...props}/>;
}

export function Textarea({className, ...props}: React.ComponentProps<"textarea">) {
  return <textarea className={cn(fieldClass, "min-h-24 py-2", className)} {...props}/>;
}

/** Select nativo con el estilo del sistema: accesible y funciona bien en móviles. */
export function NativeSelect({className, ...props}: React.ComponentProps<"select">) {
  return <select className={cn(fieldClass, "h-10 pr-8", className)} {...props}/>;
}
