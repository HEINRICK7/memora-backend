package com.memora.application.mural;

import com.memora.domain.mural.MuralStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuralCursorCodecTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-24T12:00:00.123456Z");
    private static final UUID ID = UUID.fromString("c0f5f693-7160-4e49-9d76-cfaba4edce1c");
    private final MuralCursorCodec codec = new MuralCursorCodec();

    @Test
    void encodesCanonicalBase64UrlCursorAtPostgresMicrosecondPrecision() {
        String encoded = codec.encode(MuralStatus.DRAFT, CREATED_AT, ID);

        assertThat(encoded).doesNotContain("=").hasSizeLessThanOrEqualTo(MuralCursorCodec.MAX_LENGTH);
        assertThat(codec.decode(encoded, MuralStatus.DRAFT))
                .isEqualTo(new MuralCursor("DRAFT", CREATED_AT, ID));
    }

    @Test
    void rejectsMalformedNoncanonicalAndWrongFilterCursors() {
        String valid = codec.encode(MuralStatus.DRAFT, CREATED_AT, ID);

        assertThatThrownBy(() -> codec.decode("%%%", MuralStatus.DRAFT)).isInstanceOf(MuralQueryException.class);
        assertThatThrownBy(() -> codec.decode(valid + "=", MuralStatus.DRAFT))
                .isInstanceOf(MuralQueryException.class);
        assertThatThrownBy(() -> codec.decode(valid, MuralStatus.ARCHIVED))
                .isInstanceOf(MuralQueryException.class);
        assertThatThrownBy(() -> codec.decode("a".repeat(129), MuralStatus.DRAFT))
                .isInstanceOf(MuralQueryException.class);
    }
}
