package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralRepository;
import com.memora.application.ports.UserRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListOwnedMuralsTest {
    private static final UUID OWNER_ID = UUID.fromString("cf5d4e2b-a987-43d7-9ed0-512c072aa8e1");
    private final MuralCursorCodec cursorCodec = new MuralCursorCodec();

    @Test
    void requestsLimitPlusOneAndDerivesNextCursorFromLastReturnedItem() {
        List<Mural> rows = List.of(
                mural("00000000-0000-0000-0000-000000000003", "2026-10-24T12:00:02Z"),
                mural("00000000-0000-0000-0000-000000000002", "2026-10-24T12:00:01Z"),
                mural("00000000-0000-0000-0000-000000000001", "2026-10-24T12:00:00Z")
        );
        RecordingMuralRepository repository = new RecordingMuralRepository(rows);
        ListOwnedMurals useCase = useCase(repository);

        MuralPage page = useCase.execute(2, MuralStatus.DRAFT, null);

        assertThat(page.items()).containsExactly(rows.getFirst(), rows.get(1));
        assertThat(repository.ownerId).isEqualTo(OWNER_ID);
        assertThat(repository.status).isEqualTo(MuralStatus.DRAFT);
        assertThat(repository.maxResults).isEqualTo(3);
        assertThat(cursorCodec.decode(page.nextCursor(), MuralStatus.DRAFT))
                .isEqualTo(new MuralCursor("DRAFT", rows.get(1).createdAt(), rows.get(1).id()));
    }

    @Test
    void rejectsInvalidLimitAndCursorBeforeQueryingRepository() {
        RecordingMuralRepository repository = new RecordingMuralRepository(List.of());
        ListOwnedMurals useCase = useCase(repository);
        String draftCursor = cursorCodec.encode(MuralStatus.DRAFT,
                Instant.parse("2026-10-24T12:00:00Z"), UUID.randomUUID());

        assertThatThrownBy(() -> useCase.execute(0, null, null)).isInstanceOf(MuralQueryException.class);
        assertThatThrownBy(() -> useCase.execute(51, null, null)).isInstanceOf(MuralQueryException.class);
        assertThatThrownBy(() -> useCase.execute(20, MuralStatus.ARCHIVED, draftCursor))
                .isInstanceOf(MuralQueryException.class);
        assertThat(repository.queryCalls).isZero();
    }

    private static ListOwnedMurals useCase(RecordingMuralRepository repository) {
        CurrentIdentity identity = () -> new Identity("urn:test", "subject");
        UserRepository users = ignored -> OWNER_ID;
        return new ListOwnedMurals(repository, new CurrentUserId(identity, users), new MuralCursorCodec());
    }

    private static Mural mural(String id, String createdAt) {
        Instant instant = Instant.parse(createdAt);
        return Mural.restore(UUID.fromString(id), OWNER_ID, MuralStatus.DRAFT, 1,
                "Mural " + id, null, instant, instant);
    }

    private static final class RecordingMuralRepository implements MuralRepository {
        private final List<Mural> rows;
        private UUID ownerId;
        private MuralStatus status;
        private int maxResults;
        private int queryCalls;

        private RecordingMuralRepository(List<Mural> rows) {
            this.rows = rows;
        }

        @Override
        public Optional<IdempotentMural> findByOwnerAndIdempotencyKey(UUID ownerId, UUID idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Mural save(Mural mural, UUID idempotencyKey, String requestHash) {
            return mural;
        }

        @Override
        public List<Mural> findOwned(UUID ownerId, MuralStatus status, Instant beforeCreatedAt,
                                     UUID beforeId, int maxResults) {
            queryCalls++;
            this.ownerId = ownerId;
            this.status = status;
            this.maxResults = maxResults;
            return rows.stream().limit(maxResults).toList();
        }

        @Override
        public Optional<Mural> findOwnedById(UUID ownerId, UUID muralId) {
            return Optional.empty();
        }

        @Override
        public Mural updateOwned(Mural mural, int expectedVersion) {
            throw new UnsupportedOperationException("Update is not used by this test.");
        }
    }
}
