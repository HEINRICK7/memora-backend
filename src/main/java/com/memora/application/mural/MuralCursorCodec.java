package com.memora.application.mural;

import com.memora.domain.mural.MuralStatus;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class MuralCursorCodec {
    public static final int MAX_LENGTH = 128;

    public String encode(MuralStatus filter, Instant createdAt, UUID id) {
        String status = filter == null ? "ALL" : filter.name();
        String payload = status + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public MuralCursor decode(String encoded, MuralStatus requestedFilter) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_LENGTH
                || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new MuralQueryException("O cursor da lista é inválido.");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new MuralQueryException("O cursor da lista é inválido.");
            }
            String payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 3) {
                throw new MuralQueryException("O cursor da lista é inválido.");
            }
            String expectedFilter = requestedFilter == null ? "ALL" : requestedFilter.name();
            if (!fields[0].equals(expectedFilter)) {
                throw new MuralQueryException("O cursor não corresponde ao filtro informado.");
            }
            Instant createdAt = Instant.parse(fields[1]);
            UUID id = UUID.fromString(fields[2]);
            if (!createdAt.toString().equals(fields[1]) || !id.toString().equals(fields[2])) {
                throw new MuralQueryException("O cursor da lista é inválido.");
            }
            return new MuralCursor(fields[0], createdAt, id);
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw new MuralQueryException("O cursor da lista é inválido.");
        }
    }
}
