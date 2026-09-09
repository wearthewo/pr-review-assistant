package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.AiGenerationProfile;
import io.prreviewassistant.ai.AiProvider;
import io.prreviewassistant.ai.StructuredAiRequest;
import io.prreviewassistant.ai.StructuredAiResponse;
import io.prreviewassistant.review.context.ContextFile;
import io.prreviewassistant.review.context.ContextSelectionReason;
import io.prreviewassistant.review.context.RepositoryRevisionSide;
import io.prreviewassistant.review.context.ReviewContext;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.ChangedFileStatus;
import io.prreviewassistant.review.retrieval.PatchAvailability;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;

public final class AiReviewEngine implements ReviewEngine {
    private static final int MAX_LOCATION_SPAN = 200;
    private static final Set<String> ROOT_FIELDS = Set.of("findings");
    private static final Set<String> FINDING_FIELDS = Set.of(
            "category", "severity", "confidence", "path", "startLine", "endLine",
            "title", "evidence", "impact", "explanation", "suggestedFix");

    private final AiProvider provider;
    private final ObjectMapper objectMapper;
    private final ReviewContextSerializer serializer;
    private final ReviewAnalysisProperties properties;
    private final AiGenerationProfile generationProfile;
    private final UnifiedDiffLineMapper lineMapper = new UnifiedDiffLineMapper();

