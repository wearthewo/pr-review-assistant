package io.prreviewassistant.review.config;

import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RepositoryConfigParser {
    private static final Set<String> ROOT_FIELDS = Set.of("version", "review", "ignore", "categories");
    private static final Set<String> REVIEW_FIELDS = Set.of("mode");
    private static final Map<String, ReviewFindingCategory> CATEGORY_FIELDS = categoryFields();

    private final int maxBytes;

    public RepositoryConfigParser(int maxBytes) {
        if (maxBytes < 1 || maxBytes > RepositoryConfigProperties.HARD_MAX_BYTES) {
            throw new IllegalArgumentException("repository config parser limit is invalid");
        }
        this.maxBytes = maxBytes;
    }

    private Yaml safeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(RepositoryConfigProperties.HARD_MAX_BYTES);
        return new Yaml(new SafeConstructor(options));
    }

    public RepositoryConfigLoadResult parse(byte[] content) {
        if (content == null || content.length > maxBytes) {
            return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.OVERSIZED);
        }
        String text = decode(content);
        if (text == null) {
            return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.UNSUPPORTED_ENCODING);
        }
        try {
            rejectYamlReferences(text);
            Object loaded = safeYaml().load(text);
            Map<?, ?> root = exactMap(loaded, ROOT_FIELDS);
            Object rawVersion = required(root, "version");
            if (!(rawVersion instanceof Integer version) || version != EffectiveRepositoryReviewConfig.VERSION) {
                return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.UNSUPPORTED_VERSION);
            }

            ReviewMode mode = parseMode(root.get("review"));
            List<String> patterns = parsePatterns(root.get("ignore"));
            Set<ReviewFindingCategory> categories = parseCategories(root.get("categories"));
            return new RepositoryConfigLoadResult(RepositoryConfigStatus.VALID,
                    new EffectiveRepositoryReviewConfig(mode, patterns, categories));
        } catch (YAMLException | IllegalArgumentException | ClassCastException exception) {
            return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.INVALID);
        }
    }

    private void rejectYamlReferences(String text) {
        for (Event event : safeYaml().parse(new StringReader(text))) {
            if (event instanceof AliasEvent
                    || event instanceof NodeEvent nodeEvent && nodeEvent.getAnchor() != null) {
                throw new IllegalArgumentException("YAML references are not supported");
            }
        }
    }

    private String decode(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private ReviewMode parseMode(Object value) {
        if (value == null) {
            return ReviewMode.BALANCED;
        }
        Map<?, ?> review = exactMap(value, REVIEW_FIELDS);
        Object rawMode = required(review, "mode");
        if (!(rawMode instanceof String mode)) {
            throw new IllegalArgumentException("mode type is invalid");
        }
        return switch (mode) {
            case "fast" -> ReviewMode.FAST;
            case "balanced" -> ReviewMode.BALANCED;
            case "deep" -> ReviewMode.DEEP;
            default -> throw new IllegalArgumentException("mode is invalid");
        };
    }

    private List<String> parsePatterns(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("ignore must be a list");
        }
        List<String> patterns = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String pattern)) {
                throw new IllegalArgumentException("ignore pattern type is invalid");
            }
            patterns.add(pattern);
        }
        RepositoryPathMatcher.validatePatterns(patterns);
        return List.copyOf(patterns);
    }

    private Set<ReviewFindingCategory> parseCategories(Object value) {
        EnumSet<ReviewFindingCategory> enabled = EnumSet.allOf(ReviewFindingCategory.class);
        if (value == null) {
            return enabled;
        }
        Map<?, ?> categories = exactMap(value, CATEGORY_FIELDS.keySet());
        for (Map.Entry<?, ?> entry : categories.entrySet()) {
            if (!(entry.getValue() instanceof Boolean selected)) {
                throw new IllegalArgumentException("category value type is invalid");
            }
            ReviewFindingCategory category = CATEGORY_FIELDS.get(entry.getKey());
            if (selected) {
                enabled.add(category);
            } else {
                enabled.remove(category);
            }
        }
        return enabled;
    }

    private Map<?, ?> exactMap(Object value, Set<String> allowedFields) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("mapping is required");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String field) || !allowedFields.contains(field)) {
                throw new IllegalArgumentException("unknown field");
            }
        }
        return map;
    }

    private Object required(Map<?, ?> map, String field) {
        if (!map.containsKey(field) || map.get(field) == null) {
            throw new IllegalArgumentException("required field is absent");
        }
        return map.get(field);
    }

    private static Map<String, ReviewFindingCategory> categoryFields() {
        Map<String, ReviewFindingCategory> fields = new LinkedHashMap<>();
        for (ReviewFindingCategory category : ReviewFindingCategory.values()) {
            fields.put(category.name().toLowerCase(Locale.ROOT), category);
        }
        return Map.copyOf(fields);
    }
}
