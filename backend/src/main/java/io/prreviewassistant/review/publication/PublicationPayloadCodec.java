package io.prreviewassistant.review.publication;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class PublicationPayloadCodec {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final int maxPayloadChars;

    public PublicationPayloadCodec(int maxPayloadChars) { this.maxPayloadChars = maxPayloadChars; }

    public String encode(PublicationPayload payload) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", payload.version()); root.put("body", payload.body());
        ArrayNode comments = root.putArray("comments");
        for (PublicationComment comment : payload.comments()) {
            ObjectNode node = comments.addObject();
            node.put("path", comment.path()); node.put("line", comment.line());
            if (comment.startLine() != null) node.put("startLine", comment.startLine());
            node.put("body", comment.body());
        }
        String encoded = mapper.writeValueAsString(root);
        if (encoded.length() > maxPayloadChars) throw new IllegalArgumentException("publication payload exceeds configured limit");
        return encoded;
    }

    public PublicationPayload decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > maxPayloadChars) {
            throw new IllegalArgumentException("publication payload is invalid");
        }
        try {
            JsonNode root = mapper.readTree(encoded);
            if (root == null || !root.isObject() || root.path("version").intValue() != PublicationPayload.CURRENT_VERSION
                    || !root.path("body").isString() || !root.path("comments").isArray()) {
                throw new IllegalArgumentException("publication payload is invalid");
            }
            List<PublicationComment> comments = new ArrayList<>();
            for (JsonNode node : root.path("comments")) {
                if (!node.isObject() || !node.path("path").isString() || !node.path("line").isIntegralNumber()
                        || !node.path("body").isString()) throw new IllegalArgumentException("publication payload is invalid");
                Integer start = node.has("startLine") && node.path("startLine").isIntegralNumber()
                        ? node.path("startLine").intValue() : null;
                comments.add(new PublicationComment(node.path("path").stringValue(), node.path("line").intValue(),
                        start, node.path("body").stringValue()));
            }
            return new PublicationPayload(PublicationPayload.CURRENT_VERSION, root.path("body").stringValue(), comments);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("publication payload is invalid");
        }
    }
}
