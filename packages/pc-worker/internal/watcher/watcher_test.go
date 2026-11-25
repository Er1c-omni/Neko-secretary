package watcher

import (
	"testing"

	"github.com/ER1C-6832/Neko-secretary/packages/pc-worker/internal/adapter"
)

func TestLogWatcherMatchesAlerts(t *testing.T) {
	w := NewDefaultWatcher()

	alert, ok := w.InspectLine("RuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB")
	if !ok {
		t.Fatal("expected cuda_oom alert to match")
	}
	if alert.RuleName != "cuda_oom" || alert.Severity != "critical" {
		t.Fatalf("unexpected alert: %+v", alert)
	}

	if _, ok := w.InspectLine("Step 120/500 loss=0.42"); ok {
		t.Fatal("expected normal training line not to trigger alert")
	}
}

func TestDefaultAdapterRegistry(t *testing.T) {
	reg := adapter.NewDefaultRegistry()
	for _, name := range []string{"t3code", "dsh", "codex", "claude-code", "shell"} {
		if _, ok := reg.Get(name); !ok {
			t.Fatalf("expected provider %s to be registered", name)
		}
	}
}
