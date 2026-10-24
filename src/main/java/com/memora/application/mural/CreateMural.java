package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class CreateMural {
    private final MuralRepository murals;
    private final CurrentUserId currentUserId;
    private final Clock clock;
    private final IdGenerator ids;

    public CreateMural(MuralRepository murals, CurrentUserId currentUserId, Clock clock, IdGenerator ids) {
        this.murals = Objects.requireNonNull(murals);
        this.currentUserId = Objects.requireNonNull(currentUserId);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public CreateMuralResult execute(CreateMuralCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null) {
            throw new MuralQueryException("A chave de idempotência é obrigatória.");
        }
        String title = Mural.normalizeTitle(command.title());
        String description = Mural.normalizeDescription(command.description());
        String requestHash = hashCanonicalRequest(title, description);
        UUID ownerId = currentUserId.get();

        var previous = murals.findByOwnerAndIdempotencyKey(ownerId, command.idempotencyKey());
        if (previous.isPresent()) {
            return replayOrConflict(previous.get(), requestHash);
        }

        Mural created = Mural.create(ids.next(), ownerId, title, description, clock.instant());
        try {
            return new CreateMuralResult(murals.save(created, command.idempotencyKey(), requestHash), false);
        } catch (DuplicateMuralIdempotencyKeyException concurrentRequest) {
            var winner = murals.findByOwnerAndIdempotencyKey(ownerId, command.idempotencyKey());
            if (winner.isEmpty()) {
                throw concurrentRequest;
            }
            return replayOrConflict(winner.get(), requestHash);
        }
    }

    private CreateMuralResult replayOrConflict(IdempotentMural existing, String requestHash) {
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new MuralIdempotencyConflictException();
        }
        return new CreateMuralResult(existing.mural(), true);
    }

    private static String hashCanonicalRequest(String title, String description) {
        String canonicalJson = "{\"title\":" + quoteJson(title) + ",\"description\":"
                + (description == null ? "null" : quoteJson(description)) + "}";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 não está disponível.", impossible);
        }
    }

    private static String quoteJson(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    @FunctionalInterface
    public interface IdGenerator {
        UUID next();
    }
}
