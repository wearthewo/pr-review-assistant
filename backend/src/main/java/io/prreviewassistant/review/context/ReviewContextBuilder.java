package io.prreviewassistant.review.context;

import java.nio.*; import java.nio.charset.*; import java.util.*;
import io.prreviewassistant.github.auth.*; import io.prreviewassistant.github.client.*;
import io.prreviewassistant.review.retrieval.*; import org.springframework.stereotype.Service;

@Service
public final class ReviewContextBuilder {
    private final GitHubApiClient client; private final ReviewContextProperties properties;
    private final ContextCandidateSelector selector=new ContextCandidateSelector();
    public ReviewContextBuilder(GitHubApiClient client,ReviewContextProperties properties){this.client=client;this.properties=properties;}
    public ReviewContextBuildResult build(PullRequestSnapshot snapshot){
        if(!properties.enabled())return ReviewContextBuildResult.unavailable(ContextOmissionReason.DISABLED);
        List<ContextCandidate> candidates=selector.select(snapshot,properties.maxCandidates());
        if(candidates.isEmpty())return result(snapshot,List.of(),List.of(),new ContextBudgetUsage(0,0,0,0,0,0,false));
        ContextBudget budget=new ContextBudget(candidates.size(),properties);
        List<ContextFile> files=new ArrayList<>();List<ContextOmission> omissions=new ArrayList<>();
        budget.requested();
        GitHubRepositoryMetadata repo=client.getRepository(snapshot.installationId(),snapshot.repositoryId(),PullRequestLoader.MAX_METADATA_RESPONSE_BYTES);
        if(repo.id()!=snapshot.repositoryId())throw GitHubException.malformedResponse();
        Set<String> fetchedKeys=new HashSet<>();
        for(ContextCandidate c:candidates){
            if(!budget.canFetch()){omissions.add(new ContextOmission(ContextOmissionReason.BUDGET_EXCEEDED,c.revisionSide()));continue;}
            String sha=c.revisionSide()==RepositoryRevisionSide.HEAD?snapshot.headSha():snapshot.baseSha();
            if(!fetchedKeys.add(c.path()+"|"+sha))continue; budget.requested();
            try{
                int max=c.reason()==ContextSelectionReason.CHANGED_FILE_CONTEXT?properties.maxChangedFileBytes():properties.maxFileBytes();
                GitHubRepositoryFile f=client.getRepositoryFile(snapshot.installationId(),repo.owner(),repo.name(),c.path(),sha,max*2+4096);
                if(f==null)throw GitHubException.malformedResponse();
                budget.fetched(f.content().length);
                if(f.declaredSize()>max||f.content().length>max){omissions.add(new ContextOmission(ContextOmissionReason.FILE_TOO_LARGE,c.revisionSide()));continue;}
                String text=decode(f.content());if(text==null){omissions.add(new ContextOmission(containsNul(f.content())?ContextOmissionReason.BINARY:ContextOmissionReason.INVALID_TEXT,c.revisionSide()));continue;}
                Retained retained=retain(text,properties.maxLinesPerFile(),c);
                if(retained==null){omissions.add(new ContextOmission(ContextOmissionReason.NO_RELEVANT_FRAGMENT,c.revisionSide()));continue;}
                long retainedBytes=retained.text().getBytes(StandardCharsets.UTF_8).length;
                if(!budget.canRetain(retainedBytes)){omissions.add(new ContextOmission(ContextOmissionReason.BUDGET_EXCEEDED,c.revisionSide()));continue;}
                budget.retained(retainedBytes);files.add(new ContextFile(c.path(),c.revisionSide(),sha,SourceLanguage.fromPath(c.path()),c.reason(),retained.text(),f.content().length,retainedBytes,retained.complete(),retained.startLine(),retained.endLine()));
            }catch(GitHubException e){if(e.type()==GitHubErrorType.RESOURCE_NOT_FOUND){omissions.add(new ContextOmission(ContextOmissionReason.NOT_FOUND,c.revisionSide()));}else throw e;}
        }
        return result(snapshot,files,omissions,budget.usage());
    }
    private ReviewContextBuildResult result(PullRequestSnapshot s,List<ContextFile> f,List<ContextOmission> o,ContextBudgetUsage u){ReviewContext c=new ReviewContext(new io.prreviewassistant.review.job.ReviewTarget(s.installationId(),s.repositoryId(),s.pullRequestNumber(),s.headSha()),s,f,u);return o.isEmpty()?ReviewContextBuildResult.ready(c):ReviewContextBuildResult.partial(c,o);}
    private String decode(byte[] b){if(containsNul(b))return null;try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException e){return null;}}
    private boolean containsNul(byte[] b){for(byte x:b)if(x==0)return true;return false;}
    private Retained retain(String text,int max,ContextCandidate candidate){
        String[] lines=text.split("\\R",-1);
        if(lines.length<=max)return new Retained(text,true,1,lines.length);
        Integer anchor=locateAnchor(lines,candidate.anchorSymbol(),SourceLanguage.fromPath(candidate.path()));
        if(anchor==null)return null;
        int before=(max-1)/2,start=Math.max(0,anchor-before),end=Math.min(lines.length,start+max);
        start=Math.max(0,end-max);
        return new Retained(String.join("\n",Arrays.copyOfRange(lines,start,end)),false,start+1,end);
    }
    private Integer locateAnchor(String[] lines,String symbol,SourceLanguage language){
        if(symbol==null)return null;
        java.util.regex.Pattern pattern=switch(language){
            case JAVA -> java.util.regex.Pattern.compile("\\b(?:class|interface|record|enum)\\s+"+java.util.regex.Pattern.quote(symbol)+"\\b");
            case TYPESCRIPT,JAVASCRIPT -> java.util.regex.Pattern.compile("\\b(?:class|interface|type|function|const)\\s+"+java.util.regex.Pattern.quote(symbol)+"\\b");
            case PYTHON -> java.util.regex.Pattern.compile("^\\s*(?:class|def)\\s+"+java.util.regex.Pattern.quote(symbol)+"\\b");
            default -> null;
        };
        if(pattern==null)return null;Integer found=null;
        for(int i=0;i<lines.length;i++)if(pattern.matcher(lines[i]).find()){if(found!=null)return null;found=i;}
        return found;
    }
    private record Retained(String text,boolean complete,int startLine,int endLine){}
}
