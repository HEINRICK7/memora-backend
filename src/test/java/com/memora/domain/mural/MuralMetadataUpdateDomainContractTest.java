package com.memora.domain.mural;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain RED contract for MEM-004-R01..R09 and AC01..AC06. */
class MuralMetadataUpdateDomainContractTest {
    private static final UUID ID = UUID.fromString("c0f5f693-7160-4e49-9d76-cfaba4edce1c");
    private static final UUID OWNER = UUID.fromString("cf5d4e2b-a987-43d7-9ed0-512c072aa8e1");
    private static final Instant CREATED = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant UPDATED = CREATED.plusSeconds(10);
    private static final Instant PATCHED = CREATED.plusSeconds(30);

    @Test
    void AC01_updatesOnlyPresentTitleAndAdvancesOnePublicVersion() {
        Mural original = draft(2, "Título original", "Descrição preservada", UPDATED);

        Mural patched = update(original, "  Título novo  ", true, null, false, PATCHED);

        assertThat(patched.title()).isEqualTo("Título novo");
        assertThat(patched.description()).isEqualTo("Descrição preservada");
        assertThat(patched.status()).isEqualTo(MuralStatus.DRAFT);
        assertThat(patched.version()).isEqualTo(3);
        assertThat(patched.createdAt()).isEqualTo(CREATED);
        assertThat(patched.updatedAt()).isEqualTo(PATCHED);
        assertThat(original.title()).isEqualTo("Título original");
        assertThat(original.version()).isEqualTo(2);
    }

    @Test
    void AC02_and_AC03_preserveOmittedDescriptionAndClearExplicitNull() {
        Mural original = draft(4, "Título", "Descrição", UPDATED);

        Mural omitted = update(original, "Título", true, null, false, PATCHED);
        Mural cleared = update(original, null, false, null, true, PATCHED);

        assertThat(omitted.description()).isEqualTo("Descrição");
        assertThat(omitted.version()).isEqualTo(4);
        assertThat(cleared.description()).isNull();
        assertThat(cleared.version()).isEqualTo(5);
        assertThat(cleared.updatedAt()).isEqualTo(PATCHED);
    }

    @Test
    void AC05_normalizedNoOpKeepsVersionAndUpdatedAt() {
        Mural original = draft(7, "Férias em família", "Dias especiais", UPDATED);

        Mural patched = update(original, "  Férias em família  ", true,
                "\u2003Dias especiais\u2003", true, PATCHED);

        assertThat(patched).isEqualTo(original);
        assertThat(patched.version()).isEqualTo(7);
        assertThat(patched.updatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void AC06_invalidTitleAndDescriptionKeepStableFieldSpecificValidation() {
        Mural original = draft(1, "Título seguro", "Descrição segura", UPDATED);

        assertThatThrownBy(() -> update(original, " \u2003 ", true, null, false, PATCHED))
                .isInstanceOfSatisfying(MuralValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("title");
                    assertThat(exception.code()).isEqualTo("invalid_mural_title");
                });
        assertThatThrownBy(() -> update(original, null, true, null, false, PATCHED))
                .isInstanceOfSatisfying(MuralValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("title"));
        assertThatThrownBy(() -> update(original, "x".repeat(101), true, null, false, PATCHED))
                .isInstanceOfSatisfying(MuralValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("title"));
        assertThatThrownBy(() -> update(original, null, false, "x".repeat(501), true, PATCHED))
                .isInstanceOfSatisfying(MuralValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("description");
                    assertThat(exception.code()).isEqualTo("invalid_mural_description");
                });
        assertThat(original.title()).isEqualTo("Título seguro");
        assertThat(original.description()).isEqualTo("Descrição segura");
    }

    private static Mural draft(int version, String title, String description, Instant updatedAt) {
        return Mural.restore(ID, OWNER, MuralStatus.DRAFT, version, title, description, CREATED, updatedAt);
    }

    private static Mural update(Mural mural, String title, boolean titlePresent,
                                String description, boolean descriptionPresent, Instant now) {
        Method operation;
        try {
            operation = Mural.class.getMethod("updateMetadata", String.class, boolean.class,
                    String.class, boolean.class, Instant.class);
        } catch (NoSuchMethodException missingOperation) {
            throw new AssertionError("MEM-004 RED: Mural must expose the updateMetadata domain operation ",
                    missingOperation);
        }
        try {
            return (Mural) operation.invoke(mural, title, titlePresent, description, descriptionPresent, now);
        } catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("MEM-004 domain operation failed unexpectedly.", invocation.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("MEM-004 domain operation could not be invoked.", failure);
        }
    }
}
