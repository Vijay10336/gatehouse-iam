import React from "react";
import Button, { type ButtonProps } from "@mui/material/Button";
export function ActionButton({
  className = "",
  type,
  ...props
}: ButtonProps & { download?: boolean; target?: string; rel?: string }) {
  const classes = className.split(" ");
  const secondary = classes.some((v) =>
    [
      "secondary",
      "ghost",
      "refresh",
      "checkout",
      "sign-out",
      "google-button",
    ].includes(v),
  );
  return (
    <Button
      type={type || "submit"}
      disableElevation
      variant={secondary ? "outlined" : "contained"}
      className={className}
      {...props}
    />
  );
}
