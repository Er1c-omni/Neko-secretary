package activity

import "time"

// Monitor reports the latest user keyboard/mouse activity timestamp
// to feed Core Hub's 3-source sedentary detection.
type Monitor interface {
	LastInputActiveAt() time.Time
}

type StubMonitor struct {
	startedAt time.Time
}

func NewStubMonitor() *StubMonitor {
	return &StubMonitor{startedAt: time.Now()}
}

func (m *StubMonitor) LastInputActiveAt() time.Time {
	return m.startedAt
}
