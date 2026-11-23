"use client";
import {cn} from "@/lib/utils";

/**
 * Campo para el código de 6 dígitos de la app autenticadora o un código de recuperación (XXXX-XXXX).
 * Un solo input (accesible y compatible con autocompletar de SMS/llaveros), con estilo de casillas.
 */
export function CodeInput({recovery, className, ...props}: React.ComponentProps<"input"> & {recovery?: boolean}) {
  return <input
    inputMode={recovery ? "text" : "numeric"}
    autoComplete="one-time-code"
    pattern={recovery ? "[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}" : "[0-9]{6}"}
    maxLength={recovery ? 9 : 6}
    placeholder={recovery ? "XXXX-XXXX" : "000000"}
    autoCapitalize={recovery ? "characters" : undefined}
    spellCheck={false}
    className={cn("h-14 w-full rounded-xl border border-input bg-card text-center font-mono text-2xl tracking-[0.5em] shadow-xs placeholder:text-muted-foreground/40 focus-visible:outline-2 focus-visible:outline-offset-0 focus-visible:outline-ring",
      recovery && "tracking-[0.25em] uppercase", className)}
    {...props}/>;
}
