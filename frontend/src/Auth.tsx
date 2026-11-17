import React, { useState } from "react";
import { ArrowLeft, KeyRound, ShieldCheck } from "lucide-react";
import { api, login } from "./api";
import { Brand, Form } from "./ui";

type Mode = "login" | "signup" | "forgot" | "reset" | "verify" | "resend";
const content: Record<
  Mode,
  { title: string; description: string; button: string }
> = {
  login: {
    title: "Sign in to Gatehouse",
    description: "Access your account and connected applications.",
    button: "Sign in",
  },
  signup: {
    title: "Create your account",
    description:
      "Sign up, verify your email, and start using connected applications.",
    button: "Create account",
  },
  forgot: {
    title: "Reset your password",
    description: "We’ll send a reset link to your account email.",
    button: "Send reset link",
  },
  reset: {
    title: "Choose a new password",
    description: "Use a unique password with at least 12 characters.",
    button: "Update password",
  },
  verify: {
    title: "Verify your email",
    description:
      "Enter the password you chose during sign-up to complete verification.",
    button: "Verify email",
  },
  resend: {
    title: "Resend verification",
    description: "Enter your account email to request a new verification link.",
    button: "Send verification link",
  },
};

export function Auth() {
  const token = new URLSearchParams(location.search).get("token");
  const initial: Mode =
    location.pathname === "/verify" && token
      ? "verify"
      : location.pathname === "/reset" && token
        ? "reset"
        : location.pathname === "/signup"
          ? "signup"
          : "login";
  const [mode, setMode] = useState<Mode>(initial);
  const [message, setMessage] = useState("");
  const [email, setEmail] = useState("");
  const [created, setCreated] = useState(false);
  function choose(next: Mode) {
    setMode(next);
    setMessage("");
    setCreated(false);
  }
  return (
    <div className="auth-layout">
      <aside className="auth-side">
        <Brand />
        <div>
          <span className="eyebrow light">IDENTITY, WITH INTENTION</span>
          <h1>
            One place for
            <br />
            every way in.
          </h1>
          <p>
            Connect your applications.
            <br />
            Give the right people the right access.
          </p>
          <div className="auth-diagram">
            <div className="diagram-app">
              <span>Application</span>
              <span>Application</span>
            </div>
            <div className="diagram-connector" />
            <div className="diagram-gate">
              <ShieldCheck size={22} />
              <strong>Gatehouse</strong>
              <small>Identity & access</small>
            </div>
            <div className="diagram-connector" />
            <div className="diagram-users">
              <span>Authentication</span>
              <span>Authorization</span>
            </div>
          </div>
        </div>
        <small>Built on OpenID Connect and OAuth 2.0</small>
      </aside>
      <main className="auth-main">
        <div className="auth-mobile-brand">
          <Brand />
        </div>
        <div className="auth-card">
          <div className="auth-icon">
            <KeyRound size={22} />
          </div>
          <span className="eyebrow">
            {mode === "signup"
              ? "GET STARTED"
              : mode === "login"
                ? "WELCOME BACK"
                : "ACCOUNT ACCESS"}
          </span>
          <h1>{content[mode].title}</h1>
          <p>{content[mode].description}</p>
          {message && (
            <p className="success-note" role="status">
              {message}
            </p>
          )}
          {!created && (
            <Form
              key={mode}
              label={content[mode].button}
              submit={async (form) => {
                const address = String(form.get("email") || email);
                setEmail(address);
                if (mode === "login") {
                  location.assign(
                    await login(address, String(form.get("password"))),
                  );
                  return;
                }
                let result: { message: string };
                if (mode === "signup") {
                  result = await api("/api/registration/signup", {
                    displayName: form.get("displayName"),
                    email: address,
                    password: form.get("password"),
                  });
                  setCreated(true);
                } else if (mode === "resend")
                  result = await api("/api/registration/resend", {
                    email: address,
                  });
                else if (mode === "forgot")
                  result = await api("/api/password/forgot", {
                    email: address,
                  });
                else if (mode === "verify") {
                  result = await api("/api/registration/verify", {
                    token,
                    password: form.get("password"),
                  });
                  setMode("login");
                  history.replaceState({}, "", "/login");
                } else {
                  result = await api("/api/password/reset", {
                    token,
                    password: form.get("password"),
                  });
                  setMode("login");
                  history.replaceState({}, "", "/login");
                }
                setMessage(result.message);
              }}
            >
              {mode === "signup" && (
                <label>
                  Full name
                  <input
                    name="displayName"
                    required
                    maxLength={100}
                    autoComplete="name"
                    placeholder="Your name"
                  />
                </label>
              )}
              {!["reset", "verify"].includes(mode) && (
                <label>
                  Email address
                  <input
                    type="email"
                    name="email"
                    placeholder="you@company.com"
                    required
                    maxLength={254}
                    defaultValue={email}
                    autoComplete="username"
                  />
                </label>
              )}
              {["login", "signup", "reset", "verify"].includes(mode) && (
                <label>
                  Password
                  <input
                    name="password"
                    type="password"
                    required
                    minLength={mode === "signup" || mode === "reset" ? 12 : 1}
                    maxLength={72}
                    placeholder={
                      mode === "signup" || mode === "reset"
                        ? "At least 12 characters"
                        : "Enter your password"
                    }
                    autoComplete={
                      mode === "signup" || mode === "reset"
                        ? "new-password"
                        : "current-password"
                    }
                  />
                </label>
              )}
              {mode === "login" && (
                <button
                  className="text-button forgot-link"
                  type="button"
                  onClick={() => choose("forgot")}
                >
                  Forgot password?
                </button>
              )}
            </Form>
          )}
          {mode === "login" && (
            <p className="auth-switch">
              New to Gatehouse? <a href="/signup">Create an account</a>
            </p>
          )}
          {mode === "signup" && (
            <>
              <p className="auth-switch">
                Already have an account? <a href="/login">Sign in</a>
              </p>
              <button
                className="text-button verification-link"
                onClick={() => choose("resend")}
              >
                Resend verification email
              </button>
            </>
          )}
          {mode === "login" && (
            <button
              className="text-button verification-link"
              onClick={() => choose("resend")}
            >
              Need to verify your email?
            </button>
          )}
          {!["login", "signup"].includes(mode) && (
            <a className="text-button back-link" href="/login">
              <ArrowLeft size={14} />
              Back to sign in
            </a>
          )}
        </div>
      </main>
    </div>
  );
}
