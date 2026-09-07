package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkOpenAiGatewayTest {
    @Test void mapsResponsesApiStructuredOutputWithoutToolsOrStorage() throws Exception {
        var schema = new ObjectMapper().readTree("""
                {"type":"object","properties":{"result":{"type":"string"}},
                 "required":["result"],"additionalProperties":false}
                """);
        var params = SdkOpenAiGateway.toParams(new OpenAiInvocation(
                "gpt-5.6-terra", io.prreviewassistant.ai.AiReasoningEffort.LOW, 2048,
                "application instructions", "untrusted input", "synthetic", schema, true));

        assertThat(params.model().orElseThrow().asString()).isEqualTo("gpt-5.6-terra");
        assertThat(params.instructions()).contains("application instructions");
        assertThat(params.input().orElseThrow().text()).contains("untrusted input");
        assertThat(params.maxOutputTokens()).hasValue(2048L);
        assertThat(params.reasoning().orElseThrow().effort()).contains(ReasoningEffort.LOW);
        assertThat(params.store()).hasValue(false);
        assertThat(params.tools()).isEmpty();
        var format = params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.name()).isEqualTo("synthetic");
        assertThat(format.strict()).hasValue(true);
        assertThat(format.schema()._additionalProperties()).containsKeys("type", "properties", "required", "additionalProperties");
    }

    @Test void nonObjectSchemaIsRejectedBeforeSdkCall() throws Exception {
        var invocation = new OpenAiInvocation("gpt-5.6-terra", io.prreviewassistant.ai.AiReasoningEffort.LOW,
                100, "instructions", "input", "synthetic", new ObjectMapper().readTree("[]"), true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SdkOpenAiGateway.toParams(invocation))
                .isInstanceOf(OpenAiGatewayException.class);
    }
}
