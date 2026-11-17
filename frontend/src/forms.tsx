import React, { useState } from "react";
import { KeyRound, Shield, SquareArrowOutUpRight } from "lucide-react";
import { api } from "./api";
import { Badge, CopyButton, Form } from "./ui";
import type { Client, Role, User } from "./types";
export function UserForm({
  user,
  roles,
  done,
}: {
  user?: User;
  roles: Role[];
  done: () => Promise<void>;
}) {
  return (
    <Form
      label={user ? "Save changes" : "Create user"}
      submit={async (f) => {
        const payload = {
          displayName: f.get("displayName"),
          administrator: f.has("administrator"),
          roleIds: f.getAll("roles"),
          ...(user
            ? { version: user.version, enabled: f.has("enabled") }
            : { email: f.get("email"), password: f.get("password") }),
        };
        await api(
          user ? `/api/admin/users/${user.id}` : "/api/admin/users",
          payload,
          user ? "PUT" : "POST",
        );
        await done();
      }}
    >
      <div className="two-fields">
        <label>
          Full name
          <input
            name="displayName"
            required
            maxLength={100}
            defaultValue={user?.displayName}
            placeholder="Alex Morgan"
          />
        </label>
        <label>
          Email address
          <input
            type="email"
            name="email"
            required
            maxLength={254}
            defaultValue={user?.email}
            readOnly={Boolean(user)}
            placeholder="alex@company.com"
          />
        </label>
      </div>
      {!user && (
        <label>
          Initial password
          <input
            type="password"
            name="password"
            minLength={12}
            maxLength={72}
            required
            placeholder="At least 12 characters"
            autoComplete="new-password"
          />
          <small>You can send a reset link after creating the user.</small>
        </label>
      )}
      <fieldset>
        <legend>Application roles</legend>
        {roles.length ? (
          roles.map((role) => (
            <label className="check-row" key={role.id}>
              <input
                type="checkbox"
                name="roles"
                value={role.id}
                defaultChecked={user?.roles.includes(role.name)}
              />
              <span>
                <strong>{role.name}</strong>
                <small>{role.description}</small>
              </span>
            </label>
          ))
        ) : (
          <p className="muted">
            Create a role to assign application permissions.
          </p>
        )}
      </fieldset>
      <div className="settings-switch">
        <div>
          <strong>Directory administrator</strong>
          <small>Can manage users, applications, roles, and sessions.</small>
        </div>
        <label className="switch">
          <input
            type="checkbox"
            name="administrator"
            defaultChecked={user?.administrator}
          />
          <span />
          <span className="sr-only">Directory administrator</span>
        </label>
      </div>
      {user && (
        <>
          <div className="settings-switch">
            <div>
              <strong>Account enabled</strong>
              <small>Allow this user to sign in.</small>
            </div>
            <label className="switch">
              <input
                type="checkbox"
                name="enabled"
                defaultChecked={user.enabled}
              />
              <span />
              <span className="sr-only">Account enabled</span>
            </label>
          </div>
          <p className="field-note">
            Saving changes revokes this user’s current sessions and OAuth
            grants.
          </p>
        </>
      )}
    </Form>
  );
}
export function RoleForm({ done }: { done: () => Promise<void> }) {
  return (
    <Form
      label="Create role"
      submit={async (f) => {
        await api("/api/admin/roles", {
          name: f.get("name"),
          description: f.get("description"),
        });
        await done();
      }}
    >
      <label>
        Role name
        <input
          name="name"
          required
          minLength={2}
          maxLength={40}
          pattern="[a-z][a-z0-9:_-]{1,39}"
          placeholder="e.g. billing-manager"
        />
        <small>
          Lowercase letters, digits, hyphens, underscores, and colons.
        </small>
      </label>
      <label>
        Description
        <textarea
          name="description"
          maxLength={240}
          rows={3}
          placeholder="What does this role allow?"
        />
      </label>
    </Form>
  );
}
export function ClientForm({
  done,
}: {
  done: (secret?: string) => Promise<void>;
}) {
  const [type, setType] = useState("public");
  const scopes =
    type === "machine"
      ? ["api.read"]
      : ["openid", "profile", "email", "roles", "api.read", "api.write"];
  const defaults =
    type === "machine"
      ? ["api.read"]
      : ["openid", "profile", "email", "roles", "api.read"];
  return (
    <Form
      label="Register application"
      submit={async (f) => {
        const result = await api<{ secret?: string }>("/api/admin/clients", {
          name: f.get("name"),
          clientId: f.get("clientId"),
          type,
          redirectUris:
            type === "machine"
              ? []
              : String(f.get("redirectUris") || "")
                  .split("\n")
                  .map((s) => s.trim())
                  .filter(Boolean),
          logoutUris:
            type === "machine"
              ? []
              : String(f.get("logoutUris") || "")
                  .split("\n")
                  .map((s) => s.trim())
                  .filter(Boolean),
          scopes: f.getAll("scopes"),
        });
        await done(result.secret);
      }}
    >
      <div className="two-fields">
        <label>
          Application name
          <input
            name="name"
            required
            maxLength={100}
            placeholder="Customer portal"
          />
        </label>
        <label>
          Client ID
          <input
            name="clientId"
            required
            minLength={2}
            maxLength={80}
            pattern="[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}"
            placeholder="customer-portal"
          />
        </label>
      </div>
      <label>
        Application type
        <select value={type} onChange={(e) => setType(e.target.value)}>
          <option value="public">
            Public browser app · Authorization code + PKCE
          </option>
          <option value="web">
            Confidential web app · Code + PKCE + refresh
          </option>
          <option value="machine">Service account · Client credentials</option>
        </select>
      </label>
      {type !== "machine" && (
        <>
          <label>
            Redirect URLs
            <textarea
              name="redirectUris"
              rows={2}
              required
              placeholder="https://app.example.com/callback"
            />
            <small>
              One exact URL per line. HTTPS, or localhost HTTP for development.
            </small>
          </label>
          <label>
            Post-logout redirect URLs
            <textarea
              name="logoutUris"
              rows={2}
              placeholder="https://app.example.com/"
            />
          </label>
        </>
      )}
      <fieldset key={type}>
        <legend>Allowed scopes</legend>
        <div className="scope-options">
          {scopes.map((scope) => (
            <label className="check-row" key={scope}>
              <input
                type="checkbox"
                name="scopes"
                value={scope}
                defaultChecked={defaults.includes(scope)}
                disabled={scope === "openid"}
              />
              <code>{scope}</code>
              {scope === "openid" && (
                <input name="scopes" value="openid" type="hidden" />
              )}
            </label>
          ))}
        </div>
      </fieldset>
      <p className="field-note">
        {type === "public"
          ? "Public apps use PKCE and do not hold a client secret."
          : "A client secret is generated and shown once after registration."}
      </p>
    </Form>
  );
}
export function ClientDetails({
  client,
  issuer,
  action,
}: {
  client: Client;
  issuer: string;
  action: (fn: () => Promise<void>) => void;
}) {
  const [secret, setSecret] = useState("");
  const config = {
    authority: issuer,
    client_id: client.clientId,
    response_type: "code",
    scope: client.scopes.join(" "),
    redirect_uri: client.redirectUris[0] || undefined,
  };
  return (
    <div className="client-details">
      <div className="details-status">
        <Badge tone={client.enabled ? "green" : "neutral"}>
          {client.enabled ? "Enabled" : "Disabled"}
        </Badge>
        <Badge>
          {client.type === "machine"
            ? "Client credentials"
            : "Authorization code + PKCE"}
        </Badge>
      </div>
      <label>
        Client ID
        <div className="copy-field">
          <code>{client.clientId}</code>
          <CopyButton value={client.clientId} />
        </div>
      </label>
      {client.type !== "machine" && (
        <>
          <label>
            Redirect URLs
            <div className="uri-list">
              {client.redirectUris.map((uri) => (
                <code key={uri}>{uri}</code>
              ))}
            </div>
          </label>
          <label>
            Integration configuration
            <div className="code-panel">
              <CopyButton value={JSON.stringify(config, null, 2)} />
              <pre>{JSON.stringify(config, null, 2)}</pre>
            </div>
          </label>
        </>
      )}
      <label>
        Allowed scopes
        <div className="chips">
          {client.scopes.map((scope) => (
            <Badge key={scope}>{scope}</Badge>
          ))}
        </div>
      </label>
      {secret && (
        <div className="secret-panel">
          <KeyRound size={20} />
          <strong>Copy your new client secret</strong>
          <p>
            It is shown here once. Update your application’s server
            configuration.
          </p>
          <div className="copy-field">
            <code>{secret}</code>
            <CopyButton value={secret} />
          </div>
        </div>
      )}
      <div className="detail-actions">
        {client.type !== "public" && (
          <button
            className="button secondary"
            onClick={() =>
              action(async () => {
                if (
                  confirm(
                    "Rotate this secret and invalidate its existing OAuth grants?",
                  )
                )
                  setSecret(
                    (
                      await api<{ secret: string }>(
                        `/api/admin/clients/${client.id}/rotate-secret`,
                        {},
                      )
                    ).secret,
                  );
              })
            }
          >
            <KeyRound size={15} />
            Rotate secret
          </button>
        )}
        <button
          className={`button ${client.enabled ? "secondary danger" : ""}`}
          onClick={() =>
            action(async () => {
              await api(
                `/api/admin/clients/${client.id}/enabled`,
                { enabled: !client.enabled },
                "PUT",
              );
            })
          }
        >
          {client.enabled ? "Disable application" : "Enable application"}
        </button>
      </div>
    </div>
  );
}
