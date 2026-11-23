import {cva, type VariantProps} from "class-variance-authority";
import {cn} from "@/lib/utils";

const badgeVariants = cva("inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium [&_svg]:size-3", {
  variants: {
    variant: {
      default: "bg-accent text-accent-foreground",
      neutral: "bg-muted text-muted-foreground",
      success: "bg-success/12 text-success",
      warning: "bg-warning/18 text-warning-foreground",
      destructive: "bg-destructive/10 text-destructive",
      outline: "border text-foreground",
    },
  },
  defaultVariants: {variant: "default"},
});

export function Badge({className, variant, ...props}: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({variant}), className)} {...props}/>;
}
