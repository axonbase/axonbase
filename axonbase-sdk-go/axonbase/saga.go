package axonbase

import (
	"fmt"
	"strings"
)

// SagaTransaction is a client-side helper for an AxonBase distributed saga.
type SagaTransaction struct {
	Axon          *Axon
	SagaName      string
	CorrelationID string
	begun         bool
	finished      bool
}

func NewSagaTransaction(axon *Axon, sagaName, correlationID string) *SagaTransaction {
	return &SagaTransaction{Axon: axon, SagaName: sagaName, CorrelationID: correlationID}
}

func (s *SagaTransaction) IsBegun() bool { return s.begun }

func (s *SagaTransaction) IsFinished() bool { return s.finished }

func (s *SagaTransaction) Begin() error {
	if s.begun {
		return AxonSdkError{Message: fmt.Sprintf("Saga already begun: %s", s.CorrelationID)}
	}
	result, err := s.Axon.Query(fmt.Sprintf("BEGIN SAGA %s WITH CORRELATION '%s'", escapeSagaValue(s.SagaName), escapeSagaValue(s.CorrelationID)), nil)
	if err != nil {
		return err
	}
	if hasSagaStatus(result, "RUNNING") {
		s.begun = true
		return nil
	}
	return AxonSdkError{Message: fmt.Sprintf("Failed to begin saga: %v", result)}
}

func (s *SagaTransaction) Step(axonql string) (interface{}, error) {
	if err := s.assertActive(); err != nil {
		return nil, err
	}
	if err := s.Axon.LetVar("saga_corr", s.CorrelationID); err != nil {
		return nil, err
	}
	return s.Axon.Query(axonql, nil)
}

func (s *SagaTransaction) Commit() error {
	if err := s.assertActive(); err != nil {
		return err
	}
	result, err := s.Axon.Query(fmt.Sprintf("COMMIT SAGA %s WITH CORRELATION '%s'", escapeSagaValue(s.SagaName), escapeSagaValue(s.CorrelationID)), nil)
	if err != nil {
		return err
	}
	s.finished = true
	if !hasSagaStatus(result, "COMMITTED") {
		return AxonSdkError{Message: fmt.Sprintf("Saga commit failed: %v", result)}
	}
	return nil
}

func (s *SagaTransaction) Rollback() error {
	if !s.begun || s.finished {
		return nil
	}
	if _, err := s.Axon.Query(fmt.Sprintf("CANCEL SAGA %s WITH CORRELATION '%s'", escapeSagaValue(s.SagaName), escapeSagaValue(s.CorrelationID)), nil); err != nil {
		return err
	}
	s.finished = true
	return nil
}

func (s *SagaTransaction) Describe() (interface{}, error) {
	return s.Axon.Query(fmt.Sprintf("SHOW SAGA TRANSACTION %s '%s'", escapeSagaValue(s.SagaName), escapeSagaValue(s.CorrelationID)), nil)
}

func (s *SagaTransaction) assertActive() error {
	if !s.begun {
		return AxonSdkError{Message: "Saga not begun"}
	}
	if s.finished {
		return AxonSdkError{Message: "Saga already finished"}
	}
	return nil
}

func hasSagaStatus(result interface{}, expected string) bool {
	values, ok := result.(map[string]interface{})
	if !ok {
		return false
	}
	status, ok := values["status"].(string)
	return ok && status == expected
}

func escapeSagaValue(value string) string {
	value = strings.ReplaceAll(value, "\\", "\\\\")
	return strings.ReplaceAll(value, "'", "''")
}
