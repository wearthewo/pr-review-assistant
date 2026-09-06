package io.prreviewassistant.review.context;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import io.prreviewassistant.review.retrieval.*; import org.junit.jupiter.api.Test;

class ContextCandidateSelectorTest {
    private final ContextCandidateSelector selector=new ContextCandidateSelector();
    @Test void documentationAndSufficientDiffNeedNoExtraContext(){assertThat(selector.select(snapshot(file("README.md","text",ChangedFileStatus.MODIFIED)),100)).isEmpty();assertThat(selector.select(snapshot(file("src/A.java","class A {}",ChangedFileStatus.MODIFIED)),100)).isEmpty();}
    @Test void javaKeepsProjectImportAndSuppressesExternalImports(){var c=selector.select(snapshot(file("src/main/java/com/acme/A.java","+ import java.util.List;\n+ import org.springframework.stereotype.Service;\n+ import com.acme.pay.PaymentRepository;",ChangedFileStatus.MODIFIED)),100);assertThat(c).extracting(ContextCandidate::path).containsExactly("src/main/java/com/acme/pay/PaymentRepository.java");}
    @Test void typescriptRelativeImportsAreBoundedAndPackageImportsIgnored(){var c=selector.select(snapshot(file("src/services/a.ts","+ import x from '../repo/x';\n+ import z from 'react';",ChangedFileStatus.MODIFIED)),100);assertThat(c).hasSize(4);assertThat(c).extracting(ContextCandidate::path).contains("src/repo/x.ts","src/repo/x/index.ts");}
    @Test void pythonAbsoluteAndRelativeImportsAreResolvedWithoutExecution(){var c=selector.select(snapshot(file("app/orders/service.py","+ from app.payments.repository import X\n+ from .model import Y\n+ import os",ChangedFileStatus.MODIFIED)),100);assertThat(c).extracting(ContextCandidate::path).contains("app/payments/repository.py","app/orders/model.py").doesNotContain("os.py");}
    @Test void unavailableDeletedFileUsesBaseAndNoisyPathIsExcluded(){var base=selector.select(snapshot(file("src/Old.java",null,ChangedFileStatus.REMOVED)),100);assertThat(base.getFirst().revisionSide()).isEqualTo(RepositoryRevisionSide.BASE);assertThat(selector.select(snapshot(file("vendor/X.java",null,ChangedFileStatus.MODIFIED)),100)).isEmpty();}
    @Test void orderingAndDeduplicationAreDeterministic(){var f=file("src/main/java/com/acme/A.java","import com.acme.B;\nimport com.acme.B;",ChangedFileStatus.MODIFIED);assertThat(selector.select(snapshot(f),100)).isEqualTo(selector.select(snapshot(f),100));assertThat(selector.select(snapshot(f),100)).hasSize(1);}
    private ChangedFile file(String p,String patch,ChangedFileStatus s){return new ChangedFile(p,null,s,1,0,1,patch==null?PatchAvailability.UNAVAILABLE:PatchAvailability.AVAILABLE,patch);}
    private PullRequestSnapshot snapshot(ChangedFile f){return new PullRequestSnapshot(1,2,3,"a".repeat(40),"b".repeat(40),false,List.of(f));}
}
