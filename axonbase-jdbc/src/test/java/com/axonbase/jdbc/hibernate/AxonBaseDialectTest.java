package com.axonbase.jdbc.hibernate;

import jakarta.persistence.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Hibernate Dialect + sql.translate=true")
class AxonBaseDialectTest {

    private static final AtomicInteger TABLE_NUM = new AtomicInteger(0);

    @BeforeAll
    static void requiresExternalServer() {
        String url = System.getenv("AXON_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "Defina AXON_URL para executar a integração Hibernate/JDBC.");
    }

    @Entity
    @Table(name = "hibernate_p")
    public static class Person {
        @Id
        public Long id;
        @Column(name = "name")
        public String name;
        @Column(name = "age")
        public Integer age;

        public Person() {}
        public Person(Long id, String name, Integer age) { this.id = id; this.name = name; this.age = age; }
    }

    private static SessionFactory sessionFactory(Class<?> entityClass) {
        String url = System.getenv("AXON_URL");
        int n = TABLE_NUM.incrementAndGet();
        // Use a unique table suffix so we never conflict with stale field definitions
        String tableSuffix = "_test_" + n;
        // Override the table name at the entity level via properties
        return new Configuration()
            .setProperty("hibernate.connection.driver_class", "com.axonbase.jdbc.AxonDriver")
            .setProperty("hibernate.connection.url", "jdbc:axonbase:" + url + "?ns=test&db=dev&sql.translate=true")
            .setProperty("hibernate.dialect", "com.axonbase.jdbc.hibernate.AxonBaseDialect")
            .setProperty("hibernate.hbm2ddl.auto", "create")
            .setProperty("hibernate.show_sql", "true")
            .setProperty("hibernate.format_sql", "true")
            .addAnnotatedClass(entityClass)
            .buildSessionFactory();
    }

    @Test
    @DisplayName("CRUD via Hibernate Persist + Merge")
    void crud() {
        SessionFactory sf = sessionFactory(Person.class);
        try {
            Long id = System.nanoTime();
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                Person alice = new Person(id, "Alice", 30);
                s.persist(alice);
                s.getTransaction().commit();
            }

            try (Session s = sf.openSession()) {
                Person found = s.get(Person.class, id);
                assertNotNull(found);
                assertEquals("Alice", found.name);
                assertEquals(30, found.age);
            }

            try (Session s = sf.openSession()) {
                s.beginTransaction();
                Person found = s.get(Person.class, id);
                found.age = 31;
                s.merge(found);
                s.getTransaction().commit();
            }

            try (Session s = sf.openSession()) {
                Person found = s.get(Person.class, id);
                assertEquals(31, found.age);
            }

            try (Session s = sf.openSession()) {
                s.beginTransaction();
                Person found = s.get(Person.class, id);
                s.remove(found);
                s.getTransaction().commit();
            }

            try (Session s = sf.openSession()) {
                assertNull(s.get(Person.class, id));
            }
        } finally {
            sf.close();
        }
    }

    @Test
    @DisplayName("HQL Query")
    void hql() {
        SessionFactory sf = sessionFactory(Person.class);
        try {
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.persist(new Person(System.nanoTime(), "Bob", 25));
                s.persist(new Person(System.nanoTime() + 1, "Charlie", 35));
                s.getTransaction().commit();
            }

            try (Session s = sf.openSession()) {
                var results = s.createQuery("from Person p where p.age > :min", Person.class)
                    .setParameter("min", 30)
                    .list();
                assertEquals(1, results.size());
                assertEquals("Charlie", results.get(0).name);
            }
        } finally {
            sf.close();
        }
    }
}
