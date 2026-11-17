import React, { useEffect, useState } from "react";
import {
  UserManager,
  WebStorageStateStore,
  type User as OidcUser,
} from "oidc-client-ts";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  KeyRound,
  LogIn,
  LogOut,
  ShieldCheck,
} from "lucide-react";
import { errorMessage } from "./api";
import { Badge, Brand, CopyButton } from "./ui";
const manager = new UserManager({
  authority: location.origin,
  client_id: "gatehouse-demo",
  redirect_uri: `${location.origin}/demo/callback`,
  post_logout_redirect_uri: `${location.origin}/demo`,
  response_type: "code",
  scope: "openid profile email roles api.read api.write",
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: sessionStorage }),
});
export function Demo() {
  const [user, setUser] = useState<OidcUser | null>(null),
    [ready, setReady] = useState(false),
    [error, setError] = useState(""),
    [result, setResult] = useState<unknown>(null),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    async function load() {
      try {
        if (location.pathname === "/demo/callback") {
          const returned = await manager.signinRedirectCallback();
          setUser(returned);
          history.replaceState({}, "", "/demo");
        } else if (new URLSearchParams(location.search).has("state")) {
          await manager.signoutRedirectCallback();
          history.replaceState({}, "", "/demo");
        } else {
          setUser(await manager.getUser());
        }
      } catch (e) {
        setError(errorMessage(e));
      } finally {
        setReady(true);
      }
    }
    load();
  }, []);
  async function call(path: string) {
    if (!user) return;
    setBusy(true);
    setError("");
    try {
      const response = await fetch(path, {
        headers: { Authorization: `Bearer ${user.access_token}` },
      });
      const data = await response.json();
      setResult({ httpStatus: response.status, ...data });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="demo-shell">
      <header>
        <Brand />
        <a href="/" className="text-button">
          <ArrowLeft size={15} />
          Admin console
        </a>
      </header>
      <main className="demo-main">
        <div className="demo-header">
          <Badge tone="purple">Live integration example</Badge>
          <h1>Your app. Gatehouse sign-in.</h1>
          <p>
            This page is an OIDC client. Sign in, grant access, and call the
            protected API.
          </p>
        </div>
        <div className="demo-grid">
          <section className="panel">
            <div className="panel-label">
              <LogIn size={18} />
              <h2>Authentication</h2>
            </div>
            {!ready ? (
              <p>Checking your application session…</p>
            ) : user ? (
              <>
                <div className="demo-user">
                  <span className="demo-check">
                    <Check />
                  </span>
                  <div>
                    <strong>
                      {String(
                        user.profile.name || user.profile.email || "Signed in",
                      )}
                    </strong>
                    <small>
                      {String(user.profile.email || user.profile.sub)}
                    </small>
                  </div>
                  <Badge tone={user.expired ? "red" : "green"}>
                    {user.expired ? "Expired" : "Signed in"}
                  </Badge>
                </div>
                <dl className="detail-list">
                  <div>
                    <dt>Subject</dt>
                    <dd>
                      <code>{user.profile.sub}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>Granted scopes</dt>
                    <dd>{user.scope}</dd>
                  </div>
                  <div>
                    <dt>Access token expires</dt>
                    <dd>
                      {user.expires_at
                        ? new Date(user.expires_at * 1000).toLocaleTimeString()
                        : "—"}
                    </dd>
                  </div>
                </dl>
                <button
                  className="button secondary"
                  onClick={() =>
                    manager
                      .signoutRedirect()
                      .catch((e) => setError(errorMessage(e)))
                  }
                >
                  <LogOut size={16} />
                  Sign out through Gatehouse
                </button>
              </>
            ) : (
              <>
                <div className="demo-placeholder">
                  <KeyRound size={32} />
                  <h3>Ready when you are.</h3>
                  <p>Use a user from your Gatehouse directory.</p>
                </div>
                <button
                  className="button"
                  onClick={() =>
                    manager
                      .signinRedirect({ nonce: crypto.randomUUID() })
                      .catch((e) => setError(errorMessage(e)))
                  }
                >
                  <LogIn size={16} />
                  Sign in with Gatehouse
                  <ArrowRight size={15} />
                </button>
              </>
            )}
          </section>
          <section className="panel">
            <div className="panel-label">
              <ShieldCheck size={18} />
              <h2>Authorization</h2>
            </div>
            <p>Try two access checks using the issued access token.</p>
            <div className="api-test">
              <div>
                <strong>Read profile</strong>
                <small>Requires the api.read scope</small>
              </div>
              <button
                className="button small secondary"
                disabled={!user || busy}
                onClick={() => call("/api/resource/profile")}
              >
                Call API
              </button>
            </div>
            <div className="api-test">
              <div>
                <strong>Editor access</strong>
                <small>Requires api.write and the editor role</small>
              </div>
              <button
                className="button small secondary"
                disabled={!user || busy}
                onClick={() => call("/api/resource/write")}
              >
                Check access
              </button>
            </div>
            {result !== null && (
              <div className="code-panel">
                <pre>{JSON.stringify(result, null, 2)}</pre>
              </div>
            )}
          </section>
        </div>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <section className="panel demo-claims">
          <div className="section-head">
            <h2>{user ? "ID token claims" : "Client configuration"}</h2>
            <CopyButton
              value={JSON.stringify(
                user?.profile || {
                  authority: location.origin,
                  client_id: "gatehouse-demo",
                  response_type: "code",
                  scope: "openid profile email roles api.read api.write",
                },
                null,
                2,
              )}
            />
          </div>
          <pre>
            {JSON.stringify(
              user?.profile || {
                authority: location.origin,
                client_id: "gatehouse-demo",
                redirect_uri: `${location.origin}/demo/callback`,
                response_type: "code",
                scope: "openid profile email roles api.read api.write",
              },
              null,
              2,
            )}
          </pre>
        </section>
        <p className="demo-footnote">
          Authorization code + PKCE · OIDC discovery · Session storage for this
          demo’s tokens
        </p>
      </main>
    </div>
  );
}