    public AiReviewEngine(AiProvider provider, ObjectMapper objectMapper,
            ReviewContextSerializer serializer, ReviewAnalysisProperties properties,
            AiGenerationProfile generationProfile) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.serializer = serializer;
        this.properties = properties;
        this.generationProfile = generationProfile;
    }

    @Override
    public ReviewAnalysis analyze(ReviewContext context) {
        return analyze(context, EnumSet.allOf(ReviewFindingCategory.class));
    }

    @Override
    public ReviewAnalysis analyze(ReviewContext context, Set<ReviewFindingCategory> enabledCategories) {
        if (enabledCategories == null || enabledCategories.isEmpty()) {
            throw new IllegalArgumentException("at least one enabled category is required for AI analysis");
        }
        Set<ReviewFindingCategory> allowedCategories = Set.copyOf(enabledCategories);
        String input = serializer.serialize(context);
        var schema = ReviewFindingSchema.create(objectMapper, properties.maxFindings(), allowedCategories);
        StructuredAiRequest request = new StructuredAiRequest(
                ReviewInstructions.forCategories(allowedCategories) + "\nMinimum candidate confidence: "
                        + properties.minimumCandidateConfidence() + ". Maximum findings: "
                        + properties.maxFindings() + ".",
                input, schema, generationProfile);
        StructuredAiResponse response = provider.generateStructured(request);
        List<ReviewFinding> findings = parseAndValidate(response.structuredOutput(), context, allowedCategories);
        var execution = response.executionMetadata();
        ReviewAnalysisMetadata metadata = new ReviewAnalysisMetadata(
                execution.provider(), execution.model(), generationProfile.modelTier(), response.usage(),
                execution.duration(), execution.maximumAttempts());
        return new ReviewAnalysis(context.target(), findings, metadata);
    }

    private List<ReviewFinding> parseAndValidate(String output, ReviewContext context,
            Set<ReviewFindingCategory> enabledCategories) {
        try {
            JsonNode root = objectMapper.readTree(output);
            requireExactObject(root, ROOT_FIELDS);
            JsonNode findingsNode = root.get("findings");
            if (!findingsNode.isArray() || findingsNode.size() > properties.maxFindings()) {
                throw new ReviewAnalysisException();
            }
            Map<String, ChangedFile> changedFiles = new HashMap<>();
            Map<String, Set<Integer>> validHeadLines = new HashMap<>();
            for (ChangedFile file : context.pullRequest().changedFiles()) {
                changedFiles.put(file.path(), file);
                Set<Integer> lines = new HashSet<>();
                for (UnifiedDiffLineMapper.DiffLine line : lineMapper.map(file.patch())) {
                    if (line.newLine() != null) lines.add(line.newLine());
                }
                validHeadLines.put(file.path(), lines);
            }
            addPatchUnavailableContextLines(context, changedFiles, validHeadLines);

            List<ReviewFinding> accepted = new ArrayList<>();
            Set<Candidate> seen = new HashSet<>();
            for (JsonNode node : findingsNode) {
                Candidate candidate = parseCandidate(node);
                if (!enabledCategories.contains(candidate.category())) {
                    continue;
                }
                if (!seen.add(candidate) || candidate.confidence() < properties.minimumCandidateConfidence()) {
                    continue;
                }
                ChangedFile changedFile = changedFiles.get(candidate.path());
                if (changedFile == null || !validLocation(candidate, changedFile, validHeadLines.get(candidate.path()))) {
                    continue;
                }
                accepted.add(candidate.toFinding(stableId(candidate)));
            }
            return List.copyOf(accepted);
        } catch (ReviewAnalysisException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new ReviewAnalysisException();
        }
    }

    private void addPatchUnavailableContextLines(ReviewContext context, Map<String, ChangedFile> changedFiles,
            Map<String, Set<Integer>> validHeadLines) {
        for (ContextFile file : context.files()) {
            ChangedFile changed = changedFiles.get(file.path());
            if (changed == null || changed.status() == ChangedFileStatus.REMOVED
                    || changed.patchAvailability() == PatchAvailability.AVAILABLE
                    || file.revisionSide() != RepositoryRevisionSide.HEAD
                    || file.reason() != ContextSelectionReason.CHANGED_FILE_CONTEXT) {
                continue;
            }
            Set<Integer> lines = validHeadLines.get(file.path());
            for (int line = file.startLine(); line <= file.endLine(); line++) lines.add(line);
        }
    }

    private Candidate parseCandidate(JsonNode node) {
        requireExactObject(node, FINDING_FIELDS);
        ReviewFindingCategory category = ReviewFindingCategory.valueOf(requiredText(node, "category"));
        ReviewSeverity severity = ReviewSeverity.valueOf(requiredText(node, "severity"));
        int confidence = requiredInteger(node, "confidence");
        if (confidence < 0 || confidence > 100) throw new ReviewAnalysisException();
        String path = boundedText(node, "path", 4096);
        Integer startLine = nullablePositiveInteger(node, "startLine");
        Integer endLine = nullablePositiveInteger(node, "endLine");
        String title = boundedText(node, "title", ReviewFinding.MAX_TITLE_LENGTH);
        String evidence = boundedText(node, "evidence", ReviewFinding.MAX_EVIDENCE_LENGTH);
        String impact = boundedText(node, "impact", ReviewFinding.MAX_IMPACT_LENGTH);
        String explanation = boundedText(node, "explanation", ReviewFinding.MAX_EXPLANATION_LENGTH);
        String suggestedFix = nullableBoundedText(node, "suggestedFix", ReviewFinding.MAX_SUGGESTED_FIX_LENGTH);
        return new Candidate(category, severity, confidence, path, startLine, endLine,
                title, evidence, impact, explanation, suggestedFix);
    }

    private boolean validLocation(Candidate candidate, ChangedFile file, Set<Integer> validLines) {
        if ((candidate.startLine() == null) != (candidate.endLine() == null)) return false;
        if (candidate.startLine() == null) return true;
        if (file.status() == ChangedFileStatus.REMOVED || candidate.endLine() < candidate.startLine()
                || (long) candidate.endLine() - candidate.startLine() + 1 > MAX_LOCATION_SPAN) return false;
        for (int line = candidate.startLine(); line <= candidate.endLine(); line++) {
            if (!validLines.contains(line)) return false;
        }
        return true;
    }

    private static void requireExactObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw new ReviewAnalysisException();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new ReviewAnalysisException();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new ReviewAnalysisException();
        return value.textValue();
    }

    private static String boundedText(JsonNode node, String field, int maximum) {
        String value = requiredText(node, field);
        if (value.length() > maximum) throw new ReviewAnalysisException();
        return value;
    }

    private static String nullableBoundedText(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null) throw new ReviewAnalysisException();
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum) {
            throw new ReviewAnalysisException();
        }
        return value.textValue();
    }

    private static int requiredInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new ReviewAnalysisException();
        return value.intValue();
    }

    private static Integer nullablePositiveInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) throw new ReviewAnalysisException();
        if (value.isNull()) return null;
        int integer = requiredInteger(node, field);
        if (integer < 1) throw new ReviewAnalysisException();
        return integer;
    }

    private static String stableId(Candidate candidate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, candidate.category().name());
            update(digest, candidate.severity().name());
            update(digest, Integer.toString(candidate.confidence()));
            update(digest, candidate.path());
            update(digest, String.valueOf(candidate.startLine()));
            update(digest, String.valueOf(candidate.endLine()));
            update(digest, candidate.title());
            update(digest, candidate.evidence());
            update(digest, candidate.impact());
            update(digest, candidate.explanation());
            update(digest, candidate.suggestedFix());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record Candidate(ReviewFindingCategory category, ReviewSeverity severity, int confidence,
            String path, Integer startLine, Integer endLine, String title, String evidence,
            String impact, String explanation, String suggestedFix) {
        ReviewFinding toFinding(String id) {
            return new ReviewFinding(id, category, severity, confidence, path, startLine, endLine,
                    title, evidence, impact, explanation, suggestedFix);
        }
    }
}
