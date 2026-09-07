package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.prreviewassistant.review.context.ContextFile;
import io.prreviewassistant.review.context.ContextOmission;
import io.prreviewassistant.review.context.ReviewContext;
import io.prreviewassistant.review.retrieval.ChangedFile;

import java.util.HashSet;
import java.util.Set;

public final class ReviewContextSerializer {
    static final String TRUST_BOUNDARY = "UNTRUSTED_REPOSITORY_DATA_ONLY";

    private final ObjectMapper objectMapper;
    private final UnifiedDiffLineMapper diffLineMapper;

    public ReviewContextSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.diffLineMapper = new UnifiedDiffLineMapper();
    }

    public String serialize(ReviewContext context) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("trustBoundary", TRUST_BOUNDARY);
        ObjectNode pullRequest = root.putObject("pullRequest");
        pullRequest.put("number", context.pullRequest().pullRequestNumber());
        pullRequest.put("headSha", context.pullRequest().headSha());
        pullRequest.put("baseSha", context.pullRequest().baseSha());
        pullRequest.put("draft", context.pullRequest().draft());

        ArrayNode changedFiles = root.putArray("changedFiles");
        for (ChangedFile changedFile : context.pullRequest().changedFiles()) {
            ObjectNode file = changedFiles.addObject();
            file.put("path", changedFile.path());
            if (changedFile.previousPath() == null) file.putNull("previousPath");
            else file.put("previousPath", changedFile.previousPath());
            file.put("status", changedFile.status().name());
            file.put("additions", changedFile.additions());
            file.put("deletions", changedFile.deletions());
            file.put("changes", changedFile.changes());
            file.put("patchAvailability", changedFile.patchAvailability().name());
            ArrayNode lines = file.putArray("diffLines");
            for (UnifiedDiffLineMapper.DiffLine mapped : diffLineMapper.map(changedFile.patch())) {
                ObjectNode line = lines.addObject();
                line.put("kind", mapped.kind().name());
                if (mapped.oldLine() == null) line.putNull("oldLine"); else line.put("oldLine", mapped.oldLine());
                if (mapped.newLine() == null) line.putNull("newLine"); else line.put("newLine", mapped.newLine());
                line.put("text", mapped.text());
            }
        }

        ArrayNode contextFiles = root.putArray("contextFiles");
        Set<String> serialized = new HashSet<>();
        for (ContextFile contextFile : context.files()) {
            String identity = contextFile.path() + "\u0000" + contextFile.revisionSide();
            if (!serialized.add(identity)) continue;
            ObjectNode file = contextFiles.addObject();
            file.put("path", contextFile.path());
            file.put("revisionSide", contextFile.revisionSide().name());
            file.put("language", contextFile.language().name());
            file.put("reason", contextFile.reason().name());
            file.put("complete", contextFile.complete());
            file.put("startLine", contextFile.startLine());
            file.put("endLine", contextFile.endLine());
            ArrayNode lines = file.putArray("lines");
            String[] contentLines = contextFile.content().split("\\R", -1);
            for (int index = 0; index < contentLines.length
                    && (long) contextFile.startLine() + index <= contextFile.endLine(); index++) {
                ObjectNode line = lines.addObject();
                line.put("line", contextFile.startLine() + index);
                line.put("text", contentLines[index]);
            }
        }

        ArrayNode omissions = root.putArray("contextOmissions");
        for (ContextOmission omission : context.omissions()) {
            ObjectNode item = omissions.addObject();
            item.put("reason", omission.reason().name());
            item.put("revisionSide", omission.revisionSide().name());
        }
        ObjectNode budget = root.putObject("contextBudget");
        budget.put("filesRetained", context.budgetUsage().filesRetained());
        budget.put("bytesRetained", context.budgetUsage().bytesRetained());
        budget.put("budgetExhausted", context.budgetUsage().budgetExhausted());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Review context serialization failed");
        }
    }
}
