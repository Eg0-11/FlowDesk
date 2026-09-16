package com.flowdesk.bootstrap.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ai.KnowledgeAnswerUseCase;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 默认 profile 下的问答接口契约（RAG 5/6）。
 *
 * <p>{@code flowdesk.ai.enabled=false} 时必须同时满足两件事：接口<b>不存在</b>（404），
 * 并且上下文里<b>没有任何</b>可能发起模型调用的组件。检索能力本身仍然装配
 * （它是知识库自己的功能，与模型开关无关）—— 但问答用例不装配，
 * 因为没有被检索证据约束的模型调用正是本任务要排除的东西。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeAnswerDisabledWebTests {

    private static final String ANSWER_PATH = "/api/v1/ai/knowledge-answer";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theAnswerEndpointIsNotRegisteredWhenAiIsDisabled() {
        ResponseEntity<String> response = this.restTemplate.postForEntity(ANSWER_PATH,
                Map.of("query", "VPN 无法连接应该如何处理？"), String.class);

        assertThat(response.getStatusCode())
                .as("默认 profile 下不存在问答端点，也就不可能发起模型调用")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noModelInfrastructureAndNoAnswerUseCaseAreAssembled() {
        assertThat(this.applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class))
                .isFalse();
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(ChatClient.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(KnowledgeAnswerUseCase.class)).isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(
                com.flowdesk.agent.ai.GroundedKnowledgeAnswerService.class)).isEmpty();
    }

    @Test
    void theRetrievalUseCaseStillExistsSoTheKnowledgeFeatureIsUnaffected() {
        assertThat(this.applicationContext.getBeanNamesForType(RetrieveKnowledgeUseCase.class))
                .as("问答开关不得影响检索能力本身")
                .isNotEmpty();
    }

    @Test
    void theOtherAiEndpointsStayUnregisteredToo() {
        for (String path : new String[] { "/api/v1/ai/chat", "/api/v1/ai/tool-smoke" }) {
            ResponseEntity<String> response = this.restTemplate.postForEntity(path,
                    Map.of("message", "hello"), String.class);

            assertThat(response.getStatusCode()).as("path=%s", path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
