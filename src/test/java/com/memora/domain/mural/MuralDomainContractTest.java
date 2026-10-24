package com.memora.domain.mural;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RED contract for MEM-002-R03/R04 and MEM-002-AC01/AC04. */
class MuralDomainContractTest {
    private static final String MURAL_CLASS = "com.memora.domain.mural.Mural";
    private static final UUID MURAL_ID = UUID.fromString("c0f5f693-7160-4e49-9d76-cfaba4edce1c");
    private static final UUID OWNER_ID = UUID.fromString("cf5d4e2b-a987-43d7-9ed0-512c072aa8e1");
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void createsNormalizedDraftWithPublicVersionOneAndEqualUtcTimestamps() throws Exception {
        Object mural = create("\u2003Memora ✨\u2003", " \u2003 descrição \u2003 ");

        assertThat(read(mural, "status").toString()).isEqualTo("DRAFT");
        assertThat(read(mural, "version")).isEqualTo(1);
        assertThat(read(mural, "title")).isEqualTo("Memora ✨");
        assertThat(read(mural, "description")).isEqualTo("descrição");
        assertThat(read(mural, "createdAt")).isEqualTo(NOW);
        assertThat(read(mural, "updatedAt")).isEqualTo(NOW);
        assertThat(read(mural, "ownerId")).isEqualTo(OWNER_ID);
    }

    @Test
    void trimsUnicodeWhitespaceAndCountsUnicodeCodePointsForTitleAndDescription() throws Exception {
        String titleAtLimit = "a".repeat(99) + "😀";
        String descriptionAtLimit = "b".repeat(499) + "😀";
        Object mural = create("\u2003" + titleAtLimit + "\u2003", descriptionAtLimit);

        assertThat(((String) read(mural, "title")).codePointCount(0, titleAtLimit.length())).isEqualTo(100);
        assertThat(((String) read(mural, "description")).codePointCount(0, descriptionAtLimit.length()))
                .isEqualTo(500);
    }

    @Test
    void convertsAbsentOrUnicodeWhitespaceOnlyDescriptionToNull() throws Exception {
        assertThat(read(create("Mural", null), "description")).isNull();
        assertThat(read(create("Mural", " \u2003\t "), "description")).isNull();
    }

    @Test
    void rejectsEmptyOrOverLimitTitleAndOverLimitDescription() {
        assertThatThrownBy(() -> create("\u2003\t\u2003", null))
                .as("MEM-002-R03: blank normalized title is invalid")
                .isNotNull();
        assertThatThrownBy(() -> create("a".repeat(101), null))
                .as("MEM-002-R03: title is limited to 100 Unicode code points")
                .isNotNull();
        assertThatThrownBy(() -> create("Mural", "x".repeat(501)))
                .as("MEM-002-R03: description is limited to 500 Unicode code points")
                .isNotNull();
    }

    private static Object create(String title, String description) throws Exception {
        Class<?> muralType;
        try {
            muralType = Class.forName(MURAL_CLASS);
        } catch (ClassNotFoundException missingDomain) {
            throw new AssertionError("MEM-002 RED: domain aggregate " + MURAL_CLASS + " is not implemented yet.",
                    missingDomain);
        }
        Method factory = muralType.getMethod("create", UUID.class, UUID.class,
                String.class, String.class, Instant.class);
        try {
            return factory.invoke(null, MURAL_ID, OWNER_ID, title, description, NOW);
        } catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw invocation;
        }
    }

    private static Object read(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}
