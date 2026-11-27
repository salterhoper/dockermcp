package api

import (
	"errors"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/sirtheprogrammer/docker-deployments/server/internal/auth"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/store"
)

func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) error {
	users, err := s.Store.ListUsers(r.Context())
	if err != nil {
		return Internal(err)
	}
	return JSON(w, s.Log, http.StatusOK, map[string]any{"users": users})
}

type serverGrantDTO struct {
	ServerID   string `json:"server_id"`
	Permission string `json:"permission"`
}

func (s *Server) parseServerGrants(r *http.Request, raw []serverGrantDTO) ([]store.ServerGrantInput, error) {
	if len(raw) == 0 {
		return nil, nil
	}
	seen := make(map[string]bool, len(raw))
	out := make([]store.ServerGrantInput, 0, len(raw))
	for _, item := range raw {
		id := item.ServerID
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		perm := store.ServerPermission(item.Permission)
		if perm == "" {
			perm = store.ServerOperate
		}
		if perm != store.ServerRead && perm != store.ServerOperate {
			return nil, Invalid(fields{"server_grants": "Server permission must be read or operate."})
		}
		if _, err := s.Store.ServerByID(r.Context(), id); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				return nil, Invalid(fields{"server_grants": "One or more selected servers do not exist."})
			}
			return nil, Internal(err)
		}
		out = append(out, store.ServerGrantInput{ServerID: id, Permission: perm})
	}
	return out, nil
}

type createUserRequest struct {
	Email        string           `json:"email"`
	Name         string           `json:"name"`
	Password     string           `json:"password"`
	Role         string           `json:"role"`
	ServerGrants []serverGrantDTO `json:"server_grants"`
}

func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request) error {
	var req createUserRequest
	if err := DecodeJSON(w, r, &req); err != nil {
		return err
	}

	f := fields{}
	email := f.email("email", req.Email)
	name := f.required("name", req.Name, 1, 100)
	role, err := auth.ParseRole(req.Role)
	if err != nil {
		f.add("role", err.Error())
	}
	if err := auth.ValidatePassword(req.Password); err != nil {
		f.add("password", err.Error())
	}
	if err := f.err(); err != nil {
		return err
	}

	if _, err := s.Store.UserByEmail(r.Context(), email); err == nil {
		return Invalid(fields{"email": "Someone with that email already has an account."})
	} else if !errors.Is(err, store.ErrNotFound) {
		return Internal(err)
	}

	grants, err := s.parseServerGrants(r, req.ServerGrants)
	if err != nil {
		return err
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		return Internal(err)
	}

	created, err := s.Store.CreateUserWithGrants(r.Context(), email, name, hash, role, grants)
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			return Invalid(fields{"email": "Someone with that email already has an account."})
		}
		return Internal(err)
	}

	AuditResource(r.Context(), "users", created.ID)
	AuditMeta(r.Context(), "email", email)
	AuditMeta(r.Context(), "role", string(role))
	AuditMeta(r.Context(), "scoped_servers", len(grants))

	return JSON(w, s.Log, http.StatusCreated, created)
}

func (s *Server) handleListUserServers(w http.ResponseWriter, r *http.Request) error {
	targetID := chi.URLParam(r, "userID")
	if _, err := s.Store.UserByID(r.Context(), targetID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such user.")
		}
		return Internal(err)
	}

	grants, err := s.Store.ListUserServerGrants(r.Context(), targetID)
	if err != nil {
		return Internal(err)
	}
	if grants == nil {
		grants = []store.UserServerGrant{}
	}
	return JSON(w, s.Log, http.StatusOK, map[string]any{"grants": grants})
}

type setUserServersRequest struct {
	Grants []serverGrantDTO `json:"grants"`
}

func (s *Server) handleSetUserServers(w http.ResponseWriter, r *http.Request) error {
	targetID := chi.URLParam(r, "userID")
	if _, err := s.Store.UserByID(r.Context(), targetID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such user.")
		}
		return Internal(err)
	}

	var req setUserServersRequest
	if err := DecodeJSON(w, r, &req); err != nil {
		return err
	}

	parsed, err := s.parseServerGrants(r, req.Grants)
	if err != nil {
		return err
	}

	if err := s.Store.SetUserServerGrants(r.Context(), targetID, parsed); err != nil {
		return Internal(err)
	}

	updated, err := s.Store.ListUserServerGrants(r.Context(), targetID)
	if err != nil {
		return Internal(err)
	}
	if updated == nil {
		updated = []store.UserServerGrant{}
	}

	AuditResource(r.Context(), "users", targetID)
	AuditMeta(r.Context(), "scoped_servers", len(updated))
	return JSON(w, s.Log, http.StatusOK, map[string]any{"grants": updated})
}

type updateUserRequest struct {
	Name   string `json:"name"`
	Role   string `json:"role"`
	Status string `json:"status"`
}

