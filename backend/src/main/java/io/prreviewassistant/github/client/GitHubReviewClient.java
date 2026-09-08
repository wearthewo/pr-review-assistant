package io.prreviewassistant.github.client;

import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationTokenProvider;
import io.prreviewassistant.review.publication.PublicationComment;
import io.prreviewassistant.review.publication.PublicationPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class GitHubReviewClient implements GitHubReviewPublisher {
    private static final ObjectMapper MAPPER=JsonMapper.builder().build();
    private final RestClient client; private final InstallationTokenProvider tokens;
    public GitHubReviewClient(RestClient client,InstallationTokenProvider tokens){this.client=client;this.tokens=tokens;}

    @Override
    public GitHubPublishedReview create(long installationId,String owner,String repository,int pullNumber,
            String commitId,PublicationPayload payload,int maxResponseBytes){
        InstallationAccessToken token=tokenFor(installationId);
        ObjectNode root=MAPPER.createObjectNode();root.put("commit_id",commitId);root.put("body",payload.body());root.put("event","COMMENT");
        ArrayNode comments=root.putArray("comments");
        for(PublicationComment c:payload.comments()){
            ObjectNode n=comments.addObject();n.put("path",c.path());n.put("body",c.body());n.put("line",c.line());n.put("side","RIGHT");
            if(c.startLine()!=null){n.put("start_line",c.startLine());n.put("start_side","RIGHT");}
        }
        try{
            String body=client.post().uri(b->b.pathSegment("repos",owner,repository,"pulls",Integer.toString(pullNumber),"reviews").build())
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION,"Bearer "+token.value())
                    .body(MAPPER.writeValueAsString(root)).exchange((request,response)->{
                        if(!response.getStatusCode().is2xxSuccessful())throw map(response.getStatusCode().value(),response.getHeaders(),false);
                        return read(response,maxResponseBytes);
                    });
            return parsePublished(body);
        }catch(GitHubReviewException e){throw e;}catch(RestClientException e){throw GitHubReviewException.of(GitHubReviewErrorType.AMBIGUOUS_DELIVERY);}
    }

    @Override
    public GitHubReviewPage list(long installationId,String owner,String repository,int pullNumber,int page,int maxResponseBytes){
        InstallationAccessToken token=tokenFor(installationId);
        try{return client.get().uri(b->b.pathSegment("repos",owner,repository,"pulls",Integer.toString(pullNumber),"reviews")
                .queryParam("per_page",100).queryParam("page",page).build())
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+token.value()).exchange((request,response)->{
                    if(!response.getStatusCode().is2xxSuccessful())throw map(response.getStatusCode().value(),response.getHeaders(),true);
                    String body=read(response,maxResponseBytes);return parsePage(body,response.getHeaders().getFirst("Link"));
                });}catch(GitHubReviewException e){throw e;}catch(RestClientException e){throw GitHubReviewException.of(GitHubReviewErrorType.TRANSIENT);}
    }

    private static GitHubPublishedReview parsePublished(String body){try{JsonNode n=MAPPER.readTree(body);
        return new GitHubPublishedReview(positiveId(n.path("id")),Instant.parse(required(n.path("submitted_at"))));
        }catch(RuntimeException e){throw GitHubReviewException.of(GitHubReviewErrorType.MALFORMED_RESPONSE);}}
    private static GitHubReviewPage parsePage(String body,String link){try{JsonNode root=MAPPER.readTree(body);if(!root.isArray())throw new IllegalArgumentException();
        List<GitHubReviewSummary> result=new ArrayList<>();for(JsonNode n:root){long id=positiveId(n.path("id"));
            String text=n.path("body").isString()?n.path("body").stringValue():"";
            Instant submitted=n.path("submitted_at").isString()?Instant.parse(n.path("submitted_at").stringValue()):null;
            result.add(new GitHubReviewSummary(id,text,submitted));}return new GitHubReviewPage(result,hasNext(link));
        }catch(RuntimeException e){if(e instanceof GitHubReviewException g)throw g;throw GitHubReviewException.of(GitHubReviewErrorType.MALFORMED_RESPONSE);}}
    private static String read(org.springframework.http.client.ClientHttpResponse response,int max)throws IOException{
        byte[] bytes=response.getBody().readNBytes(max+1);if(bytes.length>max)throw GitHubReviewException.of(GitHubReviewErrorType.RESPONSE_TOO_LARGE);
        return new String(bytes,StandardCharsets.UTF_8);}
    private static GitHubReviewException map(int status,HttpHeaders headers,boolean read){
        if(status==429||((status==403||status==422)
                && ("0".equals(headers.getFirst("X-RateLimit-Remaining"))||headers.getFirst("Retry-After")!=null)))
            return GitHubReviewException.of(GitHubReviewErrorType.RATE_LIMITED);
        if(status>=500)return GitHubReviewException.of(read?GitHubReviewErrorType.TRANSIENT:GitHubReviewErrorType.TRANSIENT);
        if(status==401)return GitHubReviewException.of(GitHubReviewErrorType.AUTHENTICATION);
        if(status==403)return GitHubReviewException.of(GitHubReviewErrorType.PERMISSION);
        if(status==404)return GitHubReviewException.of(GitHubReviewErrorType.NOT_FOUND);
        if(status==422)return GitHubReviewException.of(GitHubReviewErrorType.INVALID_REVIEW);
        return GitHubReviewException.of(GitHubReviewErrorType.MALFORMED_RESPONSE);
    }
    private static long positiveId(JsonNode n){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<=0)throw new IllegalArgumentException();return n.longValue();}
    private static String required(JsonNode n){if(!n.isString()||n.stringValue().isBlank())throw new IllegalArgumentException();return n.stringValue();}
    private static boolean hasNext(String link){return link!=null&&java.util.Arrays.stream(link.split(",")).anyMatch(v->v.matches("\\s*<[^>]+>\\s*;.*\\brel=\\\"next\\\".*"));}

    private InstallationAccessToken tokenFor(long installationId) {
        try {
            return tokens.tokenFor(installationId);
        } catch (GitHubException exception) {
            throw GitHubReviewException.of(switch (exception.type()) {
                case RATE_LIMITED -> GitHubReviewErrorType.RATE_LIMITED;
                case TRANSIENT_FAILURE -> GitHubReviewErrorType.TRANSIENT;
                case INSTALLATION_NOT_FOUND, RESOURCE_NOT_FOUND -> GitHubReviewErrorType.NOT_FOUND;
                case AUTHENTICATION_REJECTED, INVALID_CONFIGURATION, INVALID_INSTALLATION_ID,
                        JWT_GENERATION_FAILED -> GitHubReviewErrorType.AUTHENTICATION;
                case MALFORMED_RESPONSE, RESPONSE_TOO_LARGE -> GitHubReviewErrorType.MALFORMED_RESPONSE;
            });
        }
    }
}
