export type User = {
  id: string;
  email: string;
  displayName: string;
  enabled: boolean;
  administrator: boolean;
  emailVerified: boolean;
  version: number;
  lockedUntil: string | null;
  createdAt: string;
  roles: string[];
};
export type Role = {
  id: string;
  name: string;
  description: string;
  assignedUsers: number;
};
export type Client = {
  id: string;
  clientId: string;
  name: string;
  type: "public" | "web" | "machine";
  enabled: boolean;
  scopes: string[];
  redirectUris: string[];
  logoutUris: string[];
};
export type Session = {
  id: string;
  email: string;
  displayName: string;
  lastAccess: string;
  expiresAt: string;
  current: boolean;
};
export type AuditEvent = {
  id: string;
  actor: string;
  event: string;
  target: string;
  detail: string;
  ip: string;
  createdAt: string;
};
export type Config = {
  issuer: string;
  discovery: string;
  jwks: string;
  directory: string;
  accessTokenMinutes: number;
};
export type Overview = {
  users: number;
  enabledUsers: number;
  administrators: number;
  applications: number;
  roles: number;
  sessions: number;
  signIns: number;
  failedSignIns: number;
  tokens: number;
};
export type Data = {
  overview: Overview;
  users: User[];
  userTotal: number;
  roles: Role[];
  clients: Client[];
  sessions: Session[];
  events: AuditEvent[];
  config: Config;
};
export type Page =
  | "overview"
  | "users"
  | "applications"
  | "roles"
  | "sessions"
  | "events"
  | "integrations"
  | "account";