// handleUpdateUser changes another account.
//
// Two guard rails matter here: an admin must not be able to demote or suspend
// themselves by accident, and the instance must never end up with no active
// administrator able to fix things.
func (s *Server) handleUpdateUser(w http.ResponseWriter, r *http.Request) error {
	var req updateUserRequest
	if err := DecodeJSON(w, r, &req); err != nil {
		return err
	}

	targetID := chi.URLParam(r, "userID")
	actor := MustIdentity(r.Context())

	target, err := s.Store.UserByID(r.Context(), targetID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such user.")
		}
		return Internal(err)
	}

	f := fields{}
	name := f.required("name", req.Name, 1, 100)
	role, err := auth.ParseRole(req.Role)
	if err != nil {
		f.add("role", err.Error())
	}
	status := store.UserStatus(req.Status)
	if status != store.UserActive && status != store.UserSuspended {
		f.add("status", "Status must be active or suspended.")
	}
	if err := f.err(); err != nil {
		return err
	}

	if target.ID == actor.User.ID {
		if role != target.Role {
			return Forbidden("You cannot change your own role. Ask another admin.")
		}
		if status != target.Status {
			return Forbidden("You cannot suspend your own account.")
		}
	}

	losingAnAdmin := target.Role == auth.RoleAdmin && target.Status == store.UserActive &&
		(role != auth.RoleAdmin || status != store.UserActive)
	if losingAnAdmin {
		admins, err := s.Store.CountAdmins(r.Context())
		if err != nil {
			return Internal(err)
		}
		if admins <= 1 {
			return Conflict("This is the only active admin. Promote someone else first.")
		}
	}

	updated, err := s.Store.UpdateUser(r.Context(), targetID, name, role, status)
	if err != nil {
		return Internal(err)
	}

	// A suspended account keeps working until its cookie is gone, so drop the
	// sessions rather than waiting for them to expire.
	if status == store.UserSuspended {
		if err := s.Store.DeleteSessionsForUser(r.Context(), targetID); err != nil {
			s.Log.Warn("drop sessions for suspended user", "user", targetID, "error", err)
		}
	}

	AuditResource(r.Context(), "users", targetID)
	AuditMeta(r.Context(), "role", string(role))
	AuditMeta(r.Context(), "status", string(status))
	return JSON(w, s.Log, http.StatusOK, updated)
}

func (s *Server) handleDeleteUser(w http.ResponseWriter, r *http.Request) error {
	targetID := chi.URLParam(r, "userID")
	actor := MustIdentity(r.Context())

	if targetID == actor.User.ID {
		return Forbidden("You cannot delete your own account.")
	}

	target, err := s.Store.UserByID(r.Context(), targetID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such user.")
		}
		return Internal(err)
	}

	if target.Role == auth.RoleAdmin && target.Status == store.UserActive {
		admins, err := s.Store.CountAdmins(r.Context())
		if err != nil {
			return Internal(err)
		}
		if admins <= 1 {
			return Conflict("This is the only active admin. Promote someone else first.")
		}
	}

	if err := s.Store.DeleteUser(r.Context(), targetID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such user.")
		}
		return Internal(err)
	}

	AuditResource(r.Context(), "users", targetID)
	AuditMeta(r.Context(), "email", target.Email)
	return NoContent(w)
}

// --- invitations --------------------------------------------------------

func (s *Server) handleListInvitations(w http.ResponseWriter, r *http.Request) error {
	invitations, err := s.Store.ListInvitations(r.Context())
	if err != nil {
		return Internal(err)
	}
	return JSON(w, s.Log, http.StatusOK, map[string]any{"invitations": invitations})
}

type createInvitationRequest struct {
	Email        string           `json:"email"`
	Name         string           `json:"name"`
	Role         string           `json:"role"`
	ServerGrants []serverGrantDTO `json:"server_grants"`
}

type createInvitationResponse struct {
	store.Invitation
	// URL is returned exactly once, at creation. There is no mail server in a
	// self-hosted install, so the admin copies this link and sends it however
	// they like.
	URL string `json:"url"`
}

func (s *Server) handleCreateInvitation(w http.ResponseWriter, r *http.Request) error {
	var req createInvitationRequest
	if err := DecodeJSON(w, r, &req); err != nil {
		return err
	}

	f := fields{}
	email := f.email("email", req.Email)
	role, err := auth.ParseRole(req.Role)
	if err != nil {
		f.add("role", err.Error())
	}
	if err := f.err(); err != nil {
		return err
	}

	if _, err := s.Store.UserByEmail(r.Context(), email); err == nil {
		return Invalid(fields{"email": "Someone with that email already has an account."})
	} else if !errors.Is(err, store.ErrNotFound) {
		return Internal(err)
	}

	grants, err := s.parseServerGrants(r, req.ServerGrants)
	if err != nil {
		return err
	}

	token, hash, err := s.Hasher.NewToken()
	if err != nil {
		return Internal(err)
	}

	actor := MustIdentity(r.Context())
	invitation, err := s.Store.CreateInvitationWithGrants(r.Context(), email, req.Name, role,
		actor.User.ID, hash, time.Now().Add(invitationLifetime), grants)
	if err != nil {
		return Internal(err)
	}

	AuditResource(r.Context(), "invitations", invitation.ID)
	AuditMeta(r.Context(), "email", email)
	AuditMeta(r.Context(), "role", string(role))

	return JSON(w, s.Log, http.StatusCreated, createInvitationResponse{
		Invitation: *invitation,
		URL:        s.Config.AppURL + "/invite/" + token,
	})
}

func (s *Server) handleDeleteInvitation(w http.ResponseWriter, r *http.Request) error {
	id := chi.URLParam(r, "invitationID")
	if err := s.Store.DeleteInvitation(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return NotFound("No such invitation.")
		}
		return Internal(err)
	}
	AuditResource(r.Context(), "invitations", id)
	return NoContent(w)
}
