package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class ReviewAnalysisCheckpointCodec {
    static final int MAX_PAYLOAD_BYTES = 262_144;
    private static final Set<String> FINDING_FIELDS = Set.of(
            "id", "category", "severity", "confidence", "path", "startLine", "endLine",
            "title", "evidence", "impact", "explanation", "suggestedFix");

    private final ObjectMapper mapper;

    ReviewAnalysisCheckpointCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String encode(List<ReviewFinding> findings) {
        if (findings == null || findings.size() > ReviewCandidateAnalysis.MAX_FINDINGS) {
            throw invalid();
        }
        ArrayNode root = mapper.createArrayNode();
        for (ReviewFinding finding : findings) {
            ObjectNode node = root.addObject();
            node.put("id", finding.id());
            node.put("category", finding.category().name());
            node.put("severity", finding.severity().name());
            node.put("confidence", finding.confidence());
            node.put("path", finding.path());
            nullableInteger(node, "startLine", finding.startLine());
            nullableInteger(node, "endLine", finding.endLine());
            node.put("title", finding.title());
            node.put("evidence", finding.evidence());
            node.put("impact", finding.impact());
            node.put("explanation", finding.explanation());
            if (finding.suggestedFix() == null) node.putNull("suggestedFix");
            else node.put("suggestedFix", finding.suggestedFix());
        }
        try {
            String payload = mapper.writeValueAsString(root);
            requireBounded(payload);
            return payload;
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    List<ReviewFinding> decode(String payload, int expectedCount) {
        requireBounded(payload);
        if (expectedCount < 0 || expectedCount > ReviewCandidateAnalysis.MAX_FINDINGS) throw invalid();
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isArray() || root.size() != expectedCount) throw invalid();
            List<ReviewFinding> findings = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                requireExactFields(node);
                findings.add(new ReviewFinding(
                        text(node, "id"),
                        ReviewFindingCategory.valueOf(text(node, "category")),
                        ReviewSeverity.valueOf(text(node, "severity")),
                        integer(node, "confidence"),
                        text(node, "path"),
                        nullableInteger(node, "startLine"),
                        nullableInteger(node, "endLine"),
                        text(node, "title"),
                        text(node, "evidence"),
                        text(node, "impact"),
                        text(node, "explanation"),
                        nullableText(node, "suggestedFix")));
            }
            return List.copyOf(findings);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private static void requireExactFields(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(FINDING_FIELDS)) throw invalid();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) throw invalid();
        return value.isNull() ? null : text(node, field);
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) throw invalid();
        return value.isNull() ? null : integer(node, field);
    }

    private static void nullableInteger(ObjectNode node, String field, Integer value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void requireBounded(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw invalid();
        }
    }

    private static ReviewAnalysisCheckpointException invalid() {
        return new ReviewAnalysisCheckpointException(ReviewAnalysisCheckpointError.INCONSISTENT_STATE);
    }
}
