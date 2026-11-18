import { ThemeProvider, StyledEngineProvider } from "@mui/material/styles";
import { theme } from "./theme";
import "@fontsource-variable/inter";
import { ActionButton } from "./ActionButton";
import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Activity,
  ArrowUpRight,
  Boxes,
  ChevronRight,
  Code2,
  ExternalLink,
  Fingerprint,
  LayoutDashboard,
  LoaderCircle,
  LogOut,
  Menu,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  Shield,
  Users,
  X,
} from "lucide-react";
import { api, ApiError, errorMessage } from "./api";
import { Auth } from "./Auth";
import { Demo } from "./Demo";
import { Avatar, Badge, Brand, CopyButton, Modal } from "./ui";
import { ClientDetails, ClientForm, RoleForm, UserForm } from "./forms";
import {
  AccountPage,
  ClientPage,
  EventTable,
  IntegrationPage,
  OverviewPage,
  RolePage,
  SessionPage,
  UserPage,
} from "./pages";
import type {
  AuditEvent,
  Client,
  Config,
  Data,
  Overview,
  Page,
  Role,
  Session,
  User,
} from "./types";
import "./style.css";
import "./design.css";
type Dialog =
  | { type: "new-user" }
  | { type: "user"; id: string }
  | { type: "new-client" }
  | { type: "client"; id: string }
  | { type: "new-role" }
  | { type: "secret"; secret: string };
