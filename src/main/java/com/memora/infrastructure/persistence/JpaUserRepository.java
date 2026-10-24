package com.memora.infrastructure.persistence;

import com.memora.application.identity.Identity;
import com.memora.application.ports.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
class JpaUserRepository implements UserRepository {
    private final EntityManager entityManager;

    JpaUserRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public UUID resolveOrCreate(Identity identity) {
        entityManager.createNativeQuery("""
                        INSERT INTO users (id, identity_issuer, identity_subject, created_at)
                        VALUES (:id, :issuer, :subject, CURRENT_TIMESTAMP)
                        ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("issuer", identity.issuer())
                .setParameter("subject", identity.subject())
                .executeUpdate();

        Object id = entityManager.createNativeQuery("""
                        SELECT id FROM users
                        WHERE identity_issuer = :issuer AND identity_subject = :subject
                        """)
                .setParameter("issuer", identity.issuer())
                .setParameter("subject", identity.subject())
                .getSingleResult();
        return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
    }
}
