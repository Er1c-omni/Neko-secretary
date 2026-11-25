package watcher

import "strings"

type LogPatternRule struct {
	Name     string
	Needle   string
	Severity string
}

type LogAlert struct {
	RuleName string
	Severity string
	Line     string
}

type LogWatcher struct {
	rules []LogPatternRule
}

func NewDefaultWatcher() *LogWatcher {
	return &LogWatcher{
		rules: []LogPatternRule{
			{Name: "cuda_oom", Needle: "CUDA out of memory", Severity: "critical"},
			{Name: "test_failed", Needle: "Test Failed", Severity: "high"},
			{Name: "epoch_finished", Needle: "Epoch finished", Severity: "info"},
		},
	}
}

func (w *LogWatcher) InspectLine(line string) (LogAlert, bool) {
	for _, rule := range w.rules {
		if strings.Contains(line, rule.Needle) {
			return LogAlert{
				RuleName: rule.Name,
				Severity: rule.Severity,
				Line:     line,
			}, true
		}
	}
	return LogAlert{}, false
}
