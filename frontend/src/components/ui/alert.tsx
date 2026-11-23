import {cva, type VariantProps} from "class-variance-authority";
import {AlertTriangle, CheckCircle2, Info, XCircle} from "lucide-react";
import {cn} from "@/lib/utils";

const alertVariants = cva("flex gap-3 rounded-lg border p-4 text-sm [&>svg]:mt-0.5 [&>svg]:size-4 [&>svg]:shrink-0", {
  variants: {
    variant: {
      info: "border-brand-100 bg-brand-50 text-brand-900",
      success: "border-success/25 bg-success/8 text-success",
      warning: "border-warning/40 bg-warning/10 text-warning-foreground",
      destructive: "border-destructive/25 bg-destructive/8 text-destructive",
    },
  },
  defaultVariants: {variant: "info"},
});

const icons = {info: Info, success: CheckCircle2, warning: AlertTriangle, destructive: XCircle};

export function Alert({className, variant, title, children, ...props}:
  Omit<React.ComponentProps<"div">, "title"> & VariantProps<typeof alertVariants> & {title?: React.ReactNode}) {
  const Icon = icons[variant ?? "info"];
  return <div role={variant === "destructive" ? "alert" : "status"} className={cn(alertVariants({variant}), className)} {...props}>
    <Icon aria-hidden/>
    <div className="grid gap-1">{title && <p className="font-medium">{title}</p>}{children && <div>{children}</div>}</div>
  </div>;
}
