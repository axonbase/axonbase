package com.axonbase.springdata;

import com.axonbase.jdbc.SagaBinding;
import com.axonbase.jdbc.SagaScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AxonSagaTemplateTest {
    @Test
    void joinsAnActiveSagaWithinTheLocalTransactionWithoutOwningItsLifecycle() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(
            new RecordingScopeFactory(events, true),
            new RecordingTransactionManager(events));

        String result = template.execute("orders", "corr-1", status -> {
            events.add("work");
            return "saved";
        });

        assertEquals("saved", result);
        assertEquals(List.of("begin-local", "join-saga", "work", "leave-saga", "commit-local"), events);
    }

    @Test
    void rejectsAnInactiveSagaAndRollsBackTheLocalTransaction() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(
            new RecordingScopeFactory(events, false),
            new RecordingTransactionManager(events));

        assertThrows(IllegalStateException.class,
            () -> template.execute("orders", "corr-1", status -> "saved"));
        assertEquals(List.of("begin-local", "join-saga", "rollback-local"), events);
    }

    @Test
    void rollsBackTheLocalTransactionWhenWorkFails() {
        List<String> events = new ArrayList<>();
        AxonSagaTemplate template = new AxonSagaTemplate(
            new RecordingScopeFactory(events, true),
            new RecordingTransactionManager(events));

        assertThrows(IllegalStateException.class, () -> template.execute("orders", "corr-1", status -> {
            events.add("work");
            throw new IllegalStateException("repository failure");
        }));

        assertEquals(List.of("begin-local", "join-saga", "work", "leave-saga", "rollback-local"), events);
    }

    private static final class RecordingScopeFactory implements AxonSagaTemplate.SagaScopeFactory {
        private final List<String> events;
        private final boolean active;

        private RecordingScopeFactory(List<String> events, boolean active) {
            this.events = events;
            this.active = active;
        }

        @Override
        public SagaBinding joinSaga(String sagaName, String correlationId) {
            events.add("join-saga");
            if (!active) {
                throw new IllegalStateException("no active SAGA for correlation: " + correlationId);
            }
            return () -> events.add("leave-saga");
        }
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