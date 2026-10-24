package com.memora.domain.mural;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain acceptance for MEM-006-R02..R05 and AC06, AC08..AC11. */
class MuralPhotoCollectionTest {
    private final UUID muralId = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @Test
    void appendIsZeroBasedAndDoesNotDuplicateAnExistingPhoto() {
        MuralPhotoCollection empty = MuralPhotoCollection.empty(muralId);

        MuralPhotoCollection withFirst = empty.append(first);
        MuralPhotoCollection withSecond = withFirst.append(second);
        MuralPhotoCollection duplicate = withSecond.append(first);

        assertThat(withFirst.photos()).containsExactly(new MuralPhoto(muralId, first, 0));
        assertThat(withSecond.photos()).containsExactly(
                new MuralPhoto(muralId, first, 0), new MuralPhoto(muralId, second, 1));
        assertThat(duplicate).isSameAs(withSecond);
    }

    @Test
    void reorderRequiresACompleteUniquePermutationAndCompactsPositions() {
        MuralPhotoCollection collection = MuralPhotoCollection.empty(muralId)
                .append(first).append(second).append(third);

        MuralPhotoCollection reordered = collection.reorder(List.of(third, first, second));

        assertThat(reordered.photos()).containsExactly(
                new MuralPhoto(muralId, third, 0),
                new MuralPhoto(muralId, first, 1),
                new MuralPhoto(muralId, second, 2));
        assertThatThrownBy(() -> collection.reorder(List.of(first, second)))
                .isInstanceOf(InvalidMuralPhotoOrderException.class);
        assertThatThrownBy(() -> collection.reorder(List.of(first, first, third)))
                .isInstanceOf(InvalidMuralPhotoOrderException.class);
        assertThatThrownBy(() -> collection.reorder(List.of(first, second, UUID.randomUUID())))
                .isInstanceOf(InvalidMuralPhotoOrderException.class);
    }

    @Test
    void removeOnlyRemovesTheRequestedLinkAndRenumbersTheTail() {
        MuralPhotoCollection collection = MuralPhotoCollection.empty(muralId)
                .append(first).append(second).append(third);

        MuralPhotoCollection remaining = collection.remove(second);

        assertThat(remaining.photos()).containsExactly(
                new MuralPhoto(muralId, first, 0), new MuralPhoto(muralId, third, 1));
        assertThatThrownBy(() -> remaining.remove(second))
                .isInstanceOf(MuralPhotoNotFoundException.class);
    }

    @Test
    void restoreRejectsNonContiguousOrDuplicatePositions() {
        assertThatThrownBy(() -> MuralPhotoCollection.restore(muralId,
                List.of(new MuralPhoto(muralId, first, 0), new MuralPhoto(muralId, second, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MuralPhotoCollection.restore(muralId,
                List.of(new MuralPhoto(muralId, first, 0), new MuralPhoto(muralId, first, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
