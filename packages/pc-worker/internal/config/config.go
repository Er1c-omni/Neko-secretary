package config

import (
	"os"
	"strings"
	"time"
)

type Config struct {
	NodeID            string
	DisplayName       string
	HubURL            string
	Tags              []string
	HeartbeatInterval time.Duration
	Workspaces        []string
	DryRun            bool
}

func Default() Config {
	hostname, err := os.Hostname()
	if err != nil || strings.TrimSpace(hostname) == "" {
		hostname = "pc-worker-01"
	}
	return Config{
		NodeID:            strings.ToLower(hostname),
		DisplayName:       hostname,
		HubURL:            "ws://127.0.0.1:8787/ws",
		Tags:              []string{"desktop"},
		HeartbeatInterval: 15 * time.Second,
		Workspaces:        []string{},
		DryRun:            false,
	}
}
