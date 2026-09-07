package io.prreviewassistant.ai.openai;

interface OpenAiGateway {
    OpenAiGatewayResponse generate(OpenAiInvocation invocation);
}
