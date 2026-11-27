import { Loader2, Server as ServerIcon, Trash2, UserPlus } from 'lucide-react'
import { useState, type FormEvent } from 'react'

import { FieldError } from '@/components/AuthLayout'
import { CopyButton } from '@/components/CopyButton'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  useCreateInvitation,
  useCreateUser,
  useDeleteInvitation,
  useDeleteUser,
  useInvitations,
  useSetUserServers,
  useUpdateUser,
  useUserServers,
  useUsers,
  type CreatedInvitation,
  type ServerGrantInput,
} from '@/lib/admin'
import { useServers } from '@/lib/servers'
import { ROLE_DESCRIPTIONS, ROLE_LABELS, useSession, type Role, type User } from '@/lib/session'
import { formatRelative } from '@/lib/utils'

const ROLES: Role[] = ['admin', 'member', 'viewer']

function ServerScopeSelector({
  role,
  grants,
  onChange,
}: {
  role: Role
  grants: Record<string, 'read' | 'operate'>
  onChange: (next: Record<string, 'read' | 'operate'>) => void
}) {
  const { data: servers } = useServers()

  if (role === 'admin') {
    return (
      <p className="text-muted-foreground rounded-md border px-3 py-2 text-xs">
        Admins automatically have full access to all servers.
      </p>
    )
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label>Server Access Scope</Label>
        <span className="text-muted-foreground text-[11px]">
          Unchecked servers stay hidden from this user
        </span>
      </div>
      {!servers?.length ? (
        <p className="text-muted-foreground rounded-md border px-3 py-2 text-xs">
          No servers added yet. You can grant server access later.
        </p>
      ) : (
        <div className="max-h-48 space-y-1.5 overflow-y-auto rounded-md border p-2">
          {servers.map((sv) => {
            const grantedPerm = grants[sv.id]
            const checked = Boolean(grantedPerm)
            return (
              <div
                key={sv.id}
                className="flex items-center justify-between gap-2 rounded px-2 py-1.5 text-xs hover:bg-muted/50"
              >
                <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={(e) => {
                      const next = { ...grants }
                      if (e.target.checked) {
                        next[sv.id] = role === 'viewer' ? 'read' : 'operate'
                      } else {
                        delete next[sv.id]
                      }
                      onChange(next)
                    }}
                  />
                  <span className="truncate font-medium">{sv.name}</span>
                  <span className="text-muted-foreground truncate font-mono text-[11px]">
                    {sv.host}
                  </span>
                </label>
                {checked ? (
                  <Select
                    value={grantedPerm}
                    onValueChange={(perm) =>
                      onChange({ ...grants, [sv.id]: perm as 'read' | 'operate' })
                    }
                  >
                    <SelectTrigger className="h-7 w-24 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="operate">Operate</SelectItem>
                      <SelectItem value="read">Read</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <span className="text-muted-foreground text-[11px]">Hidden</span>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function toGrantInputs(role: Role, grants: Record<string, 'read' | 'operate'>): ServerGrantInput[] {
  if (role === 'admin') return []
  return Object.entries(grants).map(([server_id, permission]) => ({ server_id, permission }))
}

function AddUserDialog() {
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('member')
  const [grants, setGrants] = useState<Record<string, 'read' | 'operate'>>({})

  const create = useCreateUser()

  function reset() {
    setEmail('')
    setName('')
    setPassword('')
    setRole('member')
    setGrants({})
    create.reset()
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    create.mutate(
      {
        email,
        name,
        password,
        role,
        server_grants: toGrantInputs(role, grants),
      },
      {
        onSuccess: () => {
          setOpen(false)
          reset()
        },
      },
    )
  }

  const fieldErrors = create.error?.fields ?? {}
  const formError = create.error && Object.keys(fieldErrors).length === 0 ? create.error.message : null

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (!next) reset()
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm">
          <UserPlus aria-hidden />
          Add User
        </Button>
      </DialogTrigger>

      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Add user</DialogTitle>
            <DialogDescription>
              Create an account and choose which servers this user can see and manage.
            </DialogDescription>
          </DialogHeader>

          {formError ? <Alert variant="danger">{formError}</Alert> : null}

          <div className="space-y-1.5">
            <Label htmlFor="create-user-name">Name</Label>
            <Input
              id="create-user-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              autoFocus
              aria-invalid={Boolean(fieldErrors.name)}
            />
            <FieldError message={fieldErrors.name} />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-user-email">Email</Label>
            <Input
              id="create-user-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              aria-invalid={Boolean(fieldErrors.email)}
            />
            <FieldError message={fieldErrors.email} />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-user-password">Password</Label>
            <Input
              id="create-user-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              aria-invalid={Boolean(fieldErrors.password)}
            />
            <FieldError message={fieldErrors.password} />
            <p className="text-muted-foreground text-xs">At least 12 characters.</p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-user-role">Role</Label>
            <Select value={role} onValueChange={(value) => setRole(value as Role)}>
              <SelectTrigger id="create-user-role">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ROLES.map((option) => (
                  <SelectItem key={option} value={option}>
                    {ROLE_LABELS[option]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-muted-foreground text-xs">{ROLE_DESCRIPTIONS[role]}</p>
          </div>

          <ServerScopeSelector role={role} grants={grants} onChange={setGrants} />

          <DialogFooter>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? <Loader2 className="animate-spin" aria-hidden /> : null}
              Create User
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function InviteDialog() {
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<Role>('member')
  const [grants, setGrants] = useState<Record<string, 'read' | 'operate'>>({})
  const [created, setCreated] = useState<CreatedInvitation | null>(null)

  const invite = useCreateInvitation()

  function reset() {
    setEmail('')
    setName('')
    setRole('member')
    setGrants({})
    setCreated(null)
    invite.reset()
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    invite.mutate(
      { email, name, role, server_grants: toGrantInputs(role, grants) },
      { onSuccess: setCreated },
    )
  }

  const fieldErrors = invite.error?.fields ?? {}
  const formError = invite.error && Object.keys(fieldErrors).length === 0 ? invite.error.message : null

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (!next) reset()
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          <UserPlus aria-hidden />
          Invite Link
        </Button>
      </DialogTrigger>

      <DialogContent className="max-h-[90vh] overflow-y-auto">
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle>Invitation ready</DialogTitle>
              <DialogDescription>
                There is no mail server in a self-hosted install, so send this link yourself. It
                works once and expires in seven days.
              </DialogDescription>
            </DialogHeader>

            <div className="space-y-2">
              <Label htmlFor="invite-url">Invitation link</Label>
              <div className="flex gap-2">
                <Input
                  id="invite-url"
                  readOnly
                  value={created.url}
                  className="font-mono text-xs"
                  onFocus={(e) => e.currentTarget.select()}
                />
                <CopyButton value={created.url} />
              </div>
              <p className="text-muted-foreground text-xs">
                For {created.email} as {ROLE_LABELS[created.role]}.
              </p>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={reset}>
                Invite someone else
              </Button>
              <Button onClick={() => setOpen(false)}>Done</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={onSubmit} noValidate className="grid gap-4">
            <DialogHeader>
              <DialogTitle>Invite someone</DialogTitle>
              <DialogDescription>
                You will get a link to send them. Pre-select which servers they will see once they join.
              </DialogDescription>
            </DialogHeader>

            {formError ? <Alert variant="danger">{formError}</Alert> : null}

            <div className="space-y-1.5">
              <Label htmlFor="invite-email">Email</Label>
              <Input
                id="invite-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoFocus
                aria-invalid={Boolean(fieldErrors.email)}
              />
              <FieldError message={fieldErrors.email} />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="invite-name">Name (optional)</Label>
              <Input id="invite-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="invite-role">Role</Label>
              <Select value={role} onValueChange={(value) => setRole(value as Role)}>
                <SelectTrigger id="invite-role">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {ROLE_LABELS[option]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-muted-foreground text-xs">{ROLE_DESCRIPTIONS[role]}</p>
            </div>

            <ServerScopeSelector role={role} grants={grants} onChange={setGrants} />

            <DialogFooter>
              <Button type="submit" disabled={invite.isPending}>
                {invite.isPending ? <Loader2 className="animate-spin" aria-hidden /> : null}
                Create link
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

function ManageUserServersDialog({
  user,
  open,
  onOpenChange,
}: {
  user: User
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { data: currentGrants, isPending } = useUserServers(user.id, open)
  const saveGrants = useSetUserServers(user.id)
  const [draft, setDraft] = useState<Record<string, 'read' | 'operate'> | null>(null)

  const effectiveGrants: Record<string, 'read' | 'operate'> =
    draft ??
    Object.fromEntries((currentGrants ?? []).map((g) => [g.server_id, g.permission]))

  function onSave() {
    saveGrants.mutate(toGrantInputs(user.role, effectiveGrants), {
      onSuccess: () => {
        setDraft(null)
        onOpenChange(false)
      },
    })
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setDraft(null)
        onOpenChange(next)
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Server Access • {user.name}</DialogTitle>
          <DialogDescription>
            Choose which servers {user.email} can access. All unchecked servers remain hidden from them.
          </DialogDescription>
        </DialogHeader>

        {saveGrants.isError ? <Alert variant="danger">{saveGrants.error.message}</Alert> : null}

        {isPending && draft === null ? (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden />
            Loading assigned servers...
          </div>
        ) : (
          <ServerScopeSelector
            role={user.role}
            grants={effectiveGrants}
            onChange={setDraft}
          />
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          {user.role !== 'admin' ? (
            <Button onClick={onSave} disabled={saveGrants.isPending}>
              {saveGrants.isPending ? <Loader2 className="animate-spin" aria-hidden /> : null}
              Save Access
            </Button>
          ) : null}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function UsersTable() {
  const { data: currentUser } = useSession()
  const { data: users, isPending } = useUsers()
  const update = useUpdateUser()
  const remove = useDeleteUser()
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [scopingUser, setScopingUser] = useState<User | null>(null)

  const isSelf = (id: string) => id === currentUser?.id

  return (
    <Card>
      <CardHeader className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="space-y-1">
          <CardTitle>Users</CardTitle>
          <CardDescription>
            Manage team accounts and scope which servers each non-admin user can access.
          </CardDescription>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <InviteDialog />
          <AddUserDialog />
        </div>
      </CardHeader>
      <CardContent className="px-0">
        {update.isError ? (
          <Alert variant="danger" className="mx-5 mb-3">
            {update.error.message}
          </Alert>
        ) : null}
        {remove.isError ? (
          <Alert variant="danger" className="mx-5 mb-3">
            {remove.error.message}
          </Alert>
        ) : null}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>User</TableHead>
              <TableHead>Role</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Last sign-in</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending ? (
              <TableRow>
                <TableCell colSpan={5} className="text-muted-foreground py-6 text-center">
                  Loading.
                </TableCell>
              </TableRow>
            ) : null}

            {users?.map((user) => (
              <TableRow key={user.id}>
                <TableCell>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">
                      {user.name}
                      {isSelf(user.id) ? (
                        <span className="text-muted-foreground ml-1.5 text-xs">(you)</span>
                      ) : null}
                    </p>
                    <p className="text-muted-foreground truncate font-mono text-xs">{user.email}</p>
                  </div>
                </TableCell>

                <TableCell>
                  <Select
                    value={user.role}
                    disabled={isSelf(user.id) || update.isPending}
                    onValueChange={(role) => {
                      setPendingId(user.id)
                      update.mutate(
                        { id: user.id, name: user.name, role: role as Role, status: user.status },
                        { onSettled: () => setPendingId(null) },
                      )
                    }}
                  >
                    <SelectTrigger className="h-8 w-32">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {ROLES.map((option) => (
                        <SelectItem key={option} value={option}>
                          {ROLE_LABELS[option]}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </TableCell>

                <TableCell>
                  {user.status === 'active' ? (
                    <Badge variant="success">Active</Badge>
                  ) : (
                    <Badge variant="warning">Suspended</Badge>
                  )}
                </TableCell>

                <TableCell className="text-muted-foreground text-sm">
                  {user.last_login_at ? formatRelative(user.last_login_at) : 'Never'}
                </TableCell>

                <TableCell className="text-right">
                  <div className="flex items-center justify-end gap-1">
                    {user.role !== 'admin' ? (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setScopingUser(user)}
                      >
                        <ServerIcon className="size-3.5" aria-hidden />
                        Servers
                      </Button>
                    ) : null}
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={isSelf(user.id) || pendingId === user.id}
                      onClick={() =>
                        update.mutate({
                          id: user.id,
                          name: user.name,
                          role: user.role,
                          status: user.status === 'active' ? 'suspended' : 'active',
                        })
                      }
                    >
                      {user.status === 'active' ? 'Suspend' : 'Restore'}
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      className="text-muted-foreground hover:text-destructive"
                      disabled={isSelf(user.id) || remove.isPending}
                      aria-label={`Delete ${user.name}`}
                      onClick={() => {
                        if (confirm(`Delete ${user.email}? This cannot be undone.`)) {
                          remove.mutate(user.id)
                        }
                      }}
                    >
                      <Trash2 aria-hidden />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>

      {scopingUser ? (
        <ManageUserServersDialog
          user={scopingUser}
          open={true}
          onOpenChange={(open) => {
            if (!open) setScopingUser(null)
          }}
        />
      ) : null}
    </Card>
  )
}

function PendingInvitations() {
  const { data: invitations, isPending } = useInvitations()
  const revoke = useDeleteInvitation()

  if (isPending || !invitations?.length) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pending invitations</CardTitle>
        <CardDescription>Links that have been created but not yet used.</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Email</TableHead>
              <TableHead>Role</TableHead>
              <TableHead>Expires</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {invitations.map((invitation) => (
              <TableRow key={invitation.id}>
                <TableCell className="font-mono text-xs">{invitation.email}</TableCell>
                <TableCell>{ROLE_LABELS[invitation.role]}</TableCell>
                <TableCell className="text-muted-foreground text-sm">
                  {formatRelative(invitation.expires_at)}
                </TableCell>
                <TableCell className="text-right">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={revoke.isPending}
                    onClick={() => revoke.mutate(invitation.id)}
                  >
                    Revoke
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

export function Users() {
  return (
    <div className="grid max-w-5xl min-w-0 grid-cols-[minmax(0,1fr)] gap-4">
      <UsersTable />
      <PendingInvitations />
    </div>
  )
}
