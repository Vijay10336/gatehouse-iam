export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  body?: unknown,
  method?: string,
): Promise<T> {
  const verb = method || (body === undefined ? "GET" : "POST");
  const headers: Record<string, string> = { Accept: "application/json" };
  if (verb !== "GET") {
    const csrfResponse = await fetch("/api/csrf", {
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!csrfResponse.ok)
      throw new Error("Unable to establish a secure session. Please reload.");
    const csrf = await csrfResponse.json();
    headers[csrf.headerName] = csrf.token;
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(path, {
    method: verb,
    credentials: "same-origin",
    headers,
    cache: "no-store",
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new ApiError(
      result.error ||
        (response.status === 403
          ? "You do not have permission for this action."
          : "The request could not be completed."),
      response.status,
    );
  return result;
}
export async function login(email: string, password: string): Promise<string> {
  const csrf = await api<{ token: string; headerName: string }>("/api/csrf");
  const response = await fetch("/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Accept: "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: new URLSearchParams({ username: email, password }),
  });
  const result = await response.json();
  if (!response.ok)
    throw new ApiError(result.error || "Sign-in failed.", response.status);
  return result.redirect || "/";
}
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : "Something went wrong.";
export function when(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
