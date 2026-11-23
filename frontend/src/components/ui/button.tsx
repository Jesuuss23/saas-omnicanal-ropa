import {Slot} from "radix-ui";
import {cva, type VariantProps} from "class-variance-authority";
import {Loader2} from "lucide-react";
import {cn} from "@/lib/utils";

export const buttonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground shadow-card hover:bg-primary/90",
        secondary: "bg-secondary text-secondary-foreground hover:bg-secondary/70",
        outline: "border border-input bg-card hover:bg-muted",
        ghost: "hover:bg-muted",
        destructive: "bg-destructive text-destructive-foreground hover:bg-destructive/90",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: "h-10 px-4",
        sm: "h-8 px-3 text-xs",
        lg: "h-12 px-6 text-base",
        icon: "size-10",
      },
    },
    defaultVariants: {variant: "default", size: "default"},
  },
);

type Props = React.ComponentProps<"button"> & VariantProps<typeof buttonVariants> & {
  /** Renderiza el hijo (por ejemplo un Link) con el estilo de botón. */
  asChild?: boolean;
  loading?: boolean;
};

export function Button({className, variant, size, asChild, loading, disabled, children, ...props}: Props) {
  const Comp = asChild ? Slot.Root : "button";
  return <Comp className={cn(buttonVariants({variant, size}), className)} disabled={disabled || loading}
    aria-busy={loading || undefined} {...props}>
    {asChild ? children : <>{loading && <Loader2 className="animate-spin" aria-hidden/>}{children}</>}
  </Comp>;
}
