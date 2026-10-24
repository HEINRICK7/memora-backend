package com.memora.presentation.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.application.mural.AttachPhotoToMural;
import com.memora.application.mural.AttachPhotoToMuralCommand;
import com.memora.application.mural.ListMuralPhotos;
import com.memora.application.mural.MuralPhotoLink;
import com.memora.application.mural.MuralPhotoLinkCollection;
import com.memora.application.mural.MuralPhotoSnapshot;
import com.memora.application.mural.RemovePhotoFromMural;
import com.memora.application.mural.RemovePhotoFromMuralCommand;
import com.memora.application.mural.ReorderMuralPhotos;
import com.memora.application.mural.ReorderMuralPhotosCommand;
import com.memora.domain.mural.MuralPhoto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/murals/{muralId}/photos")
public final class MuralPhotoController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("^\"([1-9][0-9]*)\"$");
    private static final Set<String> ATTACH_FIELDS = Set.of("photoId");
    private static final Set<String> ORDER_FIELDS = Set.of("photoIds");

    private final ListMuralPhotos list;
    private final AttachPhotoToMural attach;
    private final ReorderMuralPhotos reorder;
    private final RemovePhotoFromMural remove;

    public MuralPhotoController(ListMuralPhotos list, AttachPhotoToMural attach,
                                ReorderMuralPhotos reorder, RemovePhotoFromMural remove) {
        this.list = list;
        this.attach = attach;
        this.reorder = reorder;
        this.remove = remove;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String muralId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(list.execute(parseUuid(muralId)));
    }

    @PostMapping
    public ResponseEntity<MuralPhotoLink> attach(
            @PathVariable String muralId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String rawIfMatch,
            @RequestBody JsonNode body
    ) {
        UUID parsedMuralId = parseUuid(muralId);
        UUID photoId = parseAttachBody(body);
        var result = attach.execute(new AttachPhotoToMuralCommand(
                parsedMuralId, photoId, parseIfMatchVersion(rawIfMatch)));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, toEtag(result.snapshot().mural().version()))
                .body(toLink(result.link()));
    }

    @PutMapping("/order")
    public ResponseEntity<MuralPhotoLinkCollection> reorder(
            @PathVariable String muralId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String rawIfMatch,
            @RequestBody JsonNode body
    ) {
        UUID parsedMuralId = parseUuid(muralId);
        List<UUID> photoIds = parseOrderBody(body);
        MuralPhotoSnapshot snapshot = reorder.execute(new ReorderMuralPhotosCommand(
                parsedMuralId, photoIds, parseIfMatchVersion(rawIfMatch)));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, toEtag(snapshot.mural().version()))
                .body(new MuralPhotoLinkCollection(snapshot.photos().stream().map(MuralPhotoController::toLink).toList()));
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> remove(
            @PathVariable String muralId,
            @PathVariable String photoId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String rawIfMatch
    ) {
        MuralPhotoSnapshot snapshot = remove.execute(new RemovePhotoFromMuralCommand(
                parseUuid(muralId), parseUuid(photoId), parseIfMatchVersion(rawIfMatch)));
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, toEtag(snapshot.mural().version()))
                .build();
    }

    private static UUID parseAttachBody(JsonNode body) {
        validateObjectFields(body, ATTACH_FIELDS);
        JsonNode photoId = body.get("photoId");
        if (photoId == null || !photoId.isTextual()) {
            throw new MuralPhotoRequestException();
        }
        return parseUuid(photoId.textValue());
    }

    private static List<UUID> parseOrderBody(JsonNode body) {
        validateObjectFields(body, ORDER_FIELDS);
        JsonNode photoIds = body.get("photoIds");
        if (photoIds == null || !photoIds.isArray()) {
            throw new MuralPhotoRequestException();
        }
        List<UUID> result = new ArrayList<>(photoIds.size());
        for (JsonNode photoId : photoIds) {
            if (!photoId.isTextual()) {
                throw new MuralPhotoRequestException();
            }
            result.add(parseUuid(photoId.textValue()));
        }
        return List.copyOf(result);
    }

    private static void validateObjectFields(JsonNode body, Set<String> allowedFields) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new MuralPhotoRequestException();
        }
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) {
                throw new MuralPhotoRequestException();
            }
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) {
                throw new MuralPhotoRequestException();
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new MuralPhotoRequestException();
        }
    }

    private static int parseIfMatchVersion(String raw) {
        if (raw == null) {
            throw new MissingIfMatchException();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(raw);
        if (!matcher.matches()) {
            throw new InvalidIfMatchException();
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException malformed) {
            throw new InvalidIfMatchException();
        }
    }

    private static String toEtag(int version) {
        return "\"" + version + "\"";
    }

    private static MuralPhotoLink toLink(MuralPhoto photo) {
        return new MuralPhotoLink(photo.photoId(), photo.position());
    }
}
