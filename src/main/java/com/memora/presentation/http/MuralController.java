package com.memora.presentation.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.application.mural.CreateMural;
import com.memora.application.mural.CreateMuralCommand;
import com.memora.application.mural.GetOwnedMural;
import com.memora.application.mural.ListOwnedMurals;
import com.memora.application.mural.MuralPage;
import com.memora.application.mural.MuralQueryException;
import com.memora.application.mural.UpdateMuralMetadata;
import com.memora.application.mural.UpdateMuralMetadataCommand;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/murals")
public final class MuralController {
    private static final Set<String> MURAL_METADATA_FIELDS = Set.of("title", "description");
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("^\"([1-9][0-9]*)\"$");

    private final CreateMural createMural;
    private final ListOwnedMurals listOwnedMurals;
    private final GetOwnedMural getOwnedMural;
    private final UpdateMuralMetadata updateMuralMetadata;

    public MuralController(CreateMural createMural, ListOwnedMurals listOwnedMurals, GetOwnedMural getOwnedMural,
                           UpdateMuralMetadata updateMuralMetadata) {
        this.createMural = createMural;
        this.listOwnedMurals = listOwnedMurals;
        this.getOwnedMural = getOwnedMural;
        this.updateMuralMetadata = updateMuralMetadata;
    }

    @PostMapping
    public ResponseEntity<MuralSummary> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
            @RequestBody JsonNode request
    ) {
        UUID idempotencyKey = parseIdempotencyKey(rawIdempotencyKey);
        CreateMuralCommand command = parseCreateRequest(request, idempotencyKey);
        var result = createMural.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(MuralSummary.from(result.mural()));
    }

    @GetMapping
    public ResponseEntity<MuralListResponse> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor
    ) {
        MuralStatus filter = parseStatus(status);
        MuralPage page = listOwnedMurals.execute(limit, filter, cursor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new MuralListResponse(page.items().stream().map(MuralSummary::from).toList(), page.nextCursor()));
    }

    @GetMapping("/{muralId}")
    public ResponseEntity<MuralSummary> get(@PathVariable String muralId) {
        Mural mural = getOwnedMural.execute(parseMuralId(muralId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, toEtag(mural.version()))
                .body(MuralSummary.from(mural));
    }

    @PatchMapping("/{muralId}")
    public ResponseEntity<MuralSummary> updateMetadata(
            @PathVariable String muralId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String rawIfMatch,
            @RequestBody JsonNode request
    ) {
        UUID parsedMuralId = parseMuralId(muralId);
        MetadataPatch patch = parseMetadataPatch(request);
        int expectedVersion = parseIfMatchVersion(rawIfMatch);
        Mural updated = updateMuralMetadata.execute(new UpdateMuralMetadataCommand(parsedMuralId, expectedVersion,
                patch.title(), patch.titlePresent(), patch.description(), patch.descriptionPresent()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, toEtag(updated.version()))
                .body(MuralSummary.from(updated));
    }

    private static int parseIfMatchVersion(String value) {
        if (value == null) {
            throw new MissingIfMatchException();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(value);
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

    private static UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new MuralQueryException("A chave de idempotência UUID é obrigatória.");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new MuralQueryException("A chave de idempotência precisa ser um UUID.");
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new MuralQueryException("A chave de idempotência precisa ser um UUID.");
        }
    }

    private static UUID parseMuralId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new MuralQueryException("O identificador do mural precisa ser um UUID.");
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new MuralQueryException("O identificador do mural precisa ser um UUID.");
        }
    }

    private static MuralStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return MuralStatus.valueOf(status);
        } catch (IllegalArgumentException malformed) {
            throw new MuralQueryException("O filtro de status é inválido.");
        }
    }

    private static CreateMuralCommand parseCreateRequest(JsonNode body, UUID idempotencyKey) {
        if (body == null || !body.isObject()) {
            throw new MuralQueryException("O corpo enviado precisa ser um objeto JSON.");
        }
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!MURAL_METADATA_FIELDS.contains(names.next())) {
                throw new MuralQueryException("O corpo contém um campo não permitido.");
            }
        }
        JsonNode title = body.get("title");
        JsonNode description = body.get("description");
        if (description != null && !description.isNull() && !description.isTextual()) {
            throw new MuralQueryException("A descrição precisa ser texto ou null.");
        }
        return new CreateMuralCommand(title != null && title.isTextual() ? title.textValue() : null,
                description == null || description.isNull() ? null : description.textValue(), idempotencyKey);
    }

    private static MetadataPatch parseMetadataPatch(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new MuralQueryException("O corpo enviado precisa ser um objeto JSON com ao menos um campo.");
        }
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            if (!MURAL_METADATA_FIELDS.contains(names.next())) {
                throw new MuralQueryException("O corpo contém um campo não permitido.");
            }
        }
        JsonNode title = body.get("title");
        JsonNode description = body.get("description");
        if (title != null && !title.isNull() && !title.isTextual()) {
            throw new MuralQueryException("O título precisa ser texto ou null.");
        }
        if (description != null && !description.isNull() && !description.isTextual()) {
            throw new MuralQueryException("A descrição precisa ser texto ou null.");
        }
        return new MetadataPatch(title == null || title.isNull() ? null : title.textValue(), title != null,
                description == null || description.isNull() ? null : description.textValue(), description != null);
    }

    private record MetadataPatch(String title, boolean titlePresent, String description,
                                 boolean descriptionPresent) { }

    public record MuralSummary(UUID id, MuralStatus status, int version, String title, String description,
                               java.time.Instant createdAt, java.time.Instant updatedAt) {
        static MuralSummary from(Mural mural) {
            return new MuralSummary(mural.id(), mural.status(), mural.version(), mural.title(), mural.description(),
                    mural.createdAt(), mural.updatedAt());
        }
    }

    public record MuralListResponse(java.util.List<MuralSummary> items, String nextCursor) { }
}
