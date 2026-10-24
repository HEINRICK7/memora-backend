package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;

import java.util.List;
import java.util.Objects;

public final class ListOwnedMurals {
    private final MuralRepository murals;
    private final CurrentUserId currentUserId;
    private final MuralCursorCodec cursors;

    public ListOwnedMurals(MuralRepository murals, CurrentUserId currentUserId, MuralCursorCodec cursors) {
        this.murals = Objects.requireNonNull(murals);
        this.currentUserId = Objects.requireNonNull(currentUserId);
        this.cursors = Objects.requireNonNull(cursors);
    }

    public MuralPage execute(Integer requestedLimit, MuralStatus status, String encodedCursor) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) {
            throw new MuralQueryException("O limite deve estar entre 1 e 50.");
        }
        MuralCursor cursor = encodedCursor == null ? null : cursors.decode(encodedCursor, status);
        List<Mural> found = murals.findOwned(currentUserId.get(), status,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(), limit + 1);
        boolean hasNext = found.size() > limit;
        List<Mural> items = hasNext ? found.subList(0, limit) : found;
        String nextCursor = hasNext && !items.isEmpty()
                ? cursors.encode(status, items.getLast().createdAt(), items.getLast().id()) : null;
        return new MuralPage(items, nextCursor);
    }
}
