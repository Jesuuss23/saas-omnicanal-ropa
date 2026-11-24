import {Label as LabelPrimitive} from "radix-ui";
import {cn} from "@/lib/utils";

export function Label({className, ...props}: React.ComponentProps<typeof LabelPrimitive.Root>) {
  return <LabelPrimitive.Root className={cn("text-sm font-medium leading-none", className)} {...props}/>;
}

/** Etiqueta, control y texto de ayuda o error en un bloque. */
export function Field({label, htmlFor, hint, error, children, className}: {
  label: React.ReactNode; htmlFor?: string; hint?: React.ReactNode; error?: React.ReactNode;
  children: React.ReactNode; className?: string;
}) {
  return <div className={cn("grid content-start gap-2", className)}>
    <Label htmlFor={htmlFor}>{label}</Label>
    {children}
    {error ? <p className="text-sm text-destructive" role="alert">{error}</p>
      : hint ? <p className="text-sm text-muted-foreground">{hint}</p> : null}
  </div>;
}
