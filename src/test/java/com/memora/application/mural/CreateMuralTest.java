package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralRepository;
import com.memora.application.ports.UserRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;
import com.memora.domain.mural.MuralValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateMuralTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00.123456789Z");
    private static final UUID OWNER = UUID.fromString("cf5d4e2b-a987-43d7-9ed0-512c072aa8e1");
    private static final UUID MURAL_ID = UUID.fromString("c0f5f693-7160-4e49-9d76-cfaba4edce1c");
    private static final UUID KEY = UUID.fromString("644d8bbf-1498-4e29-8e75-9142b2a4c9c1");

    @Test
    void normalizesContentAndReplaysEquivalentIdempotentRequest() {
        InMemoryMuralRepository repository = new InMemoryMuralRepository();
        CreateMural useCase = useCase(repository, () -> OWNER, () -> MURAL_ID);

        CreateMuralResult created = useCase.execute(new CreateMuralCommand("\u2003Festa\u2003", "  Sofia ", KEY));
        CreateMuralResult replayed = useCase.execute(new CreateMuralCommand("Festa", "Sofia", KEY));

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.mural()).isEqualTo(created.mural());
        assertThat(created.mural().status()).isEqualTo(MuralStatus.DRAFT);
        assertThat(created.mural().version()).isEqualTo(1);
        assertThat(created.mural().title()).isEqualTo("Festa");
        assertThat(created.mural().description()).isEqualTo("Sofia");
        assertThat(created.mural().createdAt()).isEqualTo(NOW);
        assertThat(repository.savedCount).isEqualTo(1);
    }

    @Test
    void rejectsConflictingPayloadForPreviouslyUsedKeyWithoutRevealingStoredValues() {
        InMemoryMuralRepository repository = new InMemoryMuralRepository();
        CreateMural useCase = useCase(repository, () -> OWNER, () -> MURAL_ID);
        useCase.execute(new CreateMuralCommand("Private title", null, KEY));

        assertThatThrownBy(() -> useCase.execute(new CreateMuralCommand("Different title", null, KEY)))
                .isInstanceOf(MuralIdempotencyConflictException.class)
                .hasMessage("A chave de idempotência já foi usada com outro conteúdo.");
        assertThat(repository.savedCount).isEqualTo(1);
    }

    @Test
    void rejectsInvalidTextBeforeResolvingOrPersistingTheOwner() {
        InMemoryMuralRepository repository = new InMemoryMuralRepository();
        int[] identityResolutions = {0};
        CreateMural useCase = useCase(repository, () -> {
            identityResolutions[0]++;
            return OWNER;
        }, () -> MURAL_ID);

        assertThatThrownBy(() -> useCase.execute(new CreateMuralCommand(" \u2003 ", null, KEY)))
                .isInstanceOf(MuralValidationException.class);
        assertThat(identityResolutions[0]).isZero();
        assertThat(repository.savedCount).isZero();
    }

    private static CreateMural useCase(InMemoryMuralRepository repository, UserIdSupplier owner,
                                       CreateMural.IdGenerator ids) {
        CurrentIdentity identity = () -> new Identity("urn:test", "subject");
        UserRepository users = ignored -> owner.get();
        return new CreateMural(repository, new CurrentUserId(identity, users),
                Clock.fixed(NOW, ZoneOffset.UTC), ids);
    }

    @FunctionalInterface
    private interface UserIdSupplier {
        UUID get();
    }

    private static final class InMemoryMuralRepository implements MuralRepository {
        private final Map<String, IdempotentMural> byOwnerAndKey = new HashMap<>();
        private int savedCount;

        @Override
        public Optional<IdempotentMural> findByOwnerAndIdempotencyKey(UUID ownerId, UUID idempotencyKey) {
            return Optional.ofNullable(byOwnerAndKey.get(ownerId + ":" + idempotencyKey));
        }

        @Override
        public Mural save(Mural mural, UUID idempotencyKey, String requestHash) {
            byOwnerAndKey.put(mural.ownerId() + ":" + idempotencyKey, new IdempotentMural(mural, requestHash));
            savedCount++;
            return mural;
        }

        @Override
        public java.util.List<Mural> findOwned(UUID ownerId, MuralStatus status, Instant beforeCreatedAt,
                                               UUID beforeId, int maxResults) {
            return java.util.List.of();
        }

        @Override
        public Optional<Mural> findOwnedById(UUID ownerId, UUID muralId) {
            return java.util.Optional.empty();
        }

        @Override
        public Mural updateOwned(Mural mural, int expectedVersion) {
            throw new UnsupportedOperationException("Update is not used by this test.");
        }
    }
}
