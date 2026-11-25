package adapter

import (
	"context"
	"fmt"
	"os/exec"
)

type DispatchRequest struct {
	ThreadID  string
	Workspace string
	Prompt    string
}

type DispatchResult struct {
	Provider string
	Accepted bool
	Message  string
}

type ProviderAdapter interface {
	Name() string
	Available() bool
	Dispatch(ctx context.Context, req DispatchRequest) (DispatchResult, error)
}

type CLIAdapter struct {
	name   string
	binary string
}

func NewCLIAdapter(name, binary string) *CLIAdapter {
	return &CLIAdapter{name: name, binary: binary}
}

func (a *CLIAdapter) Name() string {
	return a.name
}

func (a *CLIAdapter) Available() bool {
	_, err := exec.LookPath(a.binary)
	return err == nil
}

func (a *CLIAdapter) Dispatch(_ context.Context, req DispatchRequest) (DispatchResult, error) {
	if req.Prompt == "" {
		return DispatchResult{}, fmt.Errorf("empty prompt for provider %s", a.name)
	}
	return DispatchResult{
		Provider: a.name,
		Accepted: true,
		Message:  fmt.Sprintf("queued thread %s on %s", req.ThreadID, a.name),
	}, nil
}

type Registry struct {
	adapters map[string]ProviderAdapter
}

func NewDefaultRegistry() *Registry {
	r := &Registry{adapters: make(map[string]ProviderAdapter)}
	defaults := []ProviderAdapter{
		NewCLIAdapter("t3code", "t3"),
		NewCLIAdapter("dsh", "dsh"),
		NewCLIAdapter("codex", "codex"),
		NewCLIAdapter("claude-code", "claude"),
		NewCLIAdapter("shell", "pwsh"),
	}
	for _, a := range defaults {
		r.adapters[a.Name()] = a
	}
	return r
}

func (r *Registry) Names() []string {
	out := make([]string, 0, len(r.adapters))
	for k := range r.adapters {
		out = append(out, k)
	}
	return out
}

func (r *Registry) Get(name string) (ProviderAdapter, bool) {
	a, ok := r.adapters[name]
	return a, ok
}
