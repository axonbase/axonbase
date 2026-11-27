package com.axonbase.jdbc.hibernate;

import com.axonbase.jdbc.JdbcTestServer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Hibernate contra um servidor em memória: o que foi gravado é encontrado, alterado e removido pelo id. */
class HibernateCrudTest {

    @Entity(name = "Pessoa")
    @Table(name = "hib_pessoa")
    public static class Pessoa {
        @Id
        public Long id;
        @Column(name = "name")
        public String name;
        @Column(name = "age")
        public Integer age;

        public Pessoa() {
        }

        Pessoa(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }
    }

    private JdbcTestServer server;
    private SessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        server = new JdbcTestServer();
        factory = new Configuration()
            .setProperty("hibernate.connection.driver_class", "com.axonbase.jdbc.AxonDriver")
            .setProperty("hibernate.connection.url", server.url("&sql.translate=true"))
            .setProperty("hibernate.dialect", "com.axonbase.jdbc.hibernate.AxonBaseDialect")
            .setProperty("hibernate.hbm2ddl.auto", "create")
            .addAnnotatedClass(Pessoa.class)
            .buildSessionFactory();
        factory.inTransaction(s -> {
            s.persist(new Pessoa(1L, "Ana", 30));
            s.persist(new Pessoa(2L, "O'Brien", 40));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        server.close();
    }

    @Test
    void findDevolveAEntidadeGravada() {
        factory.inTransaction(s -> {
            Pessoa ana = s.find(Pessoa.class, 1L);

            assertNotNull(ana);
            assertEquals(1L, ana.id);
            assertEquals("Ana", ana.name);
            assertEquals(30, ana.age);
            assertNull(s.find(Pessoa.class, 99L));
        });
    }

    @Test
    void consultaHqlDevolveOIdDeCadaLinha() {
        factory.inTransaction(s -> {
            List<Pessoa> lista = s.createQuery("from Pessoa p where p.age > :min", Pessoa.class)
                .setParameter("min", 35)
                .list();

            assertEquals(1, lista.size());
            assertEquals(2L, lista.get(0).id);
            assertEquals("O'Brien", lista.get(0).name);
        });
    }

    @Test
    void consultaPorVariosIdsEncontraTodos() {
        factory.inTransaction(s -> {
            List<Pessoa> lista = s.createQuery("from Pessoa p where p.id in (1, 2)", Pessoa.class)
                .list();

            assertEquals(2, lista.size());
        });
    }

    @Test
    void alteracaoERemocaoAtingemORegistro() {
        factory.inTransaction(s -> s.find(Pessoa.class, 1L).age = 31);
        factory.inTransaction(s -> assertEquals(31, s.find(Pessoa.class, 1L).age));

        factory.inTransaction(s -> s.remove(s.find(Pessoa.class, 1L)));
        factory.inTransaction(s -> {
            assertNull(s.find(Pessoa.class, 1L));
            assertNotNull(s.find(Pessoa.class, 2L));
        });
    }
}
