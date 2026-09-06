package io.prreviewassistant.review.context;

import java.util.*;
import java.util.regex.*;
import io.prreviewassistant.review.retrieval.*;

final class ContextCandidateSelector {
    private static final Pattern JAVA_IMPORT = Pattern.compile("(?m)^[+\\s]*import\\s+(?:static\\s+)?([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+)\\s*;");
    private static final Pattern JS_IMPORT = Pattern.compile("(?:from\\s*|require\\s*\\()\\s*['\"](\\.{1,2}/[^'\"]+)['\"]");
    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^[+\\s]*(?:from\\s+([.\\w]+)\\s+import|import\\s+([.\\w]+))");

    List<ContextCandidate> select(PullRequestSnapshot snapshot, int limit) {
        Map<String, ContextCandidate> found = new HashMap<>();
        for (ChangedFile file : snapshot.changedFiles()) {
            SourceLanguage language = SourceLanguage.fromPath(file.path());
            if (excluded(file.path()) || language == SourceLanguage.MARKUP || language == SourceLanguage.CONFIG) continue;
            RepositoryRevisionSide side = file.status() == ChangedFileStatus.REMOVED ? RepositoryRevisionSide.BASE : RepositoryRevisionSide.HEAD;
            if (file.patchAvailability() != PatchAvailability.AVAILABLE) add(found,
                    new ContextCandidate(file.path(), side, ContextSelectionReason.CHANGED_FILE_CONTEXT, 100, 0));
            if (file.patch() != null) discoverImports(found, file.path(), language, file.patch());
            if (file.patchAvailability() != PatchAvailability.AVAILABLE) companion(file.path(), language).ifPresent(p -> add(found,
                    new ContextCandidate(p, side, ContextSelectionReason.COMPANION_TEST, 40, 0)));
        }
        return found.values().stream().sorted(Comparator.comparingInt(ContextCandidate::relevanceScore).reversed()
                .thenComparingLong(ContextCandidate::estimatedCost).thenComparing(ContextCandidate::path)
                .thenComparing(c -> c.revisionSide().name())).limit(limit).toList();
    }

    private void discoverImports(Map<String, ContextCandidate> out, String changed, SourceLanguage language, String patch) {
        if (language == SourceLanguage.JAVA) {
            Matcher m=JAVA_IMPORT.matcher(patch); while(m.find()) {
                String q=m.group(1); if (q.startsWith("java.")||q.startsWith("javax.")||q.startsWith("jakarta.")||q.startsWith("org.springframework.")) continue;
                int marker=changed.indexOf("src/main/java/"); if(marker>=0) { String symbol=q.substring(q.lastIndexOf('.')+1); add(out,new ContextCandidate(changed.substring(0,marker)+"src/main/java/"+q.replace('.','/')+".java",RepositoryRevisionSide.HEAD,ContextSelectionReason.DIRECT_IMPORT,80,0,symbol)); }
            }
        } else if (language==SourceLanguage.TYPESCRIPT || language==SourceLanguage.JAVASCRIPT) {
            Matcher m=JS_IMPORT.matcher(patch); while(m.find()) for(String p: resolveJs(changed,m.group(1))) add(out,new ContextCandidate(p,RepositoryRevisionSide.HEAD,ContextSelectionReason.DIRECT_IMPORT,80,0));
        } else if (language==SourceLanguage.PYTHON) {
            Matcher m=PY_IMPORT.matcher(patch); while(m.find()) {
                String value=m.group(1)!=null?m.group(1):m.group(2); String p=resolvePython(changed,value);
                if(p!=null) add(out,new ContextCandidate(p,RepositoryRevisionSide.HEAD,ContextSelectionReason.DIRECT_IMPORT,80,0));
            }
        }
    }

    private List<String> resolveJs(String changed,String spec) {
        String base=changed.contains("/")?changed.substring(0,changed.lastIndexOf('/')+1):"";
        String p=normalize(base+spec); if(p==null)return List.of();
        return List.of(p+".ts",p+".tsx",p+".js",p+"/index.ts");
    }
    private String resolvePython(String changed,String spec) {
        if(spec==null||spec.isBlank())return null;
        if(spec.startsWith(".")) {
            String dir=changed.contains("/")?changed.substring(0,changed.lastIndexOf('/')+1):"";
            int dots=0; while(dots<spec.length()&&spec.charAt(dots)=='.')dots++;
            for(int i=1;i<dots;i++){if(dir.isEmpty())return null;String d=dir.substring(0,dir.length()-1);int slash=d.lastIndexOf('/');dir=slash<0?"":d.substring(0,slash+1);}
            return normalize(dir+spec.substring(dots).replace('.','/')+".py");
        }
        if(!spec.contains(".")) return null;
        return spec.replace('.','/')+".py";
    }
    private String normalize(String raw){Deque<String>s=new ArrayDeque<>();for(String p:raw.split("/")){if(p.isBlank()||p.equals("."))continue;if(p.equals("..")){if(s.isEmpty())return null;s.removeLast();}else s.addLast(p);}return String.join("/",s);}
    private Optional<String> companion(String p,SourceLanguage l){int dot=p.lastIndexOf('.');if(dot<0)return Optional.empty();String b=p.substring(0,dot),e=p.substring(dot);return switch(l){case JAVA->Optional.of(b+"Test"+e);case TYPESCRIPT,JAVASCRIPT->Optional.of(b+".test"+e);case PYTHON->{int slash=p.lastIndexOf('/');yield Optional.of((slash<0?"":p.substring(0,slash+1))+"test_"+p.substring(slash+1));}default->Optional.empty();};}
    private void add(Map<String,ContextCandidate> m,ContextCandidate c){if(safe(c.path())&&!excluded(c.path()))m.merge(c.path()+"|"+c.revisionSide(),c,(a,b)->a.relevanceScore()>=b.relevanceScore()?a:b);}
    static boolean safe(String p){if(p==null||p.isBlank()||p.length()>4096||p.startsWith("/")||p.indexOf('\\')>=0||p.indexOf('\0')>=0)return false;for(String s:p.split("/",-1))if(s.isBlank()||s.equals(".")||s.equals(".."))return false;return true;}
    static boolean excluded(String p){String x=p.toLowerCase(Locale.ROOT);return x.startsWith("node_modules/")||x.contains("/node_modules/")||x.startsWith("vendor/")||x.startsWith("dist/")||x.startsWith("build/")||x.startsWith("target/")||x.startsWith(".next/")||x.startsWith("coverage/")||x.contains("/generated/")||x.endsWith(".min.js")||x.endsWith("package-lock.json")||x.endsWith("pnpm-lock.yaml")||x.endsWith("yarn.lock");}
}
