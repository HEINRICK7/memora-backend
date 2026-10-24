package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.ports.UserRepository;
import com.memora.domain.mural.Mural;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Application contract for MEM-006-R05, R08, R09 and AC08..AC10. */
class ReorderMuralPhotosTest {
    private static final Instant NOW = Instant.parse("2026-10-24T18:00:00Z");
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void delegatesTheCompleteRequestedOrderWithTheCurrentOwnerAndVersion() {
        UUID muralId = UUID.randomUUID();
        List<UUID> photoIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        MuralPhotoRepository repository = mock(MuralPhotoRepository.class);
        Mural mural = Mural.create(muralId, OWNER_ID, "Family", null, NOW);
        MuralPhotoSnapshot expected = new MuralPhotoSnapshot(mural, List.of());
        when(repository.reorder(OWNER_ID, muralId, photoIds, 3, NOW)).thenReturn(expected);

        ReorderMuralPhotos useCase = new ReorderMuralPhotos(repository, currentUserId(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MuralPhotoSnapshot actual = useCase.execute(new ReorderMuralPhotosCommand(muralId, photoIds, 3));

        assertThat(actual).isEqualTo(expected);
        verify(repository).reorder(OWNER_ID, muralId, photoIds, 3, NOW);
    }

    private static CurrentUserId currentUserId() {
        Identity identity = new Identity("urn:memora:test", "owner");
        CurrentIdentity currentIdentity = () -> identity;
        UserRepository users = ignored -> OWNER_ID;
        return new CurrentUserId(currentIdentity, users);
    }
}
