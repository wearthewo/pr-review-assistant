package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.context.ContextFile;
import io.prreviewassistant.review.context.ContextSelectionReason;
import io.prreviewassistant.review.context.RepositoryRevisionSide;
import io.prreviewassistant.review.context.ReviewContext;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.ChangedFileStatus;
import io.prreviewassistant.review.retrieval.PatchAvailability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.prreviewassistant.observability.ApplicationMetrics;

public final class DeterministicFindingSuppressionEngine implements FindingSuppressionEngine {
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_LOCATION_SPAN = 200;
    private static final int MAX_TRUSTED_CONTEXT_SPAN = 2_000;
    private static final int MAX_CONTEXT_FILES = 1_000;
    private static final int MAX_CHANGED_FILES = 3_000;
    private static final double OVERLAP_SIMILARITY_THRESHOLD = 0.60;
    private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{N}_]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "be", "by", "can", "could", "for", "in", "is", "it",
            "may", "might", "of", "on", "or", "that", "the", "this", "to", "with", "would");
    private static final Set<String> CONCRETE_BEHAVIOR_TERMS = Set.of(
            "allocation", "authorization", "bypass", "commit", "concurrent", "corrupt", "deadlock",
            "delete", "duplicate", "exception", "fail", "leak", "lock", "lost", "null", "overwrite",
            "race", "request", "return", "rollback", "save", "transaction", "unauthenticated");
    private static final Set<String> DELETION_TERMS = Set.of(
            "delete", "deleted", "deletion", "remove", "removed", "removal", "missing");
    private static final Set<String> CRITICAL_IMPACT_TERMS = Set.of(
            "administrator", "arbitrary", "compromise", "corruption", "data loss", "remote code",
            "system-wide", "unauthenticated");
    private static final Set<String> MINOR_IMPACT_TERMS = Set.of(
            "minor", "negligible", "small overhead", "unnecessary allocation");
    private static final Set<String> GENERIC_PHRASES = Set.of(
            "potential issue",
            "this could cause issues",
            "this may be problematic",
            "there might be a bug",
            "consider handling this case",
            "error handling could be improved",
            "consider improving error handling",
            "ensure this is secure",
            "consider adding validation",
            "potential performance issue");

    private static final Comparator<ReviewFinding> DUPLICATE_SURVIVOR_ORDER =
            Comparator.comparingInt(ReviewFinding::confidence).reversed()
                    .thenComparing(ReviewFinding::severity, severityOrder())
                    .thenComparingInt(DeterministicFindingSuppressionEngine::locationPrecision)
                    .thenComparing(ReviewFinding::id);
    private static final Comparator<ReviewFinding> CAP_ORDER =
            Comparator.comparing(ReviewFinding::severity, severityOrder())
                    .thenComparing(Comparator.comparingInt(ReviewFinding::confidence).reversed())
                    .thenComparingInt(DeterministicFindingSuppressionEngine::locationPrecision)
                    .thenComparing(ReviewFinding::id);
    private static final Comparator<ReviewFinding> FINAL_ORDER =
            Comparator.comparing(ReviewFinding::severity, severityOrder())
                    .thenComparing(Comparator.comparingInt(ReviewFinding::confidence).reversed())
                    .thenComparing(ReviewFinding::path)
                    .thenComparing(ReviewFinding::startLine, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(ReviewFinding::id);

    private final FindingSuppressionProperties properties;
    private final ApplicationMetrics metrics;
    private final UnifiedDiffLineMapper lineMapper = new UnifiedDiffLineMapper();

    public DeterministicFindingSuppressionEngine(FindingSuppressionProperties properties) {
        this(properties, ApplicationMetrics.noop());
    }

    public DeterministicFindingSuppressionEngine(
            FindingSuppressionProperties properties, ApplicationMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    @Override
    public ValidatedReview validate(ReviewCandidateAnalysis analysis, ReviewContext context) {
        Objects.requireNonNull(analysis, "analysis is required");
        Objects.requireNonNull(context, "context is required");
        if (!analysis.target().equals(context.target()) || analysis.findings().size() > MAX_CANDIDATES
                || context.files().size() > MAX_CONTEXT_FILES
                || context.pullRequest().changedFiles().size() > MAX_CHANGED_FILES) {
            throw new IllegalArgumentException("review analysis does not match its bounded context");
        }

        SupportIndex support = SupportIndex.from(context, lineMapper);
        EnumMap<SuppressionReason, Integer> suppressed = new EnumMap<>(SuppressionReason.class);
        List<ReviewFinding> eligible = new ArrayList<>();
        for (ReviewFinding finding : analysis.findings()) {
            SuppressionReason reason = firstFailedGate(finding, support);
            if (reason == null) {
                eligible.add(finding);
            } else {
                increment(suppressed, reason);
            }
        }

        eligible.sort(DUPLICATE_SURVIVOR_ORDER);
        List<ReviewFinding> distinct = new ArrayList<>();
        for (ReviewFinding finding : eligible) {
            SuppressionReason duplicateReason = duplicateReason(finding, distinct);
            if (duplicateReason == null) {
                distinct.add(finding);
            } else {
                increment(suppressed, duplicateReason);
            }
        }

        distinct.sort(CAP_ORDER);
        if (distinct.size() > properties.maxPublishableFindings()) {
            int removed = distinct.size() - properties.maxPublishableFindings();
            distinct = new ArrayList<>(distinct.subList(0, properties.maxPublishableFindings()));
            suppressed.merge(SuppressionReason.PUBLICATION_LIMIT, removed, Integer::sum);
        }
        distinct.sort(FINAL_ORDER);

        SuppressionSummary summary = new SuppressionSummary(
                analysis.findings().size(), distinct.size(),
                analysis.findings().size() - distinct.size(), suppressed);
        analysis.findings().forEach(finding ->
                metrics.findingsGenerated(finding.category(), finding.severity(), 1));
        distinct.forEach(finding ->
                metrics.findingsAccepted(finding.category(), finding.severity(), 1));
        suppressed.forEach(metrics::findingsSuppressed);
        return new ValidatedReview(analysis.target(), distinct, summary);
    }

    private SuppressionReason firstFailedGate(ReviewFinding finding, SupportIndex support) {
        ChangedFile changedFile = support.changedFiles().get(finding.path());
        if (finding.confidence() < properties.minimumConfidence()) return SuppressionReason.LOW_CONFIDENCE;
        if (severityRank(finding.severity()) < severityRank(properties.minimumSeverity())) {
            return SuppressionReason.BELOW_MINIMUM_SEVERITY;
        }
        if (changedFile == null) return SuppressionReason.NOT_CHANGE_RELEVANT;
        if (!locationSupported(finding, changedFile, support)) return SuppressionReason.UNSUPPORTED_LOCATION;
        if (finding.startLine() == null && changedFile.status() == ChangedFileStatus.REMOVED
                && !containsAny(normalizedCombined(finding), DELETION_TERMS)) {
            return SuppressionReason.NOT_CHANGE_RELEVANT;
        }
        if (isGeneric(finding)) return SuppressionReason.GENERIC_OR_NON_ACTIONABLE;
        if (finding.evidence().trim().length() < 20) return SuppressionReason.INSUFFICIENT_EVIDENCE;
        if (severityInconsistent(finding)) return SuppressionReason.SEVERITY_INCONSISTENT;
        return null;
    }

    private boolean locationSupported(ReviewFinding finding, ChangedFile file, SupportIndex support) {
        if (finding.startLine() == null) {
            if (file.status() == ChangedFileStatus.REMOVED) return true;
            return file.patchAvailability() != PatchAvailability.AVAILABLE
                    && support.trustedContextLines().containsKey(file.path());
        }
        if (file.status() == ChangedFileStatus.REMOVED) return false;
        if ((long) finding.endLine() - finding.startLine() + 1 > MAX_LOCATION_SPAN) return false;
        Set<Integer> validLines = file.patchAvailability() == PatchAvailability.AVAILABLE
                ? support.newSideLines().getOrDefault(file.path(), Set.of())
                : support.trustedContextLines().getOrDefault(file.path(), Set.of());
        for (long line = finding.startLine(); line <= finding.endLine(); line++) {
            if (!validLines.contains((int) line)) return false;
        }
        if (file.patchAvailability() != PatchAvailability.AVAILABLE) return true;
        Set<Integer> additions = support.addedLines().getOrDefault(file.path(), Set.of());
        for (long line = finding.startLine(); line <= finding.endLine(); line++) {
            if (additions.contains((int) line)) return true;
        }
        return false;
    }

    private boolean isGeneric(ReviewFinding finding) {
        String title = normalizePhrase(finding.title());
        String evidence = normalizePhrase(finding.evidence());
        String impact = normalizePhrase(finding.impact());
        String explanation = normalizePhrase(finding.explanation());
        if (GENERIC_PHRASES.contains(title) || GENERIC_PHRASES.contains(evidence)
                || GENERIC_PHRASES.contains(impact) || GENERIC_PHRASES.contains(explanation)) {
            return true;
        }
        Set<String> tokens = tokens(normalizedCombined(finding));
        boolean concreteBehavior = tokens.stream().anyMatch(CONCRETE_BEHAVIOR_TERMS::contains);
        boolean codeLikeIdentifier = containsCodeLikeIdentifier(finding.title())
                || containsCodeLikeIdentifier(finding.evidence())
                || containsCodeLikeIdentifier(finding.explanation());
        return !concreteBehavior && !codeLikeIdentifier;
    }

    private boolean severityInconsistent(ReviewFinding finding) {
        if (finding.severity() != ReviewSeverity.CRITICAL) return false;
        String impact = normalizePhrase(finding.impact());
        return containsAny(impact, MINOR_IMPACT_TERMS) && !containsAny(impact, CRITICAL_IMPACT_TERMS);
    }

    private SuppressionReason duplicateReason(ReviewFinding finding, List<ReviewFinding> survivors) {
        for (ReviewFinding survivor : survivors) {
            if (finding.category() != survivor.category()
                    || !finding.path().equals(survivor.path())
                    || !locationsOverlap(finding, survivor)) {
                continue;
            }
            String findingText = normalizedIdentity(finding);
            String survivorText = normalizedIdentity(survivor);
            if (findingText.equals(survivorText)) return SuppressionReason.DUPLICATE;
            if (strongTextualOverlap(tokens(findingText), tokens(survivorText))) {
                return SuppressionReason.OVERLAPPING_FINDING;
            }
        }
        return null;
    }

    private static boolean locationsOverlap(ReviewFinding left, ReviewFinding right) {
        if (left.startLine() == null || right.startLine() == null) {
            return left.startLine() == null && right.startLine() == null;
        }
        return left.startLine() <= right.endLine() && right.startLine() <= left.endLine();
    }

    private static String normalizedIdentity(ReviewFinding finding) {
        return normalizePhrase(finding.title() + " " + finding.evidence());
    }

    private static String normalizedCombined(ReviewFinding finding) {
        return normalizePhrase(finding.title() + " " + finding.evidence() + " "
                + finding.impact() + " " + finding.explanation());
    }

    private static String normalizePhrase(String value) {
        return WHITESPACE.matcher(NON_TOKEN.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim())
                .replaceAll(" ");
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : normalizePhrase(value).split(" ")) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) result.add(stem(token));
        }
        return result;
    }

    private static String stem(String token) {
        if (token.length() > 5 && token.endsWith("ing")) return token.substring(0, token.length() - 3);
        if (token.length() > 4 && token.endsWith("ed")) return token.substring(0, token.length() - 2);
        if (token.length() > 4 && token.endsWith("s")) return token.substring(0, token.length() - 1);
        return token;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static boolean strongTextualOverlap(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return intersection.size() >= 4 && jaccard(left, right) >= OVERLAP_SIMILARITY_THRESHOLD;
    }

    private static boolean containsCodeLikeIdentifier(String value) {
        for (String token : value.split("\\s+")) {
            String clean = token.replaceAll("[^\\p{L}\\p{N}_().]", "");
            if (clean.contains("(") || clean.contains("_")
                    || clean.matches(".*[a-z][A-Z].*") || clean.matches("[A-Z][A-Z0-9]{1,}")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, Set<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    private static int locationPrecision(ReviewFinding finding) {
        return finding.startLine() == null ? Integer.MAX_VALUE
                : finding.endLine() - finding.startLine();
    }

    private static int severityRank(ReviewSeverity severity) {
        return switch (severity) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

    private static Comparator<ReviewSeverity> severityOrder() {
        return Comparator.comparingInt(DeterministicFindingSuppressionEngine::severityRank).reversed();
    }

    private static void increment(Map<SuppressionReason, Integer> counts, SuppressionReason reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private record SupportIndex(
            Map<String, ChangedFile> changedFiles,
            Map<String, Set<Integer>> newSideLines,
            Map<String, Set<Integer>> addedLines,
            Map<String, Set<Integer>> trustedContextLines) {

        static SupportIndex from(ReviewContext context, UnifiedDiffLineMapper mapper) {
            Map<String, ChangedFile> changedFiles = new HashMap<>();
            Map<String, Set<Integer>> newSideLines = new HashMap<>();
            Map<String, Set<Integer>> addedLines = new HashMap<>();
            for (ChangedFile file : context.pullRequest().changedFiles()) {
                changedFiles.put(file.path(), file);
                Set<Integer> all = new HashSet<>();
                Set<Integer> additions = new HashSet<>();
                for (UnifiedDiffLineMapper.DiffLine line : mapper.map(file.patch())) {
                    if (line.newLine() != null) all.add(line.newLine());
                    if (line.kind() == UnifiedDiffLineMapper.DiffLineKind.ADDITION) {
                        additions.add(line.newLine());
                    }
                }
                newSideLines.put(file.path(), Set.copyOf(all));
                addedLines.put(file.path(), Set.copyOf(additions));
            }
            Map<String, Set<Integer>> contextLines = new HashMap<>();
            for (ContextFile file : context.files()) {
                ChangedFile changed = changedFiles.get(file.path());
                if (changed == null || changed.status() == ChangedFileStatus.REMOVED
                        || changed.patchAvailability() == PatchAvailability.AVAILABLE
                        || file.revisionSide() != RepositoryRevisionSide.HEAD
                        || file.reason() != ContextSelectionReason.CHANGED_FILE_CONTEXT
                        || file.content().isBlank()
                        || !file.revisionSha().equals(context.target().headSha())) {
                    continue;
                }
                Set<Integer> lines = contextLines.computeIfAbsent(file.path(), ignored -> new HashSet<>());
                if ((long) file.endLine() - file.startLine() + 1 > MAX_TRUSTED_CONTEXT_SPAN) {
                    throw new IllegalArgumentException("changed-file context line range is not bounded");
                }
                for (long line = file.startLine(); line <= file.endLine(); line++) lines.add((int) line);
            }
            Map<String, Set<Integer>> immutableContextLines = new HashMap<>();
            contextLines.forEach((path, lines) -> immutableContextLines.put(path, Set.copyOf(lines)));
            return new SupportIndex(Map.copyOf(changedFiles), Map.copyOf(newSideLines),
                    Map.copyOf(addedLines), Map.copyOf(immutableContextLines));
        }
    }
}