const nav = [
  { id: "overview", label: "Overview", Icon: LayoutDashboard },
  { id: "users", label: "Users", Icon: Users },
  { id: "applications", label: "Applications", Icon: Boxes },
  { id: "roles", label: "Roles", Icon: Shield },
  { id: "sessions", label: "Sessions", Icon: Fingerprint },
  { id: "events", label: "Audit events", Icon: Activity },
  { id: "integrations", label: "Integrations", Icon: Code2 },
] as const;
const headings: Record<Page, { title: string; description: string }> = {
  overview: {
    title: "Your identity, in focus.",
    description: "A clear view of users, applications, and access.",
  },
  users: {
    title: "Users",
    description: "The people who can sign in to your applications.",
  },
  applications: {
    title: "Applications",
    description: "Manage clients and how they connect to your directory.",
  },
  roles: {
    title: "Roles",
    description: "Define access once. Assign it where it belongs.",
  },
  sessions: {
    title: "Sessions",
    description: "See who is signed in and control browser access.",
  },
  events: {
    title: "Audit events",
    description: "Authentication and administration, recorded in one place.",
  },
  integrations: {
    title: "Bring your applications together.",
    description: "Connect with standard protocols and a working example.",
  },
  account: {
    title: "Your account",
    description: "Manage your password and active browser sessions.",
  },
};
function Console() {
  const [me, setMe] = useState<User | null>(null),
    [ready, setReady] = useState(false),
    [data, setData] = useState<Data | null>(null),
    [page, setPage] = useState<Page>(
      location.pathname === "/account" ? "account" : "overview",
    ),
    [modal, setModal] = useState<Dialog | null>(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [loading, setLoading] = useState(false),
    [busy, setBusy] = useState(false),
    [mobileNav, setMobileNav] = useState(false),
    [search, setSearch] = useState(""),
    [searchTerm, setSearchTerm] = useState(""),
    [userPage, setUserPage] = useState(0),
    [administrators, setAdministrators] = useState(false),
    [eventKind, setEventKind] = useState(""),
    [ownSessions, setOwnSessions] = useState<Session[]>([]);
  async function load() {
    setLoading(true);
    try {
      const user = await api<User>("/api/me");
      setMe(user);
      setOwnSessions(await api<Session[]>("/api/account/sessions"));
      if (user.administrator) {
        const [overview, users, roles, clients, sessions, events, config] =
          await Promise.all([
            api<Overview>("/api/admin/overview"),
            api<{ items: User[]; total: number }>(
              `/api/admin/users?search=${encodeURIComponent(searchTerm)}&page=${userPage}&administrators=${administrators}`,
            ),
            api<Role[]>("/api/admin/roles"),
            api<Client[]>("/api/admin/clients"),
            api<Session[]>("/api/admin/sessions"),
            api<AuditEvent[]>(
              `/api/admin/events?kind=${encodeURIComponent(eventKind)}`,
            ),
            api<Config>("/api/admin/config"),
          ]);
        setData({
          overview,
          users: users.items,
          userTotal: users.total,
          roles,
          clients,
          sessions,
          events,
          config,
        });
      } else {
        setPage("account");
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setMe(null);
      else setError(errorMessage(e));
    } finally {
      setLoading(false);
      setReady(true);
    }
  }
  useEffect(() => {
    load();
  }, [searchTerm, userPage, eventKind, administrators]);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 5000);
    return () => clearTimeout(timer);
  }, [notice]);
  async function action(fn: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await fn();
      await load();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  function navigate(next: Page) {
    setPage(next);
    setMobileNav(false);
  }
  async function completed() {
    setModal(null);
    setNotice("Changes saved.");
    await load();
  }
  if (!ready)
    return (
      <div className="startup">
        <Brand />
        <LoaderCircle className="spin" size={25} />
      </div>
    );
  if (
    !me ||
    location.pathname === "/login" ||
    ["/signup", "/verify", "/reset"].includes(location.pathname)
  )
    return <Auth />;
  const activeUser =
    modal?.type === "user"
      ? data?.users.find((user) => user.id === modal.id)
      : undefined;
  const activeClient =
    modal?.type === "client"
      ? data?.clients.find((client) => client.id === modal.id)
      : undefined;
  return (
    <div className="app">
      <aside className={`sidebar ${mobileNav ? "show" : ""}`}>
        <Brand />
        <div className="directory">
          <span className="directory-icon">
            <LayersIcon />
          </span>
          <div>
            <strong>Primary directory</strong>
            <small>Identity workspace</small>
          </div>
          <span className="directory-dot" />
        </div>
        <span className="nav-caption">MANAGE</span>
        <nav>
          {me.administrator &&
            nav.map((item) => (
              <button
                className={page === item.id ? "active" : ""}
                key={item.id}
                onClick={() => navigate(item.id)}
              >
                <item.Icon size={18} />
                <span>{item.label}</span>
                {page === item.id && <span className="nav-indicator" />}
              </button>
            ))}
          <button
            className={page === "account" ? "active" : ""}
            onClick={() => navigate("account")}
          >
            <Settings2 size={18} />
            <span>My account</span>
          </button>
        </nav>
        <a className="sidebar-demo" href="/demo">
          <Code2 size={19} />
          <div>
            <strong>Try an integration</strong>
            <small>Open the live demo app</small>
          </div>
          <ArrowUpRight size={15} />
        </a>
        <button className="profile-button" onClick={() => navigate("account")}>
          <Avatar name={me.displayName} />
          <div>
            <strong>{me.displayName}</strong>
            <small>
              {me.administrator ? "Administrator" : "Directory member"}
            </small>
          </div>
          <ChevronRight size={15} />
        </button>
        <span className="sidebar-version">
          Gatehouse <span>v0.1.0</span>
        </span>
      </aside>
      {mobileNav && (
        <button
          className="sidebar-overlay"
          aria-label="Close navigation"
          onClick={() => setMobileNav(false)}
        />
      )}
      <div className="content">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="icon-button menu-toggle"
              aria-label="Open navigation"
              onClick={() => setMobileNav(true)}
            >
              <Menu size={22} />
            </button>
            <span>Primary directory</span>
            <ChevronRight size={13} />
            <strong>
              {headings[page].title === "Your identity, in focus."
                ? "Overview"
                : page === "integrations"
                  ? "Integrations"
                  : headings[page].title}
            </strong>
          </div>
          <div className="topbar-actions">
            <span className="connection-label">
              <span className="live-dot" />
              Connected
            </span>
            <button
              className="icon-button"
              aria-label="Refresh data"
              disabled={loading || busy}
              onClick={() => load()}
            >
              <RefreshCw size={16} className={loading ? "spin" : ""} />
            </button>
            <button
              className="icon-button"
              aria-label="Sign out"
              onClick={() =>
                action(async () => {
                  await api("/logout", {});
                  location.assign("/login");
                })
              }
            >
              <LogOut size={17} />
            </button>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <span className="eyebrow">
                {page === "overview"
                  ? "DIRECTORY OVERVIEW"
                  : page === "integrations"
                    ? "DEVELOPER WORKSPACE"
                    : "IDENTITY & ACCESS"}
              </span>
              <h1>{headings[page].title}</h1>
              <p>{headings[page].description}</p>
            </div>
            {me.administrator &&
              (["users", "applications", "roles"].includes(page) ? (
                <ActionButton
                  className="button"
                  onClick={() =>
                    setModal({
                      type:
                        page === "users"
                          ? "new-user"
                          : page === "applications"
                            ? "new-client"
                            : "new-role",
                    })
                  }
                >
                  <Plus size={17} />
                  {page === "users"
                    ? "Create user"
                    : page === "applications"
                      ? "Register application"
                      : "Create role"}
                </ActionButton>
              ) : page === "overview" ? (
                <ActionButton
                  className="button secondary"
                  onClick={() => setModal({ type: "new-user" })}
                >
                  <Plus size={16} />
                  Create user
                </ActionButton>
              ) : null)}
          </div>
          {error && (
            <div className="inline-alert" role="alert">
              {error}
              <button
                className="icon-button"
                onClick={() => setError("")}
                aria-label="Dismiss error"
              >
                <X size={16} />
              </button>
            </div>
          )}
          {page === "account" ? (
            <AccountPage
              user={me}
              sessions={ownSessions}
              revoke={(id) =>
                action(async () => {
                  await api(`/api/account/sessions/${id}`, undefined, "DELETE");
                  setNotice("Browser session revoked.");
                })
              }
            />
          ) : !data ? (
            <div className="loading-panel">
              <LoaderCircle className="spin" />
              Loading directory data…
            </div>
          ) : (
            <>
              {page === "overview" && (
                <OverviewPage data={data} navigate={navigate} />
              )}{" "}
              {page === "users" && (
                <UserPage
                  data={data}
                  administrators={administrators}
                  setAdministrators={(value) => {
                    setUserPage(0);
                    setAdministrators(value);
                  }}
                  search={search}
                  setSearch={setSearch}
                  page={userPage}
                  setPage={setUserPage}
                  find={() => {
                    setUserPage(0);
                    if (searchTerm === search) load();
                    else setSearchTerm(search);
                  }}
                  manage={(user) => setModal({ type: "user", id: user.id })}
                />
              )}{" "}
              {page === "applications" && (
                <ClientPage
                  data={data}
                  manage={(client) =>
                    setModal({ type: "client", id: client.id })
                  }
                />
              )}{" "}
              {page === "roles" && (
                <RolePage
                  data={data}
                  remove={(id, name) =>
                    action(async () => {
                      if (
                        confirm(
                          `Remove the ${name} role and revoke grants for its assigned users?`,
                        )
                      ) {
                        await api(
                          `/api/admin/roles/${id}`,
                          undefined,
                          "DELETE",
                        );
                        setNotice("Role removed.");
                      }
                    })
                  }
                />
              )}{" "}
              {page === "sessions" && (
                <SessionPage
                  sessions={data.sessions}
                  revoke={(id) =>
                    action(async () => {
                      await api(
                        `/api/admin/sessions/${id}`,
                        undefined,
                        "DELETE",
                      );
                      setNotice("Browser session revoked.");
                    })
                  }
                />
              )}{" "}
              {page === "events" && (
                <section className="panel table-panel">
                  <div className="table-toolbar">
                    <label className="inline-label">
                      Event type
                      <select
                        value={eventKind}
                        onChange={(e) => setEventKind(e.target.value)}
                      >
                        <option value="">All events</option>
                        {[
                          "LOGIN_SUCCESS",
                          "LOGIN_FAILED",
                          "TOKEN_ISSUED",
                          "USER_CREATED",
                          "USER_UPDATED",
                          "ACCESS_REVOKED",
                          "PASSWORD_RESET",
                          "CLIENT_CREATED",
                          "ROLE_CREATED",
                          "SESSION_REVOKED",
                        ].map((kind) => (
                          <option key={kind} value={kind}>
                            {kind.toLowerCase().replaceAll("_", " ")}
                          </option>
                        ))}
                      </select>
                    </label>
                    <span>Latest 100 events</span>
                  </div>
                  <EventTable events={data.events} />
                </section>
              )}{" "}
              {page === "integrations" && <IntegrationPage data={data} />}
            </>
          )}
          <footer className="page-footer">
            <span>
              <Shield size={12} />
              Identity & access, under your control.
            </span>
            <a
              href="/.well-known/openid-configuration"
              target="_blank"
              rel="noreferrer"
            >
              OpenID configuration
              <ExternalLink size={11} />
            </a>
          </footer>
        </main>
      </div>
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {modal?.type === "new-user" && data && (
        <Modal
          title="Create a user"
          subtitle="Add someone to your directory."
          onClose={() => setModal(null)}
        >
          <UserForm roles={data.roles} done={completed} />
        </Modal>
      )}
      {activeUser && data && (
        <Modal
          title={activeUser.displayName}
          subtitle={activeUser.email}
          onClose={() => setModal(null)}
        >
          <UserForm user={activeUser} roles={data.roles} done={completed} />
          <div className="user-admin-actions">
            <button
              className="text-button"
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await api(`/api/admin/users/${activeUser.id}/reset-link`, {});
                  setNotice("Reset email sent.");
                })
              }
            >
              Send password reset
            </button>
            <button
              className="text-button"
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await api(`/api/admin/users/${activeUser.id}/unlock`, {});
                  setNotice("Login lock cleared.");
                })
              }
            >
              Clear login lock
            </button>
            <button
              className="text-button danger"
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await api(`/api/admin/users/${activeUser.id}/revoke`, {});
                  setNotice("Sessions and OAuth grants revoked.");
                })
              }
            >
              Revoke all access
            </button>
          </div>
        </Modal>
      )}
      {modal?.type === "new-client" && (
        <Modal
          title="Register an application"
          subtitle="Give your application a standard sign-in connection."
          onClose={() => setModal(null)}
        >
          <ClientForm
            done={async (secret) => {
              await load();
              if (secret) setModal({ type: "secret", secret });
              else {
                setModal(null);
                setNotice("Application registered.");
              }
            }}
          />
        </Modal>
      )}
      {activeClient && data && (
        <Modal
          title={activeClient.name}
          subtitle="Application credentials and integration details."
          onClose={() => setModal(null)}
        >
          <ClientDetails
            client={activeClient}
            issuer={data.config.issuer}
            action={action}
          />
        </Modal>
      )}
      {modal?.type === "new-role" && (
        <Modal
          title="Create a role"
          subtitle="Define a reusable application permission."
          onClose={() => setModal(null)}
        >
          <RoleForm done={completed} />
        </Modal>
      )}
      {modal?.type === "secret" && (
        <Modal
          title="Your client secret"
          subtitle="Copy it now. Gatehouse stores its password hash."
          onClose={() => setModal(null)}
        >
          <div className="secret-panel">
            <div className="copy-field">
              <code>{modal.secret}</code>
              <CopyButton value={modal.secret} />
            </div>
            <p>
              Keep this value in your application’s server-side secret
              configuration.
            </p>
          </div>
          <ActionButton className="button" onClick={() => setModal(null)}>
            Done
          </ActionButton>
        </Modal>
      )}
    </div>
  );
}
function LayersIcon() {
  return (
    <span className="directory-layers">
      <span />
      <span />
    </span>
  );
}
createRoot(document.getElementById("root")!).render(
  <StyledEngineProvider injectFirst>
    <ThemeProvider theme={theme}>
      {location.pathname.startsWith("/demo") ? <Demo /> : <Console />}
    </ThemeProvider>
  </StyledEngineProvider>,
);
