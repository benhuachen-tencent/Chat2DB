package ai.chat2db.server.web.api.controller.ai.chat2db.listener;

import ai.chat2db.server.domain.api.enums.AiSqlSourceEnum;
import ai.chat2db.server.domain.api.model.Config;
import ai.chat2db.server.domain.api.service.ConfigService;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.web.api.controller.ai.baichuan.model.BaichuanChatCompletions;
import ai.chat2db.server.web.api.controller.ai.baichuan.model.BaichuanChatMessage;
import ai.chat2db.server.web.api.controller.ai.chat2db.client.Chat2dbAIClient;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.chat2db.server.web.api.controller.ai.response.ChatChoice;
import ai.chat2db.server.web.api.controller.ai.response.ChatCompletionResponse;
import ai.chat2db.server.web.api.controller.ai.zhipu.model.ZhipuChatCompletions;
import ai.chat2db.server.web.api.util.ApplicationContextUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfbx.chatgpt.entity.chat.Message;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;

/**
 * Description: Chat2dbAIEventSourceListener
 *
 * @author https:www.unfbx.com
 * @date 2023-02-22
 */
@Slf4j
public class Chat2dbAIEventSourceListener extends EventSourceListener {

    private SseEmitter sseEmitter;

    /**
     * Buffer to accumulate streamed content for post-processing.
     * DeepSeek models may output reasoning text and markdown code blocks in the content field.
     */
    private final StringBuilder contentBuffer = new StringBuilder();
    private String completionId;

    public Chat2dbAIEventSourceListener(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(EventSource eventSource, Response response) {
        log.info("Chat2db AI 建立sse连接...");
    }

    /**
     * {@inheritDoc}
     */
    @SneakyThrows
    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        log.info("Chat2db AI returns data: {}", data);
        if (data.equals("[DONE]")) {
            log.info("Chat2db AI return data is over");
            // Process buffered content: clean up reasoning text and markdown code blocks
            String fullContent = contentBuffer.toString();
            String cleaned = cleanSqlOutput(fullContent);
            if (!cleaned.isEmpty()) {
                Message message = new Message();
                message.setContent(cleaned);
                sseEmitter.send(SseEmitter.event()
                    .id(completionId != null ? completionId : "[DONE]")
                    .data(message)
                    .reconnectTime(3000));
            }
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]")
                .reconnectTime(3000));
            sseEmitter.complete();
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ChatCompletionResponse completionResponse = mapper.readValue(data, ChatCompletionResponse.class);
        ChatChoice choice = completionResponse.getChoices().get(0);
        String text = choice.getDelta() == null
            ? choice.getText()
            : choice.getDelta().getContent();
        if (completionResponse.getId() != null) {
            completionId = completionResponse.getId();
        }
        // Buffer content for post-processing instead of sending immediately
        if (text != null) {
            contentBuffer.append(text);
        }
    }

    /**
     * Clean up model output to extract only the SQL statement.
     */
    private String cleanSqlOutput(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String extracted = content;
        if (content.contains("```")) {
            int lastOpenIdx = content.lastIndexOf("```");
            String beforeLast = content.substring(0, lastOpenIdx);
            int matchingOpenIdx = beforeLast.lastIndexOf("```");
            if (matchingOpenIdx >= 0) {
                extracted = content.substring(matchingOpenIdx, lastOpenIdx);
                extracted = extracted.replaceFirst("```\\w*\\s*", "");
            }
        }

        extracted = extracted.replaceAll("```\\w*", "").replaceAll("```", "");

        String[] lines = extracted.split("\n");
        StringBuilder sqlBuilder = new StringBuilder();
        boolean foundSql = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!foundSql) {
                if (trimmed.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|WITH|EXPLAIN|SHOW|DESCRIBE|USE|--.*)\\b.*")) {
                    foundSql = true;
                    sqlBuilder.append(line).append("\n");
                }
            } else {
                if (trimmed.isEmpty() || trimmed.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|WITH|EXPLAIN|SHOW|DESCRIBE|USE|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|ON|AND|OR|GROUP|ORDER|HAVING|LIMIT|OFFSET|UNION|SET|VALUES|INTO|--.*)\\b.*")
                    || trimmed.endsWith(";") || trimmed.startsWith("(") || trimmed.startsWith(")")) {
                    sqlBuilder.append(line).append("\n");
                } else {
                    break;
                }
            }
        }

        String result = foundSql ? sqlBuilder.toString().trim() : extracted.trim();
        return result;
    }

    @Override
    public void onClosed(EventSource eventSource) {
        sseEmitter.complete();
        log.info("Chat2db AI closes sse connection...");
    }

    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        try {
            if (Objects.isNull(response)) {
                String message = t.getMessage();
                Message sseMessage = new Message();
                sseMessage.setContent(message);
                sseEmitter.send(SseEmitter.event()
                    .id("[ERROR]")
                    .data(sseMessage));
                sseEmitter.send(SseEmitter.event()
                    .id("[DONE]")
                    .data("[DONE]"));
                sseEmitter.complete();
                return;
            }
            ResponseBody body = response.body();
            String bodyString = null;
            if (Objects.nonNull(body)) {
                bodyString = body.string();
                log.error("Chat2db AI sse connection exception data: {}", bodyString, t);
            } else {
                log.error("Chat2db AI sse connection exception data: {}", response, t);
            }
            eventSource.cancel();
            Message message = new Message();
            message.setContent("Chat2db AI Error：" + bodyString);
            sseEmitter.send(SseEmitter.event()
                .id("[ERROR]")
                .data(message));
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]"));
            sseEmitter.complete();
        } catch (Exception exception) {
            log.error("Exception in sending data:", exception);
        }
    }
}
