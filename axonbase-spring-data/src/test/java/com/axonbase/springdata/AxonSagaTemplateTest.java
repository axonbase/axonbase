package com.axonbase.springdata;

import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AxonSagaTemplateTest {
    @Test
    void joinsAnActiveSagaWithinTheLocalTransactionWithoutOwningItsLifecycle() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(new ActiveSagaScope(events), new RecordingTransactionManager(events));

        String result = template.execute("orders", "corr-1", status -> {
            events.add("work");
            return "saved";
        });

        assertEquals("saved", result);
        assertEquals(List.of("show", "begin-local", "bind", "work", "clear", "commit-local"), events);
    }

    @Test
    void rejectsAnInactiveSagaBeforeStartingTheLocalTransaction() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(new InactiveSagaScope(events), new RecordingTransactionManager(events));

        assertThrows(IllegalStateException.class, () -> template.execute("orders", "corr-1", status -> "saved"));
        assertEquals(List.of("show"), events);
    }

    @Test
    void clearsTheCorrelationAndRollsBackTheLocalTransactionWhenWorkFails() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(new ActiveSagaScope(events), new RecordingTransactionManager(events));

        assertThrows(IllegalStateException.class, () -> template.execute("orders", "corr-1", status -> {
            events.add("work");
            throw new IllegalStateException("repository failure");
        }));

        assertEquals(List.of("show", "begin-local", "bind", "work", "clear", "rollback-local"), events);
    }

    private static class ActiveSagaScope implements AxonSagaTemplate.SagaCorrelationScope {
        private final List<String> events;

        private ActiveSagaScope(List<String> events) {
            this.events = events;
        }

        @Override
        public AxonValue show(String sagaName, String correlationId) {
            events.add("show");
            return saga(sagaName, correlationId, "RUNNING");
        }

        @Override
        public void bind(String correlationId) {
            events.add("bind");
        }

        @Override
        public void clear() {
            events.add("clear");
        }
    }

    private static final class InactiveSagaScope extends ActiveSagaScope {
        private InactiveSagaScope(List<String> events) {
            super(events);
        }

        @Override
        public AxonValue show(String sagaName, String correlationId) {
            super.show(sagaName, correlationId);
            return saga(sagaName, correlationId, "COMMITTED");
        }
    }

    private static AxonValue saga(String name, String correlationId, String status) {
        return AxonValue.object(java.util.Map.of("saga", AxonValue.object(java.util.Map.of(
            "name", AxonValue.str(name),
            "correlation_id", AxonValue.str(correlationId),
            "status", AxonValue.str(status)))));
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<String> events;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin-local");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit-local");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback-local");
        }
    }
}
