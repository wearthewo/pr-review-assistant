package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.prreviewassistant.ai.StructuredOutputSchema;

import java.util.Comparator;
import java.util.Set;
import java.util.Arrays;

final class ReviewFindingSchema {
    private ReviewFindingSchema() { }

    static StructuredOutputSchema create(ObjectMapper mapper, int maxFindings,
            Set<ReviewFindingCategory> enabledCategories) {
        ObjectNode root = object(mapper);
        ObjectNode properties = root.putObject("properties");
        ObjectNode findings = properties.putObject("findings");
        findings.put("type", "array");
        findings.put("maxItems", maxFindings);
        ObjectNode item = object(mapper);
        findings.set("items", item);
        ObjectNode fields = item.putObject("properties");
        enumString(fields.putObject("category"), enabledCategories.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal)).map(Enum::name).toArray(String[]::new));
        enumString(fields.putObject("severity"), Arrays.stream(ReviewSeverity.values()).map(Enum::name).toArray(String[]::new));
        integer(fields.putObject("confidence"), 0, 100);
        string(fields.putObject("path"), 1, 4096);
        nullableInteger(fields.putObject("startLine"), 1);
        nullableInteger(fields.putObject("endLine"), 1);
        string(fields.putObject("title"), 1, ReviewFinding.MAX_TITLE_LENGTH);
        string(fields.putObject("evidence"), 1, ReviewFinding.MAX_EVIDENCE_LENGTH);
        string(fields.putObject("impact"), 1, ReviewFinding.MAX_IMPACT_LENGTH);
        string(fields.putObject("explanation"), 1, ReviewFinding.MAX_EXPLANATION_LENGTH);
        nullableString(fields.putObject("suggestedFix"), ReviewFinding.MAX_SUGGESTED_FIX_LENGTH);
        addRequired(item, "category", "severity", "confidence", "path", "startLine", "endLine",
                "title", "evidence", "impact", "explanation", "suggestedFix");
        addRequired(root, "findings");
        try {
            return new StructuredOutputSchema("review_findings", mapper.writeValueAsString(root), true);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Review schema construction failed");
        }
    }

    private static ObjectNode object(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", false);
        return node;
    }

    private static void string(ObjectNode node, int minimum, int maximum) {
        node.put("type", "string");
        node.put("minLength", minimum);
        node.put("maxLength", maximum);
    }

    private static void nullableString(ObjectNode node, int maximum) {
        ArrayNode types = node.putArray("type");
        types.add("string").add("null");
        node.put("maxLength", maximum);
    }

    private static void integer(ObjectNode node, int minimum, int maximum) {
        node.put("type", "integer");
        node.put("minimum", minimum);
        node.put("maximum", maximum);
    }

    private static void nullableInteger(ObjectNode node, int minimum) {
        ArrayNode types = node.putArray("type");
        types.add("integer").add("null");
        node.put("minimum", minimum);
    }

    private static void enumString(ObjectNode node, String... values) {
        node.put("type", "string");
        ArrayNode allowed = node.putArray("enum");
        for (String value : values) allowed.add(value);
    }

    private static void addRequired(ObjectNode node, String... names) {
        ArrayNode required = node.putArray("required");
        for (String name : names) required.add(name);
    }
}
