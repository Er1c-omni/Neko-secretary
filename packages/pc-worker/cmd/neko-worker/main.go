package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/ER1C-6832/Neko-secretary/packages/pc-worker/internal/activity"
	"github.com/ER1C-6832/Neko-secretary/packages/pc-worker/internal/adapter"
	"github.com/ER1C-6832/Neko-secretary/packages/pc-worker/internal/config"
	"github.com/ER1C-6832/Neko-secretary/packages/pc-worker/internal/watcher"
)

func main() {
	cfg := config.Default()
	var tagsCSV string

	flag.StringVar(&cfg.NodeID, "node-id", cfg.NodeID, "Unique worker node ID")
	flag.StringVar(&cfg.DisplayName, "name", cfg.DisplayName, "Display name")
	flag.StringVar(&cfg.HubURL, "hub-url", cfg.HubURL, "Core Hub WebSocket URL")
	flag.StringVar(&tagsCSV, "tags", strings.Join(cfg.Tags, ","), "Comma-separated tags")
	flag.BoolVar(&cfg.DryRun, "dry-run", false, "Print registration payload and exit")
	flag.Parse()

	if strings.TrimSpace(tagsCSV) != "" {
		cfg.Tags = strings.Split(tagsCSV, ",")
	}

	reg := adapter.NewDefaultRegistry()
	mon := activity.NewStubMonitor()
	logWatcher := watcher.NewDefaultWatcher()
	_ = logWatcher

	payload := map[string]any{
		"nodeId":            cfg.NodeID,
		"displayName":       cfg.DisplayName,
		"hubUrl":            cfg.HubURL,
		"tags":              cfg.Tags,
		"providers":         reg.Names(),
		"lastInputActiveAt": mon.LastInputActiveAt().UnixMilli(),
	}

	encoded, _ := json.MarshalIndent(payload, "", "  ")
	fmt.Printf("[neko-worker] initialized:\n%s\n", string(encoded))

	if cfg.DryRun {
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	ticker := time.NewTicker(cfg.HeartbeatInterval)
	defer ticker.Stop()

	fmt.Printf("[neko-worker] heartbeat loop running (target=%s). Press Ctrl+C to exit.\n", cfg.HubURL)
	for {
		select {
		case <-ctx.Done():
			fmt.Println("[neko-worker] shutting down cleanly")
			return
		case t := <-ticker.C:
			fmt.Printf("[neko-worker] heartbeat tick ts=%d lastInputActiveAt=%d\n",
				t.UnixMilli(), mon.LastInputActiveAt().UnixMilli())
		}
	}
}
