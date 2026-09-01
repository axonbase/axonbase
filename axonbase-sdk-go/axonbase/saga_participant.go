package axonbase

import "fmt"

// SagaParticipantTransaction joins an active Saga while controlling only the local transaction.
type SagaParticipantTransaction struct {
	Axon          *Axon
	CorrelationID string
	begun         bool
	finished      bool
}

func NewSagaParticipantTransaction(axon *Axon, correlationID string) *SagaParticipantTransaction {
	return &SagaParticipantTransaction{Axon: axon, CorrelationID: correlationID}
}

func (s *SagaParticipantTransaction) IsBegun() bool { return s.begun }

func (s *SagaParticipantTransaction) IsFinished() bool { return s.finished }

func (s *SagaParticipantTransaction) Begin() error {
	if s.begun {
		return AxonSdkError{Message: fmt.Sprintf("Transaction already begun: %s", s.CorrelationID)}
	}
	if err := s.Axon.Begin(); err != nil {
		return err
	}
	if err := s.Axon.LetVar("saga_corr", s.CorrelationID); err != nil {
		_ = s.Axon.Cancel()
		return err
	}
	s.begun = true
	return nil
}

func (s *SagaParticipantTransaction) Step(axonql string) (interface{}, error) {
	if err := s.assertActive(); err != nil {
		return nil, err
	}
	return s.Axon.Query(axonql, nil)
}

func (s *SagaParticipantTransaction) Commit() error {
	if err := s.assertActive(); err != nil {
		return err
	}
	if err := s.Axon.Commit(); err != nil {
		return err
	}
	s.finished = true
	return nil
}

func (s *SagaParticipantTransaction) Rollback() error {
	if !s.begun || s.finished {
		return nil
	}
	if err := s.Axon.Cancel(); err != nil {
		return err
	}
	s.finished = true
	return nil
}

func (s *SagaParticipantTransaction) assertActive() error {
	if !s.begun {
		return AxonSdkError{Message: "Transaction not begun"}
	}
	if s.finished {
		return AxonSdkError{Message: "Transaction already finished"}
	}
	return nil
}
