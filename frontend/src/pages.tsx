import { ActionButton } from "./ActionButton";
import React from "react";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Boxes,
  Check,
  ChevronLeft,
  ChevronRight,
  Code2,
  ExternalLink,
  Fingerprint,
  Globe,
  KeyRound,
  Layers,
  Mail,
  Plus,
  Search,
  Shield,
  ShieldCheck,
  Users,
} from "lucide-react";
import { api, when } from "./api";
import { Avatar, Badge, CopyButton, Empty, Form } from "./ui";
import type { AuditEvent, Client, Data, Page, Session, User } from "./types";
export function OverviewPage({
  data,
  navigate,
}: {
  data: Data;
  navigate: (page: Page) => void;
}) {
  const { overview, config } = data;
  return (
    <>
      <div className="metric-grid">
        {[
          {
            label: "Total users",
            value: overview.users,
            description: `${overview.enabledUsers} enabled · ${overview.administrators} administrator${overview.administrators === 1 ? "" : "s"}`,
            Icon: Users,
          },
          {
            label: "Applications",
            value: overview.applications,
            description: "Connected to your directory",
            Icon: Boxes,
          },
          {
            label: "Active sessions",
            value: overview.sessions,
            description: "Authenticated browser sessions",
            Icon: Fingerprint,
          },
          {
            label: "Directory roles",
            value: overview.roles,
            description: "Reusable application permissions",
            Icon: Shield,
          },
        ].map(({ label, value, description, Icon }) => (
          <article className="metric" key={label}>
            <div>
              <span>{label}</span>
              <Icon size={17} />
            </div>
            <strong>{value.toLocaleString()}</strong>
            <small>{description}</small>
          </article>
        ))}
      </div>
      <div className="activity-strip">
        <span>
          <span className="live-dot" />
          Last 24 hours
        </span>
        <div>
          <strong>{overview.signIns}</strong> successful sign-ins
        </div>
        <div>
          <strong>{overview.failedSignIns}</strong> rejected sign-ins
        </div>
        <div>
          <strong>{overview.tokens}</strong> tokens issued
        </div>
      </div>
      <div className="dashboard-grid">
        <section className="connect-card">
          <div className="connect-label">
            <Code2 size={19} />
            <span>BUILD WITH GATEHOUSE</span>
          </div>
          <h2>
            One identity layer.
            <br />
            Every application.
          </h2>
          <p>
            Connect your next app with OpenID Connect.
            <br />
            Start with the working integration example.
          </p>
          <div className="issuer-field">
            <span>ISSUER URL</span>
            <div>
              <code>{config.issuer}</code>
              <CopyButton value={config.issuer} />
            </div>
          </div>
          <div className="connect-actions">
            <ActionButton className="button" href="/demo">
              Open demo application
              <ArrowUpRight size={16} />
            </ActionButton>
            <button
              className="text-button"
              onClick={() => navigate("integrations")}
            >
              Integration guide
              <ArrowRight size={14} />
            </button>
          </div>
        </section>
        <section className="panel checklist">
          <div className="section-head">
            <h2>Directory at a glance</h2>
            <Badge tone="green">Connected</Badge>
          </div>
          {[
            {
              label: "User directory",
              text: `${overview.users} account${overview.users === 1 ? "" : "s"} managed`,
              Icon: Users,
              page: "users",
            },
            {
              label: "Application access",
              text: `${overview.applications} enabled clients`,
              Icon: Boxes,
              page: "applications",
            },
            {
              label: "Role-based authorization",
              text: `${overview.roles} roles available`,
              Icon: Shield,
              page: "roles",
            },
            {
              label: "Session controls",
              text: `${overview.sessions} active sessions`,
              Icon: Fingerprint,
              page: "sessions",
            },
          ].map((item) => (
            <button
              className="glance-row"
              key={item.page}
              onClick={() => navigate(item.page as Page)}
            >
              <span className="glance-icon">
                <item.Icon size={18} />
              </span>
              <div>
                <strong>{item.label}</strong>
                <small>{item.text}</small>
              </div>
              <ChevronRight size={15} />
            </button>
          ))}
        </section>
      </div>
      <section className="panel">
        <div className="section-head">
          <div>
            <h2>Recent activity</h2>
            <p>The latest events across your directory.</p>
          </div>
          <button className="text-button" onClick={() => navigate("events")}>
            View all events
            <ArrowRight size={14} />
          </button>
        </div>
        <EventTable events={data.events.slice(0, 6)} compact />
      </section>
    </>
  );
}
export function UserPage({
  data,
  search,
  setSearch,
  page,
  setPage,
  find,
  manage,
  administrators,
  setAdministrators,
}: {
  data: Data;
  administrators: boolean;
  setAdministrators: (value: boolean) => void;
  search: string;
  setSearch: (s: string) => void;
  page: number;
  setPage: (n: number) => void;
  find: () => void;
  manage: (user: User) => void;
}) {
  return (
    <section className="panel table-panel">
      <div className="table-toolbar">
        <form
          className="search"
          onSubmit={(e) => {
            e.preventDefault();
            find();
          }}
        >
          <Search size={16} />
          <input
            aria-label="Search users"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            maxLength={100}
            placeholder="Search by name or email…"
          />
          <button type="submit">Search</button>
        </form>
        <label className="admin-filter">
          <input
            type="checkbox"
            checked={administrators}
            onChange={(event) => setAdministrators(event.target.checked)}
          />
          Administrators only
        </label>
        <span>
          {data.userTotal} {data.userTotal === 1 ? "user" : "users"}
        </span>
      </div>
      {!data.users.length ? (
        <Empty
          title="No users found"
          detail="Create a user or try another search."
        />
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th>Status</th>
                <th>Roles</th>
                <th>Created</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.users.map((user) => (
                <tr key={user.id}>
                  <td>
                    <div className="person-cell">
                      <Avatar name={user.displayName} />
                      <div>
                        <strong>
                          {user.displayName}
                          {user.administrator && (
                            <ShieldCheck className="inline-icon" size={13} />
                          )}
                        </strong>
                        <small>{user.email}</small>
                      </div>
                    </div>
                  </td>
                  <td>
                    <Badge
                      tone={
                        !user.enabled
                          ? "neutral"
                          : !user.emailVerified
                            ? "neutral"
                            : user.lockedUntil &&
                                new Date(user.lockedUntil) > new Date()
                              ? "red"
                              : "green"
                      }
                    >
                      {!user.enabled
                        ? "Disabled"
                        : !user.emailVerified
                          ? "Pending verification"
                          : user.lockedUntil &&
                              new Date(user.lockedUntil) > new Date()
                            ? "Locked"
                            : "Active"}
                    </Badge>
                  </td>
                  <td>
                    <div className="chips">
                      {user.roles.length ? (
                        user.roles
                          .slice(0, 3)
                          .map((role) => <Badge key={role}>{role}</Badge>)
                      ) : (
                        <span className="muted">—</span>
                      )}
                      {user.roles.length > 3 && (
                        <Badge>+{user.roles.length - 3}</Badge>
                      )}
                    </div>
                  </td>
                  <td className="muted">{when(user.createdAt)}</td>
                  <td>
                    <button className="row-action" onClick={() => manage(user)}>
                      Manage
                      <ChevronRight size={13} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pagination">
        <span>
          Page {page + 1} of {Math.max(1, Math.ceil(data.userTotal / 50))}
        </span>
        <div>
          <button
            className="icon-button"
            aria-label="Previous page"
            disabled={!page}
            onClick={() => setPage(page - 1)}
          >
            <ChevronLeft size={17} />
          </button>
          <button
            className="icon-button"
            aria-label="Next page"
            disabled={(page + 1) * 50 >= data.userTotal}
            onClick={() => setPage(page + 1)}
          >
            <ChevronRight size={17} />
          </button>
        </div>
      </div>
    </section>
  );
}
export function ClientPage({
  data,
  manage,
}: {
  data: Data;
  manage: (client: Client) => void;
}) {
  return (
    <div className="client-grid">
      {data.clients.map((client) => (
        <button
          className="application-card"
          key={client.id}
          onClick={() => manage(client)}
        >
          <div className="application-card-top">
            <span className={`application-icon ${client.type}`}>
              <Boxes size={23} />
            </span>
            <Badge tone={client.enabled ? "green" : "neutral"}>
              {client.enabled ? "Enabled" : "Disabled"}
            </Badge>
          </div>
          <h2>{client.name}</h2>
          <code>{client.clientId}</code>
          <div className="application-type">
            <Globe size={14} />
            {client.type === "public"
              ? "Public browser application"
              : client.type === "web"
                ? "Confidential web application"
                : "Service account"}
          </div>
          <div className="application-bottom">
            <span>{client.scopes.length} scopes</span>
            <span>
              {client.type === "machine" ? "Client credentials" : "Code + PKCE"}
              <ArrowUpRight size={15} />
            </span>
          </div>
        </button>
      ))}
    </div>
  );
}
export function RolePage({
  data,
  remove,
}: {
  data: Data;
  remove: (id: string, name: string) => void;
}) {
  return (
    <div className="role-grid">
      {data.roles.map((role) => (
        <section className="panel role-card" key={role.id}>
          <span className="role-icon">
            <Shield size={22} />
          </span>
          <h2>{role.name}</h2>
          <p>{role.description || "No description added."}</p>
          <div className="role-bottom">
            <span>
              <Users size={14} />
              {role.assignedUsers} assigned
            </span>
            <button
              className="text-button danger"
              onClick={() => remove(role.id, role.name)}
            >
              Remove role
            </button>
          </div>
        </section>
      ))}
    </div>
  );
}
export function SessionPage({
  sessions,
  revoke,
}: {
  sessions: Session[];
  revoke: (id: string) => void;
}) {
  return (
    <section className="panel table-panel">
      {!sessions.length ? (
        <Empty
          title="No active sessions"
          detail="Authenticated browser sessions will appear here."
        />
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th>Last active</th>
                <th>Expires</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {sessions.map((session) => (
                <tr key={session.id}>
                  <td>
                    <div className="person-cell">
                      <Avatar name={session.displayName} />
                      <div>
                        <strong>
                          {session.displayName}
                          {session.current && (
                            <Badge tone="purple">This session</Badge>
                          )}
                        </strong>
                        <small>{session.email}</small>
                      </div>
                    </div>
                  </td>
                  <td>{when(session.lastAccess)}</td>
                  <td className="muted">{when(session.expiresAt)}</td>
                  <td>
                    <button
                      className="text-button danger"
                      onClick={() => revoke(session.id)}
                    >
                      Revoke session
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
export function EventTable({
  events,
  compact = false,
}: {
  events: AuditEvent[];
  compact?: boolean;
}) {
  return !events.length ? (
    <Empty
      title="No activity yet"
      detail="Authentication and management events will appear here."
    />
  ) : (
    <div className="table-scroll">
      <table className="event-table">
        <thead>
          <tr>
            <th>Event</th>
            <th>Actor</th>
            {!compact && <th>Target</th>}
            <th>Time</th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => (
            <tr key={event.id}>
              <td>
                <div className="event-name">
                  <span
                    className={`event-dot ${event.event.includes("FAILED") ? "red" : event.event.includes("SUCCESS") ? "green" : "neutral"}`}
                  />
                  <div>
                    <strong>
                      {event.event.toLowerCase().replaceAll("_", " ")}
                    </strong>
                    {!compact && <small>{event.detail}</small>}
                  </div>
                </div>
              </td>
              <td>
                <span className="actor">{event.actor}</span>
                {!compact && event.ip && (
                  <small className="table-note">{event.ip}</small>
                )}
              </td>
              {!compact && (
                <td>
                  <span className="actor muted">{event.target}</span>
                </td>
              )}
              <td className="muted nowrap">{when(event.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function IntegrationPage({ data }: { data: Data }) {
  const { config } = data;
  const snippet = `import { UserManager } from 'oidc-client-ts';\n\nconst auth = new UserManager({\n  authority: '${config.issuer}',\n  client_id: 'your-client-id',\n  redirect_uri: window.location.origin + '/callback',\n  response_type: 'code',\n  scope: 'openid profile email roles api.read'\n});\n\n// Sign in. Call signinRedirectCallback() on /callback.\nawait auth.signinRedirect({ nonce: crypto.randomUUID() });`;
  return (
    <div className="integration-layout">
      <section className="panel">
        <div className="section-head">
          <div>
            <h2>Connect a browser application</h2>
            <p>Register a public application, then use an OIDC client.</p>
          </div>
          <ActionButton className="button small secondary" href="/demo">
            Try the demo
            <ArrowUpRight size={15} />
          </ActionButton>
        </div>
        <div className="install-command">
          <code>npm install oidc-client-ts</code>
          <CopyButton value="npm install oidc-client-ts" />
        </div>
        <div className="code-panel">
          <CopyButton value={snippet} />
          <pre>{snippet}</pre>
        </div>
        <div className="integration-note">
          <ShieldCheck size={19} />
          <p>
            The browser SDK manages PKCE, state, and nonce. Register an exact
            callback URL for each application.
          </p>
        </div>
      </section>
      <section className="panel endpoint-panel">
        <h2>Directory endpoints</h2>
        {[
          ["Issuer", config.issuer],
          ["OpenID configuration", config.discovery],
          ["Public signing keys", config.jwks],
          ["Authorization", `${config.issuer}/oauth2/authorize`],
          ["Token", `${config.issuer}/oauth2/token`],
          ["Token introspection", `${config.issuer}/oauth2/introspect`],
          ["Token revocation", `${config.issuer}/oauth2/revoke`],
          ["User info", `${config.issuer}/userinfo`],
        ].map(([name, url]) => (
          <div className="endpoint" key={name}>
            <span>{name}</span>
            <div>
              <code>{url}</code>
              <CopyButton value={url} />
            </div>
          </div>
        ))}
      </section>
      <section className="panel">
        <div className="panel-label">
          <KeyRound size={19} />
          <h2>Protect your backend</h2>
        </div>
        <p>
          Validate the token signature with the published keys, check the issuer
          and audience, then enforce scopes and roles. The example API uses the{" "}
          <code>gatehouse-api</code> audience.
        </p>
        <div className="code-panel">
          <pre>{`spring.security.oauth2.resourceserver.jwt.issuer-uri=${config.issuer}\nspring.security.oauth2.resourceserver.jwt.audiences=gatehouse-api`}</pre>
        </div>
        <p className="field-note">
          Access tokens expire after {config.accessTokenMinutes} minutes. For
          immediate revocation checks in other applications, use token
          introspection.
        </p>
      </section>
    </div>
  );
}
export function AccountPage({
  user,
  sessions,
  revoke,
}: {
  user: User;
  sessions: Session[];
  revoke: (id: string) => void;
}) {
  return (
    <>
      <div className="account-grid">
        <section className="panel">
          <h2>Your account</h2>
          <div className="account-person">
            <Avatar name={user.displayName} />
            <div>
              <strong>{user.displayName}</strong>
              <small>{user.email}</small>
            </div>
          </div>
          <div className="chips">
            {user.administrator && (
              <Badge tone="purple">Directory administrator</Badge>
            )}
            {user.roles.map((role) => (
              <Badge key={role}>{role}</Badge>
            ))}
          </div>
        </section>
        <section className="panel">
          <h2>Change password</h2>
          <Form
            label="Update password"
            submit={async (f) => {
              await api("/api/account/password", {
                currentPassword: f.get("currentPassword"),
                newPassword: f.get("newPassword"),
              });
              location.assign("/login");
            }}
          >
            <label>
              Current password
              <input
                type="password"
                name="currentPassword"
                required
                autoComplete="current-password"
              />
            </label>
            <label>
              New password
              <input
                type="password"
                name="newPassword"
                minLength={12}
                maxLength={72}
                required
                autoComplete="new-password"
                placeholder="At least 12 characters"
              />
            </label>
            <p className="field-note">
              Changing your password ends your sessions and revokes existing
              OAuth grants.
            </p>
          </Form>
        </section>
      </div>
      <h2 className="standalone-heading">Your browser sessions</h2>
      <SessionPage sessions={sessions} revoke={revoke} />
    </>
  );
}
